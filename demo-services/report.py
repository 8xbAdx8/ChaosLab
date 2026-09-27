"""Generate a three-window observation report from the local Prometheus API.

This is a read-only teaching tool. It does not start or stop experiments.
"""

import argparse
import json
import math
import sys
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode, urlparse
from urllib.request import urlopen


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


def build_report(query, start, end, uri, experiment_id, execution_id, source="local Prometheus"):
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
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": source,
        "experiment_id": experiment_id,
        "execution_id": execution_id,
        "job": "order-service",
        "uri": uri,
        "window_seconds": round(duration.total_seconds(), 3),
        "phases": phases,
        "comparison": comparison,
        "conclusion": "仅记录观察关联；FakeChaosEngine 不注入真实故障，不能据此证明故障效果或因果关系。"
        if comparison is not None else "样本不足，无法比较三个阶段；不能据此判断稳态偏离或恢复。",
    }


def render_markdown(report):
    lines = [
        "# ChaosLab 实验观察报告",
        "",
        f"- 生成时间（UTC）：{report['generated_at']}",
        f"- 数据来源：{report['source']}",
        f"- 实验 ID（调用者提供，未校验）：{report['experiment_id']}",
        f"- 执行 ID（调用者提供，未校验）：{report['execution_id']}",
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
    parser.add_argument("--started-at", required=True, help="执行开始时间，带时区 ISO-8601")
    parser.add_argument("--finished-at", required=True, help="执行结束时间，带时区 ISO-8601")
    parser.add_argument("--experiment-id", required=True)
    parser.add_argument("--execution-id", required=True)
    parser.add_argument("--uri", default="/orders/{orderId}")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    args = parser.parse_args(argv)
    parsed_url = urlparse(args.prometheus)
    if parsed_url.scheme != "http" or parsed_url.hostname not in ("127.0.0.1", "localhost") or parsed_url.path not in ("", "/"):
        parser.error("Prometheus 地址必须是本机 HTTP 地址")
    try:
        start = parse_instant(args.started_at)
        end = parse_instant(args.finished_at)
        query = lambda expression, at: prometheus_value(args.prometheus.rstrip("/"), expression, at)
        report = build_report(query, start, end, args.uri, args.experiment_id, args.execution_id, args.prometheus)
        print(json.dumps(report, ensure_ascii=False, indent=2) if args.format == "json" else render_markdown(report))
    except (ValueError, OSError) as exception:
        print(f"报告生成失败：{exception}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
