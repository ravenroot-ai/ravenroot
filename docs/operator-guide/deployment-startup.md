# Deployment and startup

Establish a safe service boundary before admitting workflow traffic.

## Operator procedure

1. Select JAR, distribution archive, or container topology and allocate a durable data path.
2. Bind to loopback for a local deployment; configure authentication before any non-loopback bind.
3. Start the service, then require `/health`, `/ready`, `/v1/runtime`, and `/v1/node-types` to succeed.
4. Publish the UI and API through one origin and expose no internal adapter or store ports.

For the explicit trusted-local runner topology, use `./service.sh start --runner` (or
`restart --runner`) after preparing the approved local catalog, worker runtime and dedicated
quota-capable Linux Docker daemon. This is genuinely tokenless: the loopback UI is USER and the
supervised in-process worker uses a private WORKLOAD capability, not a generated bearer token.
Only exact host 127.0.0.1 publication is accepted. `./service.sh stop` drains and stops the worker's
active and retained Workspaces before storage closes. See
[local runner setup and recovery](governed-runners.md#tokenless-trusted-local-service).
Production OIDC and independently scaled remote worker/coordinator deployments are unchanged.

## Source receipt custody and restart

The [SOURCE entry contract](../reference/source-entry-contract.md) lists every built-in and bundled
source and its transport custody model.

Activating a deployment opens its inbound SOURCE listeners. An event accepted for a named SOURCE
starts at that exact enabled node in the pinned graph. The source emits the admitted input; its
ordinary listener handler does not run as graph work. A graph edge arriving at a SOURCE stops that
branch, including a loop back to the source that emitted the event.

With SQLite or PostgreSQL, a committed source receipt transfers custody of the original bounded JSON
input and authenticated business context to the execution store. The input and its scoped business
relationship commit together before the receipt returns. Live deferred dispatch and restart recovery
both use that immutable snapshot. Changing the caller's original map after receiving the receipt
cannot replace either the input or its established application identity.

Input remains retained through the original SOURCE start and completion, until the actual traversal
settles. Those boundaries preserve the original source visit, attempt and causal event identities.
Activation scans bounded scalar pages and loads input only for selected recovery work. It can resume
missing source successors without producer redelivery. Already-created real handler work keeps its
original identities and the existing descriptor-governed recovery and repeatability rules; source
recovery does not authorize repeating an ambiguous external effect.

This retained input is recovery state, separate from optional activity capture. Disabling activity
capture does not discard a committed receipt's custody input or expose it through history metadata.
The retained record has a finite deadline, bounded by business history retention. An expired entry
is settled as failed and unreachable before its input is retired. Explicit cancellation also settles
the business relationship and retires the input in the lifecycle transaction. Closed admission holds
recovery; reopening uses the current deployment generation and the same logical deployment/version.
Drain permits already-admitted work to finish. A missing legacy input produces an explicit unavailable
reconciliation result rather than a fabricated empty input.

Configure per-scope pending count and byte limits in the
[business history policy](../reference/configuration.md#business-history-and-correlated-join-limits).
A full limit refuses new custody admission. Recovery rejects corrupted retained records and cannot
substitute another tenant, deployment, graph version or source target.

## Authority

Only the operator chooses listeners, process identity, engine, storage, and admission. A graph or API caller cannot change these controls.

## Verification

Save probe output and the effective configuration fingerprint. Readiness must become unavailable during drain and recover only after a clean restart.

- [Contract](../reference/configuration.md)
- [Runbook](../troubleshooting/startup-readiness.md)
