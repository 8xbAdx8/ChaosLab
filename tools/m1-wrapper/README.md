# M1 privileged wrapper

## Current status — 2026-10-06

The historical Phase 2D-2 review below describes the original implementation
gate, not current deployment authorization. The approved REAL/FAKE-slot wrapper
is now installed on the dedicated VM with an exact, no-argv sudo boundary.
Only short-lived, single-use, identity-bound FAKE authorizations are permitted.
REAL authorization and real ChaosBlade create remain prohibited.

The VM production v4 wrapper passed the actual root FAKE lifecycle and probe
regression; REAL read-only preflight passed, CPU baseline is 0%, and REAL create
without authorization was rejected three times. Java preallocated UID, trusted
root identity attestation and the existing recovery evidence Gate are now wired.
Windows and isolated Linux backend verification passed. Actual Java backend
runtime/configuration on the VM has also passed the 2026-10-07 read-only
acceptance: Java 21.0.12.1, ordinary uid999/gid987, dedicated loopback MySQL8,
validated V1-V11, real beans and observed Java-to-root-wrapper ancestry. See
`../../docs/10-m1-privilege-deployment.md` for deployment evidence and blockers.

First REAL M1 executed once on 2026-10-07: **M1 INCOMPLETE**.
Injection observed (0% -> 9.80%); active same-UID destroy reached engine Destroyed,
but backend final status is ROLLBACK_FAILED, never SUCCESS. Later CLEAR/HEALTHY
probes do not retroactively certify the failed recovery flow. Occupancy retained.
REAL authorization consumed/absent; no second create authorized. Stop for review.

2026-10-07 root-cause-only follow-up: harmless Java and isolated root FAKE tests
confirmed the one-observe PRESENT failure path. A local Java-only bounded
status/observe settling fix and allowlisted recovery reasons passed Windows/Linux
verification; production root probe/Gate/wrapper unchanged, no VM upgrade or
REAL authorization. First M1 remains INCOMPLETE. Second M1 is NOT authorized.

## Historical Phase 2D-2 implementation review (before installation)

Phase 2D-2. Go 1.25, standard library only. Production target: Linux amd64.
No real Blade executable is invoked by the tests. No VM permissions, accounts,
sudoers or services are changed. No root wrapper execution is used for testing.

## Identity and boundary

The proposed backend account is **chaoslab**, not the interactive administrator
**w**. The future account has no login, no sudo/docker group and no capabilities.
Only a future exact named-user sudoers entry may grant the fixed wrapper command
with no arguments; no generic sudo/root/docker capability is granted to Java.
No account or sudo rule is created in this phase.

```text
Java (chaoslab) -> exact sudo entry -> root-owned compiled wrapper
                                     -> fixed pinned blade + companions
                                     -> Docker Unix socket / namespace / cgroup
```

The wrapper rejects production invocation unless Linux euid is 0 and argv has
no arguments. Windows has no production execution path. The unexported config
constructor and fake target check are Go unit-test seams only: no production
flag, environment variable or request field enables them. Build production with
CGO_ENABLED=0; a dynamically linked privileged executable would create an
additional pre-main loader/environment surface. Sudo environment sanitization
must also be reviewed; clearing environment in main cannot undo runtime startup.

Fixed production paths (not accepted in policy or stdin):

- executable: `/opt/chaoslab/m1/api3-identified/blade-chaoslab-api3-identified`
- policy: `/etc/chaoslab-m1/policy.json`
- node marker: `/etc/chaoslab-m1/node-id`
- state: `/var/lib/chaoslab-m1/state`
- state marker: `<state>/.chaoslab-state-id`
- Docker socket: `/var/run/docker.sock`
- future wrapper installation: `/usr/local/libexec/chaoslab-m1-wrapper`

Policy supplies exact container/image identity, pinned companion hashes, node/
state identities and CPU/duration values. It cannot select an executable, socket,
environment, namespace, PID, cgroup or state path. The supported sandbox is
`chaoslab-cpu-sandbox`, user `65534:65534`, network none, readonly rootfs, no
mounts/devices/binds, no privileged mode, no added capabilities, CapDrop ALL,
private cgroup namespace, 0.5 CPU, 128 MiB, PIDs 32, restart no. Full IDs and
Running are checked using a bounded GET to the fixed Docker socket. No raw
inspect response is returned. No fixture establishes the actual VM target ID.

