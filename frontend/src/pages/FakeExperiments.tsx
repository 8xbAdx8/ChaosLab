import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { z } from "zod";
import { type Identity, canManageFake } from "../api/auth";
import { get } from "../api/client";
import {
  experimentSchema,
  executionSchema,
  reportSchema,
  targetSchema,
  scenarioSchema,
  type Experiment,
  type Execution,
} from "../api/contracts";
import { fakeWrite, intentKey } from "../api/fake";
import { Heading, Panel, QueryState, Badge } from "../components/ui";
import { PermissionDenied } from "./Access";

export function FakeBanner() {
  return (
    <div className="notice simulation">
      <strong>FAKE 模拟实验 · 不执行真实故障</strong>
      <span>SIMULATED：只验证教学生命周期，不声明 CPU/物理恢复/因果证明。</span>
    </div>
  );
}
const dryRunSchema = z.object({
  experiment: experimentSchema,
  accepted: z.boolean(),
  checks: z.array(
    z.object({ code: z.string(), passed: z.boolean(), message: z.string() }),
  ),
  plan: z.unknown(),
});

export function FakeExperimentForm({ identity }: { identity: Identity }) {
  const { id } = useParams();
  const query = useQuery({
    queryKey: ["experiment", id],
    queryFn: ({ signal }) =>
      get(`/api/v1/console/experiments/${id}`, experimentSchema, signal),
    enabled: !!id,
  });
  if (!canManageFake(identity)) return <PermissionDenied />;
  return (
    <>
      {id ? (
        <QueryState query={query}>
          {(e) =>
            e.status === "CREATED" ? (
              <DefinitionForm identity={identity} existing={e} />
            ) : (
              <Panel title="不可编辑">
                仅 CREATED 草稿允许编辑。请保留已校验/执行过的定义。
              </Panel>
            )
          }
        </QueryState>
      ) : (
        <DefinitionForm identity={identity} />
      )}
    </>
  );
}
function DefinitionForm({
  identity,
  existing,
}: {
  identity: Identity;
  existing?: Experiment;
}) {
  const targets = useQuery({
    queryKey: ["targets"],
    queryFn: ({ signal }) =>
      get("/api/v1/targets", z.array(targetSchema), signal),
  });
  const scenarios = useQuery({
    queryKey: ["scenarios"],
    queryFn: ({ signal }) =>
      get("/api/v1/scenarios", z.array(scenarioSchema), signal),
  });
  const [name, setName] = useState(existing?.name ?? ""),
    [hypothesis, setHypothesis] = useState(existing?.hypothesis ?? "");
  const [target, setTarget] = useState(existing?.targetId ?? ""),
    [scenario, setScenario] = useState(existing?.scenarioId ?? "");
  const [duration, setDuration] = useState(existing?.durationSeconds ?? 10);
  const [parameters, setParameters] = useState(
    JSON.stringify(existing?.parameters ?? { percent: 10 }, null, 2),
  );
  const [localError, setLocalError] = useState("");
  const client = useQueryClient(),
    navigate = useNavigate(),
    busy = useRef(false);
  const save = useMutation({
    retry: false,
    mutationFn: async () => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(parameters);
      } catch {
        throw new Error("参数必须是合法 JSON 对象");
      }
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed))
        throw new Error("参数必须是 JSON 对象");
      const definition = {
        name,
        hypothesis,
        targetId: target || targets.data?.find((t) => t.enabled)?.id,
        scenarioId: scenario || scenarios.data?.find((s) => s.enabled)?.id,
        durationSeconds: duration,
        parameters: parsed,
      };
      return fakeWrite(
        identity,
        existing ? "PUT" : "POST",
        `/api/v1/experiments${existing ? `/${existing.id}` : ""}`,
        experimentSchema,
        existing ? { definition, version: existing.version } : definition,
      );
    },
    onSuccess: async (e) => {
      await client.invalidateQueries();
      navigate(`/experiments/${e.id}`);
    },
  });
  return (
    <>
      <Heading
        eyebrow="FAKE DEFINITION"
        title={existing ? "编辑实验草稿" : "创建 Fake 实验"}
        description="参数由场景 JSON Schema 在后端校验；创建不会启动执行。"
      />
      <Panel title="实验配置">
        <form
          className="fake-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (busy.current) return;
            busy.current = true;
            setLocalError("");
            void save
              .mutateAsync()
              .catch((e) =>
                setLocalError(e instanceof Error ? e.message : "保存失败"),
              )
              .finally(() => {
                busy.current = false;
              });
          }}
        >
          <label>
            实验名称
            <input
              required
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <label>
            实验假设
            <textarea
              required
              maxLength={500}
              value={hypothesis}
              onChange={(e) => setHypothesis(e.target.value)}
            />
          </label>
          <label>
            目标
            <select
              required
              value={target || targets.data?.find((t) => t.enabled)?.id || ""}
              onChange={(e) => setTarget(e.target.value)}
            >
              {targets.data
                ?.filter((t) => t.enabled)
                .map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            故障场景
            <select
              required
              value={
                scenario || scenarios.data?.find((s) => s.enabled)?.id || ""
              }
              onChange={(e) => {
                setScenario(e.target.value);
                setParameters(
                  JSON.stringify(
                    scenarios.data?.find((s) => s.id === e.target.value)
                      ?.code === "NETWORK_DELAY"
                      ? { delayMs: 100 }
                      : { percent: 10 },
                    null,
                    2,
                  ),
                );
              }}
            >
              {scenarios.data
                ?.filter((s) => s.enabled)
                .map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            持续秒数
            <input
              type="number"
              min={5}
              max={30}
              required
              value={duration}
              onChange={(e) => setDuration(Number(e.target.value))}
            />
          </label>
          <label>
            参数 JSON
            <textarea
              required
              className="mono"
              value={parameters}
              onChange={(e) => setParameters(e.target.value)}
            />
          </label>
          <p className="muted">
            CPU_LOAD: percent 为 10–80 整数；NETWORK_DELAY: delayMs 为 10–3000
            整数。仅模拟参数，不产生真实压力或延迟。
          </p>
          {(targets.isError || scenarios.isError) && (
            <p role="alert">目标或场景读取失败，不能提交。</p>
          )}
          {localError && (
            <p role="alert" className="diagnostic">
              {localError}
            </p>
          )}
          <div className="fake-actions">
            <button
              className="button"
              disabled={
                save.isPending ||
                !targets.data?.length ||
                !scenarios.data?.length
              }
            >
              {save.isPending ? "保存中…" : "保存草稿"}
            </button>
            <Link
              to={existing ? `/experiments/${existing.id}` : "/experiments"}
            >
              取消
            </Link>
          </div>
        </form>
      </Panel>
    </>
  );
}

