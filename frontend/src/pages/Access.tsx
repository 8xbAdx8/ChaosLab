import { useQuery } from "@tanstack/react-query";
import { get } from "../api/client";
import { accessSchema } from "../api/auth";
import { Badge, Heading, Panel, QueryState } from "../components/ui";
export function AccessPage() {
  const query = useQuery({
    queryKey: ["access"],
    queryFn: ({ signal }) =>
      get("/api/v1/console/access", accessSchema, signal),
  });
  return (
    <>
      <Heading
        eyebrow="ADMIN / READ-ONLY PERMISSION FOUNDATION"
        title="权限信息"
        description="ADMIN 只读权限摘要，不是用户管理或故障授权入口。"
      />
      <QueryState query={query}>
        {(data) => (
          <Panel title="当前已开放能力">
            <p>{data.capabilities.join(" · ")}</p>
            <div className="notice compact">
              <p>
                真实故障执行：
                <Badge
                  value={data.realExecutionAllowed ? "ENABLED" : "DISABLED"}
                />
                。VIEWER 与 ADMIN 都不能 create/destroy、生成授权或执行 root
                命令。
              </p>
            </div>
          </Panel>
        )}
      </QueryState>
    </>
  );
}
export function PermissionDenied() {
  return (
    <div className="query-state error" role="alert">
      <h2>权限不足</h2>
      <p>当前角色不能查看此页面。权限由服务端验证，隐藏菜单不代表授权。</p>
    </div>
  );
}
