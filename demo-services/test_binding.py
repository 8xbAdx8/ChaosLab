import json
import unittest
from datetime import datetime, timezone

from binding import COMPOSE_FILE, NETWORK, SERVICES, verify_local_demo_binding


class BindingTests(unittest.TestCase):
    def setUp(self):
        self.target = {
            "id": "target-1", "name": "order-service", "type": "DOCKER_CONTAINER",
            "environment": "CHAOS_LAB", "enabled": True,
        }
        self.window_start = datetime(2026, 9, 27, 10, tzinfo=timezone.utc)
        self.targets = [{
            "labels": {"job": "order-service", "instance": "order-service:8080"},
            "scrapeUrl": "http://order-service:8080/actuator/prometheus",
            "health": "up",
        }]
        self.context = {"Name": "desktop-linux", "Endpoints": {"docker": {
            "Host": "npipe:////./pipe/dockerDesktopLinuxEngine"
        }}}
        self.container = {
            "Id": "order-id", "Image": "sha256:image", "State": {
                "Running": True, "StartedAt": "2026-09-27T09:00:00Z",
                "Health": {"Status": "healthy"},
            },
            "Config": {"Labels": {
                "com.docker.compose.project": "chaoslab-demo",
                "com.docker.compose.service": "order-service",
                "com.docker.compose.project.config_files": str(COMPOSE_FILE),
                "com.docker.compose.image": "sha256:image",
                "com.chaoslab.role": "demo-target",
                "com.chaoslab.environment": "CHAOS_LAB",
                "com.chaoslab.service": "order-service",
            }},
            "NetworkSettings": {"Networks": {NETWORK: {}}},
        }
        self.network = {
            "Name": NETWORK,
            "Labels": {"com.docker.compose.project": "chaoslab-demo",
                       "com.docker.compose.network": "chaoslab-demo-net"},
            "Containers": {"order-id": {}, "inventory-id": {}, "prometheus-id": {}},
        }

    def output(self, *args):
        if args == ("context", "inspect"):
            return json.dumps([self.context])
        if args[:5] == ("compose", "--profile", "observability", "-f", str(COMPOSE_FILE)):
            self.assertEqual(args[5], "ps")
            self.assertEqual(args[6], "-q")
            self.assertIn(args[7], SERVICES)
            return {"order-service": "order-id", "inventory-service": "inventory-id",
                    "prometheus": "prometheus-id"}[args[7]]
        if args == ("inspect", "order-id"):
            return json.dumps([self.container])
        if args == ("network", "inspect", NETWORK):
            return json.dumps([self.network])
        self.fail(args)

    def verify(self):
        return verify_local_demo_binding(self.target, [self.target], "target-1", self.window_start,
                                         self.targets, self.output)

    def test_valid_local_demo_binding(self):
        evidence = self.verify()
        self.assertEqual(evidence["status"], "verified_local_demo")
        self.assertEqual(evidence["container_id"], "order-id")

    def test_wrong_target_or_unhealthy_scrape_fails_closed(self):
        self.target["environment"] = "PRODUCTION"
        with self.assertRaisesRegex(ValueError, "Target"):
            self.verify()
        self.target["environment"] = "CHAOS_LAB"
        self.targets[0]["health"] = "down"
        with self.assertRaisesRegex(ValueError, "抓取目标"):
            self.verify()

    def test_recreated_container_or_unexpected_network_member_fails_closed(self):
        self.container["State"]["StartedAt"] = "2026-09-27T10:01:00Z"
        with self.assertRaisesRegex(ValueError, "基线窗口开始后"):
            self.verify()
        self.container["State"]["StartedAt"] = "2026-09-27T09:00:00Z"
        self.network["Containers"]["extra-id"] = {}
        with self.assertRaisesRegex(ValueError, "非预期容器"):
            self.verify()

    def test_remote_docker_context_fails_closed(self):
        self.context["Endpoints"]["docker"]["Host"] = "ssh://remote-host"
        with self.assertRaisesRegex(ValueError, "不是本机"):
            self.verify()

    def test_duplicate_target_alias_fails_closed(self):
        alias = dict(self.target, id="target-2")
        with self.assertRaisesRegex(ValueError, "重复"):
            verify_local_demo_binding(self.target, [self.target, alias], "target-1",
                                      self.window_start, self.targets, self.output)


if __name__ == "__main__":
    unittest.main()