export function FakeExperimentActions({
  identity,
  experiment: e,
}: {
  identity: Identity;
  experiment: Experiment;
}) {
  const client = useQueryClient(),
    navigate = useNavigate();
  const [confirm, setConfirm] = useState(false),
    [checks, setChecks] = useState<z.infer<typeof dryRunSchema>["checks"]>([]);
  const [error, setError] = useState(""),
    busy = useRef(false);
  const action = useMutation({
    retry: false,
    mutationFn: async (op: "validate" | "dry-run" | "start") => {
      const base = `/api/v1/experiments/${e.id}`;
      if (op === "start")
        return fakeWrite(
          identity,
          "POST",
          `${base}/executions`,
          executionSchema,
          undefined,
          intentKey("start", e.id, identity.username),
        );
      if (op === "dry-run") {
        const r = await fakeWrite(
          identity,
          "POST",
          `${base}/dry-run`,
          dryRunSchema,
        );
        setChecks(r.checks);
        return r.experiment;
      }
      return fakeWrite(
        identity,
        "POST",
        `${base}/validation`,
        experimentSchema,
      );
    },
    onSuccess: async (r, op) => {
      await client.invalidateQueries();
      if (op === "start" && "experimentId" in r)
        navigate(`/executions/${r.id}`);
    },
  });
  if (!canManageFake(identity)) return null;
  const run = (op: "validate" | "dry-run" | "start") => {
    if (busy.current) return;
    busy.current = true;
    setError("");
    setConfirm(false);
    void action
      .mutateAsync(op)
      .catch((e) => setError(e instanceof Error ? e.message : "操作失败"))
      .finally(() => {
        busy.current = false;
      });
  };
  return (
    <Panel title="Fake 实验操作">
      <div className="fake-actions">
        {e.status === "CREATED" && (
          <Link className="button" to={`/experiments/${e.id}/edit`}>
            编辑草稿
          </Link>
        )}
        {["CREATED", "VALIDATED", "READY"].includes(e.status) && (
          <button
            className="button"
            disabled={action.isPending}
            onClick={() => run("validate")}
          >
            校验参数
          </button>
        )}
        {["VALIDATED", "READY"].includes(e.status) && (
          <button
            className="button"
            disabled={action.isPending}
            onClick={() => run("dry-run")}
          >
            安全预检 / Dry-run
          </button>
        )}
        {e.status === "READY" && (
          <button
            className="button"
            disabled={action.isPending}
            onClick={() => setConfirm(true)}
          >
            启动 Fake 实验
          </button>
        )}
      </div>
      {confirm && (
        <section role="dialog" aria-label="确认启动 Fake">
          <p>
            确认仅模拟此目标、参数和 {e.durationSeconds}{" "}
            秒持续时间？不会调用真实执行器。
          </p>
          <button
            className="button"
            disabled={action.isPending}
            onClick={() => run("start")}
          >
            确认启动
          </button>
          <button className="button" onClick={() => setConfirm(false)}>
            取消
          </button>
        </section>
      )}
      {action.isPending && <p role="status">正在提交，请勿重复操作…</p>}
      {error && (
        <p role="alert" className="diagnostic">
          {error}
        </p>
      )}
      {checks.length > 0 && (
        <ul className="checks-list">
          {checks.map((c) => (
            <li key={c.code}>
              <Badge value={c.passed ? "PASSED" : "REJECTED"} />
              {c.code}: {c.message}
            </li>
          ))}
        </ul>
      )}
      <p className="muted">
        CREATED → VALIDATED → READY →
        RUNNING；只有后端状态允许时才能继续。启动结果未知时保留同一个幂等键，刷新后读取已持久化状态。
      </p>
    </Panel>
  );
}