## Protocol

One JSON document on stdin, then EOF; production input deadline 2 seconds and
limit 4096 bytes. Strict UTF-8, exact field spelling, no unknown/duplicate keys
(including escaped aliases), no trailing JSON/data, no nested depth beyond 16.

```json
{"operation":"status","executionId":"12345678-1234-4234-8234-123456789abc","nativeUid":"0123456789abcdef"}
```

Operations: `preflight`, `create-cpu`, `status`, `destroy`, `observe`.
Preflight accepts neither identity field. Others require canonical lowercase
execution UUID and exactly 16 lowercase hex native UID. There are NO path,
container, flags, shell, environment or log fields. The wrapper does not generate
the production UID: its caller must persist a CSPRNG UID with the platform intent
before requesting dispatch. `newNativeUID` is used by the platform proof fixture,
not a claim that Java has already been changed.

Stdout is one versioned Result JSON with code/outcome/exitCode/cleanupComplete/
handoff, optional nativeUid and validated child response. Raw child stderr, OS
errors, invalid JSON and attacker request text are never echoed. Child response
must be a strict success envelope (`code=200`, `success=true`, no error). For
create its result MUST equal the bound UID. For status/destroy the existing Java
operation decoder still has to validate the complete business response schema:
wrapper OK is transport success, not `Destroyed` or `RecoveryVerified`. Result
writing is also limited to two seconds; caller backpressure cannot retain the
wrapper indefinitely. A lost/partial response never clears the durable binding.

`observe` deliberately returns `OBSERVATION_UNKNOWN`: CPU/residual/health probes
are not implemented in this wrapper phase. Preflight OK means only the checks
implemented here passed; it is NOT an overall REAL EXECUTION READY decision.

## Authorization and binding durability

Create is DISABLED without a trusted `authorization.json` in the fixed state
directory. Authorization has the same fields as Binding, must match the request
and current policy, and expires after five minutes. No request can install an
authorization. Only a later explicitly authorized administrator procedure can
create it. No such installation utility or production authorization is supplied.

Binding fields: executionId, nativeUid, exact containerId/imageId, toolIdentity
(SHA-256 of the ordered four-file hash manifest), stateIdentity, nodeId, createdAt.

Before child start:

1. Validate policy, files, target and authorization.
2. Acquire an exclusive fixed operation lock. Stale lock fails closed; no automatic
   stale-lock removal, no cross-process concurrent dispatch.
3. Require a fresh dedicated native state: no chaosblade.dat, WAL, SHM or journal.
   This M1 restriction prevents adopting a collision with an old/foreign native
   record after a duplicate-UID create failure. Root must not run tools out of band.
4. Create `binding.json` with O_EXCL, mode 0600; write, fsync file, close, fsync
   directory. It cannot overwrite a previous binding.
5. Remove authorization and fsync directory. The binding already prevents replay
   even if removal or its fsync fails. Any failure means NO child dispatch.
6. Reverify tool files immediately before starting the fixed argv.

An interrupted/partial binding blocks future create and requires manual review;
it is not interpreted as success. Every create attempt consumes the single-shot
slot, including malformed JSON, timeout, nonzero exit, response loss and tool
identity change immediately before launch. No automatic reset or retry API exists.
Status/destroy/observe require exact binding and current policy/target match.

This is **pre-dispatch authorization/ownership state**, NOT a post-create receipt.
Only PREALLOCATED_UID is selected; a second durable receipt is not implemented.
Java intent-transaction integration is still pending and must not dispatch before
its commit. State files must remain available throughout manual recovery.

## Privileged child lifecycle

The wrapper uses exec.Command with an absolute executable and an internally built
argument array. It never calls `sh -c`. Environment is cleared/rebuilt; children
receive only PATH=/usr/bin:/bin, LANG=C, LC_ALL=C, HOME=<state> and
CHAOSBLADE_DATAFILE_PATH=<state>. Production uses umask 077 and marks inherited
FDs above stderr close-on-exec. No request bytes are forwarded to child stdin.

