# M1 Core final field acceptance — PASS

One explicitly authorized experiment, on independent `chaoslab-m1-core-r3` only.
Execution ended; no follow-up fault, cleanup/reset or replay is authorized.

## Result

| Field | Recorded result |
| --- | --- |
| Locked main merge | `0cb29a13690e89a5d4bda2be1c64ebb3408f5536` |
| Experiment | `aa73e482-5584-4e03-baca-0ba6a7b0c635` |
| Execution | `dd07ca41-fbbc-416f-944a-712100376c86` |
| Java preallocated native UID | `2d479cbbf2ea6dfe` |
| CPU baseline → during → after | **0% → 10.9112165161% → 0%** |
| Create / active destroy API requests | **1 / 1**, no retry |
| Native record | Same UID, `cri cpu fullload`, `Destroyed`; one native experiment |
| Final production Recovery Gate | **VERIFIED** |
| Physical recovery | **VERIFIED**, Residual=CLEAR, Health=HEALTHY |
| Recovery cause | **UNKNOWN**; not ACTIVE_RECOVERY_CONFIRMED |
| Backend committed final state | **SUCCESS**, finishedAt set, version3 |
| Occupancy | Released; execution no longer active |
| REAL authorization | Consumed; ABSENT afterward |
| Binding/native DB | Retained as historical evidence, not reset |
| Emergency containment | Not needed |
| Core result | **M1 CORE PASS** |

Counts refer to this operator's single create and active destroy through normal
backend REST calls. They do not claim that the native timeout helper never invoked
its own internal recovery path. No recovery winner/provenance is available.

## Production evidence order

Times here use Asia/Shanghai, **2026-10-10**; original JSON retains UTC timestamps.

1. Fresh preflight passed on ordinary chaoslab UID999/GID987, zero capabilities,
   exact deployed Java21 artifact, fixed wrapper/tool/policy/node/state, loopback
   MySQL/HTTP, unchanged idle sandbox/isolation, CPU0%, residual CLEAR. New-generation
   authorization/binding/native state and experimental rows were absent.
2. Java created the execution and committed the CSPRNG UID/CRI intent in PREPARING.
   An independent MySQL READ COMMITTED query saw that UID before the administrator
   wrote its 20-second, single-use full-policy/tool/target/node/state authorization.
   InnoDB transaction/binlog durability settings were 1/1.
3. Production Java→wrapper sampled the fixed cgroup and committed
   `M1_CPU_OBSERVATION` at **01:39:00.937361**, including CPU10.9112%, baseline0%,
   usage433244usec, exact UID/container/image/node/state/tool identity. This preceded
   RUNNING at **01:39:00.955376**. No operator-side stress or delayed during sampler.
4. One normal backend active destroy request was sent at **01:39:01.107241** for the
   same execution/UID. No second create/destroy, direct Blade invocation or strace.
5. Production settling round1 at **01:39:09.395862**: same-UID native Destroyed,
   residual PRESENT, health HEALTHY, not success eligible. This was an intermediate
   observation, not an application terminal result. Its process-level cause is UNKNOWN.
6. Production round2 at **01:39:14.072386**: fresh engine status (observed
   **01:39:12.927484**), residual CLEAR, health HEALTHY, same subject, Gate VERIFIED.
7. `M1_PHYSICAL_RECOVERY` was committed at **01:39:14.114461**, with active request
   ACKNOWLEDGED and recoveryCause UNKNOWN, BEFORE the final application SUCCESS
   transaction's finishedAt **01:39:14.128254**. Final DB/API status confirms SUCCESS;
   the adapter assessment alone is not substituted for this final transaction.
8. Read-only reporting corroboration afterward showed CPU0%, CLEAR/HEALTHY and the
   same UID native Destroyed. It was not an extra post-SUCCESS acceptance veto.

## Scope and preservation

- Fixed sandbox ID `18bb4f8734edbd6e1dc35582692677bcfcf98fef175832fc1d1e29361faab97f`.
- Image ID `sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8`.
- CPU10%, cpu-count1, timeout10s; no escalation or other fault type.
- Existing backend/wrapper/candidate were not redeployed, rebuilt or modified for
  this run. No permissions, migration, scheduler or native provenance change.
- New operator is `tools/m1-wrapper/deployment/core-real-once.py`; it refuses a
  repeated attempt and uses only the normal backend for create/destroy. New harmless
  operator checks were tested; earlier passed full deployment suites were not rerun.
- R1 remains INCOMPLETE/recoveryVerified=false. R2 remains external INCOMPLETE,
  backend SUCCESS, released occupancy, external recoveryVerified=false. Neither was
  reclassified under Core, destroyed again, reoccupied or rewritten.
- Original R1/R2 VM stayed powered off. Original raw JSON hashes and inherited
  historical rows were verified unchanged. Clone databases and archived native state
  remain preserved; the R3 binding/native DB remain in their active generation.

## Sealed evidence

- Original root result: `/var/lib/chaoslab-m1/audit/m1-core-real-result.json`.
- Exact raw byte copy: `docs/evidence/m1-core-real-20261010.json`.
- SHA-256, matched VM and local copy:
  `b57cac027022e3c3b009b6daf20eef22c9146d2c7f32dd5c579841fe6eb84f33`.
- R1 SHA `7e8867fcdd2475ecf5c969c0eb6c1bf7539503c9a4ca65d42d0d0178dec5fffa`.
- R2 SHA `db66f4ac5df2ccbd1dc09bfd68c9e07389f6b73c9b440ff4385dae9f26909e67`.

## Stop line and next scope

**M1 CORE PASS — PHYSICAL RECOVERY VERIFIED — RECOVERY CAUSE UNKNOWN.**

SUCCESS now means the production Core conditions and physical-recovery evidence
were satisfied before releasing occupancy. It does not identify whether active
destroy or timeout actually eliminated the fault, and does not upgrade the report
module's separate metrics/source-attribution conclusions to VERIFIED.

M1 Core is finished. Subsequent platform work can prioritize execution/report APIs
and reviewable UI integration using this existing contract. M1+ provenance remains
a separate enhancement. No more live experiment, intensity increase, state reset
or platform feature implementation is performed by this acceptance task.
