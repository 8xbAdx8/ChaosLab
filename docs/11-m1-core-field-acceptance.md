# Independent M1 Core field acceptance — PLAN ONLY, NOT AUTHORIZED

This revision authorizes code, harmless tests and Git/PR only. No VM deployment,
REAL authorization, new Blade create, R2 cleanup or state rotation is performed.
R1/R2 results/artifacts stay unchanged; passing future Core cannot relabel them.

## Approval and independent environment

Before field work, approve the reviewed PR/commit and a separate clean acceptance
environment explicitly. Preserve the original R2 VM/database/binding/native state
in place. Prefer an independent dedicated VM clone for the new acceptance run;
any clean-state initialization, deployment identity updates and permissions there
need explicit approval, and must not operate on the source R2 environment.
Use the already-reviewed ordinary chaoslab account, exact sudo wrapper, fixed
candidate and isolated idle sandbox policy. No new privilege model/Agent is needed.
Build the approved Java21 commit, record jar SHA and deploy side-by-side; do not
use 698a905/a1cd739/36dc137 as if they implemented this new Core contract.

## Fresh preflight, before any authorization

- Verify ordinary chaoslab backend UID/GID, zero capabilities, only fixed wrapper
  sudo permission and no direct Docker socket access; bind HTTP to 127.0.0.1.
- Verify Java21, reviewed jar/commit, MySQL/Flyway and an independent empty app DB;
  never reset/reuse the source R2 schema or execution IDs.
- Confirm fixed container full ID/image ID, Running, exact sleep3600 command,
  nonroot user, network none, no sensitive mounts/socket, cap-drop ALL,
  no-new-privileges and unchanged CPU/memory/PID/restart limits. On natural sleep
  expiry, starting an existing reviewed container needs the approved idle flow;
  no rebuild/update/exec or configuration expansion is authorized by this plan.
- Pin wrapper, four tool hashes, policy digest, node/state identity, PID/start-time/
  cgroup. Confirm baseline normal, residual CLEAR, health probe available.
- Verify binding/native experiment state absent in the **new** generation and
  REAL authorization ABSENT. Missing/wrong identities abort before side effects.
- Prove actual Java→channel→sudo wrapper read-only chain and MySQL commit/rollback
  there. Harmless local tests are not a substitute for this fresh field preflight.

## Single future run, only after explicit experiment approval

1. Java creates a new execution and CSPRNG 16-lowercase-hex native UID, and commits
   PREPARING + CRI intent/UID. Independently confirm the durable commit.
2. Only then may the approved administrator create a short-lived, single-use REAL
   authorization for this exact execution/UID, full deployment/tool/policy/target/
   node/state identities and fixed CPU10%, count1, timeout10s. No path/UID via HTTP.
3. Normal backend chain dispatches **one** create:
   `create cri cpu fullload --container-runtime docker --container-id <fixed full ID> --cpu-percent 10 --cpu-count 1 --timeout 10 --uid <Java UID>`.
   No manual Blade command, auto-retry, stronger load or repeated run to obtain PASS.
4. Java checks the matching create UID and immediately reads the existing root
   direct-cgroup sampler. It validates baseline and during CPU and commits the
   typed M1_CPU_OBSERVATION before returning RUNNING. Missing proof is uncertain,
   preserves occupancy and UID, and must not cause another create.
5. After sufficient production fault evidence, issue one normal backend destroy
   for the same execution/UID. Strict native acknowledgement establishes the active
   request, not the recovery winner. No second destroy/create during settling.
6. Fresh identity/status/observe may repeat within the unchanged 15-second bounded
   settling window. Same-UID Destroyed + CLEAR + HEALTHY + fresh same-subject Gate
   and committed during proof are all required **inside production** before SUCCESS.
7. Persisted physical assessment, structured rounds and execution state are read
   for reporting only. recoveryCause remains UNKNOWN even if timeout may have won.
   Do not apply a timestamp/actor/strace condition after SUCCESS to veto Core.

## Acceptance and failure semantics

SUCCESS is Core completion under this contract, not proof of ACTIVE causality or
the report module's separate three-window metrics attribution. Use separate fields:

- execution/Core result: committed SUCCESS or incomplete/manual;
- physicalRecovery: existing Gate VERIFIED or INCOMPLETE/MANUAL;
- recoveryCause: UNKNOWN (M1+; no caller receipt exists).

The M1_PHYSICAL_RECOVERY audit is an adapter assessment before the final execution
transaction. It is not proof that SUCCESS committed: always report the final DB
status too. Preserve all uncertain/failed attempts and occupancy; no external
backfill, replay authorization or success laundering. If missing during proof,
residual PRESENT/UNKNOWN, health UNKNOWN/UNHEALTHY, wrong identity, stale evidence,
audit persistence or final transaction fails, production must not report SUCCESS.
If SUCCESS occurs without a required Core condition, that is a contract defect to
report and investigate, not permission to rewrite a terminal record.

The future operator report contains experiment/execution/UID, full target identity,
baseline/during CPU from production audit, active request time, same-UID status,
safe per-round summaries, final physical assessment, final committed status,
occupancy and consumed authorization. Do not require `strace -p Java` or add a
post-SUCCESS veto. Report-module metrics may still be NOT_COLLECTED/UNVERIFIED.

If actual unsafe CPU/residual/VM behavior occurs, only explicitly approved stop-
sandbox/VM emergency actions may be used; manual containment is not Gate VERIFIED.
After this one future run, stop regardless of outcome. This plan creates no
authorization and does not itself approve a third real experiment.
