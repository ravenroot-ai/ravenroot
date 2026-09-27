# Embed, privacy, and audit

The embedded viewer exposes only a registered projection whose privacy and takedown facts an accountable operator has attested.

## Controls

- Record deployment, provenance, classification, retention, DSR suppression, takedown, and EEA residence gates before registration.
- Bind launch and exchange to exact origin, host, source, registration revision, scope, expiry, and one-time use.
- Exclude mutation, global lifecycle control, credentials, raw operator APIs, and non-projected graph data from the viewer.
- For live sources, bind observation to deployment id, graph version, incarnation, session proof key,
  and an opaque process-local cursor. Emit only allowlisted lifecycle and execution fields.
- For a selectable v2 source, filter durable process inventory server-side by tenant, deployment, and
  graph version and the durably recorded admitting incarnation, then bind observation to one
  authorized process instance. Never serialize workload,
  lease, worker, correlation, payload, credential, or tenant-wide discovery metadata.
- Treat `showStartExecution` as presentation only. Require separate embed-execute authority, exact
  deployment/version/incarnation reconciliation, and a bounded idempotency identity before the server
  executes immutable deployed content. No GraphML or operator token reaches the browser.
- Audit registration, acknowledgement, session issue, projection access, observation access, expiry,
  and revocation without logging token, proof, cursor, or secret values.
- Dynamic discovery is a distinct workload-authorized read and returns only READY, tenant-owned,
  process-local deployment coordinates. Dynamic grants are fixed read-only, short-lived, bounded,
  revocable, stored only by digest, and pinned to the selected incarnation before browser exchange.

The parent page receives protocol health messages but never the bearer, proof key, projection, cursor,
or observation events. The frame uses an explicit credentialed request with an authorization header;
it does not use ambient cookies. A gap, authority change, undeploy, or source replacement clears
observed state and terminates the attachment so stale runtime decoration cannot be mistaken for live
state.

A selected-run replay-gap recovery reads only that process's durable journal. The viewer receives an
atomic runtime reset followed by allowlisted node/edge lifecycle facts; correlation, payload, worker,
lease, workload, and sibling-process records remain server-side. Each bounded journal page reports
the atomically observed retained floor and next sequence. A fully compacted stream, a discontinuity,
or compaction between pages fails closed instead of presenting a partial replay as authoritative.

The run list is reconciled authoritatively. Revocation, authorization loss, undeploy, source
replacement, or disappearance clears the selection and runtime decoration. Completion may remain as
a bounded recent terminal row. A visibility check, cached row, or parent-page event never grants
execution or observation authority.

## Residual responsibility

The seven gates are operator attestations, not facts computed from the graph. Ravenroot can enforce their recorded result and revocation, while the organization owns their truth and timely reevaluation.

## Application

- [Definitions and limits](../reference/embed-extension-contracts.md)
- [Operator procedure or recovery](../operator-guide/embed-operations.md)