Command deadline 5 seconds; combined stdout/stderr limit 64 KiB. Both streams
must finish within the deadline. An inherited pipe held by a helper therefore
cannot cause indefinite waiting: it becomes TIMEOUT, not a false HANDOFF.

- create: exit 0 + drained bounded streams + valid success JSON + same UID allows
  HANDOFF. helper survives; cleanupComplete is **false**, handoff is true.
- invalid/mismatched create response is CREATE_UNCERTAIN; known helpers are cleaned
  but binding remains. Cleanup is NOT proof the fault never existed/recovered.
- status/destroy: STRICT_FOREGROUND. Surviving observed descendants produce
  DESCENDANTS_REMAINED and bounded cleanup, not EXITED success.
- timeout/cancellation/SIGTERM/SIGINT/output limit/nonzero/IO/invalid response:
  wrapper-side TERM then KILL of known process identities; no Java ProcessHandle
  dependency. Cleanup deadline 700 ms plus bounded wait for foreground reap.
- Linux pidfd signals avoid killing a recycled numeric PID. Tracking uses bounded
  /proc ancestry and process-group discovery with start-time identity checks.

**Limits:** cleanupComplete concerns observed/responsible processes, not global
residual CLEAR. Very fast setsid/double-fork escape before observation is not fully
contained. SIGKILL of the wrapper, kernel uninterruptible sleep, unavailable pidfd,
proc visibility failures and privileged lifecycle behavior require further review.
No claim that killing sudo or a process group proves complete recovery. Candidate
namespace/cgroup migration and sudo/PAM behavior are NOT exercised by these tests.

## Native UID and timeout proof

`proof/api3_uid_test.go.txt` is copied ONLY into a disposable copy of locked api3.
Neither source tree is modified; source mounts and dependency cache are readonly.
The compiled TEST program selects only TestM1UIDProof and TestM1TimeoutProof.
No actual Blade main or real executor runs. It runs as uid 1000, network none,
no Docker socket. The fake executor only returns the current test PID.

Confirmed against candidate source:

- create.go Init declares persistent `--uid`; actual Cobra inheritance reaches
  createExpModel. recordExpModel uses nonempty UID rather than generating one.
- data/experiment.go uses UNIQUE uid and ordinary INSERT, not REPLACE/upsert.
- an independent SQLite connection observes U before the fake Executor.Exec entry.
- duplicate U fails before a second executor invocation and inserts no alternate UID.
- create response, status query, destroy lookup/context/update all use U.
- original recoveryCommandFor starts a timer whose destination is a **compiled
  fake test executable**, with quoted/spaced absolute path, same UID/state/euid.
  Candidate's internal fixed shell timer is being tested; wrapper has no shell.

Source hashes used (SHA-256):

| File | Hash |
| --- | --- |
| cli/cmd/create.go | 198ba48bb0adb38b07c45bc192c4e1737ea54994c523adf1f99392463b8d57b5 |
| cli/cmd/command.go | 167fe3050f9e5ea3a66ae28a81622d84cf06150686bb2c6cb290c7f0de8b80c6 |
| data/experiment.go | 727428e866843a5a86a2280fb988a3172d7a7aa681f8c1eafef265e9f33b943b |
| cli/cmd/recovery_command.go | adefc4c054462a6235a32d1ef849df1fd2593079749bfd08d3333045b3990f36 |

Conclusion: PREALLOCATED_UID ACCEPT for this locked synchronous CRI contract.
This proves ordering/SQLite visibility and same UID semantics, not physical-disk
power-loss durability, actual fault behavior or final installed binary attestation.

## Filesystem and TOCTOU limits

Production checks root ownership, no group/other write, no setuid/setgid,
no symlinks along all ancestors, no extended/default ACLs, file type, bounded
128 MiB hashes, executable owner bit, companion files, and both identity markers.
Tests simulate owner with the nonroot test UID inside a private temporary root;
that exception cannot be selected by a production invocation.

