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
had been accepted — without any surface reporting that a substitution had occurred. One case is
concrete and reachable: every recovery path rebuilds its runner under the standard submission policy,
so an execution admitted as structural test evidence would have come back invoking production
behaviors.

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
property true without validating third-party text and refusing an otherwise sound deployment.

Before initial dispatch and before every recovery, resume, restart after a hold and ownership
takeover, the manifest is read back, re-digested from its stored fields, and compared for equality
against what the runtime resolves now. Every dimension is compared exactly; there is no
"close enough" rule, because this contract has no basis for deciding which differences are harmless.
A package installed since acceptance that the execution never used is not a difference: the document
decides which behaviors run and the document is pinned exactly.

A missing, corrupt, digest-mismatched or incompatible manifest fails closed with a typed outcome and
a bounded diagnostic naming each differing dimension and both of its values. No similar graph,
package, artifact or policy is substituted. On the recovery loop that means the claimed work is
neither dispatched nor acknowledged: it stays claimable, which is that loop's existing fail-closed
answer.

Retention cannot remove a manifest whose execution still exists. Reachability is recomputed inside
the removal transaction rather than tracked by a counter, and removal is refused rather than answered
with silence.

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
- Three dependency classes are pinned less than completely, and the manifest's wording claims only
  what it records. A node package is pinned by id, version and SDK contract but not by content
  digest, so a package republished under an unchanged version is not detected. A program artifact's
  content digest is pinned transitively, because its source lives in the document, but its approval
  state is deliberately not pinned and stays a live check at redemption. There is no parser version
  and no planner version because neither exists in this build.
- The unknown-behavior stance is recorded from a probe that an integrator may override, and a value
  outside the three defined ones is recorded as `unknown` rather than verbatim. Two different
  unrecognised stances therefore compare equal.
- Verification adds one durable read per submission and one per recovery attempt.
- The comparison is intolerant by design, so an operator changing an execution limit, an engine
  capability or an installed package version will find retained work refusing to resume until that
  change is reverted or the work is abandoned deliberately.
