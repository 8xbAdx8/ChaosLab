"""Read-only local Demo target-to-container-to-Prometheus binding checks."""

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import urlopen


COMPOSE_FILE = Path(__file__).resolve().parent / "compose.yml"
NETWORK = "chaoslab-demo_chaoslab-demo-net"
SERVICES = ("order-service", "inventory-service", "prometheus")


def docker_output(*args):
    completed = subprocess.run(
        ["docker", *args], capture_output=True, text=True,
        encoding="utf-8", timeout=15, check=True,
    )
    return completed.stdout.strip()


def docker_document(output, *args):
    try:
        value = json.loads(output(*args))
    except json.JSONDecodeError as error:
        raise ValueError("Docker 未返回有效 JSON") from error
    if not isinstance(value, list) or len(value) != 1 or not isinstance(value[0], dict):
        raise ValueError("Docker inspect 未返回唯一对象")
    return value[0]


def parse_docker_time(value):
    if not isinstance(value, str):
        raise ValueError("容器缺少启动时间")
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.tzinfo is None:
        raise ValueError("容器启动时间缺少时区")
    return instant.astimezone(timezone.utc)


def verify_local_demo_binding(target, registered_targets, expected_target_id,
                              window_start, active_targets, output=docker_output):
    """Verify one explicit local order target and return inspectable evidence."""
    if window_start.tzinfo is None:
        raise ValueError("基线时间必须包含时区")
    if not isinstance(target, dict) or (
            target.get("id") != expected_target_id
            or target.get("name") != "order-service"
            or target.get("type") != "DOCKER_CONTAINER"
            or target.get("environment") != "CHAOS_LAB"
            or target.get("enabled") is not True):
        raise ValueError("Target 不是已启用的隔离订单容器目标")
    if not isinstance(registered_targets, list) or any(
            not isinstance(item, dict) for item in registered_targets):
        raise ValueError("Target 列表无效")
    aliases = [item for item in registered_targets
               if item.get("name") == "order-service"]
    if len(aliases) != 1 or aliases[0].get("id") != expected_target_id:
        raise ValueError("订单容器存在重复或不匹配的 Target 别名")
    context = docker_document(output, "context", "inspect")
    endpoints = context.get("Endpoints")
    docker_endpoint = endpoints.get("docker") if isinstance(endpoints, dict) else None
    host = docker_endpoint.get("Host", "") if isinstance(docker_endpoint, dict) else ""
    if not isinstance(host, str) or not (
            host.startswith("npipe://") or host.startswith("unix://")):
        raise ValueError("Docker context 不是本机管道或 Unix socket")
    compose = ("compose", "--profile", "observability", "-f", str(COMPOSE_FILE))
    service_ids = {}
    for service in SERVICES:
        ids = output(*compose, "ps", "-q", service).splitlines()
        if len(ids) != 1 or not ids[0].strip():
            raise ValueError(f"Compose 服务 {service} 缺失或不唯一")
        service_ids[service] = ids[0].strip()
    if len(set(service_ids.values())) != len(SERVICES):
        raise ValueError("Compose 服务容器 ID 重复")
    container = docker_document(output, "inspect", service_ids["order-service"])
    if container.get("Id") != service_ids["order-service"]:
        raise ValueError("Docker inspect 容器 ID 与 Compose 不一致")
    config = container.get("Config")
    labels = config.get("Labels") if isinstance(config, dict) else None
    required_labels = {
        "com.docker.compose.project": "chaoslab-demo",
        "com.docker.compose.service": "order-service",
        "com.chaoslab.role": "demo-target",
        "com.chaoslab.environment": "CHAOS_LAB",
        "com.chaoslab.service": "order-service",
    }
    if not isinstance(labels, dict) or any(
            labels.get(key) != value for key, value in required_labels.items()):
        raise ValueError("订单容器的 Compose／靶场标签不匹配")
    image_id = container.get("Image")
    if (not isinstance(image_id, str) or not image_id.startswith("sha256:")
            or labels.get("com.docker.compose.image") != image_id):
        raise ValueError("订单容器镜像身份与 Compose 不匹配")
    config_file = labels.get("com.docker.compose.project.config_files")
    if not config_file or Path(config_file).resolve() != COMPOSE_FILE:
        raise ValueError("订单容器不是由当前仓库的 Compose 文件创建")
    state = container.get("State")
    if not isinstance(state, dict):
        raise ValueError("订单容器缺少运行状态")
    health = state.get("Health")
    if not state.get("Running") or not isinstance(health, dict) or health.get("Status") != "healthy":
        raise ValueError("订单容器未健康运行")
    started_at = parse_docker_time(state.get("StartedAt"))
    if started_at > window_start.astimezone(timezone.utc):
        raise ValueError("订单容器在基线窗口开始后启动或重启")
    network_settings = container.get("NetworkSettings")
    networks = network_settings.get("Networks") if isinstance(network_settings, dict) else None
    if not isinstance(networks, dict) or NETWORK not in networks:
        raise ValueError("订单容器不在专用 Demo 网络")
    network = docker_document(output, "network", "inspect", NETWORK)
    network_labels = network.get("Labels")
    if (network.get("Name") != NETWORK
            or not isinstance(network_labels, dict)
            or network_labels.get("com.docker.compose.project") != "chaoslab-demo"
            or network_labels.get("com.docker.compose.network") != "chaoslab-demo-net"):
        raise ValueError("专用 Demo 网络的身份不匹配")
    network_containers = network.get("Containers")
    if not isinstance(network_containers, dict):
        raise ValueError("专用 Demo 网络缺少容器列表")
    network_ids = set(network_containers.keys())
    if network_ids != set(service_ids.values()):
        raise ValueError("专用 Demo 网络包含非预期容器或缺少服务")
    if not isinstance(active_targets, list):
        raise ValueError("Prometheus active targets 响应无效")
    matches = [item for item in active_targets if isinstance(item, dict)
               and isinstance(item.get("labels"), dict)
               and item["labels"].get("job") == "order-service"]
    if len(matches) != 1:
        raise ValueError("Prometheus 订单抓取目标缺失或不唯一")
    metric_target = matches[0]
    scrape_url = "http://order-service:8080/actuator/prometheus"
    if (metric_target.get("health") != "up"
            or metric_target.get("scrapeUrl") != scrape_url
            or metric_target.get("labels", {}).get("instance") != "order-service:8080"):
        raise ValueError("Prometheus 订单抓取目标不健康或地址不匹配")
    return {
        "status": "verified_local_demo",
        "target_id": expected_target_id,
        "container_id": container["Id"],
        "image_id": image_id,
        "container_started_at": started_at.isoformat(),
        "docker_context": context.get("Name"),
        "network": NETWORK,
        "job": "order-service",
        "scrape_url": scrape_url,
        "checked_at": datetime.now(timezone.utc).isoformat(),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description="只读核验本机隔离订单容器身份")
    parser.add_argument("--target-id", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--type", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--enabled", required=True, choices=("true", "false"))
    parser.add_argument("--alias-count", required=True, type=int)
    parser.add_argument("--window-start", required=True)
    args = parser.parse_args(argv)
    if args.alias_count != 1:
        parser.error("订单服务的 Target 别名必须恰好一个")
    target = {
        "id": args.target_id,
        "name": args.name,
        "type": args.type,
        "environment": args.environment,
        "enabled": args.enabled == "true",
    }
    try:
        window_start = parse_docker_time(args.window_start)
        with urlopen("http://127.0.0.1:19090/api/v1/targets?state=active", timeout=5) as response:
            payload = json.load(response)
        if payload.get("status") != "success":
            raise ValueError("Prometheus targets 查询失败")
        targets = payload.get("data", {}).get("activeTargets")
        binding = verify_local_demo_binding(
            target, [target], args.target_id, window_start, targets
        )
        print(json.dumps(binding, ensure_ascii=False))
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"绑定验证失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
