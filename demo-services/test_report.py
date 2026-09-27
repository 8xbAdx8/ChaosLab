import unittest
from datetime import datetime, timedelta, timezone

from report import build_report, parse_instant, render_markdown


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
        self.assertIn("FakeChaosEngine", render_markdown(report))

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


if __name__ == "__main__":
    unittest.main()
