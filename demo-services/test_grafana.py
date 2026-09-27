import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
DASHBOARD = ROOT / "grafana" / "dashboards" / "order-service.json"


class GrafanaDashboardTests(unittest.TestCase):
    def test_dashboard_uses_only_order_business_route_and_provisioned_source(self):
        dashboard = json.loads(DASHBOARD.read_text(encoding="utf-8"))
        self.assertEqual(dashboard["uid"], "chaoslab-order-service")
        self.assertEqual(len(dashboard["panels"]), 3)
        expressions = []
        for panel in dashboard["panels"]:
            self.assertEqual(panel["datasource"]["uid"], "chaoslab-prometheus")
            expression = panel["targets"][0]["expr"]
            self.assertIn('job="order-service"', expression)
            self.assertIn('uri="/orders/{orderId}"', expression)
            self.assertIn("$__rate_interval", expression)
            expressions.append(expression)
        self.assertIn("http_server_requests_seconds_count", expressions[0])
        self.assertIn('status=~"5.."', expressions[1])
        self.assertIn("or vector(0)", expressions[1])
        self.assertIn("histogram_quantile(0.95", expressions[2])
        self.assertIn("http_server_requests_seconds_bucket", expressions[2])


if __name__ == "__main__":
    unittest.main()