Replacing the tool between first verification and final pre-exec verification is
tested and rejected. There remains a path-based check/exec race after the final
check, and companions are subsequently opened by the candidate. This does NOT
defend against a privileged concurrent updater, mount replacement or malicious
root. A no-update-while-active procedure plus reviewed root-owned installation
is mandatory. Do not describe this as complete TOCTOU elimination or fexecve.

## Tests and review gates

Windows executes the portable parser/authorization/binding/response tests using a
cross-compiled Go test executable. Windows production and process execution are
unsupported; Linux ownership/ACL/fsync/process/signal tests are **not** claimed
as Windows coverage. Linux runs all tests as uid 1000 with --init, --network none,
no Docker socket. Fake executables live in per-test temporary directories.

Use `go test ./...`, `go vet ./...` on Linux; run `-race` for portable parser/state
tests separately (race-built subprocesses have extra runtime/exit delays and are
not the bounded lifecycle fixtures used for this timing validation). Build with
`CGO_ENABLED=0 go build -trimpath`. Windows tests can be built with
`GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go test -c` and run on Windows.
Do not run these tests as root. No sudo/root smoke run is authorized in this phase.

Remaining before VM application: review wrapper code/attack coverage; decide how
administrator provisions one-shot authorization after platform intent commit;
wire Java without treating sudo's exit as child's lifecycle outcome; audit real
sudo/PAM and service account; verify actual sandbox and immutable deployment;
test privileged namespace/cgroup/helper behavior only in an approved later phase;
implement residual/health/CPU and final recovery gates. Audit logging installation
and retention also remain deployment work. This module does not release occupancy.

### Candidate proof reproduction

In a **nonroot**, network-disabled container with no Docker socket, mount the two
candidate source trees and `proof/` readonly and the existing Go module cache
readonly. Within that disposable container only:

```text
cp -r /source/chaosblade-patched /tmp/chaosblade-patched
cp -r /source/chaosblade-cri-patched /tmp/chaosblade-cri-patched
cp /proof/api3_uid_test.go.txt /tmp/chaosblade-patched/cli/cmd/m1_uid_proof_test.go
cd /tmp/chaosblade-patched
go test -p 2 -vet=off -c -o /tmp/uid-proof.test ./cli/cmd
/tmp/uid-proof.test
```

GOPROXY=off, GOSUMDB=off, HOME and GOCACHE under /tmp. Invoke the proof test binary
without CLI arguments: candidate imports parse flags before testing.Init; the
proof's TestMain selects ONLY its two harmless tests. Never invoke candidate main.
No other candidate test or executor is dispatched.

### Final verification — 2026-10-04

| Check | Result |
| --- | --- |
| Linux Go 1.25, uid 1000, full wrapper suite | 17 top-level tests plus attack/lifecycle subtests; three consecutive passes (`-count=3`) |
| Linux portable parser/state race selection | 9 top-level tests, passed |
| Linux go vet | passed |
| Linux static production build | CGO_ENABLED=0, build passed; no installation |
| Windows native execution of cross-compiled test binary | 10 portable top-level tests plus subtests, passed |
| api3 source + fake executor UID proof | passed |
| api3 original timer + compiled fake destination | passed |
| Production binary invoked as uid 1000 | correctly returned DENIED / exit 1, no child execution |

No wrapper was executed as root. Linux process/signal/permission behavior is not
claimed as Windows coverage; Windows privilege path is intentionally disabled.
No Java changes, Maven verification claim, migration, api3 modification, VM
deployment, commit or push occurred in this phase. Existing unrelated working-tree
files, including the old recovery-evidence prototype, are untouched and not wired.

Initial validation caught a bad readonly-rootfs test mutation (it set true rather
than false); the fixture was corrected, not the production allowlist. Candidate
test launch initially hit its existing init-time flag parser; compiling then
launching the isolated test without arguments resolved it without altering api3.

WRAPPER IMPLEMENTATION REVIEW READY

VM PERMISSION CHANGES NOT AUTHORIZED

REAL EXECUTION NOT AUTHORIZED

## Current M1 closeout status — 2026-10-06

