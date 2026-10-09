# M1 Core independent field preparation — readonly PASS

Preparation only. No REAL authorization, third create, or CPU fault was executed.
Observations below are a checkpoint, not authorization or a permanent freshness guarantee.

## Code and exact artifact

- Reviewed PR #1 merged: `0cb29a13690e89a5d4bda2be1c64ebb3408f5536`.
- Reviewed source: `76a2fd2021d2040a58804e7fc98d3b685b9132e7`.
- Identical merge/reviewed tree: `9680dc45710ee1072c71ddbb5c3fe02edeace310`.
- Main CI PASS, Windows/Linux/root harmless jobs: https://github.com/8xbAdx8/ChaosLab/actions/runs/37961691101
- Clean-source, non-root, network-none Java 21 / Maven 3.9.16 verify: 374 tests, zero failures/errors/skips.
- Backend SHA-256: `7be24e5e60d018ff3e938b49765f005e2e838e219ae39a065c0c22641b7d1768`.
- Side-by-side deployment: `/opt/chaoslab-backend/m1-core-76a2fd/`.
- Runtime Java: OpenJDK `21.0.12.1+1-1-24.04.4-Ubuntu`.
- Loaded engine class SHA: `8659414df0370d999b47eeb9305aa622e1224d1b39a71afd4d3c87ac11692038`, matched the pinned backend artifact.

## Independent VM and history preservation

- Original `chaoslab-executor` was gracefully shut down, not reverted. Its VM files, R1/R2 schemas, active state and historical outcomes remain in the original VM.
- Independent FULL current-state clone: `D:\ChaosLab-VMs\chaoslab-m1-core-r3\chaoslab-m1-core-r3.vmx`. No linked disk dependency or old-snapshot revert.
- Clone hostname `chaoslab-m1-core-r3`, VMware MAC `00:0c:29:9c:fe:f7`, independent management DHCP identity. Address at verification: `192.168.32.132`.
- Original remains powered off; only the clone is running.
- Clone inherited history/native state was preserved in root-only `/var/lib/chaoslab-m1/archive/m1-core-r3-inherited/` before generation rotation.
- Archive manifest SHA: `033589f9345a6f0d8ffc5153317790227ff7823fff49070017d2683cc253ab67`.
- Both inherited MySQL full dumps, terminal rows, archived native state and original JSON evidence were checked unchanged after deployment/resume.
- R1 remains INCOMPLETE / recoveryVerified=false. R2 remains external INCOMPLETE, backend SUCCESS, occupancy released, external recoveryVerified=false. No result was rewritten or reclassified.
- Original evidence hashes remain R1 `7e8867fcdd2475ecf5c969c0eb6c1bf7539503c9a4ca65d42d0d0178dec5fffa`, R2 `db66f4ac5df2ccbd1dc09bfd68c9e07389f6b73c9b440ff4385dae9f26909e67`.

## Actual ordinary backend and readonly chain

- Actual Java PID at verification: `2758`; UID `999`, GID `987`, only ordinary service group; effective/permitted/ambient capabilities zero.
- Actual Spring `ChaosBladeEngine` and its production `BladeProcessChannel` were used for fresh target/local identity and root preflight/observation.
- Root wrapper process ancestry was observed leading back to this actual Java PID.
- Direct Docker socket access from Java: DENIED.
- HTTP actual socket: `[::ffff:127.0.0.1]:18080` — IPv4-mapped IPv6 LOOPBACK, not wildcard. Actual HTTP health UP and target repository API readable.
- First installer stopped on a literal string-address check (`HTTP not loopback`). Original Java acceptance had passed; the failed report was preserved. Targeted resume did NOT reinitialize DB/state/archive or replace the backend/wrapper/tool.
- Corrected diagnostic check parses literal socket addresses and rejects wildcard/nonloopback IPv4/IPv6. Observed mapped address confirms a check-script false negative, not widened HTTP exposure.
- No new sudoers, account/group/capability, Docker socket or tool permission changes. Fixed wrapper only; negative sudo tests for sh/bash/docker/env/python/systemctl/systemd-run/direct Blade all denied.
- Ordinary `chaoslab` and `w` cannot directly write wrapper, policy, active state, archive or the four candidate files. Root-owned path/ACL checks PASS.

## Database and new state

