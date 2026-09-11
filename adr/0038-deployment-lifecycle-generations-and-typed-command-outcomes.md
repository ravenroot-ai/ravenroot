# ADR 0038: Deployment lifecycle generations and typed command outcomes

- Status: Accepted contract; implementation is not implied
- Date: 2026-09-06
- Supersedes in part: ADR 0023, which accepted a fenced, durably reconciled deployment authority and
  said nothing about generations, command outcomes, barriers, or which effects a lifecycle port may
  reach. This record extends it on exactly those points and leaves the rest of it in force. It also
  supersedes the `GraphDeployment` Javadoc's account of `stop` as the single way a deployment closes
  admission: `stop` is one of five desired levels, and closing admission is no longer the same event
  as releasing resources.
- Superseded by: None
- Public references: [Deployment and startup](../docs/operator-guide/deployment-startup.md),
  [ADR 0021](0021-deployment-runtime-ownership.md),
  [ADR 0023](0023-remote-deployment-control-plane.md),
  [ADR 0012](0012-engine-supervision-cancellation-and-drain.md),
  [ADR 0035](0035-cancellation-as-a-distinct-termination-reason.md)

## Context

`DeploymentRegistry` and `InMemoryDeploymentRegistry` already model durable compare-and-set, an
idempotency ledger, leases, fencing tokens, and a generation counter. None of it is connected to
anything: `DefaultRavenrootApplication` says in as many words that they "remain wired to nothing
here", and the lifecycle a deployment actually has is two process-local maps. The gap is not that the
authority is missing. It is that the vocabulary the authority would need does not yet exist: there is
one intent level besides stopped, no way for a caller to say which lifecycle state its decision was
made against, no answer type for a command, and no boundary saying what a lifecycle port may reach.

Three things made the gap worse than a missing feature. The registry has a `generation`, a `fence`,
and a `revision`, and nothing wrote down what each is for, so any of the three could plausibly have
been made to carry the others' meaning. `DesiredKind`, `ObservedKind`, and `DeploymentState`
described the same lifecycle in three vocabularies that had already drifted — `DeploymentState` has
neither `PAUSED` nor `DRAINING`, `ObservedKind` had `DRAINING` but no `PAUSED` — with no record of
whether that was a decision or an oversight. And the rules governing leases, fences, and the order in
which an authority checks them are cited across the codebase as **ADR 0010 sections 4, 5, 7, 9, 12
and 13.2**, on `LeaseHandle`, `PendingWork`, `ExecutionStore`, `RevisionExpectation`,
`StoreCapability` and others — while `adr/` contains no ADR 0010 and the curation manifest does not
list one. Those citations resolve to nothing a reader can open.

## Decision

**D0 — The ownership rules this record depends on are stated here, not deferred to an unpublished
one.** This record does not cite ADR 0010. The gap is stated as a finding: the citations exist, the
record does not, and a decision that leaned on it would be leaning on nothing. Four rules are
therefore absorbed and restated as this record's own, in the form the deployment authority needs.

1. *A lease is time-bounded ownership evaluated against the authority's clock, never the holder's,
   and lazily rather than by a background reaper.* A holder cannot decide it still owns anything; it
   can only observe how much of its window the authority says remains.
2. *A fencing token orders successive holders and is presented on every write derived from ownership.*
   A write carrying a superseded token is refused whether or not the holder believes its lease is
   live.
3. *Check order is fencing, then replay, then expectation.* Fencing precedes every check that could
   yield a success, because answering a fenced holder's request out of an idempotency record tells it
   its work landed and lets it briefly believe it still owns the deployment — the split-brain belief
   the fence exists to destroy. The expectation is checked last, because a write that already
   happened necessarily moved the revision, so a legitimate replay's expectation is stale by
   construction and checking it first would fail every replay.
4. *Existence is a precondition of fencing, not a competitor to it.* A deployment that does not exist
   is answered `NotFound` whether or not a token was presented: a token is a claim about a lease, and
   a lease on a nonexistent deployment is not stale, it is impossible.

