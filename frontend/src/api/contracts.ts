import { z } from "zod";
// Exact wire contracts: optional execution fields reflect Jackson NON_NULL.
export const executionStates = [
  "PREPARING",
  "CREATE_UNCERTAIN",
  "RUNNING",
  "DESTROYING",
  "SUCCESS",
  "FAILED",
  "ROLLBACK_FAILED",
] as const;
export const experimentStates = [
  "CREATED",
  "VALIDATED",
  "READY",
  "RUNNING",
  "DESTROYING",
  "SUCCESS",
  "ROLLBACK_FAILED",
] as const;
export const operations = [
  "START_EXPERIMENT",
  "DESTROY_EXPERIMENT",
  "AUTOMATIC_RECOVERY",
  "EMERGENCY_RECOVERY",
  "EMERGENCY_STOP",
  "M1_CPU_OBSERVATION",
  "M1_PHYSICAL_RECOVERY",
] as const;
// Java UUID and seeded scenario IDs include version-0 UUIDs; do not require RFC v4.
const id = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const instant = z.iso.datetime({ offset: true });
const nullableString = z.string().nullable();
const nullableNumber = z.number().finite().nullable();
export const experimentSchema = z.object({
  id,
  name: z.string(),
  hypothesis: z.string(),
  targetId: id,
  scenarioId: id,
  durationSeconds: z.number().int(),
  parameters: z.record(z.string(), z.unknown()),
  status: z.enum(experimentStates),
  version: z.number().int(),
});
export const executionSchema = z.object({
  id,
  experimentId: id,
  attempt: z.number().int(),
  idempotencyKey: z.string(),
  status: z.enum(executionStates),
  engineExperimentId: z.string().optional(),
  errorMessage: z.string().optional(),
  createdAt: instant,
  startedAt: instant.optional(),
  finishedAt: instant.optional(),
  version: z.number().int(),
});
export const auditSchema = z.object({
  id,
  actor: z.string(),
  operation: z.enum(operations),
  experimentId: id.nullable(),
  executionId: id.nullable(),
  targetId: id.nullable(),
  scenarioCode: nullableString,
  parameters: z.record(z.string(), z.unknown()),
  result: z.enum(["SUCCESS", "REJECTED", "FAILED"]),
  failureCode: nullableString,
  occurredAt: instant,
});
export const evidenceSchema = z.object({
  executionId: id,
  nativeUid: nullableString,
  snapshotFormat: nullableString,
  baselineCpuPercent: nullableNumber,
  duringCpuPercent: nullableNumber,
  afterCpuPercent: nullableNumber,
  nativeStatus: z.enum(["Destroyed", "UNKNOWN"]),
  physicalRecovery: z.enum(["VERIFIED", "UNKNOWN"]),
  residual: z.enum(["CLEAR", "PRESENT", "UNKNOWN"]),
  health: z.enum(["HEALTHY", "UNHEALTHY", "UNKNOWN"]),
  recoveryGate: z.enum(["VERIFIED", "UNKNOWN"]),
  recoveryCause: z.literal("UNKNOWN"),
  cpuObservedAt: instant.nullable(),
  recoveryAttemptStartedAt: instant.nullable(),
  engineObservedAt: instant.nullable(),
  recoveryObservedAt: instant.nullable(),
  cpuAuditId: id.nullable(),
  recoveryAuditId: id.nullable(),
  note: z.string(),
});
const metrics = z.enum(["NOT_COLLECTED", "INSUFFICIENT_DATA", "OBSERVED"]);
export const reportSchema = z.object({
  id,
  experimentId: id,
  executionId: id,
  targetId: id,
  scenarioCode: z.string(),
  startAuditId: id,
  recoveryAuditId: id,
  generatedAt: instant,
  executionMode: z.enum(["SIMULATED", "UNVERIFIED"]),
  metricsStatus: metrics,
  conclusionStatus: z.enum([
    "INSUFFICIENT_DATA",
    "SIMULATED_ONLY",
    "EXECUTION_UNVERIFIED",
    "COMPARISON_AVAILABLE",
  ]),
  bindingStatus: z.enum(["NOT_VERIFIED", "VERIFIED_LOCAL_DEMO"]),
  containerId: nullableString,
  imageId: nullableString,
  metricsJob: nullableString,
  route: nullableString,
  reason: z.string(),
  windows: z.array(
    z.object({
      phase: z.string(),
      start: instant,
      end: instant,
      metricsStatus: metrics,
      scrapeSamples: nullableNumber,
      estimatedRequests: nullableNumber,
      estimated5xx: nullableNumber,
      errorRate: nullableNumber,
      p95Seconds: nullableNumber,
      reason: nullableString,
    }),
  ),
});
export const overviewSchema = z.object({
  experimentCount: z.number().int(),
  executionCount: z.number().int(),
  targetCount: z.number().int(),
  reportCount: z.number().int(),
  executionStates: z.record(z.string(), z.number().int()),
  successRate: nullableNumber,
  successRateDefinition: z.string(),
  recentExperiments: z.array(experimentSchema),
  engineMode: z.string(),
  readOnlyMode: z.boolean(),
  authenticationConfigured: z.boolean(),
});
export const targetSchema = z.object({
  id,
  name: z.string(),
  type: z.string(),
  environment: z.string(),
  enabled: z.boolean(),
});
export const scenarioSchema = z.object({
  id,
  code: z.string(),
  name: z.string(),
  description: z.string(),
  parameterSchema: z.unknown(),
  enabled: z.boolean(),
});
export const healthSchema = z.object({ status: z.string() });
export const pageOf = <T extends z.ZodType>(schema: T) =>
  z.object({
    items: z.array(schema),
    total: z.number().int(),
    page: z.number().int(),
    size: z.number().int(),
  });
export type Experiment = z.infer<typeof experimentSchema>;
export type Execution = z.infer<typeof executionSchema>;
export type Evidence = z.infer<typeof evidenceSchema>;
export type Audit = z.infer<typeof auditSchema>;
export type Report = z.infer<typeof reportSchema>;
export type Page<T> = { items: T[]; total: number; page: number; size: number };
