# ADR 0042: Explicit Workspaces, named Agents and independent runner coordinators

- Date: 2026-09-16
- Status: Accepted
- Supersedes: ADR 0041
- Related: ADRs 0007, 0022, 0023, 0024, 0034 and 0040; issue #423

## Context

The generic runner node coupled actor identity, filesystem ownership and container lifetime. That
prevented an author from expressing a visible Agent team with direct handoffs and independently
controlled resources. Shared PostgreSQL execution state alone also does not make unrelated
process-local server authorities safe to replicate.

## Decision

Filesystem ownership, runtime reuse and conversational identity have different lifetimes. The
generic `workspace-agent` behavior is removed, without an alias or compatibility shim. A visible
`workspace` node names an immutable tenant-approved Workspace profile. Named `agent` nodes select
that node through optional typed, same-graph `workspaceRef`. Missing, wrong-kind and cross-graph
references fail validation. Ordinary Agent graphs retain their bounded conversational behavior.

A named definition also applies without `workspaceRef`. The registered AI package implements the
typed `GovernedAgentCapable` SDK boundary and captures approved definitions and operator model
profiles at composition. Delivery selects only the authenticated tenant's captured action and
rechecks approval. Graph-supplied provider, instructions, tools and budgets cannot override it.
This remains the ordinary manifest-bound managed HTTP/resource-authority path, not worker dispatch.
No Workspace, repository or container is allocated. Legacy Agents without a definition retain their
original semantics. Governed conversational output must contain an approved outcome and direct JSON
payload; malformed or undeclared outcomes fail. Versioned `skillInstructions` supply the local
`load-skill` tool only when deployment/definition/command grants intersect to permit it. Filesystem
tools are unavailable without a Workspace; graph MCP properties do not grant tools to a named Agent.

Definitions own finite `budgets` (model turns, tool calls, total model tokens and tokens per turn).
Workers intersect these with their startup budgets; conversational Agents also obey managed package
resource and profile ceilings. Definition wire v2 stores these values and immutable skill bodies;
v1 definitions migrate to explicit finite 12/24/20000/2048 ceilings. Operator JSON may override each
positive value. Existing immutable v1 catalog rows are compared semantically at bootstrap rather than
rewritten or silently reapproved. Older workspace/assignment envelopes remain readable.

Workspace commands are `open`, `inspect`, `checkpoint`, `close` and `abort`. Opening reports `ready`
and lets the graph proceed; the node does not hold an engine worker or wrap downstream execution.
A process may have multiple independent Workspaces. Durable resource states include UNMATERIALIZED,
OPENING, READY, CLOSING, CLOSED, ABORTING, ABORTED, FAILED and RECOVERY_REQUIRED. RELEASING and
RELEASED distinguish cleanup reservation from acknowledged cleanup.

`workspaceScope` is independent of `runtimeLifecycle`:

| Dimension | Contract |
| --- | --- |
| EPHEMERAL | Fresh filesystem per Agent invocation |
| PROCESS_INSTANCE | Shared across Agents, loops and later traversals of one process |
| NAMED | Explicit tenant/profile name; sequential ownership generations after quiescence |
| PER_WORKSPACE | One retained container throughout that resource's ownership |
| PER_INVOCATION | New containers restore the retained snapshot, without recloning |

Profile version, scope, runtime, pool and placement are pinned. Agents cannot move, reopen or widen
a resource. Named ownership and physical deletion contend under the same shared-store lock; old
ownership cleanup cannot delete a newer owner's files. Profile changes require explicit migration.
Conversational identity is separately derived from tenant, process and Agent definition; sharing a
Workspace does not merge named Agents. Files, inputs and model output remain untrusted data.

## Admission and cancellation

The identity chain remains process/traversal/invocation/attempt/job. One ExecutionBatch parks the
exact attempt and records the approved definition, effective authority, resource, pinned graph
continuation and causal event. Process revision/CAS serializes graph changes; increasing job fences
authorize reports. In-memory, SQLite and PostgreSQL implement the same store contract.

Authority intersects deployment, Workspace, Agent definition, command and approved worker policy.
Read-only commands structurally remove writes, subprocess tools, egress, secrets and mounts. No
dispatch envelope carries control-plane credentials or graph continuations. Legacy durable envelopes
remain readable for reconciliation; they do not restore the removed graph abstraction.