Because these are restated rather than invented, the existing `ExecutionStore` and `LeaseHandle`
behaviour is unchanged; what changes is that the deployment authority now has a published source for
the rules it follows.

**D1 — `(tenant, deployment)` carries three monotone axes, and they are disjoint.** `revision` is the
compare-and-set token and advances on every accepted mutation. `fence` is the ownership epoch and
advances only when ownership moves. `generation` is the lifecycle generation and advances only when
the lifecycle intent moves. **A takeover never moves the generation, and a command never moves the
fence.** The alternative — one counter — was rejected because each axis answers a question the others
cannot: a reconciler dying and being replaced would report as an operator changing their mind, and an
operator pressing pause would revoke the current owner's authority as a side effect. The revision
cannot stand in for either, because "something was written" is exactly what it means, and a lease
renewal by the current owner writes.

**D2 — Fencing is stratified, and there is no third fence.** The deployment fence governs lifecycle,
admission, and dispatch. Execution writes remain governed by `LeaseHandle.fencingToken`, per process
instance. `ExecutionStore` and its schema are untouched. A third token spanning both would have to be
presented by writers on both sides of a boundary neither can see across, and the first thing anyone
would do with it is reason about deployment ownership from an execution write.

*The reading of criterion 4, stated explicitly because the layering is what makes it true:* **a
takeover makes the old authority stale immediately, and makes execution stale at the expiry of its
own lease.** Hops already dispatched complete under a valid instance lease, which is itself a fence.
There is no window in which unfenced work runs: work in flight is not unprotected, it is protected by
the *other* token. An operator watching a takeover therefore sees authority move at once and in-flight
hops finish afterwards, and that is correct rather than a lag to be engineered away — cutting the
instance leases short at takeover would abandon work that is provably still owned in order to make
two independent clocks appear to be one.

**D3 — The name on the wire is `deploymentGeneration`.** Never `lifecycleGeneration`, which
`ProcessInventoryEntry` already publishes with a per-instance meaning: the count of authoritative
status transitions applied to one process instance. Two different axes on two different aggregates
sharing a name is a defect that surfaces only after someone has joined on it.

**D4 — A command's outcome is a sealed value, returned, never thrown.** Nine members:
`Accepted(commandId, fromGeneration, toGeneration)`, `Converged(commandId, generation, observed)`,
`Replayed(original)`, `IdempotencyConflict(key)`, `StaleGeneration(expected, current)`,
`Superseded(bySupersedingLevel, generation)`, `Refused(reason)` over the closed enum
`{IncompatibleState, MissingDisposition, ShuttingDown, CapacityExceeded, Tombstoned}`,
`Failed(classifiedCause)`, and `Terminal`. Eight of the nine are answers a correct caller must plan
for; throwing them would move the ordinary answers of this contract onto the path reserved for the
ones nobody anticipated, and would discard the exhaustiveness check that is the reason the hierarchy
is sealed. `Failed` joins them rather than remaining the single exception, because a caller already
handling eight answers gains nothing from a ninth arriving by a different mechanism.
`RegistryException` and `FailureReason` are unchanged and keep their own role: they report that the
*store* could not answer, which is a different event from the lifecycle answering "no".

`Terminal` and `Refused(Tombstoned)` are deliberately distinct and are the pair most likely to be
collapsed by a later reader. `Terminal` is the success of the command that removed the deployment;
`Refused(Tombstoned)` is the refusal of a *different* command that arrived after removal. Merging
them would give the operator who successfully removed a deployment and the operator whose command
arrived a second too late the identical reply.

**Three answers are deliberately outside the client-facing type.** `Unauthorized` is decided by the
surface that authenticated the caller, before a lifecycle command exists; answering it from inside
this hierarchy would let a client learn which deployments exist by reading which refusal it received.
`Fenced` and `LeaseLost` describe the relationship between an internal owner and the authority, which
a client neither holds nor can act on: a coordinator that loses its fence steps down and the
surviving owner answers, which is an internal reconciliation event rather than the fate of anyone's
command.

