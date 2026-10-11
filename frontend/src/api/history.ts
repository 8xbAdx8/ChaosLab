import { z } from "zod";

const scalar = z.union([
  z.string(),
  z.number().finite(),
  z.boolean(),
  z.null(),
]);
export const factSchema = z.object({
  key: z.string(),
  label: z.string(),
  value: scalar,
  pointer: z.string(),
});
const sourceSchema = z.object({
  path: z.string(),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  checksumMode: z.enum(["EXACT_BYTES_SHA256", "UTF8_LF_NORMALIZED_SHA256"]),
  archiveCommit: z.string().regex(/^[0-9a-f]{40}$/),
  url: z.url(),
});
export const historySchema = z.object({
  schemaVersion: z.literal(1),
  dataMode: z.literal("ARCHIVED_REAL_COMPLETED"),
  evidenceSource: sourceSchema,
  reportSource: sourceSchema,
  facts: z.array(factSchema),
  cpu: z
    .array(
      z.object({
        phase: z.enum(["baseline", "during", "after"]),
        percent: factSchema,
        observedAt: z.string().nullable(),
        timePointer: z.string(),
      }),
    )
    .length(3),
  timeline: z.array(
    z.object({
      label: z.string(),
      observedAt: z.string().nullable(),
      pointer: z.string(),
    }),
  ),
  recoveryRounds: z.array(
    z.object({ facts: z.array(factSchema), pointer: z.string() }),
  ),
  audits: z.array(
    z.object({
      operation: z.string().nullable(),
      result: z.string().nullable(),
      occurredAtRaw: z.string().nullable(),
      pointer: z.string(),
      parameters: z.array(factSchema),
    }),
  ),
  cpuCountReportClaim: z.object({
    label: z.string(),
    quote: z.string(),
    section: z.string(),
  }),
});
export type History = z.infer<typeof historySchema>;
export type Fact = z.infer<typeof factSchema>;
export function findFact(facts: Fact[], key: string) {
  return facts.find((f) => f.key === key);
}
export function factText(facts: Fact[], key: string): string | null {
  const value = findFact(facts, key)?.value;
  return typeof value === "string" ? value : null;
}
export function factNumber(facts: Fact[], key: string): number | null {
  const value = findFact(facts, key)?.value;
  return typeof value === "number" ? value : null;
}
export function scalarText(value: Fact["value"] | undefined): string {
  return value == null
    ? "未记录 / UNKNOWN"
    : typeof value === "boolean"
      ? String(value)
      : String(value);
}
