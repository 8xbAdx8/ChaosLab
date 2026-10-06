# M1 privileged wrapper

## Current status — 2026-10-06

The historical Phase 2D-2 review below describes the original implementation
gate, not current deployment authorization. The approved REAL/FAKE-slot wrapper
is now installed on the dedicated VM with an exact, no-argv sudo boundary.
Only short-lived, single-use, identity-bound FAKE authorizations are permitted.
REAL authorization and real ChaosBlade create remain prohibited.

The first VM fake suite stopped at read-only preflight before launching a child;
root lifecycle acceptance is still pending. Java now persists the preallocated
native UID before dispatch and uses the fixed wrapper transport, but production
identity attestation and recovery evidence integration remain incomplete.
Windows and isolated Linux backend verification passed. See
`../../docs/10-m1-privilege-deployment.md` for deployment evidence and blockers.

NOT REAL EXECUTION READY. REAL EXECUTION NOT AUTHORIZED.

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
