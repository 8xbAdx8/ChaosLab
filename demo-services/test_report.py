import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from report import build_report, load_execution_evidence, main, parse_instant, render_markdown


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.start = datetime(2026, 9, 27, 10, 0, tzinfo=timezone.utc)
        self.end = datetime(2026, 9, 27, 10, 0, 30, tzinfo=timezone.utc)

    def query(self, expression, at):
        if "count_over_time(up" in expression:
            return 6.0
        if "min_over_time(up" in expression:
            return 1.0
        if "_bucket" in expression:
            return 0.2 if at == self.start else 0.4
        if 'status=~"5.."' in expression:
            return 2.0 if at == self.end else None
        if "_count" in expression:
            return 10.0
        self.fail(expression)

    def test_three_nonoverlapping_windows_and_comparison(self):
        report = build_report(self.query, self.start, self.end, "/orders/{orderId}", "exp", "exec")
        self.assertEqual(report["phases"]["before"]["end"], report["phases"]["during"]["start"])
        self.assertEqual(report["phases"]["during"]["end"], report["phases"]["after"]["start"])
        self.assertEqual(report["comparison"]["during_minus_before_error_rate_pp"], 20.0)
        self.assertEqual(report["phases"]["during"]["p95_seconds"], 0.4)
        self.assertIn("观察事实", render_markdown(report))
        self.assertIn("manual_unverified", render_markdown(report))

    def test_missing_samples_refuses_to_compare(self):
        def missing(expression, at):
            if "count_over_time(up" in expression and at == self.end:
                return 1.0
            return self.query(expression, at)

        report = build_report(missing, self.start, self.end, "/orders/{orderId}", "exp", "exec")
        self.assertIsNone(report["comparison"])
        self.assertEqual(report["phases"]["during"]["status"], "insufficient_data")

    def test_no_requests_is_not_zero_error_rate(self):
        def empty(expression, at):
            if "_count" in expression:
                return None
            return self.query(expression, at)

        report = build_report(empty, self.start, self.end, "/orders/{orderId}", "exp", "exec")
        self.assertIsNone(report["comparison"])
        self.assertIsNone(report["phases"]["before"]["error_rate"])

    def test_requires_timezone_and_minimum_window(self):
        with self.assertRaises(ValueError):
            parse_instant("2026-09-27T10:00:00")
        with self.assertRaises(ValueError):
            build_report(self.query, self.start, self.start, "/orders/{orderId}", "exp", "exec")

    def test_fractional_second_execution_timestamps_are_supported(self):
        end = self.start + timedelta(seconds=30, milliseconds=427)
        report = build_report(self.query, self.start, end, "/orders/{orderId}", "exp", "exec")
        self.assertEqual(report["window_seconds"], 30.427)

    def test_backend_execution_and_audit_evidence(self):
        experiment_id = "00000000-0000-0000-0000-000000000001"
        execution_id = "00000000-0000-0000-0000-000000000002"
        execution = {
            "id": execution_id, "experimentId": experiment_id, "status": "SUCCESS",
            "startedAt": "2026-09-27T10:00:00Z", "finishedAt": "2026-09-27T10:00:30Z",
        }
        audits = [
            {"id": "start-audit", "experimentId": experiment_id, "executionId": execution_id,
             "operation": "START_EXPERIMENT", "result": "SUCCESS", "targetId": "target",
             "scenarioCode": "CPU_LOAD", "occurredAt": "2026-09-27T10:00:01Z"},
            {"id": "stop-audit", "experimentId": experiment_id, "executionId": execution_id,
             "operation": "AUTOMATIC_RECOVERY", "result": "SUCCESS", "targetId": "target",
             "occurredAt": "2026-09-27T10:00:31Z"},
        ]
        def fetch(path):
            return audits if "audit-logs" in path else execution

        start, end, evidence = load_execution_evidence(fetch, experiment_id, execution_id)
        self.assertEqual((start, end), (self.start, self.end))
        self.assertEqual(evidence["status"], "lifecycle_verified")
        report = build_report(self.query, start, end, "/orders/{orderId}",
                              experiment_id, execution_id, evidence=evidence)
        self.assertIn("start-audit", render_markdown(report))
        self.assertEqual(report["evidence"]["target_metric_binding"], "unverified")

    def test_missing_or_cross_execution_audit_fails_closed(self):
        experiment_id = "00000000-0000-0000-0000-000000000001"
        execution_id = "00000000-0000-0000-0000-000000000002"
        execution = {"id": execution_id, "experimentId": experiment_id,
                     "status": "SUCCESS", "startedAt": "2026-09-27T10:00:00Z",
                     "finishedAt": "2026-09-27T10:00:30Z"}
        with self.assertRaisesRegex(ValueError, "缺少成功启动"):
            load_execution_evidence(lambda path: [] if "audit-logs" in path else execution,
                                    experiment_id, execution_id)
        wrong = [{"experimentId": experiment_id, "executionId": "other"}]
        with self.assertRaisesRegex(ValueError, "不匹配"):
            load_execution_evidence(lambda path: wrong if "audit-logs" in path else execution,
                                    experiment_id, execution_id)

    def test_incomplete_execution_is_not_a_report(self):
        execution = {"id": "exec", "experimentId": "exp", "status": "ROLLBACK_FAILED"}
        with self.assertRaisesRegex(ValueError, "尚未成功恢复"):
            load_execution_evidence(lambda path: execution, "exp", "exec")

    def test_verified_output_is_created_but_never_overwritten(self):
        evidence = {"status": "lifecycle_verified", "target_metric_binding": "unverified",
                    "target_id": "target", "audit_events": []}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.md"
            args = ["--backend", "http://127.0.0.1:8080",
                    "--experiment-id", "00000000-0000-0000-0000-000000000001",
                    "--execution-id", "00000000-0000-0000-0000-000000000002",
                    "--output", str(output)]
            with patch("report.load_execution_evidence", return_value=(self.start, self.end, evidence)), \
                    patch("report.prometheus_value", side_effect=lambda base, expr, at: self.query(expr, at)), \
                    patch("report.backend_json", side_effect=lambda base, path: [
                        {"id": "target"}
                    ] if path == "/api/v1/targets" else {"id": "target"}), \
                    patch("report.prometheus_targets", return_value=[]), \
                    patch("report.verify_local_demo_binding", return_value={
                        "status": "verified_local_demo", "container_id": "container",
                        "image_id": "sha256:image", "job": "order-service"
                    }):
                self.assertEqual(main(args), 0)
                original = output.read_text(encoding="utf-8")
                self.assertIn("lifecycle_verified", original)
                self.assertEqual(main(args), 1)
                self.assertEqual(output.read_text(encoding="utf-8"), original)


if __name__ == "__main__":
    unittest.main()
