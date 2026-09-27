"""Generate a three-window observation report from the local Prometheus API.

This is a read-only teaching tool. It does not start or stop experiments.
"""

import argparse
import hashlib
import json
import math
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode, urlparse
from urllib.request import urlopen
from uuid import UUID


SCRAPE_INTERVAL_SECONDS = 5


def parse_instant(value):
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.tzinfo is None:
        raise ValueError("时间必须包含时区，例如 2026-09-27T10:00:00Z")
    return instant.astimezone(timezone.utc)


def prometheus_value(base_url, expression, at):
    params = urlencode({"query": expression, "time": at.timestamp()})
    with urlopen(f"{base_url}/api/v1/query?{params}", timeout=10) as response:
        payload = json.load(response)
    if payload.get("status") != "success" or payload.get("data", {}).get("resultType") != "vector":
        raise ValueError(f"Prometheus 查询失败：{payload.get('error', 'unexpected response')}")
    series = payload["data"]["result"]
    if not series:
        return None
    if len(series) != 1:
        raise ValueError("查询返回多个时间序列，无法生成单值报告")
    value = float(series[0]["value"][1])
    if not math.isfinite(value):
        return None
    return value


def backend_json(base_url, path):
    with urlopen(base_url + path, timeout=10) as response:
        return json.load(response)


def load_execution_evidence(fetch, experiment_id, execution_id):
    """Trust server-side execution times only when matching audit evidence exists."""
    execution = fetch(f"/api/v1/experiments/{experiment_id}/executions/{execution_id}")
    if not isinstance(execution, dict):
        raise ValueError("后端未返回执行记录对象")
    if (execution.get("id") != execution_id
            or execution.get("experimentId") != experiment_id
            or execution.get("status") != "SUCCESS"):
        raise ValueError("执行记录不匹配或尚未成功恢复")
    if not execution.get("startedAt") or not execution.get("finishedAt"):
        raise ValueError("执行记录缺少开始或结束时间")
    start = parse_instant(execution["startedAt"])
    end = parse_instant(execution["finishedAt"])
    if end <= start:
        raise ValueError("执行记录的时间顺序无效")
    logs = fetch(f"/api/v1/audit-logs/by-execution/{experiment_id}/{execution_id}")
    if not isinstance(logs, list):
        raise ValueError("审计查询未返回事件列表")
    if any(not isinstance(log, dict) for log in logs):
        raise ValueError("审计事件格式无效")
    if any(log.get("experimentId") != experiment_id
           or log.get("executionId") != execution_id for log in logs):
        raise ValueError("审计记录与执行 ID 不匹配")
    starts = [log for log in logs if log.get("operation") == "START_EXPERIMENT"
              and log.get("result") == "SUCCESS"]
    stops = [log for log in logs if log.get("operation") in (
        "DESTROY_EXPERIMENT", "AUTOMATIC_RECOVERY", "EMERGENCY_RECOVERY"
    ) and log.get("result") == "SUCCESS"]
    if not starts or not stops:
        raise ValueError("缺少成功启动或成功恢复的审计事件")
    if any(not log.get("id") or not log.get("occurredAt") for log in starts + stops):
        raise ValueError("成功操作的审计证据不完整")
    start_log = min(starts, key=lambda log: parse_instant(log["occurredAt"]))
    stop_log = min(stops, key=lambda log: parse_instant(log["occurredAt"]))
    if (start_log.get("targetId") is None
            or start_log.get("targetId") != stop_log.get("targetId")):
        raise ValueError("启动与恢复审计的目标不一致")
    if parse_instant(start_log["occurredAt"]) > parse_instant(stop_log["occurredAt"]):
        raise ValueError("启动与恢复审计的时间顺序无效")
    evidence = {
        "status": "lifecycle_verified",
        "execution_status": execution["status"],
        "target_id": start_log["targetId"],
        "scenario_code": start_log.get("scenarioCode"),
        "audit_events": [{
            "id": log["id"],
            "operation": log["operation"],
            "result": log["result"],
            "occurred_at": log["occurredAt"],
        } for log in (start_log, stop_log)],
        "target_metric_binding": "unverified",
    }
    return start, end, evidence


