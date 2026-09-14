# ADR 0041: Governed workspace agents execute through a fenced runner plane

- Date: 2026-09-14
- Status: Proposed implementation contract
- Related: ADRs 0007, 0022, 0023, 0024, 0034 and 0040

## Context

Long-running workspace effects cannot occupy engine workers or inherit host authority from graph
payloads. Durable specialist handoffs need a process-scoped ownership barrier, explicit runner
trust, and recovery that distinguishes missed acknowledgements from permission to repeat work.

## Decision

Keep the bounded conversational Agent behavior unchanged. Introduce an explicitly composed
`workspace-agent` worker behavior whose graph properties name an immutable, tenant-owned agent
definition version and a designated approved runner. Instructions are data; they cannot grant a
capability, select a host path, change execution identity or approve a runner.

The identity chain is process instance, traversal, node invocation, node attempt, runner job.
The workspace belongs to the process, not the traversal or agent. Its placement remains pinned.
Another traversal of that process shares the workspace; another process receives a different
workspace identity. Sequential specialists cross a quiescence barrier. There is one runner job
claim/fence, not a separate agent-session lease.

Admission atomically parks the exact graph attempt and records the job, immutable definition,
effective authority, graph-budget/join continuation and causal event in an ExecutionBatch.
The process fence serializes control-plane changes; the independently increasing job fence
authorizes runner reports. This is an additive ExecutionStore capability implemented independently
by the in-memory, SQLite and PostgreSQL adapters and tested through their shared contract.

Authority is the structural intersection of deployment, definition, command and runner capability.
Read-only commands remove workspace writes, subprocess tools, egress, secrets and additional mounts.
They are not prompts asking a powerful agent to behave. Protocol version 1 is explicit. Its bounded
dispatch view contains no graph continuation, ingress credentials or operator security context.

Expiry after dispatch means UNKNOWN, never permission to retry. Cancellation and deadlines are
sticky stop requests until quiescence is positively reported. Reconciliation grants a fresh,
report-only fence. A report cannot change the graph pin or the original attempt. A duplicate accepted
terminal report is an idempotent read, not a second journal effect. Downstream uncertainty belongs
to graph recovery and is not a reason to rerun the workspace agent.

Graph-delivery uncertainty has an explicit operator resolution transaction. RESUME requires zero
recorded successors; ACKNOWLEDGE requires the exact pinned-graph successor target multiset and a
completed source invocation; ABANDON atomically fails an unfinished traversal while preserving its
recorded effects. The same-tenant user supplies the observed process revision. A process fence and
revision CAS commit graph transitions, barrier clearance and the actor's audit event together.
Neither stale/repeated resolution nor partial fan-out permits speculative runner or branch replay.

## Reference enforcement boundary

The reference driver runs an operator-installed image by digest, without a host workspace mount,
network, ambient secret binding or arbitrary external tool interface. A stopped-container snapshot
is the persistent process workspace. Read-only jobs use a read-only container root. Writable jobs
require an independently exercised storage quota: a daemon accepting a storage option is not proof
that it enforces one. The driver must refuse writable work when its quota probe fails. Non-root UID,
process, memory, scratch-storage, log and wall-time bounds remain mandatory.

The remote integration is the same designated workload protocol, with issuer and subject bound to
runner identity. Registration creates a draft. Only a same-tenant user with the administrative
scope may approve or retire it. A restarted deployment never silently reapproves a retired profile.
Other drivers are explicit trusted implementations of the SPI; capability advertisements alone
must never be mistaken for an isolation guarantee.

## Evidence, retention and operations

Artifact references identify a job, opaque artifact ID, kind, digest and size, never a host path or
caller URL. The control-plane artifact volume is separate from every runner filesystem.
Every retrieval verifies tenant authorization and digest. Budgets cover streaming admission,
retained bytes and object counts. Results are bounded structured payloads; large logs remain artifacts.
LOG, STDOUT and STDERR share a cumulative directory-locked budget, including unreferenced/pending
uploads. A JVM stripe plus the process-shared file lock serializes concurrent writers and cleanup.

Cleanup requires a terminal process, terminal jobs, elapsed configured retention and an authenticated
release proof. UNKNOWN ownership is not cleaned into a reusable workspace. Operators must keep
terminal execution inventory long enough for runner cleanup and preserve the database, artifact
volume and runner snapshot state together. If a runner stays offline beyond retained authority,
cleanup requires explicit operator reconciliation; absence alone is not a release proof.
Workspace storage version 2 pins the first process-terminal store-clock timestamp atomically with
the graph transition. Retrieval and cleanup anchor retention to that timestamp; later writes cannot
extend it. Version-one envelopes remain readable and are conservatively retained until an explicit
reconciliation creates a store-clock anchor. Runner protocol version 1 is unchanged.
The existing OpenTelemetry exporter receives fixed-enum observation counters and an unlabelled
0–4 worker-active gauge. These describe local observations, not global or distinct durable job counts.

## Consequences

Existing Agent behavior, engine NodeCommand values, read-only graph traversal, model-provider SPI
and execution-store capability defaults are unchanged. New runner operations fail closed on an
adapter that does not implement them. SQLite migration 24 and PostgreSQL migration 4 add the new
state. Older binaries cannot operate new workspace-agent graphs; rollback requires draining this
plane and restoring an appropriate backup rather than deleting its rows.

Benefits are explicit authority, durable identity and no speculative repeated workspace effects.
Costs are a new operator-governed inventory, retained container snapshots, deployment-specific
isolation requirements and deliberate refusal when past effects cannot be established. This record
does not claim that a container daemon, remote host or arbitrary agent image is intrinsically trusted.