- Real MySQL `8.0.46-0ubuntu0.24.04.4`, loopback only.
- Independent `chaoslab_m1_core_r3`, principal `chaoslab_m1_core_r3@127.0.0.1`, schema-only privileges. No use of H2 as field evidence.
- Flyway V1–V11 validated; repository commit/rollback PASS. Only fixed target metadata was committed.
- `experiments=0`, `experiment_executions=0`, `blade_execution_snapshots=0`.
- Node `m1-core-r3-executor`, new active generation `m1-core-r3-state`; fixed state path unchanged.
- REAL authorization ABSENT; binding ABSENT; native DB/WAL/SHM/journal ABSENT. Active generation contains only its root-owned marker.
- Clone REAL policy scope unchanged except node/state identity; CPU ceiling 10%, one CPU in the fixed command contract, timeout 10s. No authorization file created.

## Fixed deployment identities

| Item | SHA-256 |
| --- | --- |
| wrapper | `2e27c2f2b15fb011c1562f0a2fc19640e7b5e0f3fef1d9c166bf54969c654b94` |
| CLI | `c0c987bbd0aa9d158e90ab48f737680fe1b7eefdbf6c9e74744444c49c847e96` |
| nsexec | `693219257100421d3c2321c2b1bfb3d285d1b6eed1fa82feabd033797a51e793` |
| chaos_os | `dee72446e32411f6a0d7a29c71b0ba0cc4dabbab1fcaba212c6c87d0ea965cfb` |
| CRI YAML | `f8deebf2b90c44f414745cc6316e0cef545332173a7ef9a1998b9804531e7880` |
| policy digest | `e12cee75d1e08fcdf74e0089b45d6949f6c816db8c774aafd00776b9e6ed2eef` |

## Sandbox and probes

- Full container ID: `18bb4f8734edbd6e1dc35582692677bcfcf98fef175832fc1d1e29361faab97f`.
- Image ID: `sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8`.
- Existing cloned container only; no run/create/update/exec/rebuild. Cmd `sleep 3600`, Entrypoint null, Running=true.
- Isolation unchanged: user65534:65534, network none, no mounts/socket, not privileged, CapDrop ALL, no-new-privileges, readonly rootfs, CPU0.5 limit, memory128MiB, PID32, restart no.
- PID `2313`, process start ticks `65253`; exact fixed-container cgroup v2. Only that sleep member, only loopback interface.
- Direct `cpu.stat usage_usec` baseline sampling: 0.0%; same PID/start/cgroup during the checkpoint.
- Actual root observation: residual CLEAR, probeReady=true, health UNKNOWN because no recovery binding exists. This is readiness, NOT physical-recovery verification or proof of a fault.

## Harmless production-path verification

Separate memory-only mock ports exercised the SAME engine class loaded from the deployed artifact: preallocated UID intent before simulated dispatch, validated simulated direct CPU observation before RUNNING, one simulated active destroy, fresh status/observe, PRESENT then CLEAR/HEALTHY, physical Gate VERIFIED and `recordPhysicalRecovery` before DESTROYED, cause UNKNOWN.

`M1_CPU_OBSERVATION` and `M1_PHYSICAL_RECOVERY` method paths are verified. The real MySQL experiment tables remain empty; no simulated fault/recovery audit is presented as actual field-fault evidence. Production audit persistence tests are covered by the reviewed full CI. No strace or native provenance change was introduced.

## Sealed readonly result and stop line

- Root audit report: `/var/lib/chaoslab-m1/audit/m1-core-r3-resume.json`.
- Downloaded report SHA: `6aadcdcefbee3026018ca7cb1fb4aed0d0b1d36617919c0139d309fcbf495cf3`.
- Local full report: `D:\ChaosLab-VMs\m1-core-preparation-76a2fd\field-readonly-resume.json`.
- Initial failure report is preserved separately, not overwritten.
- Original offline source-disk hashes recorded after clone: base `4c138a6004917b8594509fa67a323c9e904b34b1f78c9138477e306b514c629f`; delta `03845314a1a192e3fa23e22f6dc013542899856d13b796edeafff675817df5f9`.

M1 CORE CODE: MERGED

FIELD DEPLOYMENT: READY

READONLY PREFLIGHT: PASS

R1/R2 HISTORY: UNCHANGED

REAL AUTHORIZATION: ABSENT

THIRD REAL CREATE: NOT EXECUTED

Stop here. Before any separately approved experiment, reverify all fresh pins/state/identity/baseline and idle-sandbox liveness; `sleep 3600` naturally expires. Do not treat this report as experiment authorization, a new M1 PASS, or proof of active-recovery causality.