**D5 — Desired-state commands and barrier commands are two natures, not eight special cases.**
`Start`, `Pause`, `Resume`, `Drain`, `Stop` and `Undeploy` name a level and converge to it, so
applying one twice is applying it once. `Cancel` and `Restart` are **barriers**: they capture a
generation and leave `desired` untouched. `DesiredKind` accordingly grows to
`{RUNNING, PAUSED, DRAINED, STOPPED, REMOVED}`. Forcing a barrier to be a level would require a level
meaning "running, having discarded what was in flight", which is not a state anything converges to —
it is an event; and forcing a level to be a barrier would leave a deployment that is already running
with nothing to converge to. `Restart` in particular is not a `Stop` followed by a `Start`: those are
two decisions, and a coordinator dying between them leaves a deployment an operator asked to keep
running durably stopped.

Only `RUNNING` selects a graph version and an update strategy, because only `RUNNING` answers "which
version should be active". The version a paused or draining deployment still holds is reported as
evidence, on `Observation.activeVersion`, not restated as intent.

**An operator pause has a reason, and the reason lives on the command.** `DeploymentStatus` holds the
invariant that only `DEGRADED` and `FAILED` carry a cause. Widening it would tell every probe,
alert and dashboard that a cause may now appear on a healthy state, for the benefit of one case whose
reason is already recorded on the command that caused it. The invariant is left exactly as it is.

**D6 — A barrier is half-open.** Admission closes at `G`, work already admitted completes *under*
`G`, and everything arriving afterwards enters at `G + 1`. The comparison is exact equality and never
`>=`: work carrying `G` belongs to the closing side and work carrying `G + 1` to the opening side,
while an ordering test would place a much older `G - 2` on the closing side of a barrier it predates
entirely.

**D7 — Precedence is `Shutdown > Stop > Cancel > Drain > Pause`.** For the desired-state commands this
falls out of the restriction lattice directly; the ranks are spaced rather than consecutive so that
`Cancel`, which has no level of its own, can be ranked between `Stop` and `Drain` on the same
published scale instead of growing a second, parallel ordering for barriers. `Resume` is valid only
from `PAUSED`; from any other level it is `Refused(IncompatibleState)`. `Undeploy` requires an
explicit disposition for work still in flight, and a command arriving after a shutdown intent is
`Refused(ShuttingDown)`.

**Service shutdown is not in this hierarchy at all.** It is service-scoped and carries its own epoch,
on the model of `AgentAuthorityControl.epoch`. It is not a deployment generation: a generation is a
fact about one `(tenant, deployment)` aggregate, and a shutdown that advanced every deployment's
generation would rewrite the lifecycle history of deployments nobody touched.

**D8 — The unit of serialization is `(tenant, deployment)`, never the tenant.** In-process
single-flight sits *above* durable compare-and-set rather than replacing it: the single-flight
collapses concurrent callers within one process, and the CAS is what makes two processes safe.
Serializing per tenant would make one busy deployment's slow drain block every other deployment that
tenant owns, which is a scaling property nobody asked for and would be discovered under load.

**D9 — The runtime port is a new SPI, `DeploymentLifecycleTarget`; `GraphDeployment` is not widened.**
The port must not be able to reach `engine.drain()` or `engine.close()`. Widening `GraphDeployment`
would hand the lifecycle authority the engine's own shutdown, so an operator draining one deployment
could take down every deployment sharing that engine — precisely the blast radius ADR 0012's
supervision boundary exists to bound.

**D10 — `Command` carries `expectedGeneration` beside `expectedRevision`.** Criterion 1 requires both,
and they are not redundant. A lease renewal by the current owner advances the revision without
touching the generation, so a revision-only expectation refuses a lifecycle command for a reason
unrelated to the lifecycle. Conversely a caller replaying an accepted command sees a revision it
cannot predict, while the generation it decided against is exactly what it wants to pin. Mutations
that are not lifecycle decisions state no generation expectation, so that a write with no opinion
about the lifecycle does not acquire one by omission. A violated generation expectation is reported
as `Conflict`, the same as a violated revision: `FailureReason` is sealed and describes store faults,
and the client-facing `StaleGeneration` distinction is drawn one layer up from the record the
coordinator already holds. Adding a member to `FailureReason` would break every exhaustive switch on
it to say something the layer above can already say.

