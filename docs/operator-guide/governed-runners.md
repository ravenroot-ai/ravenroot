# Governed agents and runners

The approved Workspace profile explicitly selects a driver. The Docker path below remains a Docker
supervisor, including when deployed by Helm. For native Pod/PVC execution without a host socket,
use [Kubernetes-native runners](kubernetes-runners.md), its positive preflight and support matrix.

The runner plane is opt-in. It adds visible Workspace lifecycle nodes and named Agents with optional
typed `workspaceRef`; ordinary Agent graphs retain the bounded AI-extension behavior. A named
definition also works without `workspaceRef`: its approved model profile, instructions, command and
budgets are composed through the ordinary managed AI extension, with no container or filesystem.
Graph provider/instructions/tool overrides are ignored for named definitions. It returns a validated
approved outcome and direct JSON payload. Optional versioned `skillInstructions` map declared skill
names to bounded bodies; conversational `load_skill` additionally requires the intersected
`TOOL_CALL` capability and `load-skill` tool grant. Workspace-only skill references can instead bind
operator-installed worker skills. Graph MCP properties never widen a named definition's tool grants.

Each definition's `budgets` object contains positive `modelTurns`, `toolCalls`, `modelTokens`, and
`tokensPerTurn`. Omitted legacy values migrate to 12, 24, 20000, and 2048 respectively; publish explicit
values for new versions. Workers apply the minimum of definition and operator budgets. A definition
change, including its budgets or skill body, requires a new version. The generic
`workspace-agent` node is removed. See [ADR 0042](https://github.com/ravenroot-ai/ravenroot/blob/dev/adr/0042-explicit-workspaces-and-runner-coordinators.md).

## Enable the control plane

Use a durable execution store. Set `RAVENROOT_RUNNER_CONFIG` to an operator-owned JSON file using
the [example configuration](../examples/governed-runner/control-plane.json). The configuration
declares protocol version 1, approved tenant ceilings, bootstrap definitions and runners, the
workload issuer, and a separate control-plane artifact volume. It contains no bearer tokens.
Without this setting the authenticated runner routes return `RUNNER_PLANE_UNAVAILABLE` (501).
For Compose, combine the root `compose.yaml` with the
[runner override](../examples/governed-runner/compose.override.yaml), supplying the operator-owned
configuration file and OIDC settings. Change `artifactDirectory` in that mounted file to
`/opt/ravenroot/data/runner-artifacts`, under the existing control-plane data volume. Do not mount
the Docker socket in a production control plane. The worker runs separately on its designated trusted host.

### Tokenless trusted-local service

For a single trusted developer host, `./service.sh start --runner` and
`./service.sh restart --runner` supervise both control plane and worker in one JVM. No bearer token
is entered, generated or hidden. The UI is structurally USER; the worker holds a private in-process
WORKLOAD capability. No public route/header can select that identity. Production OIDC is unchanged.
Only host **127.0.0.1** publication is accepted; an extra/wildcard port fails before startup.

Prepare operator-owned `.ravenroot-local/runner/control-plane.json` and `worker.json` once:

1. Adapt the example control-plane catalog to tenant `local` (replace the `example-tenant` key),
   set `runnerIssuer` to `urn:ravenroot:local-worker`, and place `artifactDirectory` at
   `/opt/ravenroot/data/runner-artifacts`. Retain exact approved definitions/profiles/registration.
2. Adapt the worker example to `tenantId: local`, **remove** `endpoint` and `tokenFile`, use
   `/usr/bin/docker` and `/opt/ravenroot/data/runner-state`, and install an immutable Agent image ID.
   Keep the exact catalog registration. Configure an approved model profile and finite budgets;
   a secretless local endpoint needs no `credentialReference`. Model credentials, if explicitly
   selected for a manual local provider, remain separate operator secret-reference bindings.
3. Set `RAVENROOT_DOCKER_CLI_IMAGE` to an approved `docker@sha256:<digest>` CLI image. Set
   `RAVENROOT_LOCAL_DOCKER_SOCKET` only if the dedicated daemon uses a nondefault Unix socket.
   `service.sh` derives its group and builds the explicit `local-runner` target. The ordinary
   image does not contain that CLI or receive the socket. Existing `-si`/`-sb`/`-st` choices apply.

Then run `./service.sh start --runner`. Configuring a model/approved runtime is separate from
authenticating the local worker; no user token management is involved. The explicit local mode
trusts this server host with its dedicated daemon, never an Agent container. Linux Landlock/seccomp
and effective writable quotas remain mandatory; macOS/nono is not required or a substitute.

`./service.sh stop` also stops the supervised worker. Shutdown first stops admission, durably
requests sticky stops for owned active **and idle retained** Workspaces, and acknowledges only
confirmed physical quiescence before closing storage. Restart preserves database/artifacts/receipts
and those stops; it does not silently reopen a stopped resource. A crash or cleanup failure retains
recovery obligations. Configure `RAVENROOT_LOCAL_RUNNER_STOP_GRACE` (default `5m`) to cover the HTTP
drain and your configured Workspace cleanup budget. Do not force-kill or discard worker state.
An immediate restart waits for the previous worker's persisted availability lease to expire;
it never reuses that session or takes over a live lease. The approved worker policy's `wallTime`
bounds this wait. Set `RAVENROOT_COMPOSE_WAIT_TIMEOUT` to cover the prior `worker.availabilityTtl`
and normal startup, especially when increasing the advertisement lifetime.

Definitions are immutable by tenant, name and version. A changed body needs a new version.
Approval changes use `expectedRevision`; a stale editor receives a conflict. Bootstrap creates
missing approved resources but never reapproves a retired resource after restart. Runner
self-registration creates a draft and cannot alter its immutable identity or grant approval.

Give operators the narrow scopes they need:

| Scope | Purpose |
|---|---|
| `ravenroot.runner.read` | Catalogue, workspace and retained artifact inspection |
| `ravenroot.runner.admin` | Definition publication and approval/retirement; user principals only |
| `ravenroot.runner.control` | Cancellation, liveness reconciliation and explicit continuation resolution; user principals only |
| `ravenroot.runner.dispatch` | Designated workload registration, claims, heartbeats and reports |

Normal role checks still apply. A workload's issuer must match the deployment's configured issuer;
its authenticated subject must be the designated runner ID. Tenant selection comes from
authentication, never GraphML, query strings, report fields or a runner advertisement.

## Run the designated worker

The standalone entry point is `ai.ravenroot.server.RunnerWorkerMain`, taking one
[worker configuration](../examples/governed-runner/worker.json). Install the real model-backed runtime image
locally and replace the example all-zero image digest with its real `sha256:` image ID.
The entry point refuses non-digest image selections. The workload credential file must be
owner-only; rotate that file rather than copying a token into the graph, definition or state directory.
Use HTTPS except for explicit loopback development.
The worker's `worker.maxConcurrentJobs` configures job slots (default 4, not a maximum), with independent
heartbeats. Workspace profile reader/writer capacities, queue policy and five fleet ceilings are
independent settings. Workers advertise live incarnation-scoped slots; approved registration alone
does not prove availability. A queued opening can be placed when a compatible pool worker arrives.

Build the real model-backed runtime from [Agent.Dockerfile](../examples/governed-runner/Agent.Dockerfile),
using an operator-verified digest-pinned Python/Alpine `BASE_IMAGE`. Record the resulting image ID
under `runtimeImages.agent`. Its disposable Git repository has an intentional addition bug for the
bounded plan/implement/test/remediate/review exercise. Image construction installs Git; Agent
execution has no network. The separate [Dockerfile](../examples/governed-runner/Dockerfile) and
`reference_runtime.py` are deterministic protocol-conformance fixtures, not real Agents.

Configure `agentRuntime.models.governed-model` with the complete HTTPS chat-completions `endpoint`,
operator-approved `model`, and `credentialReference`. Keep credentials out of JSON. Reference
`runner-model` resolves through the existing secret provider to environment variable
`RAVENROOT_CREDENTIAL_72756E6E65722D6D6F64656C`, injected into the supervisor only. Model profiles have
independent `maxConcurrency`, `maxRequestBytes` and `maxResponseBytes`. The runtime's `modelTurns`,
`toolCalls`, `modelTokens`, `tokensPerTurn`, `protocolBytes`, `toolOutputBytes`, `listedFiles`, approved
`testCommand` and skill texts are finite operator settings. HTTP connect and complete-body deadlines
apply. Token accounting must be returned by the model endpoint; excess reported usage fails closed.

Writable reference jobs require a Linux Docker daemon that actually enforces per-container storage
quotas. The worker runs a bounded probe before advertising writable service. Some daemons accept
`--storage-opt size` while ignoring it; those configurations are deliberately refused. Do not remove
the probe or substitute an unbounded host bind mount. Read-only invocations inside a reused writable
container use kernel-enforced Landlock/seccomp restrictions; an isolated read-only container also
uses a read-only root. In both cases,
network, secret mounts, additional host mounts and external tool grants are unsupported by the
reference driver. The Docker control socket belongs only to the trusted worker host and is never
mounted inside a job container.

Snapshot layers consume a cumulative conservative Workspace budget; replacing or deleting a file
does not refund old immutable layers. Subsequent writable jobs receive only the remaining byte
allowance. Commands without process-execution authority receive a PID ceiling of one. Cleanup
retains only stable lock/ownership metadata after removing the accepted containers and snapshots.
Linux Landlock ABI 3 or later is required for per-invocation write confinement inside a reused
container. Unsupported kernels and ineffective quotas refuse dispatch, including Docker installations
that accept a storage flag without enforcing it. Model/tool cancellation is checked at each boundary.

## Graph lifecycle and independent scaling

Add a Workspace node, select `workspaceProfile` and its exact version, then connect `open` → `ready`
to named Agents. Their `workspaceRef` dropdown only offers Workspace nodes in the same graph. Route
`close` → `closed` before END when the profile uses REQUIRE_CLOSED. ABORT completion policy instead
requests audited cleanup. Multiple resources have separate state, capacity and stop controls.
PROCESS_INSTANCE shares one worktree across Agents, loops and later traversals. PER_WORKSPACE retains
one container; PER_INVOCATION replaces containers without recloning. EPHEMERAL isolates invocations;
NAMED explicitly reuses tenant/profile ownership only after prior quiescence. Session identity remains
tenant/process/Agent scoped, independently of the shared filesystem.

The Compose override supervises worker-a and worker-b with separate persistent state and workload
identity files. Supply `RAVENROOT_WORKER_IMAGE`, worker configuration/token paths and a dedicated
quota-capable Docker socket/GID. Build the supervisor with `Worker.Dockerfile` and approved immutable
Docker CLI/server images. `RAVENROOT_WORKER_MODEL_ENV_FILE` is an operator-owned environment file
containing the approved model credential mapping; it is loaded into supervisors, never Agent containers.
For services, see `deploy/systemd/ravenroot-runner@.service`; each instance uses its own configuration,
credential and state directory. Do not hand-start a replacement with another worker's state.

Helm `runnerPlane.enabled=true` composes a distinct scalable runner API, **not multiple general
Ravenroot servers**. Set `runnerPlane.configMap`, `sharedEnvironmentSecret` and `artifactClaim`;
the secret supplies PostgreSQL/OIDC settings and the shared volume must support cross-process POSIX
locks. Configure `artifactDirectory=/var/lib/ravenroot/runner-artifacts`. The single graph authority
and coordinator replicas mount that same volume. `coordinatorReplicas`, `coordinatorPort`,
`coordinatorHttpThreads`, `coordinatorHttpQueue`, `coordinatorResources` and each
`workerPools[].replicas` scale independently. Pool entries need immutable supervisor image, worker
configMap, workload identitySecret, modelSecret, quota-host nodeSelector and state storage. Ordinal
pod names become stable worker IDs; preapprove every intended ID and provide its matching token key.
The operator worker JSON may use `{instance}` in registration.runnerId and tokenFile.
`RAVENROOT_RUNNER_INSTANCE` supplies that stable value (the Helm pod name); it does not grant
registration approval or change the identity proved by the separately mounted workload token.

`RunnerCoordinatorMain` requires PostgreSQL, OIDC and `RAVENROOT_RUNNER_SHARED_ARTIFACTS=true`. It
exposes only `/v1/runner-plane/`; graph execution, authoring credentials, consent, program builds and
embedded browsers are absent. Reports commit to shared storage, then the single graph authority's
recovery loop delivers continuations. Resolve uncertain graph fan-out through that graph authority.
The existing full-server replica guard is unchanged; a graph-authority outage delays continuation,
not worker report durability or cancellation ownership.

CLI inspection/control uses the normal authenticated `--server` connection: `runner catalog`,
`runner availability`, `runner health`, `runner audit`, `runner workspace <process>`,
`runner stop-workspace <process> <node> <revision>`, and `runner cancel-job|reconcile-job <process> <job>`.
Explicit `resolve-job` requires the current revision and reviewed RESUME/ACKNOWLEDGE/ABANDON disposition.

## Observe and reconcile

Open **Run → Agents and runners** in Workbench after authenticating to the service.
Inspect the catalogue or enter an exact process instance UUID. The panel shows the workspace and
runner, full invocation/attempt/job chain, version, command, effective authority, lease fence,
deadline, outcome and bounded digest-verified artifact previews.

- QUEUED work may receive one execution claim.
- CLAIMED work requires fenced heartbeats.
- Cancellation requests a stop; it does not prove the process tree has stopped.
- Expired dispatched work becomes UNKNOWN and keeps workspace ownership.
- Reconciliation obtains a fresh report-only fence. It never executes the job again.
- A quiescent accepted terminal report releases the sequential handoff barrier.

Process lifecycle is authoritative over runner delivery. `POST /v1/processes/{processInstanceId}/{command}`
requires `Idempotency-Key` and `X-Ravenroot-Expected-Generation`. `pause` and recoverable `stop`
prevent new execution claims and keep accepted terminal reports parked, including reports arriving
through HTTP or after restart. They do not erase evidence or prove that an already dispatched
process tree stopped. STOP additionally requests cancellation of every Workspace; those sticky
resource stops survive process resume. Process `resume` (or `drain`) admits eligible parked delivery;
runner `RESUME` resolution alone never overrides a process hold. A racing lifecycle command wins
or loses at the same process revision used by continuation delivery; a stale write must re-read.

Process `cancel` atomically records the graph as `FAILED` with termination reason `CANCELLED`,
the lifecycle journal event, per-job cancellation audit, runner stop requests and the process
terminal-retention timestamp. Queued work is cancelled without execution. Claimed work becomes
cancelling; unknown and reconciling jobs retain their sticky stop request and workspace ownership
until a correctly fenced quiescence report arrives. Accepted terminal evidence remains unchanged.
Cancellation clears obsolete graph-delivery uncertainty and never routes normal or blocked
successors. Recovery and health include unresolved runner jobs even in terminal processes.
Duplicate reports and lifecycle commands cannot restore a cancelled process or advance its retention
anchor. Retain terminal inventory until these remote jobs and their cleanup obligations are settled.

The job lifecycle is journaled with process/traversal/invocation/attempt identity. Admission is
caused by the original node-start event; terminal reports causally precede graph completion.
Authorization decisions use the existing audit sink. Catalogue rows retain the latest approving
actor and revision. With the existing `RAVENROOT_OTEL_ENABLED` configuration enabled, recovery exports
`ravenroot.runner.observations`, labelled only by the fixed `ravenroot.runner.counter` enum:
`RECOVERY_OBSERVED`, `UNKNOWN_OBSERVED`, `RECOVERY_CONFLICT`, `WORKER_FAILURE`,
`JOB_COMPLETED`, `JOB_CANCELLED`, `JOB_DEADLINE_EXCEEDED`, `WORKSPACE_CHECKPOINTED`,
`WORKSPACE_STOPPED`, and `WORKSPACE_RELEASED`.
The designated worker exports `ravenroot.runner.worker.active`, an unlabelled gauge bounded by its
configured capacity, plus `ravenroot.runner.worker.capacity` and `ravenroot.runner.worker.available`.
The worker uses the same existing OpenTelemetry environment configuration/exporter as the server.
Worker `driverCommandTimeout` (default `PT30S`), `driverOutputTimeout` (default `PT10S`) and
`maxSupervisorOutputBytes` (default 65536) govern daemon operations, bounded pipe draining and cleanup
inventory output. These are operator properties, not universal daemon-speed or fleet-size limits.
These are process-local observations, not durable distinct-job totals: repeat sweeps can count the
same uncertainty again and counters reset on restart. No tenant, runner, job, credential or content
is a metric label. Use the durable journal for exact event history.
Unlabelled `ravenroot.runner.recovery_page.*` gauges describe the last bounded recovery page:
`queued_jobs`, `cancelling_jobs`, `active_workspaces`, `retained_workspaces`,
`per_invocation_workspaces`, `per_workspace_workspaces`, `reserved_storage_bytes`, and
`retained_artifact_bytes`. They are explicitly page observations, not global totals or measured
filesystem usage. The direct Agent result also carries bounded model-turn/tool-call usage; reported
model tokens remain provider-accounted rather than a claim about billing or unused reservations.
Workbench's **Next health / metrics page** reads tenant-scoped active-job health and page gauges;
these are not global totals and registration alone never proves an idle runner healthy. **Next audit
page** reads bounded durable runner-event history, including the committing revision, actor and fence.

If the worker cannot prove an old effect, leave it UNKNOWN. Inspect the named container, accepted
fence and retained evidence, and restore the original worker state if available. Do not resubmit the
job, clone the process onto its old workspace or infer quiescence from a missed heartbeat.
If a restart observes only part of a terminal job's successor fan-out, it durably records
`RUNNER_JOB_CONTINUATION_UNCERTAIN`. Workbench shows the marker; automatic redelivery, new
workspace admissions and cleanup remain refused. Inspect the graph's accepted child invocations
and audit, then choose a disposition in Workbench, confirming the displayed process revision:

- **Resume undispatched successors** (`RESUME`) requires zero recorded child invocations and an
  open traversal. It clears the barrier and permits trusted graph continuation, never runner execution.
- **Acknowledge complete successors** (`ACKNOWLEDGE`) requires a completed source invocation and
  the exact successor target multiset from the pinned graph, including duplicate-target edges.
  It clears the barrier without dispatching a missing branch.
- **Abandon unfinished traversal** (`ABANDON`) preserves recorded effects and children, fails the
  unfinished traversal (and the process if no other traversal remains open), and clears the barrier.
  This does not assert that a missing branch executed or undo an already observed effect.

Partial fan-out cannot be resumed automatically. Either establish the complete observed successor
set through the graph's normal recovery procedure and acknowledge it, or explicitly abandon it.
Every disposition requires a same-tenant user with control authority, an exact current revision,
and the process fence. Its graph transition, marker clearance and `RUNNER_JOB_CONTINUATION_RESOLVED`
audit event commit atomically. Stale or repeated submissions conflict; refresh and review again.
After a successful resolution, ordinary admission and cleanup guards still apply. A crash after
`RESUME` commits is recoverable by the normal sweep without repeating runner effects.

## Retention, backup and rollback

Keep terminal process inventory longer than the largest workspace retention plus the planned
runner outage window. A live designated worker receives cleanup authority only after the process
and all jobs are terminal and retention has elapsed. It removes only the accepted job containers
and workspace-labelled snapshots. Artifact retention is separately enforced on retrieval and
control-plane cleanup. Retrieval expiry is the process terminal timestamp plus the artifact job's
pinned retention, not the early job completion time. Cleanup uses that same timestamp plus the
largest pinned retention. Subsequent audit/runner writes do not extend it; in-progress processes
keep their evidence available. LOG, STDOUT and STDERR share one cumulative retained-log quota,
inside the total artifact quota. Directory-locked accounting includes unreferenced and pending
uploads across restarts; failed uploads cannot reserve unlimited hidden bytes.
Minimal lock metadata may remain to preserve cross-process synchronization.

Back up the execution database, operator configuration, control-plane artifact volume and worker
state/container snapshots consistently. A database-only restore cannot reconstruct a workspace
whose physical effects were lost. SQLite migration 28 and PostgreSQL migration 8 originally added
runner state/catalogue; migrations 30 and 10 add live availability, fleet locks and retention guards.
Existing bounded Agent graphs do not acquire Workspace access implicitly. Workspace storage v3
adds explicit resources and ownership generations; all adapters retain legacy v1/v2 reading.
Process retention cannot purge outstanding physical ownership, including unknown jobs. Cleanup must
be positively acknowledged before that retention guard is released.
Legacy terminal rows without a timestamp are conservatively retained and cannot authorize cleanup;
an explicit operator liveness reconciliation records a new conservative retention anchor using the
store clock. It never guesses a historical terminal time from a later audit update. Back up before
this migration; older binaries cannot read rewritten version-3 workspace envelopes.
Before rolling back to an older binary, drain and reconcile runner work and follow the storage
backup/restore procedure; do not delete runner rows to make an older process start.

SQLite migration 29 and PostgreSQL migration 9 persist process control state on the process row,
under the same revision/CAS as its audit and idempotency writes. Journal compaction cannot release
a PAUSE/STOP or prevent a later RESUME. Stop every writer and back up before this schema upgrade;
running old and new writer binaries against the same upgraded store is unsupported, and older
binaries reject the newer schema on startup. Existing rows recover the last retained lifecycle
command, or RUNNING when the tenant history is complete and contains no command for that process.
If a compacted legacy prefix makes the state unknowable, the row becomes RECOVERY_REQUIRED:
runner claims and continuation delivery remain parked until an authorized explicit process RESUME,
PAUSE, STOP, DRAIN or CANCEL with the observed generation settles it. No migration guesses RUNNING
from missing evidence. New processes start RUNNING even when older tenant history was compacted.
The control state survives journal delivery, expiry, compaction and restart, until the process itself
is removed by normal terminal retention. The journal remains bounded audit history, not current authority.

## Verification

The documented sample deliberately fails its first unit test, uses the Agent's write tool to remediate, then
passes testing and review. It includes an explicit planner → reader → planner call/return using
`read`, `answered`, and `resume`, with a new caller invocation and no waiting caller worker.
Its planner, test and remediation nodes use explicit `joinPolicy=any` for the alternative loop
arrivals. An already terminal ProcessInstance is never resurrected: later
traversals reuse a workspace while the enclosing process/ingress session is still open.

The daemon-backed read-only/reconciliation/cleanup contract is an explicit test requiring an
operator-selected Linux base: run the core `LocalContainerRunnerIntegrationTest` with
`-Dravenroot.runner.testBaseImage=<repository>@sha256:<digest>` and, when necessary,
`-Dravenroot.runner.testDocker=<absolute-docker-path>`. It builds only its fixture image and removes
its exact job container/snapshot resources. Run writable development-cycle acceptance only on a
quota-enforcing daemon; the worker's quota attestation must pass first.
Build the example image, then run
`WorkspaceAgentRuntimeTest#writableContainerDevelopmentCycleUsesRealWorkspaceAcrossEveryRestart`
with `-Dravenroot.runner.testWritableImage=sha256:<local-image-id>` and
`-Dravenroot.runner.testAgentConfiguration=<absolute-worker-json>`. This executes the five-step
minimal team and the eleven-step development cycle under both runtime lifecycles,
checks the intentional failing test followed by successful remediation, and restarts both the
worker and SQLite-backed graph continuation between jobs. It refuses a non-enforcing daemon
before dispatch. The supplied base image is never removed; successful execution cleans its own
workspace containers and snapshots. A failed execution may retain its exact named job resources
for diagnosis; do not prune unrelated Docker state.

Full-tier GitHub CI also invokes `scripts/fixtures/runner_quota_acceptance.py` on its ephemeral
Ubuntu x86_64 host. The fixture requires preinstalled XFS tools and passwordless sudo; it never
installs packages or changes the default Docker daemon. It creates a bounded 4 GiB regular file,
formats only that file as XFS with project quotas, and starts a separate classic-overlay2 daemon
with isolated data/state/socket/PID paths, no bridge and no firewall management. It builds the
example from an immutable official Python image manifest, runs the production quota probe and
the exact model-backed Workspace test, and rejects missing, skipped or failed test evidence.
The fixture starts its own bounded 127.0.0.1 chat-completions endpoint, generates a secretless profile,
and uses a secret provider that throws on any attempted lookup. `RAVENROOT_RUNNER_ACCEPTANCE_CONFIG`
is forbidden in this CI entry point. No live model endpoint or credential is accepted or required.
The **hermetic model-protocol endpoint fixture** returns deterministic tool proposals, not final
job reports: the unchanged production gateway and bounded Agent runtime execute real reads, writes,
tests, results and remediation inside native containers. The endpoint has no Workspace/daemon access.
This is protocol/lifecycle evidence, not trained-model inference. The test also starts the same Agent
for independent processes, compares later-traversal session/container identities, and checks
uncommitted filesystem sentinels and isolation. Teardown verifies and stops only its identified
daemon, unmounts surviving namespace and overlay mounts deepest-first before the backing XFS
filesystem, then removes its own temporary directory. Unconfirmed shutdown, unexpected mounts or
failed unmounts retain the fixture instead of forcing deletion. A cleanup failure is reported
separately without replacing the original acceptance exception; failed cleanup also fails an
otherwise successful run. No host-wide prune, lazy unmount or default-daemon operation is used.
Unsupported tooling, kernel, filesystem or quota behavior fails the job; it does not fall back to
an unbounded writable substrate. This fixture refuses local and self-hosted environments.
Only a successful host-backed job is writable acceptance evidence; a passing fixture unit test
or Docker's acceptance of a flag is not evidence of enforcement.

Live-provider inference is a separate **owner-only local opt-in smoke**, never invoked by CI or a
PR/merge/release gate:

```sh
python3 scripts/fixtures/runner_live_provider_smoke.py --owner-opt-in \
  --worker-config /absolute/operator-worker.json --image sha256:<installed-agent-image-id>
```

Use an existing quota-enforcing Linux daemon and the approved `agentRuntime.models.governed-model`
HTTPS endpoint, model, finite bounds and `credentialReference` described above. The owner supplies
and attests that reference's authorized local binding; do not infer authorization from ambient
credentials. The command refuses CI. Its absence never blocks contributors or merge. Optional local
nono protection is additional only, never a replacement for the native Linux enforcement exercised
by the mandatory secretless CI fixture. No real credentials or live provider may be added to required
checks. See the [acceptance map](../integrator-guide/workspace-acceptance.md).
Startup allows at most 60 seconds of monotonic elapsed time, including individual Docker probes
(at most five seconds each). A successful CLI exit with server errors or empty server fields is
not readiness. A live response using a different driver or data root fails immediately. Startup
failures emit `RUNNER_QUOTA_STARTUP_FAILURE` before teardown with the daemon exit code, bounded
last-probe evidence and the last 16 KiB of the private daemon log; environment variables and the
complete Docker info response are not dumped. Readiness never substitutes for the subsequent
production quota-enforcement probe and model-backed Workspace acceptance.
The supported substrate follows Docker's documented
[XFS project-quota requirement](https://docs.docker.com/reference/cli/dockerd/#overlay2-options)
and [isolated-daemon contract](https://docs.docker.com/reference/cli/dockerd/#run-multiple-daemons).
