import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  Inbox,
  AlertCircle,
  LoaderCircle,
} from "lucide-react";
import type { UseQueryResult } from "@tanstack/react-query";

export function Badge({ value }: { value: string | undefined | null }) {
  const good = [
    "SUCCESS",
    "VERIFIED",
    "CLEAR",
    "HEALTHY",
    "UP",
    "Destroyed",
    "OBSERVED",
    "VERIFIED_LOCAL_DEMO",
  ].includes(value ?? "");
  const bad = [
    "FAILED",
    "ROLLBACK_FAILED",
    "PRESENT",
    "UNHEALTHY",
    "DOWN",
    "REJECTED",
  ].includes(value ?? "");
  const active = ["RUNNING", "PREPARING", "DESTROYING"].includes(value ?? "");
  return (
    <span
      className={`badge ${good ? "good" : bad ? "bad" : active ? "active" : "neutral"}`}
    >
      <span className="status-dot" />
      {value ?? "UNKNOWN"}
    </span>
  );
}
export function Heading({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p className="muted">{description}</p>
      </div>
      {action}
    </header>
  );
}
export function Panel({
  title,
  subtitle,
  children,
  action,
  className = "",
}: {
  title?: string;
  subtitle?: string;
  children: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      {title && (
        <div className="panel-heading">
          <div>
            <h2>{title}</h2>
            {subtitle && <p className="muted small">{subtitle}</p>}
          </div>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
export function Empty({
  text = "暂无记录",
  detail = "当前后端未返回记录。控制台不会补入示例数据。",
}: {
  text?: string;
  detail?: string;
}) {
  return (
    <div className="empty">
      <Inbox size={30} />
      <h3>{text}</h3>
      <p className="muted">{detail}</p>
    </div>
  );
}
export function QueryState<T>({
  query,
  children,
}: {
  query: UseQueryResult<T, Error>;
  children: (data: T) => ReactNode;
}) {
  if (query.isPending)
    return (
      <div className="query-state" role="status">
        <LoaderCircle className="spin" />
        正在读取真实 API…
      </div>
    );
  if (query.isError)
    return (
      <div className="query-state error" role="alert">
        <AlertCircle />
        <div>
          <h3>数据暂不可用</h3>
          <p>{query.error.message}</p>
          <button className="button" onClick={() => void query.refetch()}>
            重新读取
          </button>
        </div>
      </div>
    );
  return (
    <>
      {children(query.data)}
      {query.isFetching && (
        <p className="muted small" role="status">
          正在刷新…
        </p>
      )}
    </>
  );
}
export function Pagination({
  total,
  page,
  size,
  onPage,
}: {
  total: number;
  page: number;
  size: number;
  onPage: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="pagination">
      <span className="muted small">
        共 {total} 条 · 第 {page + 1} / {pages} 页
      </span>
      <div>
        <button
          className="icon-button"
          aria-label="上一页"
          disabled={page <= 0}
          onClick={() => onPage(page - 1)}
        >
          <ChevronLeft size={17} />
        </button>
        <button
          className="icon-button"
          aria-label="下一页"
          disabled={page + 1 >= pages}
          onClick={() => onPage(page + 1)}
        >
          <ChevronRight size={17} />
        </button>
      </div>
    </div>
  );
}
export function Id({ value }: { value: string | null | undefined }) {
  return (
    <code className="identifier" title={value ?? ""}>
      {value ?? "—"}
    </code>
  );
}
export function DetailLink({
  to,
  children,
}: {
  to: string;
  children: ReactNode;
}) {
  return (
    <Link className="detail-link" to={to}>
      {children}
      <ArrowRight size={15} />
    </Link>
  );
}
export function Fields({ values }: { values: [string, ReactNode][] }) {
  return (
    <dl className="fields">
      {values.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
export const date = (value: string | null | undefined) =>
  value
    ? new Intl.DateTimeFormat("zh-CN", {
        dateStyle: "medium",
        timeStyle: "medium",
        hour12: false,
      }).format(new Date(value))
    : "未记录";
export const number = (value: number | null | undefined, suffix = "") =>
  value == null
    ? "未采集"
    : `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(value)}${suffix}`;