def observe_window(query, start, end, uri):
    seconds = (end - start).total_seconds()
    if seconds < 20 or seconds > 3600:
        raise ValueError("每个观察窗口必须是 20–3600 秒")
    duration = f"{round(seconds * 1000)}ms"
    route = json.dumps(uri)
    selector = f'job="order-service",uri={route}'
    samples = query(f'sum(count_over_time(up{{job="order-service"}}[{duration}]))', end)
    availability = query(f'min(min_over_time(up{{job="order-service"}}[{duration}]))', end)
    total = query(
        f'sum(increase(http_server_requests_seconds_count{{{selector}}}[{duration}]))', end
    )
    result = {
        "start": start.isoformat(),
        "end": end.isoformat(),
        "scrape_samples": samples,
        "estimated_requests": total,
        "estimated_5xx": None,
        "error_rate": None,
        "p95_seconds": None,
        "status": "insufficient_data",
        "reason": None,
    }
    minimum_samples = max(3, math.floor(seconds / SCRAPE_INTERVAL_SECONDS * 0.8))
    if samples is None or samples < minimum_samples or availability != 1:
        result["reason"] = "抓取样本不足或目标曾不可用"
        return result
    if total is None or total <= 0:
        result["reason"] = "该窗口内没有可计算的订单请求"
        return result
    errors = query(
        f'sum(increase(http_server_requests_seconds_count{{{selector},status=~"5.."}}[{duration}]))', end
    )
    p95 = query(
        "histogram_quantile(0.95, sum by (le) "
        f'(increase(http_server_requests_seconds_bucket{{{selector}}}[{duration}])))', end
    )
    if p95 is None:
        result["reason"] = "缺少延迟直方图，无法计算 P95"
        return result
    result.update({
        "estimated_5xx": errors if errors is not None else 0.0,
        "error_rate": min(1.0, max(0.0, (errors or 0.0) / total)),
        "p95_seconds": p95,
        "status": "observed",
    })
    return result


def build_report(query, start, end, uri, experiment_id, execution_id,
                 source="local Prometheus", evidence=None):
    if end <= start:
        raise ValueError("结束时间必须晚于开始时间")
    duration = end - start
    phases = {
        "before": observe_window(query, start - duration, start, uri),
        "during": observe_window(query, start, end, uri),
        "after": observe_window(query, end, end + duration, uri),
    }
    comparison = None
    if all(phase["status"] == "observed" for phase in phases.values()):
        comparison = {
            "during_minus_before_error_rate_pp": 100 * (
                phases["during"]["error_rate"] - phases["before"]["error_rate"]
            ),
            "after_minus_before_error_rate_pp": 100 * (
                phases["after"]["error_rate"] - phases["before"]["error_rate"]
            ),
            "during_to_before_p95_ratio": (
                phases["during"]["p95_seconds"] / phases["before"]["p95_seconds"]
                if phases["before"]["p95_seconds"] > 0 else None
            ),
        }
    verified = evidence is not None and evidence.get("status") == "lifecycle_verified"
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": source,
        "experiment_id": experiment_id,
        "execution_id": execution_id,
        "evidence": evidence if verified else
        {"status": "manual_unverified", "target_metric_binding": "unverified"},
        "job": "order-service",
        "uri": uri,
        "window_seconds": round(duration.total_seconds(), 3),
        "phases": phases,
        "comparison": comparison,
        "conclusion": (
            "生命周期已有执行记录和审计事件佐证，但目标与指标来源尚未绑定；"
            "这些差异不能证明故障效果或因果关系。"
            if verified else
            "执行 ID 与时间未经后端核验；这些差异仅供演示，不能证明故障效果。"
        ) if comparison is not None else
        "样本不足，无法比较三个阶段；不能据此判断稳态偏离或恢复。",
    }