**D11 — Idempotency: a client-chosen key, scoped to `(tenant, deployment, commandKind)`, protecting a
canonical digest that includes `expectedGeneration`, under bounded retention.** The generation is
*inside* the digest rather than beside it, and that is the load-bearing part. A client that retries
"stop this deployment" under the same key after the lifecycle moved underneath it has made a new
decision. With the generation outside, the retry replays the first outcome and the client is told its
newer decision was applied when it was not; with the generation inside, the retry is an
`IdempotencyConflict` — a visible, actionable answer rather than a silent wrong one. Retention is
bounded, on the model of `ExecutionStore`'s idempotency purge: an unbounded ledger is a durable table
that only grows, and the first time anyone notices is when it is too large to migrate.

**D12 — A workload is the activation `(deployment, graphVersion, generation)`.** There is no sixth
kind of identifier. Every component already exists and is already durable; minting a new opaque id
for their conjunction would add a value that must be kept consistent with three others and can only
ever disagree with them.

**D13 — No published contract changes in this record.** `POST /v1/deployments/{id}/stop`,
`DELETE /v1/deployments/{id}`, the CLI verbs and `openapi.json` keep exactly today's semantics. The
external surface is a separate decision, and this record explicitly does not pre-empt it.
`DeploymentState` therefore does **not** grow `PAUSED` or `DRAINING`: every one of its members is
mapped onto `LocalDeploymentState` and published, so a new member is a change to an externally
published value space, which belongs to the change that owns that surface and can carry its migration
and release notes. The divergence between the three lifecycle vocabularies is closed as documentation
— a mapping table on `DeploymentState` — rather than by growing the published one.

**D14 — Registry operating limits have one typed programmatic authority.**
`DeploymentRegistryPolicy` owns command-ledger retention, maximum page size, maximum lease TTL and
clock-skew allowance. The SQLite and PostgreSQL adapters accept that value directly and retain their
source-compatible constructors by delegating to its defaults. These values are deliberately separate
from execution-store retention, inventory and lease settings: the two stores coordinate different
resources and changing one must not silently change the other. Ravenroot has no production registry
composition that can truthfully bind an environment variable in this release, so embedders supply
the typed policy and no unused deployment setting is advertised.

## Consequences

- **The contract is implemented in this change; only the external surface is not.**
  `DeploymentCoordinator` and `DeploymentReconciler` (`ai.ravenroot.core.deployment`) issue and resume
  `LifecycleCommand`s and produce `DeploymentCommandOutcome`s against `DeploymentLifecycleTarget`,
  which `DefaultRavenrootApplication.localDeploymentTargets()` publishes for this process's hosted
  deployments, and `SqliteDeploymentRegistry` backs the registry durably. No HTTP route, CLI verb, or
  UI surface produces or consumes either type — that surface is issue 109's, still open — so
  `POST /v1/deployments/{id}/stop` and `DELETE /v1/deployments/{id}` keep exactly their present
  behaviour, nothing yet connects an incoming request to the coordinator, and the admission fencing
  this record wires stays dormant until something outside this change drives the port.
- **D9's `DeploymentLifecycleTarget` is declared, implemented, and wired to a runtime in this
  change.** `DefaultGraphDeployment` implements it alongside `GraphDeployment`, and the constraint
  this record fixed on its shape holds: no method on it reaches `engine.drain()` or `engine.close()`,
  and `GraphDeployment` was not widened to carry it.
- **`DeploymentRegistry.Limits` gains a required component and is a source-breaking change for an
  out-of-tree adapter that constructs it.** `maxClockSkew` has no default, deliberately: a caller
  deciding when to stop working before it can be fenced needs the authority's real allowance, and a
  plausible constant supplied on the caller's behalf is the kind of value that is calibrated against
  once and then trusted everywhere. The in-tree reference adapter publishes zero, because its clock
  *is* the caller's clock, and publishing a comfortable non-zero value there would let a caller
  calibrate against a tolerance that does not exist and carry it to a durable adapter where it does.
