import { z } from "zod";
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}
export function isReadPath(path: string): boolean {
  return (
    /^\/api\/v1\/console\/reports\/[0-9a-f-]{36}\/analysis$/.test(path) ||
    /^\/api\/v1\/console\/(overview|experiments|executions|audit-logs|reports)(\/[0-9a-f-]{36})?(\/evidence)?$/.test(
      path,
    ) ||
    [
      "/api/v1/targets",
      "/api/v1/scenarios",
      "/actuator/health",
      "/api/v1/console/history/m1-core",
      "/api/v1/console/access",
      "/api/v1/auth/me",
      "/api/v1/auth/csrf",
    ].includes(path)
  );
}
export async function get<T>(
  path: string,
  schema: z.ZodType<T>,
  signal?: AbortSignal,
): Promise<T> {
  if (!isReadPath(path.split("?")[0] ?? ""))
    throw new ApiError(403, "控制台不允许此接口");
  const base = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  let response: Response;
  try {
    response = await fetch(`${base}${path}`, {
      method: "GET",
      credentials: "same-origin",
      signal,
      headers: { Accept: "application/json" },
    });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new ApiError(0, "无法连接后端，请检查 API 地址与代理配置");
  }
  // Never render raw backend bodies, stacktraces or privileged stderr.
  if (response.status === 401 && !path.startsWith("/api/v1/auth/"))
    window.dispatchEvent(new Event("chaoslab-auth-expired"));
  if (!response.ok)
    throw new ApiError(
      response.status,
      response.status === 401
        ? "登录已失效，请重新登录"
        : response.status === 403
          ? "权限不足：当前账号不能读取此接口"
          : response.status === 404
            ? "记录不存在或已不可见"
            : `读取失败（HTTP ${response.status}）`,
    );
  try {
    return schema.parse(await response.json());
  } catch {
    throw new ApiError(502, "API 响应与当前契约不匹配，请核对前后端版本");
  }
}
export function params(
  values: Record<string, string | number | undefined>,
): string {
  const out = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== undefined && value !== "") out.set(key, String(value));
  const query = out.toString();
  return query ? `?${query}` : "";
}
