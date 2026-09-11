# ADR 0034: An accepted execution pins the dependencies it resolved, not the environment it finds

- Status: Accepted
- Date: 2026-09-04
- Supersedes: Recovering an accepted execution by replaying its pinned document against whatever the recovering process happens to compose
- Superseded by: None
- Public references: [persistence lifecycle](../docs/operator-guide/persistence-lifecycle.md), [API and CLI reference](../docs/reference/api-cli.md), [backup and recovery](../docs/reference/backup-recovery.md)

## Context

ADR 0031 made the exact canonical document behind an accepted execution durable, and that is
necessary. It is not sufficient. The same document produces different behavior depending on the
submission policy it runs under, the admission stance for a behavior no trusted catalog entry claims,
the node packages installed beside it, the graph admission and traversal limits in force, the engine
it runs on and the program runtime composed with it. Every one of those can change between the moment
an execution is accepted and the moment it is recovered, and none of them was recorded.

The consequence was silent rather than loud. A recovery read the pinned document, resolved the rest
from the running process, and produced an execution that could differ from the one a caller was told
had been accepted — without any surface reporting that a substitution had occurred. Two cases are
concrete and reachable rather than theoretical: the pinned recovery runner hard-codes the standard
submission policy, so an execution admitted as structural test evidence comes back invoking
production behaviors; and it takes execution limits from the recovering process rather than from the
execution, so a traversal admitted under one set of bounds resumes under today's.

Not every dependency the problem names has an identity this build can record. There is no planner and
no versioned parser dialect, so there is no such version to pin. A node package's content digest
exists but is verified by the bundle loader at start-up and never reaches the engine. A program
artifact's approval state is mutable authorization that ADR 0020 requires to be re-read at redemption
rather than restored.

## Decision

An accepted execution pins a versioned, immutable, tenant-scoped **execution manifest**, keyed by the
process instance and write-once at the same granularity the graph version pin already is.

The manifest records the graph content address it was accepted against — the same address ADR 0031
files the document under, so there is one graph identity rather than two — together with the canonical
snapshot and definition format versions, the submission policy, the unknown-behavior stance, and
digests of the engine's identifier and capability set, the execution store's capability set, the graph
execution limits, and the program runtime's identifier and compatibility contract. It records each
resolved node package by id, its own build version and the Node SDK contract it was compiled against.

The manifest is committed after the definition and before the acceptance that references it. The
asymmetry that makes that ordering sufficient is ADR 0031's: a manifest pinned for an acceptance that
then fails is an unreferenced row, while an acceptance with no manifest is an execution that can only
be recovered by substitution.

Every field is an integer, an instant, a constrained identifier or a hexadecimal digest. A manifest
has no free-form value channel, so a credential, a bearer token or an authorization snapshot cannot be
represented in one. Adapter-supplied identities are digested rather than copied, which keeps that
property true without validating third-party text and refusing an otherwise sound deployment. That
applies to a node package's own declared version and SDK contract too: the Node SDK contract has never
constrained their shape, so validating them would refuse a package the runtime has always accepted,
inside plugin activation, at start-up.

Not carrying a secret is not the same as being safe to hand to anyone. A comparison's two values
describe the *deployment* — which packages are installed, a digest of the operator's limits, which
engine is composed — and belong in a server-side diagnostic. A projection crossing a tenant boundary
reports the differing dimensions and nothing else, which answers whether a caller's execution can
still be reproduced without answering what is installed on the servers running it.

Before initial dispatch and before every recovery, resume, restart after a hold and ownership
takeover, the manifest is read back and re-digested from its stored fields. Identity and compatibility
dimensions are compared exactly. Format 2's operational values are restored instead of compared with
current defaults. A package installed since acceptance that the execution never used is not a
difference: the document decides which behaviors run and the document is pinned exactly.

A missing, corrupt, digest-mismatched or incompatible manifest fails closed with a typed outcome and
a bounded diagnostic naming each differing dimension and both of its values. No similar graph,
package, artifact or policy is substituted. On the recovery loop that means the claimed work is
neither dispatched nor acknowledged: it stays claimable, which is that loop's existing fail-closed
answer, and the refusal is logged because a boolean `supports` cannot carry the reason and the
condition never resolves on its own.

Before format 2, format 1 detected and refused the two reachable defects above without repairing
them. The format 1 recovery runner hard-coded the standard policy and took the then-current limits;
a composition holding a manifest refused mismatches before reaching it, while a composition with no
manifest store performed no verification. That historical boundary is why a format 1 row cannot be
silently promoted: it never contained the values that format 2 restores.

### Operational policy format 2

Manifest format 2 closes that deferred work for new executions. In addition to the format 1
identities and digests, it stores the complete resolved `GraphExecutionLimits` tuple, whether durable
results are enabled and their maximum retained payload size, and the finite byte, duration,
concurrency and queue capacities for each node package the accepted graph actually uses. Package
capacity records explicitly distinguish a package with no managed egress from one with bounded
managed egress. A graph-scoped record also stores the request, response and timeout ceilings for the
core `http-request` behavior when that behavior is present. These records never contain destinations,
headers, credential references, credentials,
allowlists, bearer material or an authorization decision.