Live store-clock worker advertisements are distinct from approved registrations. Incarnation,
compatible runtimes and configured slots govern pool selection before first physical dispatch.
Placement cannot change once effects may exist. QUEUE retains pending work; REJECT requires immediate
capacity; AUTOSCALE retains demand for deployment-managed scaling, without granting graph authority
to create machines. Atomic fleet ceilings cover global, tenant, pool, worker and profile scopes:
claimed/uncertain jobs, queues, retained Workspaces and reserved storage. Per-Workspace reader/writer
limits and FIFO handoff are independent. Unknown effects retain capacity.

Worker slots, continuation executor size/queue, recovery pages/interval, leases, HTTP bounds and
cleanup/shutdown timings are operator settings. Four is a compatibility slot default, not a maximum.
Architectural rule: every operational limit is a property/configuration value with an explicit owner,
default and provenance, never an anonymous constant. A fixed bound is permitted only for a documented
inviolable physical/protocol representation limit, with validation and a boundary test. Configuration
inventory classifications and reconciliation are part of the release gate, not optional paperwork.

Expiry after dispatch means UNKNOWN, never retry permission. Reconciliation is report-only. Duplicate
terminal reports do not duplicate audit or graph effects. Partial successor fan-out becomes
CONTINUATION_UNCERTAIN. Revision-protected RESUME requires zero successors; ACKNOWLEDGE requires
the exact pinned successor multiset; ABANDON preserves recorded effects. None reruns an Agent job.

PAUSE parks delivery. Process STOP also requests stops for every Workspace. CANCEL atomically
terminates the process with sticky job/resource stops, audit and retention anchor. A resource abort
does not stop other resources. Workers check cancellation before model turns and each tool call;
terminal reporting requires descendant quiescence. Missing heartbeats are not proof. Effective
process cancellation never routes ordinary successors.

Cleanup requires terminal process/jobs, no unresolved delivery, elapsed profile/definition retention
and authenticated physical acknowledgement. Process TTL cannot erase outstanding resource ownership.
SQL retention guards are conservatively backfilled and changed atomically with runner state. A cleanup
reservation alone does not free capacity. Preserve database, artifacts and worker state together.

## Model and isolation boundary

`Agent.Dockerfile` and `agent_runtime.py` provide the real model-backed bounded loop. The trusted
worker selects an operator-approved HTTPS model profile and credential reference, or an explicitly
approved secretless loopback model-protocol endpoint; credentials never
enter the container. Model turns, tools, reported tokens, output, wall time and concurrency are finite.
Missing token accounting or usage over the remaining budget is refused; reported usage enforcement
does not claim that already billed provider tokens can be undone.

The immutable container has no network, host workspace/socket mount, capabilities or ambient secrets.
It uses non-root UID, CPU/memory/PID/storage ceilings, Landlock write confinement and seccomp process
restrictions. Only policy-permitted read/list/write/test/finish tools are offered; each crosses a
worker stop gate. Writable dispatch requires a positive storage-quota probe. Unsupported enforcement
and uncertain old effects fail closed. Read-only enforcement must withstand malicious instructions,
not merely a cooperative prompt. Detached descendants must quiesce before success.

The supervisor controls a dedicated container daemon and is therefore privileged infrastructure.
Never share its daemon with untrusted workloads or mount its socket into an Agent. The deterministic
`reference_runtime.py` remains a protocol-conformance fixture, not an Agent or model acceptance proof.

Artifacts are opaque job-scoped IDs with verified digest/size. Shared-volume POSIX locks and local
locks serialize uploads and cleanup; unreferenced uploads remain charged. Direct Agent results are
shown separately from ownership metadata. Metrics use fixed enums and unlabelled capacities, never
tenant/job/worker/content labels. The journal audits decisions but is not cancellation authority.

## Independently scalable runner control plane

The general authoring/graph server remains single replica. `ReplicaTopologyStartupCheck` is unchanged:
shared execution persistence does not make every local service distributable.

`RunnerCoordinatorMain` is a distinct executable requiring PostgreSQL, OIDC and one operator-attested
shared POSIX-locking artifact volume, also mounted by the graph authority. It constructs only the
authenticated runner API, approved catalog, admission/recovery state and artifacts. Graph execution,
program builds, authoring credentials, consent, embedded browser and local audit-file services are
absent, not independently replicated. Authorization decisions go to structured deployment logs;
runner mutations journal in the shared store.

