"""Exercise platform reports with disposable MySQL and the local Docker Demo.

Requires a packaged backend JAR, Java 21+, and built Demo images. Keeps Demo
services running; removes only its uniquely named, tmpfs-backed MySQL container.
"""

import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]
HTTP = build_opener(ProxyHandler({}))
NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(*args, env=None):
    result = subprocess.run(args, cwd=ROOT, env=env, text=True,
                            encoding="utf-8", capture_output=True,
                            timeout=180, creationflags=NO_WINDOW)
    require(result.returncode == 0,
            f"{args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def request(url, method="GET", body=None, key=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    payload = None if body is None else json.dumps(body).encode()
    try:
        with HTTP.open(Request(url, data=payload, headers=headers, method=method),
                       timeout=60) as response:
            require(response.status == expected,
                    f"{method} {url}: expected {expected}, got {response.status}")
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError(f"{method} {url}: {error.code} {error.read().decode()}") from error


def wait_for(check, label, seconds=90):
    deadline = time.monotonic() + seconds
    last_error = None
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, RuntimeError) as error:
            last_error = error
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for {label}: {last_error}")


def instant(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java", help="Java 21+ executable")
    parser.add_argument("--mysql-image", default="mysql:8.4")
    parser.add_argument("--port", type=int, default=18080)
    args = parser.parse_args()
    jar = ROOT / "backend/target/chaoslab-backend-0.0.1-SNAPSHOT.jar"
    require(jar.is_file(), "Package backend first: mvnw -f backend/pom.xml package")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))

    run_id = uuid4().hex
    container = f"chaoslab-report-check-{run_id[:12]}"
    output = ROOT / "backend/target/report-acceptance" / run_id
    output.mkdir(parents=True, exist_ok=False)
    base = f"http://127.0.0.1:{args.port}"
    api = base + "/api/v1"
    process = None
    log = None
    mysql_created = False
    execution_path = None
    stop_load = threading.Event()
    traffic = {"success": 0, "failure": 0}
    worker = None
    evidence = {"runId": run_id, "checks": [], "traffic": traffic}
    mysql_env = dict(os.environ, MYSQL_PASSWORD=secrets.token_hex(24))

    def sql(query):
        return command("docker", "exec", container, "sh", "-c",
                       'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -uchaoslab '
                       '-Dchaoslab_acceptance -N -B -e "$1"', "mysql-check", query)

    def stop_backend():
        nonlocal process, log
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            process = None
        if log is not None:
            log.close()
            log = None

    def start_backend(env, name):
        nonlocal process, log
        log = (output / f"{name}.log").open("w", encoding="utf-8")
        process = subprocess.Popen([
            args.java, "-jar", str(jar), "--server.address=127.0.0.1",
            f"--server.port={args.port}"], cwd=ROOT, env=env,
            stdout=log, stderr=subprocess.STDOUT, creationflags=NO_WINDOW)

        def ready():
            require(process.poll() is None, f"Backend exited; see {output / (name + '.log')}")
            return request(base + "/actuator/health").get("status") == "UP"

        wait_for(ready, "backend")

    def load():
        while not stop_load.is_set():
            try:
                request("http://127.0.0.1:18081/orders/order-1")
                traffic["success"] += 1
            except (OSError, RuntimeError):
                traffic["failure"] += 1
            stop_load.wait(0.5)

    def check(name, condition):
        require(condition, name)
        evidence["checks"].append(name)
        print("PASS " + name, flush=True)

    try:
        print(f"Evidence directory: {output}", flush=True)
        command("docker", "compose", "--profile", "observability", "-f",
                str(ROOT / "demo-services/compose.yml"), "up", "-d", "--wait", "--no-build")
        request("http://127.0.0.1:18081/orders/order-1")
        command("docker", "run", "-d", "--pull=never", "--name", container,
                "--label", f"com.chaoslab.acceptance={run_id}",
                "-p", "127.0.0.1::3306", "--tmpfs", "/var/lib/mysql:rw",
                "-e", "MYSQL_RANDOM_ROOT_PASSWORD=yes", "-e", "MYSQL_DATABASE=chaoslab_acceptance",
                "-e", "MYSQL_USER=chaoslab", "-e", "MYSQL_PASSWORD",
                args.mysql_image, env=mysql_env)
        mysql_created = True
        port = command("docker", "port", container, "3306/tcp").split(":")[-1]
        wait_for(lambda: sql("SELECT 1") == "1", "MySQL")
        evidence["mysqlVersion"] = sql("SELECT VERSION()")
        backend_env = dict(os.environ,
            CHAOSLAB_DB_URL=f"jdbc:mysql://127.0.0.1:{port}/chaoslab_acceptance?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
            CHAOSLAB_DB_USERNAME="chaoslab", CHAOSLAB_DB_PASSWORD=mysql_env["MYSQL_PASSWORD"],
            CHAOSLAB_DEMO_BINDING_SCRIPT=str(ROOT / "demo-services/binding.py"),
            CHAOSLAB_REPORT_PROMETHEUS_URL="http://127.0.0.1:19090")
        start_backend(backend_env, "backend")
        check("MySQL migration V10 applied", sql(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='10' AND success=1") == "1")

        target = request(api + "/targets", "POST", {
            "name": "order-service", "type": "DOCKER_CONTAINER", "environment": "CHAOS_LAB"}, expected=201)
        scenario = next(item for item in request(api + "/scenarios") if item["code"] == "CPU_LOAD")
        experiment = request(api + "/experiments", "POST", {
            "name": "Report acceptance " + run_id[:8], "hypothesis": "Local Demo remains available during Fake execution",
            "targetId": target["id"], "scenarioId": scenario["id"],
            "durationSeconds": 30, "parameters": {"percent": 40}}, expected=201)
        experiment_path = api + "/experiments/" + experiment["id"]
        request(experiment_path + "/validation", "POST")
        dry_run = request(experiment_path + "/dry-run", "POST")
        evidence["dryRun"] = dry_run
        check("Docker target passes identity safety gate", dry_run["accepted"])

        worker = threading.Thread(target=load, daemon=True)
        worker.start()
        print("Generating baseline traffic for 45 seconds", flush=True)
        time.sleep(45)
        execution = request(experiment_path + "/executions", "POST", key=run_id, expected=201)
        execution_path = experiment_path + "/executions/" + execution["id"]
        check("Execution uses Fake engine", execution["engineExperimentId"] == "fake-" + execution["id"])
        print("Waiting for the 30-second automatic recovery", flush=True)
        wait_for(lambda: request(execution_path)["status"] == "SUCCESS", "automatic recovery", seconds=45)
        recovered = request(execution_path)
        evidence["execution"] = recovered
        check("Execution automatically recovered", recovered["status"] == "SUCCESS")
        report_path = execution_path + "/reports"
        early = request(report_path, "POST", key="early", expected=201)
        evidence["earlyReport"] = early
        check("Early report is bound but uncollected", early["bindingStatus"] == "VERIFIED_LOCAL_DEMO"
              and early["metricsStatus"] == "NOT_COLLECTED")
        start, end = instant(recovered["startedAt"]), instant(recovered["finishedAt"])
        deadline = end + (end - start)
        wait_seconds = max(0, (deadline - datetime.now(timezone.utc)).total_seconds() + 8)
        print(f"Observing recovery for {wait_seconds:.1f} seconds", flush=True)
        time.sleep(wait_seconds)
        observed = request(report_path, "POST", key="observed", expected=201)
        evidence["report"] = observed
        check("All three windows are observed", observed["metricsStatus"] == "OBSERVED"
              and len(observed["windows"]) == 3
              and all(window["metricsStatus"] == "OBSERVED" for window in observed["windows"]))
        check("Metrics do not imply real fault effects", observed["conclusionStatus"] == "SIMULATED_ONLY")
        check("Order traffic succeeded", traffic["success"] > 100 and traffic["failure"] == 0)
        saved = request(report_path + "/" + observed["id"])
        evidence["readbackReport"] = saved
        check("Metrics survive MySQL readback", saved["windows"] == observed["windows"])
        check("Created report matches stored snapshot", saved == observed)
        check("Idempotent report replay", request(report_path, "POST", key="observed") == saved)
        early_saved = request(report_path + "/" + early["id"])
        check("Early snapshot stays immutable", request(report_path, "POST", key="early") == early_saved
              and early_saved["metricsStatus"] == "NOT_COLLECTED")
        check("MySQL stores a three-window JSON array", sql(
            "SELECT CONCAT(JSON_TYPE(observations), ':', JSON_LENGTH(observations)) "
            f"FROM experiment_reports WHERE id='{observed['id']}'") == "ARRAY:3")
        stop_load.set()
        worker.join(timeout=10)

        stop_backend()
        # A bound, non-listening loopback socket makes the metrics endpoint
        # unavailable without stopping the user's Prometheus container.
        with socket.socket() as unavailable:
            unavailable.bind(("127.0.0.1", 0))
            backend_env["CHAOSLAB_REPORT_PROMETHEUS_URL"] = f"http://127.0.0.1:{unavailable.getsockname()[1]}"
            start_backend(backend_env, "backend-restarted")
            check("Snapshot survives backend restart", request(report_path + "/" + saved["id"]) == saved)
            check("Replay needs no Prometheus", request(report_path, "POST", key="observed") == saved)
            failed = request(report_path, "POST", key="offline", expected=201)
            evidence["unavailableReport"] = failed
            check("Unavailable metrics fail closed", failed["bindingStatus"] == "VERIFIED_LOCAL_DEMO"
                  and failed["metricsStatus"] == "INSUFFICIENT_DATA"
                  and failed["conclusionStatus"] == "INSUFFICIENT_DATA")
        evidence["status"] = "passed"
    except Exception as error:
        evidence["status"] = "failed"
        evidence["error"] = str(error)
        raise
    finally:
        stop_load.set()
        if worker is not None:
            worker.join(timeout=10)
        if execution_path and process is not None and process.poll() is None:
            try:
                request(execution_path + "/destroy", "POST")
            except (OSError, RuntimeError) as error:
                evidence["cleanupRecoveryError"] = str(error)
        stop_backend()
        if mysql_created:
            label = command("docker", "inspect", "--format",
                            '{{index .Config.Labels "com.chaoslab.acceptance"}}', container)
            require(label == run_id, "Refusing to remove a container not owned by this run")
            command("docker", "rm", "-f", "-v", container)
        (output / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Result: {evidence.get('status', 'incomplete')}; evidence: {output / 'evidence.json'}", flush=True)


if __name__ == "__main__":
    main()
