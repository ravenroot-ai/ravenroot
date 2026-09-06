# ADR 0039: Unreachable executions are reconciled, not waited out

- Status: Accepted
- Date: 2026-09-06
- Supersedes: A traversal proven unable to reach any outcome staying open for the life of the
  process, holding its admission capacity, with the condition observable only as a diagnostic
- Superseded by: None
- Public references: [Executions, outcomes, and events](../docs/reference/execution-events.md),
  [HTTP API and CLI](../docs/reference/api-cli.md),
  [Durable execution results](../docs/architecture/execution-results.md),
  [Execution and actor model](../docs/architecture/execution-actor-model.md),
  [ADR 0012](0012-engine-supervision-cancellation-and-drain.md),
  [ADR 0022](0022-ambiguous-work-is-parked.md),
  [ADR 0028](0028-iteration-correlated-fan-in.md),
  [ADR 0035](0035-cancellation-as-a-distinct-termination-reason.md)

## Context

A traversal can reach a state in which nothing it holds can ever settle it: no node is running on its
behalf, no join deadline is armed, and a branch is parked at a fan-in waiting for an arrival that
will never come — most often because the call that would have delivered it neither succeeded nor
failed. The runtime can already identify that state from positive evidence rather than from elapsed
time, and does so without misreporting the ordinary node-to-node handoff, in which the previous
node's worker instance is released before the successor's is registered.

Identifying it changed nothing. The traversal stayed registered on its runner, its admission capacity
stayed held, its result never settled, and no read or event ever said why. A graph that strands one
branch per run therefore consumed a tenant's capacity one execution at a time, reporting nothing
while it did, and the only recovery was to restart the process.

Cooperative cancellation cannot recover this state, and that is not a gap in cancellation. A stop
refuses the traversal's next hop; an unreachable traversal has no next hop to refuse. The two
mechanisms address different conditions and neither substitutes for the other.

## Decision

**An unreachable traversal is ended by an explicit reconciliation, through the same release every
traversal already performs, and its termination is recorded with a reason of its own.**

Reconciliation strands the parked branches with a verdict. The resulting failure propagates into the
traversal's own stage, and the terminal handler that every traversal ends through does the rest: it
records the termination, publishes the event, and releases the admission capacity, the dispatch gate
and the actor instances. Nothing in the reconciliation releases capacity itself. One release path
means one release, so "capacity is returned exactly once" is a property of the code's shape rather
than of a check that has to be remembered — and reconciliation racing a cancellation, a shutdown or a
late completion cannot produce a second ending, because the release is once-only and hands every
later caller the first one's result.

**`ExecutionTerminationReason` gains `UNREACHABLE`**, exactly the growth [ADR
0035](0035-cancellation-as-a-distinct-termination-reason.md) describes: a member is added for a
termination whose provenance a reader would otherwise get wrong. The status stays `FAILED` and the
event stays `EXECUTION_FAILED`, and both are correct here in a way they are not for a cancellation.
Nobody asked for this outcome and the run did not do what it was submitted to do, so it is an
incident and belongs in the failure series an operator pages on. What the reason adds is which
incident: not a behaviour that raised, but a traversal left with nothing able to settle it, which
sends an operator to look at what stopped delivering rather than for a failing node.

**Where both a cancellation and an unreachable verdict apply to one traversal, the cancellation is
reported.** One classifier walks the cause chain to the end and then decides, rather than answering
with whichever verdict happened to wrap the other; a reason that depended on that ordering would be a
race rather than a classification. Cancellation is the stronger statement about provenance, and a
reader told "this could never settle" about a deliberate stop would go looking for a defect that does
not exist.

**A traversal that was already asked to stop is ended as a cancellation, on both paths that can end
one.** A cooperative stop refuses the traversal's next hop, and an unreachable traversal has none to
refuse — the "stalled" case [ADR 0012](0012-engine-supervision-cancellation-and-drain.md)'s
cooperative model hands to the forced teardown. Such a traversal was already being ended, by that
teardown; what it was not doing was ending as a cancellation. It was stranded with a bare join
failure carrying no cause, and so recorded as an unqualified `FAILED` — an operator's deliberate stop
reported back to them as an incident, on precisely the path the application composes to make
cancellation reach a stalled execution. The verdict is therefore supplied by the forced teardown as
well as by reconciliation, because the teardown is the half an operator's cancel actually reaches.
The cancellation names a join the traversal is parked at when there is one, which is the truthful
reading of "the first hop that did not run", and names no node when there is none rather than
inventing one.

