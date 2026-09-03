# ADR 0031: Durable canonical graph definitions for accepted executions

- Status: Accepted
- Date: 2026-09-03
- Supersedes: Accepting an execution against a graph whose bytes nothing retains
- Superseded by: None
- Public references: [ADR 0008](0008-graph-definition-versioning-and-lifecycle.md), [ADR 0014](0014-local-execution-store-operational-surface.md), [ADR 0018](0018-credential-boundary-and-secret-bindings.md), [ADR 0023](0023-remote-deployment-control-plane.md), [persistence and lifecycle](../docs/operator-guide/persistence-lifecycle.md), [bundle format and commands](../docs/reference/backup-recovery.md)

## Context

An accepted execution records the identity of the graph it runs, but durable execution state retains
no graph content. After a restart or an ownership transfer, a recovering runtime holds an identifier
and nothing that identifier resolves to, so it cannot reconstruct the graph without asking the caller
to submit the document again. A hash cannot supply the bytes it names.

ADR 0008 already separates a graph's logical identity, its revision identity derived from a canonical
semantic form, and its lifecycle. It does not make any representation of the graph durable, and it
deliberately keeps transport details out of *revision* identity so that two documents describing the
same graph are recognised as the same revision.

## Decision

Ravenroot stores immutable graph definitions durably, in a tenant-scoped store that is separate from
the execution store and addressed by content.

**Canonical executable form is the exact accepted document.** The canonical executable representation
of a graph definition is the byte sequence that passed GraphML ingest validation. Nothing
re-serialises, reorders or renormalises it. This matches the behaviour the product already has, in
which an imported document is written back out unchanged, and it is the only representation that lets
a recovering runtime replay the graph it was accepted against rather than one that means the same
thing.

**Content identity is the digest of those bytes.** A definition's address is the lowercase
hexadecimal SHA-256 digest of the canonical document. The server computes it and never accepts it
from a caller. This is byte-identical to the graph version reference an accepted execution already
records, so an execution pinned before durable definitions existed addresses a stored definition with
no data migration and no second identity to keep in step.

**Content identity and revision identity answer different questions.** ADR 0008's rule that transport
details stay out of identity governs *revision* identity: whether two documents are the same version
of a graph. Content identity answers a narrower question — which exact bytes an execution must
replay — and for that question the transport form is the subject, not an irrelevant detail. The two
coexist: a semantic canonical form continues to decide revision equality, while the content address
decides which document is fetched. The observable consequence is stated plainly: two documents
differing only in whitespace are one revision and two stored definitions.

**Logical version binding is separate from content.** An immutable graph version binds to one content
address. Binding the same document under a second version is legal and stores no second copy; binding
one version to different content is refused, because an execution pinned to that version would
otherwise silently change what it replays.

**A definition is durable before the execution that names it.** Acceptance may never succeed while
the definition it pins is absent. The required ordering is: commit the definition, then commit the
acceptance. The ordering alone is sufficient and no shared transaction is required, because the two
failure states are not symmetric — a definition committed for an acceptance that then fails is an
unreferenced blob that retention reclaims, while an acceptance committed for a definition that was
never written is an execution that can never be recovered. Only one ordering can reach the second
state. A content-addressed write is idempotent, which is what makes the surviving state harmless
rather than merely rare.

**Every read is verified.** Stored bytes are hashed and compared to the address they are filed under
before they are returned. There is no unverified read and no verification flag. Missing, corrupt,
oversized, cross-tenant and digest-mismatched definitions fail closed with stable typed outcomes and
bounded diagnostics that never carry document content.

**Runtimes may cache; the durable store is the authority.** A runtime may hold a verified definition
for as long as it holds the execution. After a restart or an ownership transfer the durable store is
the authority, because a cache cannot outlive the process that holds it and a new owner has none.

**Deduplication never crosses a tenant.** Two tenants submitting byte-identical documents hold two
definitions. One shared copy would let one tenant learn that another holds a given document, and
would give one tenant's retention decision authority over another tenant's recoverability.

**Retention is reachability, recomputed, never a stored count.** A definition is removable only when
recomputed reachability, evaluated inside the removal transaction, shows that no retained durable
execution reaches it. No reference counter is maintained, because a counter is a second copy of a
fact the referencing rows already hold and every crash between the two writes leaves them
disagreeing. Reclamation is an operation a caller invokes, not a background sweep.

**Definitions carry graph content and non-secret references only.** Credentials and secret values
remain outside graph definitions under ADR 0018, and the store adds no exception to that boundary and
no encryption of its own.

**Relationship to the remote deployment control plane.** ADR 0023 defines an accepted, unimplemented
deployment-control contract in which a deployment version carries canonical graph bytes and their
digest. That contract is not superseded and is not duplicated: when it is implemented, its version
content is persisted *through* this store rather than through a second definition store of its own,
so one document has one durable home and one address.

## Consequences

- A server can accept an execution, lose every in-memory graph object, restart, and return the exact
  canonical document without a caller re-upload. This unblocks resuming recovered work, which was
  previously impossible for a stated reason rather than an accidental one.
- Acceptance gains a durable write that can refuse it. A submission whose definition cannot be
  committed is rejected instead of becoming an unrecoverable execution.
- Storage grows with distinct documents. Because identity is byte-derived, a whitespace-only edit
  produces a second stored definition, and because reclamation is caller-invoked, an operator who
  never invokes it accumulates definitions indefinitely.
- Definitions live in the same durable store as the executions that pin them, so one consistent
  backup snapshot captures both and one schema version describes both.
- The stated retention guarantee covers retained durable executions. Deployments, continuations,
  execution results and audit obligations are not durable in this build, so no implementation can
  compute reachability from them; the guarantee will widen as those reference classes become durable
  and must not be read more broadly until they do.