- **Durable registry defaults now have one source and an explicit override seam.** Existing
  constructors continue to use seven-day command retention, page size 100, five-minute maximum lease
  TTL and five-second skew. An embedder that needs different values passes one
  `DeploymentRegistryPolicy`; the adapter publishes and enforces that exact policy.
- **`DeploymentRegistry.Command` gains a component, and the pre-existing constructors keep compiling
  and keep meaning what they meant.** They supply `GenerationExpectation.any()`. An out-of-tree caller
  that deconstructs the record positionally, or implements it by pattern match on the canonical
  shape, needs the new component; one that uses a constructor does not.
- **`DesiredKind` and `ObservedKind` each gain members, which is a breaking change to an exhaustive
  `switch` written outside this repository** — the identical cost `ExecutionLookup` paid in ADR 0037,
  stated rather than softened. A consumer that treats `PAUSED` as a form of held-and-resumable, and
  `DRAINED` as drain-completed-but-not-released, is correct without reading further, and must not map
  either onto `STOPPED`, which would report a deployment that is still bound to its version as one
  that has released it.
- **Writing a barrier through `DeploymentRegistry.command` also clears the recorded failure.** That
  method treats a new generation as the explicit recovery boundary and sets `failure` to null, which
  is right for a `Restart` and questionable for a `Cancel`: cancelling in-flight work is not a
  statement that the last recorded failure did not happen. The behaviour is left exactly as it is here
  and flagged for the coordinator, which is the layer that knows which barrier it is applying. It is
  recorded rather than quietly fixed because changing it would change what an existing conformance
  test asserts about explicit recovery.
- **`ObservedKind.DRAINED` and `DesiredKind.DRAINED` both require the deployment to still name its
  active version**, which means a drain that has completed is not the same record as a stop that has
  completed. An operator surface that renders them identically will show a deployment holding a
  version it is no longer serving, which is accurate and will look like a bug to someone who expected
  drain to be a stop.
- **The conformance suite covers the axes, the levels, the eight-command matrix and the outcome
  invariants; it does not cover partition, slow shutdown, or restart under load.** All three need a
  durable adapter and something actually executing, and the suite names the gap rather than implying
  the rows exist.
- **ADR 0010 remains unpublished, and the citations to it elsewhere in the codebase still resolve to
  nothing.** This record absorbs the four rules it needed and claims no more than that. Every other
  citation — sections 2, 3, 6, 8, 10, 11 and 12, on batches, idempotency records, payloads, timers,
  capabilities and the failure taxonomy — is unaffected and still points at a record a reader cannot
  open. Publishing it, or absorbing the rest of it, is a separate piece of work.
- **`start` carries no principal, per project, and `DefaultGraphDeployment` answers for one only
  because a local `start(SecurityContext)` already ran.** `DeploymentLifecycleTarget.start(long,
  long)` is a port for effects, not an authorization boundary, so — unlike `GraphDeployment.start`,
  which requires a `SecurityContext` — it carries none. `DefaultGraphDeployment` bridges the two by
  retaining the identity its own most recent `start(SecurityContext)` captured, in the one place that
  assigns `lifecycleIdentity`. A process that hosts a deployment but has never started it locally in
  this process therefore has no such identity on hand, and an authority-driven start then fails the
  stage with an `IllegalStateException` instead of minting one on the caller's behalf. Closing that
  gap is issue 109's, together with the external surface that would have to supply the principal.
- **The crash-recovery conformance for criterion 2 demonstrates this against `MarkerLifecycleTarget`,
  a test double, using `start-1-g1`.** `DeploymentCrashRecoveryCrossProcessTest` shows the protocol
  resumes an interrupted `Start` exactly once across a simulated process crash — but `Start` is
  precisely the one command whose production implementation, `DefaultGraphDeployment`, cannot be
  resumed this way without the identity the previous point describes, and the test double's own
  implementation does not need one. The protocol is proven; the production path for `Start` is not,
  and choosing that command for the conformance test is what leaves the gap looking closed.