**A shutdown that catches a traversal nobody asked to stop is not a cancellation.** The verdict is
conditional on a stop having been requested, because labelling every traversal a shutdown happens to
reach as cancelled would fabricate an operator action nobody took — the same defect in the opposite
direction.

**Reconciliation is caller-invoked and is not a background sweep.** A sweep needs a period, and a
period over this condition is an elapsed-time guess — which is precisely what the criterion exists to
avoid. The whole value of the condition is that it rests on positive evidence, and a deadline nobody
armed would put the guess back one layer up. This follows the boundary the store ports already draw
for retention: a caller decides when to ask, and the runtime answers without a clock.

**The invocation surface is the application API, and no transport surface is added here.** There is
no HTTP route and no CLI verb, so this release makes the recovery reachable from an embedding
supervisor and not from an operator command. That is a scoping decision recorded as a limitation
rather than presented as a design: the decision above says who *may* ask, and until a transport
exposes it the set of callers who *can* is smaller than that.

**A reconciled traversal does not wait for its join records to be discarded before it ends.** For
every other termination the discard is sequenced into the traversal's own ending, so a caller seeing
it complete may assert the records are gone. That guarantee cannot hold here: the store call that
will never return is the very thing the criterion found, so sequencing recovery behind it would make
recovery conditional on the dependency whose failure created the condition, and the traversal would
stay open exactly as before. The discard stays armed and still runs if the store answers later.

## Consequences

- **A traversal proven unable to reach an outcome now ends with `status == FAILED` and
  `terminationReason == UNREACHABLE`**, readable from the result surface and from the durable process
  and traversal inventories, and its capacity is available to a new execution.
- **The termination is a fault and is counted as one.** Unlike a cancellation it does not leave the
  failure series, and no new terminal event type is introduced: consumers that classify by event type
  need no change, and the distinction is carried by the reason and by the failure classification on
  the event.
- **A reader that switches over `ExecutionTerminationReason` must tolerate a value it does not know**
  and treat it as "not a cancellation". This is the tolerance ADR 0035 already required of every such
  reader; this is the first value to exercise it. A binary predating this change replays a durable
  `UNREACHABLE` name as corrupted rather than as an absent reason, so the value is never silently
  misread as an ordinary termination.
- **Nothing is reconciled that has not been proven unreachable.** The recovery consults the existing
  criterion and adds no condition of its own, so a healthy handoff — no worker running, no deadline
  armed, and no branch parked — is never ended by it. A traversal that settles between the criterion
  reading it and the recovery acting is left alone; it reached an outcome, which is the result the
  recovery exists to produce.
- **Cancelling a stuck execution is now recorded as a cancellation.** It already ended — the forced
  teardown the application composes behind the stop saw to that — but it ended with no termination
  reason at all, which under [ADR 0035](0035-cancellation-as-a-distinct-termination-reason.md)'s own
  reading rule is an ordinary failure. It now carries `terminationReason == CANCELLED`, so an
  operator's deliberate act is not reported back to them as an incident.
- **Effects issued before the traversal became unreachable are not undone and cannot be**, the same
  concession [ADR 0012](0012-engine-supervision-cancellation-and-drain.md) states for cancellation.
  Reconciliation records what the traversal became; it does not roll back what it did.
- **An operator has no command for this yet.** The recovery is exposed on the application API alone;
  a transport surface is deferred, so a deployment that does not embed its own supervisor cannot
  invoke it in this release.
- **A join record belonging to a store that never answers may outlive the traversal it belonged to.**
  It is reclaimed if the store recovers, and otherwise it is recovery's to reclaim. The alternative —
  holding the traversal open until the record is provably gone — is the defect this decision removes.