def render_markdown(report):
    lines = [
        "# ChaosLab 实验观察报告",
        "",
        f"- 生成时间（UTC）：{report['generated_at']}",
        f"- 数据来源：{report['source']}",
        f"- 实验 ID：{report['experiment_id']}",
        f"- 执行 ID：{report['execution_id']}",
        f"- 生命周期证据：{report['evidence']['status']}",
        f"- 目标与指标绑定：{report['evidence']['target_metric_binding']}",
        f"- 观测对象：`{report['job']}`，路由 `{report['uri']}`",
        f"- 每阶段窗口：{report['window_seconds']} 秒",
        "",
        "## 观察事实",
        "",
        "| 阶段（UTC） | 抓取样本 | 估算请求数 | 估算 5xx | 错误率 | P95 延迟 | 状态 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for name, phase in report["phases"].items():
        def number(value, suffix=""):
            return "—" if value is None else f"{value:.3f}{suffix}"
        error_rate = None if phase["error_rate"] is None else phase["error_rate"] * 100
        status = "有效" if phase["status"] == "observed" else phase["reason"]
        lines.append(
            f"| {name} ({phase['start']} → {phase['end']}) | "
            f"{number(phase['scrape_samples'])} | {number(phase['estimated_requests'])} | "
            f"{number(phase['estimated_5xx'])} | {number(error_rate, '%')} | "
            f"{number(phase['p95_seconds'], ' s')} | {status} |"
        )
    lines.extend(["", "## 比较与结论", ""])
    if report["evidence"]["status"] == "lifecycle_verified":
        lines.extend([
            "关联的审计事件：" + "、".join(
                f"`{event['operation']}` (`{event['id']}`)"
                for event in report["evidence"]["audit_events"]
            ),
            "",
        ])
    if report["comparison"] is not None:
        delta = report["comparison"]
        lines.append(
            f"实验中相对基线的错误率变化：{delta['during_minus_before_error_rate_pp']:+.2f} 个百分点；"
            f"恢复后相对基线：{delta['after_minus_before_error_rate_pp']:+.2f} 个百分点。"
        )
    lines.extend([
        report["conclusion"],
        "",
        "说明：`increase` 会按抓取间隔外推，表中请求数并非精确日志计数；"
        "P95 来自直方图桶的估算。三段窗口不重叠，但边界附近请求可能被归入相邻阶段。",
        "",
    ])
    return "\n".join(lines)


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="从本机 Prometheus 生成实验三阶段观察报告")
    parser.add_argument("--prometheus", default="http://127.0.0.1:19090")
    parser.add_argument("--backend", help="ChaosLab 后端本机地址；提供后核验执行与审计记录")
    parser.add_argument("--started-at", help="手动演示模式：执行开始时间，带时区 ISO-8601")
    parser.add_argument("--finished-at", help="手动演示模式：执行结束时间，带时区 ISO-8601")
    parser.add_argument("--experiment-id", required=True)
    parser.add_argument("--execution-id", required=True)
    parser.add_argument("--uri", default="/orders/{orderId}")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--output", help="将已核验报告写入新文件；不会覆盖现有文件")
    args = parser.parse_args(argv)
    for label, address in (("Prometheus", args.prometheus), ("后端", args.backend)):
        if address is None:
            continue
        parsed_url = urlparse(address)
        if (parsed_url.scheme != "http"
                or parsed_url.hostname not in ("127.0.0.1", "localhost")
                or parsed_url.path not in ("", "/")
                or parsed_url.username is not None
                or parsed_url.query or parsed_url.fragment):
            parser.error(f"{label} 地址必须是本机 HTTP 根地址")
    if args.backend and (args.started_at or args.finished_at):
        parser.error("后端核验模式不能同时指定手工时间")
    if not args.backend and not (args.started_at and args.finished_at):
        parser.error("请提供 --backend，或同时提供 --started-at 与 --finished-at")
    if args.output and not args.backend:
        parser.error("只有后端核验模式可以保存报告文件")
    try:
        experiment_id = str(UUID(args.experiment_id))
        execution_id = str(UUID(args.execution_id))
        if args.backend:
            fetch = lambda path: backend_json(args.backend.rstrip("/"), path)
            start, end, evidence = load_execution_evidence(fetch, experiment_id, execution_id)
        else:
            start = parse_instant(args.started_at)
            end = parse_instant(args.finished_at)
            evidence = None
        query = lambda expression, at: prometheus_value(args.prometheus.rstrip("/"), expression, at)
        report = build_report(query, start, end, args.uri, experiment_id, execution_id,
                              args.prometheus, evidence)
        content = json.dumps(report, ensure_ascii=False, indent=2) if args.format == "json" else render_markdown(report)
        if args.output:
            with Path(args.output).open("x", encoding="utf-8", newline="\n") as output:
                output.write(content + "\n")
            print(f"已保存 {args.output}；SHA-256: {hashlib.sha256((content + chr(10)).encode('utf-8')).hexdigest()}")
        else:
            print(content)
    except (ValueError, OSError) as exception:
        print(f"报告生成失败：{exception}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