The stored values are the authority for every dispatch and continuation of that process instance.
Current environment defaults neither widen nor narrow them. Request-specific external-I/O limits may
narrow the stored capacities, while destination authorization, credential resolution and revocation
remain live checks. The runtime resolves policy from the stored manifest and binds it to the trusted
execution and traversal identity before a package handler can run; values carried by a package-created
message are not an authority. Durable result encoding and adapter validation use the same stored
payload maximum.

| Pinned field family | Typed acceptance-time authority | Resolution precedence |
| --- | --- | --- |
| Graph parsing, payload and traversal limits | `GraphExecutionLimits` | A new process snapshots the composed value. Every manifest-backed dispatch and continuation uses the stored tuple; current environment values apply only to later process instances. |
| Durable-result availability and payload cap | `ExecutionStore.capabilities()` and `ExecutionStore.maxExecutionResultPayloadBytes()` | Acceptance snapshots the composed adapter decision. Result encoding and the adapter validate against that stored cap even when the current adapter default is higher or lower. |
| Managed node-package I/O capacities | `NodePackageServices.egressCapacityProfile()` as `NodePackageEgressCapacityProfile` | Acceptance stores profiles only for packages used by the graph. A request may narrow its stored profile; current numeric service defaults cannot replace it. |
| Core `http-request` request, response and timeout ceilings | `BehaviorEnvironment.outboundHttpPolicy()` as `OutboundHttpPolicy` | Acceptance stores the values only when the graph uses `http-request`. The stored ceilings control its later requests; current destination, port, tool and credential authorization still run independently. |

Format 1 remains byte-for-byte readable and keeps its original digest calculation. A format 1
execution may continue only when its legacy digest proves the current graph-limit tuple is identical,
the legacy store digest proves durable results were disabled, and trusted behavior registration proves
that its graph cannot call any operationally bounded external I/O, including the core HTTP behavior.
If either newly required numeric policy could matter, the
runtime returns `LEGACY_OPERATIONAL_POLICY_UNAVAILABLE`; it does not fill the missing value from the
current environment and does not overwrite the write-once format 1 row with format 2.

### Generic persistence capacity format 3

Manifest format 3 adds the execution store's generic maximum payload size as a separate persisted
value. It is deliberately distinct from the durable-result limit: results may be disabled, and a
custom result accessor may impose a different maximum from the store that persists process events,
timers, approvals and authority budgets. A server-managed process therefore snapshots the actual
composed execution store capacity at acceptance. The managed store verifies the pinned manifest
identity and capacity atomically with the first process write, and requires the immutable live store
capacity to equal the pin before later new writes or claims. A higher current capacity cannot widen
the accepted policy, and a lower one cannot silently make only some continuations writable.

Fencing and matching idempotent replay retain their existing precedence. A matching committed replay
returns its recorded result without creating a new fold even when the current capacity differs;
stale fencing still refuses. Candidate discovery is read-only, but pending-work and timer claims are
restricted transactionally to keys whose format 3 manifest and generic capacity were verified. Live
authorization and tenant isolation remain independent checks.

Formats 1 and 2 remain byte-for-byte readable with their original digests. They did not record a
generic persistence capacity, and the durable-result field cannot establish it. Server-managed paths
therefore refuse new writes and claims for those rows with a typed missing-policy outcome rather than
inventing a historical value. Before format 4, unmanaged adapter compositions kept their format 2
contract, and new process instances pinned format 3 only when the composed execution store provided
the managed atomic manifest/process boundary. Format 4 changes the new-admission representation as
described below without changing those stored rows.

### External-I/O execution envelope format 4

Manifest format 4 adds the decompression-ratio component of each used node package's capacity and
the quantitative envelope of each non-bypassed pin-capable graph node. A node entry contains a
canonical digest of its graph node, package and behavior binding together with message bytes,
fragment count, timeout and concurrency. It does not contain the profile destination, headers,
subprotocols, credential reference or secret. Those remain live authorization and can revoke a
recovered operation.

The runtime validates the graph before calling package capacity code, resolves the quantitative
values once for admission, and reuses that exact snapshot for the manifest and handler construction.
A hosted deployment materializes pin-capable handlers once per active traversal, selected by the
runtime's process, traversal and node identity; overlapping executions accepted under different
profile revisions therefore keep their own bounds. Their sends still compete in the same
tenant-and-profile admission counter, with each operation applying its own pinned maximum.

Format 4 represents generic persistence capacity separately and explicitly. A server-managed
execution carries the capacity and retains format 3's atomic first-write and claim checks. An
unmanaged embedded composition carries an absent persistence disposition rather than inventing a
store limit, while still pinning external-I/O values. Managed writes continue to refuse that absent
authority.

