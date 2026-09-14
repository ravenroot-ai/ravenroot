# Governed agents and runners

The runner plane is opt-in. It adds process-scoped workspace agents; it does not change the bounded
Agent node supplied by the AI extension.

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
the Docker socket in the control plane. The worker runs separately on its designated trusted host.

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
[worker configuration](../examples/governed-runner/worker.json). Install the reference runtime image
locally and replace the example all-zero image digest with its real `sha256:` image ID.
The entry point refuses non-digest image selections. The workload credential file must be
owner-only; rotate that file rather than copying a token into the graph, definition or state directory.
Use HTTPS except for explicit loopback development.
The reference worker has four bounded job slots with independent heartbeats. Different process
workspaces may progress concurrently; the durable control-plane barrier still admits only one
active job within any one process workspace.

The reference runtime image can be built from
[the example Dockerfile](../examples/governed-runner/Dockerfile). Supply an operator-verified,
digest-pinned Python base image as `BASE_IMAGE`; record the resulting local image ID in the worker
configuration. The included Python runtime is a deterministic workspace/test harness, not an LLM.
Production specialists may implement the versioned driver/runtime contract with an approved
provider, but must enforce the same authority rather than treating its instructions as permission.

Writable reference jobs require a Linux Docker daemon that actually enforces per-container storage
quotas. The worker runs a bounded probe before advertising writable service. Some daemons accept
`--storage-opt size` while ignoring it; those configurations are deliberately refused. Do not remove
the probe or substitute an unbounded host bind mount. Read-only jobs use a read-only root;
network, secret mounts, additional host mounts and external tool grants are unsupported by the
reference driver. The Docker control socket belongs only to the trusted worker host and is never
mounted inside a job container.

Snapshot layers consume a cumulative conservative process budget; replacing or deleting a file
does not refund old immutable layers. Subsequent writable jobs receive only the remaining byte
allowance. Commands without process-execution authority receive a PID ceiling of one. Cleanup
retains only stable lock/ownership metadata after removing the accepted containers and snapshots.

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

The job lifecycle is journaled with process/traversal/invocation/attempt identity. Admission is
caused by the original node-start event; terminal reports causally precede graph completion.
Authorization decisions use the existing audit sink. Catalogue rows retain the latest approving
actor and revision. With the existing `RAVENROOT_OTEL_ENABLED` configuration enabled, recovery exports
`ravenroot.runner.observations`, labelled only by the fixed `ravenroot.runner.counter` enum:
`RECOVERY_OBSERVED`, `UNKNOWN_OBSERVED`, `RECOVERY_CONFLICT`, and `WORKER_FAILURE`.
The designated worker exports `ravenroot.runner.worker.active`, an unlabelled gauge bounded to 0–4.
The worker uses the same existing OpenTelemetry environment configuration/exporter as the server.
These are process-local observations, not durable distinct-job totals: repeat sweeps can count the
same uncertainty again and counters reset on restart. No tenant, runner, job, credential or content
is a metric label. Use the durable journal for exact event history.
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
whose physical effects were lost. SQLite migration 28 and PostgreSQL migration 8 add runner workspace
and catalogue tables. Existing bounded Agent graphs do not migrate to workspace agents implicitly.
Workspace storage envelope version 2 adds the store-clock terminal timestamp without changing
runner wire protocol version 1 or SQL table layouts. All adapters read version 1 and write version 2.
Legacy terminal rows without a timestamp are conservatively retained and cannot authorize cleanup;
an explicit operator liveness reconciliation records a new conservative retention anchor using the
store clock. It never guesses a historical terminal time from a later audit update. Back up before
this lazy migration; older binaries cannot read rewritten version-2 workspace envelopes.
Before rolling back to an older binary, drain and reconcile runner work and follow the storage
backup/restore procedure; do not delete runner rows to make an older process start.

## Verification

The documented sample deliberately fails its first unit test, applies `remediationFiles`, then
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
with `-Dravenroot.runner.testWritableImage=sha256:<local-image-id>`. This executes all nine jobs,
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
the exact writable nine-job test, and rejects missing, skipped or failed test evidence. Teardown
stops only its identified daemon, unmounts its filesystem and removes its own temporary directory.
Unsupported tooling, kernel, filesystem or quota behavior fails the job; it does not fall back to
an unbounded writable substrate. This fixture refuses local and self-hosted environments.
Only a successful host-backed job is writable acceptance evidence; a passing fixture unit test
or Docker's acceptance of a flag is not evidence of enforcement.
The supported substrate follows Docker's documented
[XFS project-quota requirement](https://docs.docker.com/reference/cli/dockerd/#overlay2-options)
and [isolated-daemon contract](https://docs.docker.com/reference/cli/dockerd/#run-multiple-daemons).
