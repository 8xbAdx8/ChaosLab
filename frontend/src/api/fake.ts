import { z } from "zod";
import { type Identity, canManageFake } from "./auth";
import { ApiError, get } from "./client";
import { fakeMutationPath } from "./fakePaths";

/** Fixed business routes, CSRF, no engine/path/authorization inputs and no automatic write retry. */
export async function fakeWrite<T>(
  identity: Identity,
  method: "POST" | "PUT",
  path: string,
  schema: z.ZodType<T>,
  body?: unknown,
  key?: string,
): Promise<T> {
  if (!canManageFake(identity) || !fakeMutationPath(method, path))
    throw new ApiError(403, "仅 FAKE_DEMO 中的 ADMIN 可执行此操作");
  const token = await get(
    "/api/v1/auth/csrf",
    z.object({
      headerName: z.literal("X-CSRF-TOKEN"),
      token: z.string().min(1),
    }),
  );
  const base = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  let response: Response;
  try {
    response = await fetch(`${base}${path}`, {
      method,
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        [token.headerName]: token.token,
        ...(key ? { "Idempotency-Key": key } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "请求结果未知，请刷新持久化状态；不会自动重新执行");
  }
  if (response.status === 401)
    window.dispatchEvent(new Event("chaoslab-auth-expired"));
  if (!response.ok) {
    const messages: Record<number, string> = {
      401: "登录已失效，请重新登录",
      403: "权限或 CSRF 校验失败",
      409: "状态或版本冲突，请刷新后检查",
      422: "参数不符合场景约束",
      400: "请求格式或字段无效",
    };
    throw new ApiError(
      response.status,
      messages[response.status] ??
        `操作失败（HTTP ${response.status}），请检查持久化状态`,
    );
  }
  try {
    return schema.parse(await response.json());
  } catch {
    throw new ApiError(
      502,
      "响应契约不匹配，操作结果未知；请读取状态，不自动重试",
    );
  }
}

export function intentKey(
  operation: "start" | "report",
  id: string,
  username: string | null,
): string {
  const name = `chaoslab-fake-${operation}:${username ?? ""}:${id}`;
  let key = sessionStorage.getItem(name);
  if (!key) {
    key = crypto.randomUUID();
    sessionStorage.setItem(name, key);
  }
  return key; // Survives reload/ambiguous transport failure; only IDs, never credentials or CSRF.
}
export const activeExecution = (status: string) =>
  [
    "PREPARING",
    "RUNNING",
    "DESTROYING",
    "CREATE_UNCERTAIN",
    "ROLLBACK_FAILED",
  ].includes(status);