The preceding phase records are historical. The dedicated VM now has the reviewed
no-login chaoslab account and exact no-argv sudo boundary. The installed production
v3 wrapper has passed the actual chaoslab -> sudo -> root FAKE child lifecycle
suite, including HANDOFF, failure cleanup, STRICT status/destroy and signal tests.
SIGKILL cannot produce a cleanup attestation: no result is UNKNOWN and exact known
test children required harness cleanup. All FAKE authorizations were consumed or
removed; cross-deployment, changed-policy, expiry and replay rejection passed.

The REAL candidate is deployed separately at /opt/chaoslab/m1/api3-identified,
root-owned, without a current symlink; original home-directory files remain.
REAL policy is fixed and has no authorization. Version/root help and idle sandbox
inspect/baseline passed without a real experiment. Version/help initialized an
empty native SQLite store through eager package initialization. Root diagnosis
proved all three tables empty and integrity OK; that exact SHA-locked DB was
recoverably archived before regression, not deleted or adopted.

Current probe build adds fixed-target cgroup-v2 usage_usec sampling, conservative
residual scope and identity/CPU-based sandbox health. The pre-dispatch baseline
is fsynced with the root binding; observation carries the same execution/UID/node/
container/image. No binding means health UNKNOWN even if idle/readiness is good.
Java correlates fresh observations using the existing recovery Validator/Gate.
Neither a clean process group nor engine Destroyed alone means RecoveryVerified.

Probe build SHA-256:
`2e27c2f2b15fb011c1562f0a2fc19640e7b5e0f3fef1d9c166bf54969c654b94`.
Windows portable Go tests and Linux full Go tests/vet passed. Installed SHA was
independently checked after final-probe-verification.json reported passed=true.
The actual root chain confirmed helper PRESENT while alive and CLEAR/HEALTHY
after known fixture cleanup, with unchanged UID/execution and baseline 0%.
The REAL policy was restored, sudo unchanged, no authorization remains.
Do not rerun initialization scripts or bypass the fresh-store gate. No binding
in the REAL slot means its read-only preflight health stays UNKNOWN, as intended.

No REAL authorization or real Blade create is authorized. Ordinary backend
runtime/configuration subsequently passed the 2026-10-07 final read-only
deployment acceptance (unchanged 698a905 artifact plus one-shot diagnostic main).
See docs/08-m1-tool-compatibility.md and docs/10-m1-privilege-deployment.md for
current evidence and blockers. No permission model change or new framework is
needed to investigate these deployment blockers.

Final read-only deployment report: java-readonly-deployment.json passed=true.
HTTP is only 127.0.0.1:18080, MySQL only 127.0.0.1:3306/chaoslab_m1;
repository commit/rollback tested only target metadata. No Experiment, execution
or native UID exists. Root/no-argv sudo rule and all tool/policy identities are
unchanged; ordinary Java cannot access Docker socket or write privileged paths.
Probe readiness is true, residual CLEAR, CPU baseline 0%. Health without a
recovery binding stays UNKNOWN, not RecoveryVerified. The diagnostic entry point
uses existing private read-only seams of the actual configured beans, not mocks
or a new endpoint. Standalone observe requires a binding and was not faked.
This is readiness for a separately approved first experiment, not a successful
real fault/recovery. Do not rerun database/deployment initializers.

### First REAL M1 result supersedes the earlier readiness-only record

See docs/evidence/m1-first-real-20261007.json (SHA-256
7e8867fcdd2475ecf5c969c0eb6c1bf7539503c9a4ca65d42d0d0178dec5fffa).
One backend create only, UID21d2d20071b3f449 committed before root authorization;
binding/native record/strict successful receipt preserve that UID. Target cgroup
contained the actual pinned nsexec/chaos_os in both during samples; CPU was
9.800499% over 1.256354 seconds. Primary destroy was requested without waiting
for timeout; native Destroyed timestamp was about 5.15 seconds after create.
Backend reported IllegalStateException / ROLLBACK_FAILED. Exact failure cause
was not preserved; timer-residual timing is a candidate inference, not a proven
diagnosis. Root observations afterward showed 0%, CLEAR and HEALTHY. They were
not used to rewrite the platform status or release occupancy. No retry, new
authorization, configuration change or emergency stop was performed afterward.
