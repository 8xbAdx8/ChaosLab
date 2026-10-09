// TEST-ONLY fixtures. Never imported by runtime pages or used as fallback data.
import type { Evidence, Execution } from '../api/contracts'
export const execution: Execution = {
  id: '11111111-1111-4111-8111-111111111111', experimentId: '22222222-2222-4222-8222-222222222222', attempt: 1,
  idempotencyKey: 'test-fixture', status: 'SUCCESS', createdAt: '2026-10-10T00:00:00Z', startedAt: '2026-10-10T00:00:01Z', finishedAt: '2026-10-10T00:00:10Z', version: 1,
}
export const evidence: Evidence = {
  executionId: execution.id, nativeUid: '0123456789abcdef', snapshotFormat: 'CRI_CPU_V1', baselineCpuPercent: 0, duringCpuPercent: 10.91, afterCpuPercent: null,
  nativeStatus: 'Destroyed', physicalRecovery: 'VERIFIED', residual: 'CLEAR', health: 'HEALTHY', recoveryGate: 'VERIFIED', recoveryCause: 'UNKNOWN',
  cpuObservedAt: '2026-10-10T00:00:04Z', recoveryAttemptStartedAt: '2026-10-10T00:00:05Z', engineObservedAt: '2026-10-10T00:00:09Z', recoveryObservedAt: '2026-10-10T00:00:10Z',
  cpuAuditId: null, recoveryAuditId: null, note: 'test-only same-subject evidence',
}