export function FakeExecutionActions({
  identity,
  execution: e,
}: {
  identity: Identity;
  execution: Execution;
}) {
  const client = useQueryClient(),
    navigate = useNavigate(),
    busy = useRef(false);
  const [confirm, setConfirm] = useState(false),
    [error, setError] = useState("");
  const action = useMutation({
    retry: false,
    mutationFn: async (op: "stop" | "report") => {
      const base = `/api/v1/experiments/${e.experimentId}/executions/${e.id}`;
      return op === "stop"
        ? fakeWrite(identity, "POST", `${base}/destroy`, executionSchema)
        : fakeWrite(
            identity,
            "POST",
            `${base}/reports`,
            reportSchema,
            undefined,
            intentKey("report", e.id, identity.username),
          );
    },
    onSuccess: async (r, op) => {
      await client.invalidateQueries();
      if (op === "report") navigate(`/reports/${r.id}`);
    },
  });
  if (!canManageFake(identity) || e.engineExperimentId !== `fake-${e.id}`)
    return null;
  const run = (op: "stop" | "report") => {
    if (busy.current) return;
    busy.current = true;
    setConfirm(false);
    setError("");
    void action
      .mutateAsync(op)
      .catch((e) => setError(e instanceof Error ? e.message : "操作失败"))
      .finally(() => {
        busy.current = false;
      });
  };
  return (
    <Panel title="模拟执行操作">
      <div className="fake-actions">
        {e.status === "RUNNING" && (
          <button
            className="button"
            disabled={action.isPending}
            onClick={() => setConfirm(true)}
          >
            停止 Fake 实验
          </button>
        )}
        {e.status === "SUCCESS" && (
          <button
            className="button"
            disabled={action.isPending}
            onClick={() => run("report")}
          >
            生成模拟报告
          </button>
        )}
        <Link to={`/audit?experimentId=${e.experimentId}`}>查看相关审计</Link>
      </div>
      {confirm && (
        <section role="dialog" aria-label="确认停止 Fake">
          <p>确认请求同一执行的模拟恢复？</p>
          <button
            className="button"
            disabled={action.isPending}
            onClick={() => run("stop")}
          >
            确认停止
          </button>
          <button className="button" onClick={() => setConfirm(false)}>
            取消
          </button>
        </section>
      )}
      {action.isPending && <p role="status">正在提交…</p>}
      {error && (
        <p role="alert" className="diagnostic">
          {error}
        </p>
      )}
      <p className="muted">
        模拟报告不会采集物理指标；缺失指标保持
        NOT_COLLECTED/INSUFFICIENT_DATA。自动恢复与主动停止均复用后端恢复服务。
      </p>
    </Panel>
  );
}
