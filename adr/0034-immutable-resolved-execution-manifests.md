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
takeover, the manifest is read back, re-digested from its stored fields, and compared for equality
against what the runtime resolves now. Every dimension is compared exactly; there is no
"close enough" rule, because this contract has no basis for deciding which differences are harmless.
A package installed since acceptance that the execution never used is not a difference: the document
decides which behaviors run and the document is pinned exactly.

A missing, corrupt, digest-mismatched or incompatible manifest fails closed with a typed outcome and
a bounded diagnostic naming each differing dimension and both of its values. No similar graph,
package, artifact or policy is substituted. On the recovery loop that means the claimed work is
neither dispatched nor acknowledged: it stays claimable, which is that loop's existing fail-closed
answer, and the refusal is logged because a boolean `supports` cannot carry the reason and the
condition never resolves on its own.

**The two reachable defects above are detected and refused, not repaired.** The pinned recovery
runner still hard-codes the standard policy and still takes today's limits; what changes is that a
composition holding a manifest refuses to reach it rather than running it. That distinction is
load-bearing in both directions. A deployment that composes a manifest store gets a refusal instead
of a silent substitution. A deployment that composes none — which is every constructor except the
widest one, and therefore the default an embedder gets — verifies nothing, and both substitutions
still happen exactly as before. Repairing them is separate work with its own decisions to make: a
resumed pass-through traversal has no defined meaning today, and restoring limits would require the
manifest to carry their values rather than a digest, which is a different disclosure decision than
the one taken below.

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
- The two reachable recovery defects remain live wherever no manifest store is composed, which is
  every composition that does not opt in. The manifest makes them refusable, not absent.
- Three dependency classes are pinned less than completely, and the manifest's wording claims only
  what it records. A node package is pinned by id and by a digest of its declared version and SDK
  contract, never by a content digest, so a package republished under an unchanged version is not
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
- The comparison is intolerant by design, so an operator changing an execution limit, an engine
  capability or an installed package version will find retained work refusing to resume until that
  change is reverted or the work is abandoned deliberately.