Requests may hit any coordinator. Reports commit durably; the single graph authority's recovery loop
resumes exact pinned continuations. Graph-specific uncertainty resolution belongs to that authority.
Its outage delays graph delivery but never permits replaying physical work. Coordinator replicas and
worker pools scale independently. This is not full-server horizontal scaling; shared database capacity
and artifact-lock semantics remain operator responsibilities.

Helm composes the single graph authority, coordinator Deployment and independently sized worker-pool
StatefulSets with stable worker IDs and persistent receipts. Compose/systemd supervise workers.
Supervisor containers are separate from Agent containers. Dedicated quota-capable daemon hosts,
OIDC identities and model credentials are deployment inputs, not properties supplied by GraphML.

## Migration and evidence

SQLite migration 30 and PostgreSQL migration 10 add worker availability, fleet serialization and
retention guards. Workspace storage v3 adds resources and ownership generations; older envelopes
remain readable. Stop writers before upgrade; mixed old/new writers are unsupported. Replace generic
nodes with explicit Workspaces and named Agents, then validate. Rollback requires drained ownership
and a matching backup, never deletion of ownership rows.

Shared-store tests cover independent Workspace stops/retention on all three adapters. Coordinator
tests use real PostgreSQL for concurrent startup, claims, reports and incarnation fencing and prove
unsafe routes absent. The existing full-server replica guard remains tested. Native Linux evidence
additionally requires Landlock/seccomp and quota-enforcing storage. Per owner clarification on
issue #423 (comment 5704464414), automated evidence is mandatory, hermetic and secretless:
`runner_quota_acceptance.py` starts a bounded loopback **model-protocol endpoint fixture** which
returns deterministic chat completions and tool proposals. The production model gateway and
unchanged bounded Agent runtime execute the actual turn/tool/result/remediation loop and native
filesystem effects. The endpoint cannot access Workspaces or execute tools. This proves the runtime
protocol and lifecycle, not trained-model inference. It never substitutes deterministic final job
reports for the production runtime. All thirteen acceptance scenarios remain automated gates.

Live-provider inference is a separate owner-only local opt-in smoke, never called by CI, required
checks or release gates. No real credential, external model provider, offline weights, macOS or
nono installation is a contributor prerequisite. Optional local nono protection cannot replace
Linux Landlock/seccomp or XFS quota enforcement. Absence of owner smoke attestation never blocks merge.

## Tokenless trusted-local composition

`service.sh start --runner` and `restart --runner` explicitly compose one general server and an
in-process supervised worker. Public loopback authentication remains USER. A fixed WORKLOAD
RequestContext exists only in the host-created `LocalRunnerClient`, which directly invokes the same
`AuthorizedRunnerControl` reference monitor. There is no worker listener, bearer token, hidden token,
header-based identity switch or public bypass. Remote worker/OIDC composition is unchanged.

Only exact IPv4 127.0.0.1 host publication is supported. Runtime validation additionally requires
disabled local authentication and either that exact bind or the existing verified container-loopback
contract; service.sh verifies the rendered single-port publication before any lifecycle effect.
The local catalog and worker must share tenant `local`, exact preapproved registration and durable
storage. Worker configuration rejects remote endpoint/token fields. This explicit host mode grants
the server supervisor access to its dedicated Docker daemon; ordinary images/deployments do not gain
that authority and Agent containers never receive it. Quota and Linux confinement checks remain
mandatory, including on developer machines.

Shutdown drains graph/HTTP admission and closes recovery before stopping worker admission. Every
nonterminal retained Workspace owned by the local worker receives a durable sticky stop, including
idle PER_WORKSPACE containers. Only physical driver quiescence permits the stopped acknowledgement;
then the worker/driver closes before storage. A failure remains visible and retains stop/recovery
obligations; it is not relabelled successful cleanup. Restart preserves the durable store, artifacts,
receipts and sticky stops. Compose's operator shutdown grace must cover configured drain/cleanup time.
Replacement startup waits for the prior store-clock worker advertisement to expire, bounded by
the approved registration's wall-time ceiling; it never steals or reuses a live incarnation.
The operator Compose health-wait budget must cover that availability TTL plus normal startup.

## Consequences

Graph authors explicitly model resource acquisition and release while ordinary Agent graphs remain
compatible. Operators gain independently configured worker pools and runner coordinators but retain
one graph authority; its outage delays continuation delivery. Driver capability must be established
by positive native evidence, so unsupported quota or read-only enforcement prevents execution.
Durable unknown ownership consumes capacity until reconciled and cannot be discarded by retention.
The corrected graph model is a breaking authoring change with no generic-node compatibility alias.