Formats 1 through 3 keep their original bytes and digests. The historical package policy admitted
caller decompression ratios through the fixed platform ceiling of 1000, so decoding a format 2 or 3
package record restores exactly 1000 rather than the new-admission default of 100. Older manifests
did not carry node-bound I/O. Recovery therefore refuses an older row when its graph contains a
non-bypassed pin-capable node, and does not fill the missing values from today's profile. An authored
bypass remains a structural statement that the behavior is neither resolved nor constructed.

Inbound sources have no execution key and do not put their receive-loop settings into a manifest.
Their core-issued context is instead bound to one deployment activation generation. Construction is
deny-only, activation occurs immediately before start, and rollback, stop, restart or undeploy revoke
ingress, health reporting, credential lookups, new transport calls and handed-off managed sessions
before package cleanup callbacks run. Cancellation requests are cooperative; admission remains held
until the managed resource actually settles, rather than claiming that arbitrary plugin or JDK work
has stopped within a fixed wall-clock duration.

A comparison covers the dependency profile and the node packages. It does not cover the graph content
address or the logical graph identity, because a caller obtains the "current" side by describing the
runtime for the manifest it just read, so those fields are copied and could not differ. The graph is
enforced more strongly elsewhere: the definition store re-derives a document's address from its bytes
on every read. What is pinned and what is compared are therefore different sets, and the contract
says so rather than leaving a reader to infer that a compatible verdict covers everything.

Retention cannot remove a manifest whose execution still exists. Reachability is recomputed inside
the removal transaction rather than tracked by a counter, and removal is refused rather than answered
with silence. Reclamation is a caller-invoked operation over one tenant, mirroring the definition
store's, because two populations otherwise accumulate that nothing would ever remove: manifests
pinned for an acceptance that then failed, and manifests whose process instance a later retention
pass deleted. As with definitions, no operator-facing surface invokes it in this release.

Composing a manifest store is what turns both halves on. A deployment that composes none behaves
exactly as it did before this record. A deployment that composes one refuses to recover an execution
accepted before it did so; nothing backfills such an execution, because a backfilled manifest would
be a description of today's environment presented as a record of yesterday's, which is the
substitution this record exists to prevent.

## Consequences

- Recovery either reproduces the execution that was accepted or refuses; it no longer silently
  produces a third thing.
- An execution accepted under a non-standard submission policy is refused by recovery rather than
  resumed as a standard run.
- Enabling manifests on a database that already holds accepted executions makes those executions
  unrecoverable through the paths that verify. They remain readable and remain retained; what is
  refused is resuming them.
- The two reachable recovery defects remain live wherever no manifest store is composed. Format 2
  removes them where a manifest is present by restoring the values that were accepted.
- Three dependency classes are pinned less than completely, and the manifest's wording claims only
  what it records. A node package is pinned by id and by a digest of its declared version and SDK
  contract, never by a digest of its content, so a package republished under an unchanged version is not
  detected. A program artifact's
  content digest is pinned transitively, because its source lives in the document, but its approval
  state is deliberately not pinned and stays a live check at redemption. There is no parser version
  and no planner version because neither exists in this build.
- The unknown-behavior stance is recorded from a probe that an integrator may override, and a value
  outside the three defined ones is recorded as `unknown` rather than verbatim. Two different
  unrecognised stances therefore compare equal.
- Verification adds one durable read per submission and one per recovery attempt.
- A tenant reading the compatibility projection learns which dimensions changed and never what they
  changed to. Diagnosing *why* a difference appeared requires the server-side record, which is
  deliberate: the alternative discloses a deployment's inventory to anyone who submitted one graph.
  One residual follows from reporting a dimension per difference rather than a set: a caller whose
  packages all differ can recover the size of *its own* pinned package set from the length of that
  list. It is never the deployment's current inventory, it appears only once packages already
  differ, and it is bounded. Collapsing the duplicates would hide whether one package changed or
  five, which is worth more to the caller than the residual costs it.
- Identity and compatibility comparisons remain intolerant. Changing an engine capability or a used
  package version refuses retained work. Changing a current numeric default does not alter a format 2
  execution; format 1 still requires its original graph-limit digest to match exactly.
- Format 3 makes generic persistence capacity an explicit compatibility boundary for managed server
  execution. Increasing or decreasing that deployment value refuses new managed effects for an
  existing process, while an already committed matching replay remains observable. Formats 1 and 2
  are retained but cannot authorize managed persistence after upgrade because neither format proves
  the historical generic capacity.
- Format 4 makes quantitative package decompression and pin-capable node I/O reproducible without
  freezing destinations or credentials. Existing format 2 and 3 rows remain recoverable when the
  graph has no non-bypassed pin-capable node; affected rows refuse rather than inheriting current
  profile values.
- Source receive loops keep deployment-lifecycle settings rather than execution-manifest settings.
  Their authority is narrower in a different dimension: a retained context cannot mutate ingress,
  health, credentials or managed transport after its activation generation is retired.
