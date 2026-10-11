import { useState } from "react";
import {
  Link,
  NavLink,
  Navigate,
  Route,
  Routes,
  useLocation,
} from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  LayoutDashboard,
  FlaskConical,
  ListChecks,
  ScrollText,
  FileBarChart,
  Sun,
  Moon,
  RefreshCw,
  LockKeyhole,
  Menu,
  X,
  Archive,
  LogOut,
  ShieldCheck,
} from "lucide-react";
import { Dashboard } from "./pages/Dashboard";
import { Experiments, ExperimentDetail, Executions } from "./pages/Experiments";
import { ExecutionDetail } from "./pages/ExecutionDetail";
import { Audits } from "./pages/Audits";
import { Reports, ReportDetail } from "./pages/Reports";
import { Empty } from "./components/ui";
import { HistoryPage } from "./pages/History";
import { SessionBoundary } from "./components/SessionBoundary";
import { AccessPage, PermissionDenied } from "./pages/Access";
import { type Identity, canManageFake } from "./api/auth";
import { FakeExperimentForm, FakeBanner } from "./pages/FakeExperiments";

const navigation = [
  { to: "/", text: "概览", icon: LayoutDashboard, end: true },
  { to: "/experiments", text: "实验管理", icon: FlaskConical },
  { to: "/executions", text: "执行记录", icon: ListChecks },
  { to: "/audit", text: "审计日志", icon: ScrollText },
  { to: "/reports", text: "实验报告", icon: FileBarChart },
  { to: "/history/m1-core", text: "真实历史 · M1 Core", icon: Archive },
  { to: "/access", text: "权限信息", icon: ShieldCheck },
];
export function App() {
  return (
    <SessionBoundary>
      {(identity, logout) => <ConsoleApp identity={identity} logout={logout} />}
    </SessionBoundary>
  );
}
function ConsoleApp({
  identity,
  logout,
}: {
  identity: Identity;
  logout: () => Promise<void>;
}) {
  const [logoutPending, setLogoutPending] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  // Dedicated local archive launcher has no business DB APIs. Normal console stays unchanged.
  const historyOnly = identity.mode === "LOCAL_HISTORY";
  const visibleNavigation = historyOnly
    ? navigation.filter((n) => n.to === "/history/m1-core")
    : navigation.filter(
        (n) => n.to !== "/access" || identity.roles.includes("ADMIN"),
      );
  const queryClient = useQueryClient();
  const [theme, setTheme] = useState(() => {
    const stored = localStorage.getItem("chaoslab-theme");
    return stored === "light" || stored === "dark"
      ? stored
      : window.matchMedia("(prefers-color-scheme: light)").matches
        ? "light"
        : "dark";
  });
  const [menu, setMenu] = useState(false);
  const location = useLocation();
  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    setTheme(next);
    localStorage.setItem("chaoslab-theme", next);
  };
  return (
    <div className={`app ${theme}`}>
      <a className="skip-link" href="#main">
        跳转到主要内容
      </a>
      <aside className={`sidebar ${menu ? "open" : ""}`}>
        <Link to="/" className="brand" onClick={() => setMenu(false)}>
          <span className="brand-symbol">
            <Activity size={22} />
          </span>
          <span>
            ChaosLab<small>ENGINEERING CONSOLE</small>
          </span>
        </Link>
        <div className="workspace-label">
          <span className="status-dot" />
          {historyOnly
            ? "LOCAL HISTORY / NO LOGIN IDENTITY"
            : identity.executionMode === "FAKE_DEMO"
              ? "FAKE DEMO / SIMULATED"
              : "AUTHENTICATED READ-ONLY"}
        </div>
        <nav aria-label="主导航">
          {visibleNavigation.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              onClick={() => setMenu(false)}
              className={({ isActive }) =>
                `nav-item ${isActive ? "selected" : ""}`
              }
            >
              <item.icon size={18} />
              {item.text}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <LockKeyhole size={18} />
          <div>
            {identity.executionMode === "FAKE_DEMO"
              ? "Fake 教学闭环"
              : "观测，不改变现场"}
            <p>真实执行权限始终关闭</p>
          </div>
        </div>
      </aside>
      {menu && (
        <button
          aria-label="关闭导航遮罩"
          className="nav-backdrop"
          onClick={() => setMenu(false)}
        />
      )}
      <div className="workspace">
        <div className="topbar">
          <div className="topbar-left">
            <button
              className="icon-button mobile-toggle"
              aria-label={menu ? "关闭导航" : "打开导航"}
              onClick={() => setMenu(!menu)}
            >
              {menu ? <X size={20} /> : <Menu size={20} />}
            </button>
            <span className="muted">Workspace</span>
            <span className="divider">/</span>
            <span>
              {navigation.find((n) =>
                n.to === "/"
                  ? location.pathname === "/"
                  : location.pathname.startsWith(n.to),
              )?.text ?? "详情"}
            </span>
          </div>
          <div className="topbar-actions">
            <span className="session-user">
              {historyOnly
                ? "本地历史演示 · 非登录身份"
                : `${identity.username ?? ""} · ${identity.roles.join(" / ")}`}
            </span>
            {!historyOnly && (
              <button
                className="icon-button"
                aria-label="退出登录"
                disabled={logoutPending}
                onClick={() => {
                  setLogoutPending(true);
                  setLogoutError(null);
                  void logout()
                    .catch((e) =>
                      setLogoutError(
                        e instanceof Error ? e.message : "退出失败",
                      ),
                    )
                    .finally(() => setLogoutPending(false));
                }}
              >
                <LogOut size={17} />
              </button>
            )}
            <span className="read-only">
              <LockKeyhole size={13} />
              {canManageFake(identity) ? "仅 Fake 操作" : "只读控制台"}
            </span>
            <button
              className="icon-button"
              aria-label="刷新当前数据"
              onClick={() => void queryClient.invalidateQueries()}
            >
              <RefreshCw size={17} />
            </button>
            <button
              className="icon-button"
              aria-label={`切换${theme === "dark" ? "浅色" : "深色"}主题`}
              onClick={toggleTheme}
            >
              {theme === "dark" ? <Sun size={18} /> : <Moon size={18} />}
            </button>
          </div>
        </div>
        <main id="main">
          {identity.executionMode === "FAKE_DEMO" && <FakeBanner />}
          {logoutError && (
            <div className="notice warning" role="alert">
              {logoutError}
            </div>
          )}
          <Routes>
            <Route
              path="/"
              element={
                historyOnly ? (
                  <Navigate to="/history/m1-core" replace />
                ) : (
                  <Dashboard identity={identity} />
                )
              }
            />
            <Route path="/history/m1-core" element={<HistoryPage />} />
            {!historyOnly && (
              <>
                <Route
                  path="/access"
                  element={
                    identity.roles.includes("ADMIN") ? (
                      <AccessPage />
                    ) : (
                      <PermissionDenied />
                    )
                  }
                />
                <Route
                  path="/experiments"
                  element={<Experiments identity={identity} />}
                />
                <Route
                  path="/experiments/new"
                  element={<FakeExperimentForm identity={identity} />}
                />
                <Route
                  path="/experiments/:id/edit"
                  element={<FakeExperimentForm identity={identity} />}
                />
                <Route
                  path="/experiments/:id"
                  element={<ExperimentDetail identity={identity} />}
                />
                <Route path="/executions" element={<Executions />} />
                <Route
                  path="/executions/:id"
                  element={<ExecutionDetail identity={identity} />}
                />
                <Route path="/audit" element={<Audits />} />
                <Route path="/reports" element={<Reports />} />
                <Route path="/reports/:id" element={<ReportDetail />} />
              </>
            )}
            <Route
              path="*"
              element={
                <Empty text="页面不存在" detail="请通过左侧导航返回控制台。" />
              }
            />
          </Routes>
        </main>
        <footer className="workspace-footer">
          <span>CHAOSLAB / OBSERVABILITY FIRST</span>
          <span>历史证据只读 · 不声明恢复因果归属</span>
        </footer>
      </div>
    </div>
  );
}
