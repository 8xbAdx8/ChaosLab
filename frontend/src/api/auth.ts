import { z } from "zod";
import { ApiError, get } from "./client";
export const identitySchema = z.object({
  mode: z.enum(["SESSION", "LOCAL_HISTORY"]),
  authenticated: z.boolean(),
  username: z.string().nullable(),
  roles: z.array(z.enum(["VIEWER", "ADMIN"])),
  readOnly: z.literal(true),
  realExecutionAllowed: z.literal(false),
});
export type Identity = z.infer<typeof identitySchema>;
export const anonymous: Identity = {
  mode: "SESSION",
  authenticated: false,
  username: null,
  roles: [],
  readOnly: true,
  realExecutionAllowed: false,
};
export const accessSchema = z.object({
  capabilities: z.array(z.string()),
  realExecutionAllowed: z.literal(false),
});
const csrfSchema = z.object({
  headerName: z.literal("X-CSRF-TOKEN"),
  token: z.string().min(1),
});
export async function sessionAction(
  operation: "login" | "logout",
  credentials?: { username: string; password: string },
): Promise<void> {
  // Fetch a fresh masked session CSRF token for each action. Not persisted in localStorage/cookies.
  const token = await get("/api/v1/auth/csrf", csrfSchema);
  const base = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  let response: Response;
  try {
    response = await fetch(`${base}/api/v1/auth/${operation}`, {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        [token.headerName]: token.token,
      },
      body:
        operation === "login" && credentials
          ? new URLSearchParams(credentials).toString()
          : "",
    });
  } catch {
    throw new ApiError(0, "无法连接登录服务，请检查同源代理配置");
  }
  // No automatic mutation retry; never display raw server/credential/error content.
  if (!response.ok)
    throw new ApiError(
      response.status,
      response.status === 401
        ? "登录失败，请核对用户名与密码"
        : response.status === 403
          ? "安全校验失败，请重新尝试"
          : `认证请求失败（HTTP ${response.status}）`,
    );
}
