# M1 Core preparation — read-only checkpoint

This is a preparation report, not a deployment or experiment authorization.

## Reviewed code and merge

- PR: https://github.com/8xbAdx8/ChaosLab/pull/1
- Reviewed head: `76a2fd2021d2040a58804e7fc98d3b685b9132e7`.
- PR and push CI both PASS: runs `37957956939` and `37957943411`, including Windows, Linux, and harmless root proof.
- PR merged with an exact-head guard. Final main merge: `0cb29a13690e89a5d4bda2be1c64ebb3408f5536`.
- Merge parents: previous main `b31086f41185b9916b43e196a2ed375780114447`, reviewed head above.
- Merge tree and reviewed-head tree both `9680dc45710ee1072c71ddbb5c3fe02edeace310`: no additional code was introduced.
- Unrelated dirty files were not staged, committed, reverted, or included in the merge.

## Production path inspection

`docs/11-m1-core-field-acceptance.md` was read. Source inspection confirms that `ChaosBladeEngine.create` observes direct CPU evidence and commits `M1_CPU_OBSERVATION` before RUNNING. Its recovery path requires fresh physical Gate evidence and commits `M1_PHYSICAL_RECOVERY` before returning DESTROYED. Recovery cause remains UNKNOWN. This is code/CI evidence, not verification of a newly deployed VM.

## Original environment inventory — no changes

- Running source VM: `D:\ChaosLab-VMs\chaoslab-executor\chaoslab-executor.vmx`.
- Resources: 4 GiB RAM, 2 vCPUs, NAT.
- Active disk: `chaoslab-executor-000001.vmdk`; base disk and snapshot chain exist.
- Existing snapshot: `chaoslab-safe-baseline-20261003`. It will NOT be reverted or used as a substitute for the current R1/R2 state.
- Current allocated base plus delta disk files total approximately 10.1 GiB. D: free space at inspection approximately 85.9 GiB. Full clone space must be checked again before execution; actual allocation may differ.
- Proposed destination `D:\ChaosLab-VMs\chaoslab-m1-core-r3` does not yet exist.

Historical evidence hashes remain unchanged:

| Evidence | SHA-256 |
| --- | --- |
| R1 | `7e8867fcdd2475ecf5c969c0eb6c1bf7539503c9a4ca65d42d0d0178dec5fffa` |
| R2 | `db66f4ac5df2ccbd1dc09bfd68c9e07389f6b73c9b440ff4385dae9f26909e67` |

No guest database, root state, policy, wrapper, sandbox, account, authorization, or historical execution was modified during this checkpoint. Live authorization/state absence was not re-observed: do not treat previous observations as a fresh preflight.

## Proposed operations — approval required, NOT executed

1. Record original artifact/state identities, then gracefully shut down the original VM (no forced power-off, no snapshot revert). This interrupts its services and may write ordinary shutdown logs; historical experiment outcomes and evidence must not be changed.
2. Full-clone the powered-off CURRENT disk state, not the October 3 snapshot, to `D:\ChaosLab-VMs\chaoslab-m1-core-r3\chaoslab-m1-core-r3.vmx`. Do not use a linked clone. Preserve all original VM files and R1/R2 databases/evidence in place. Stop if the clone cannot be made independently without changing historical state.
3. First boot the clone disconnected from networking and in maintenance mode, preventing the copied backend/services from automatically running the old environment. Give the clone its own VMware identity/MAC and unique executor/node identity before connecting its management network; never reuse the source VM IP concurrently.
4. In the CLONE ONLY, preserve a root-only copy/archive of inherited R1/R2 state and policy. Keep inherited R1/R2 schemas unchanged; create an independent local `chaoslab_m1_core_r3` schema and schema-only database credentials. Rotate the cloned fixed active root state to a new generation, retaining an archive rather than deleting inherited records. Require authorization, binding, and native experiment DB ABSENT in the new generation.
5. Build exact reviewed code with Java 21, record jar SHA-256, deploy side-by-side in the clone, and run as the existing ordinary `chaoslab` account. Retain fixed sudoers/wrapper/tool privileges and tool SHA set. Change only clone-specific DB/node/state identity and corresponding policy/config pins; bind HTTP to localhost. No REAL authorization is created.
6. Verify the existing idle sandbox and its copied exact container/image identity before any approved restart of that existing container. No rebuild/update/exec, no isolation changes. Perform only read-only Java→sudo→wrapper preflight/observe, MySQL/Flyway and transaction checks, baseline/residual readiness, and separate harmless fixtures proving the CPU-observation/physical-recovery production paths. Never write fixture results into historical evidence or claim idle CPU is fault evidence.
7. Produce fresh acceptance identities and clean-state checks, then stop before any real experiment authorization.

## Checkpoint outcome

M1 CORE CODE: MERGED

FIELD DEPLOYMENT: BLOCKED — awaiting explicit approval for the operations above.

READONLY PREFLIGHT: NOT RUN — no independent field environment has been established.

R1/R2 HISTORY: UNCHANGED by this work.

REAL AUTHORIZATION: NOT CREATED; live absence still requires fresh verification.

THIRD REAL CREATE: NOT EXECUTED.
