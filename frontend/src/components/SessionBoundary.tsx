import { useEffect, useState, type ReactNode, type FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { LockKeyhole, Activity } from "lucide-react";
import {
  identitySchema,
  anonymous,
  sessionAction,
  type Identity,
} from "../api/auth";
import { get } from "../api/client";
import { QueryState } from "./ui";

function returnPath(value: unknown) {
  return typeof value === "string" &&
    /^\/(?:experiments(?:\/[0-9a-f-]{36})?|executions(?:\/[0-9a-f-]{36})?|audit|reports(?:\/[0-9a-f-]{36})?|access|history\/m1-core)?(?:\?[^\\]*)?$/.test(
      value,
    )
    ? value
    : "/";
}
function returnFromState(state: unknown) {
  const from =
    state && typeof state === "object" && "from" in state
      ? state.from
      : undefined;
  return returnPath(from);
}
export function SessionBoundary({
  children,
}: {
  children: (identity: Identity, logout: () => Promise<void>) => ReactNode;
}) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const query = useQuery({
    queryKey: ["identity"],
    queryFn: ({ signal }) => get("/api/v1/auth/me", identitySchema, signal),
    staleTime: 60_000,
    retry: false,
    refetchOnWindowFocus: true,
  });
  useEffect(() => {
    const expired = () => {
      void client.cancelQueries();
      client.removeQueries({ predicate: (q) => q.queryKey[0] !== "identity" });
      client.setQueryData(["identity"], anonymous);
    };
    window.addEventListener("chaoslab-auth-expired", expired);
    return () => window.removeEventListener("chaoslab-auth-expired", expired);
  }, [client]);
  const loggedIn = async () => {
    await client.cancelQueries();
    client.removeQueries({ predicate: (q) => q.queryKey[0] !== "identity" });
    const identity = await get("/api/v1/auth/me", identitySchema);
    client.setQueryData(["identity"], identity);
    navigate(returnFromState(location.state), { replace: true });
  };
  const logout = async () => {
    await sessionAction("logout");
    await client.cancelQueries();
    client.removeQueries({ predicate: (q) => q.queryKey[0] !== "identity" });
    client.setQueryData(["identity"], anonymous);
    navigate("/login", { replace: true });
  };
  return (
    <QueryState query={query}>
      {(identity) => {
        if (identity.mode === "LOCAL_HISTORY") {
          if (location.pathname !== "/history/m1-core")
            return <Navigate to="/history/m1-core" replace />;
          return children(identity, logout);
        }
        if (!identity.authenticated)
          return location.pathname === "/login" ? (
            <LoginPage onLoggedIn={loggedIn} />
          ) : (
            <Navigate
              to="/login"
              replace
              state={{ from: location.pathname + location.search }}
            />
          );
        if (location.pathname === "/login")
          return <Navigate to={returnFromState(location.state)} replace />;
        if (
          !identity.roles.some((role) => role === "VIEWER" || role === "ADMIN")
        )
          return (
            <div className="app dark">
              <div className="query-state error" role="alert">
                权限不足：当前账号没有控制台读取权限。
              </div>
            </div>
          );
        return children(identity, logout);
      }}
    </QueryState>
  );
}
function LoginPage({ onLoggedIn }: { onLoggedIn: () => Promise<void> }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    setPending(true);
    setError(null);
    try {
      await sessionAction("login", { username, password });
      await onLoggedIn();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "登录暂不可用");
    } finally {
      setPassword("");
      setPending(false);
    }
  }
  const storedTheme =
    localStorage.getItem("chaoslab-theme") === "light" ? "light" : "dark";
  return (
    <div className={`app ${storedTheme} login-layout`}>
      <section className="login-card panel">
        <div className="brand">
          <span className="brand-symbol">
            <Activity size={22} />
          </span>
          <span>
            ChaosLab<small>READ-ONLY CONSOLE</small>
          </span>
        </div>
        <p className="eyebrow">SESSION AUTHENTICATION</p>
        <h1>登录控制台</h1>
        <p className="muted">安全服务端会话 · 只读权限 · 不开放故障执行</p>
        <form onSubmit={(event) => void submit(event)}>
          <label>
            用户名
            <input
              autoComplete="username"
              required
              maxLength={64}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label>
            密码
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          {error && (
            <p className="diagnostic" role="alert">
              {error}
            </p>
          )}
          <button className="button login-submit" disabled={pending}>
            <LockKeyhole size={16} />
            {pending ? "正在登录…" : "登录"}
          </button>
        </form>
        <p className="muted small">
          账号由部署管理员配置。没有默认账号或注册入口。Cookie 用于会话，密码与
          Token 不写入浏览器存储。
        </p>
      </section>
    </div>
  );
}
