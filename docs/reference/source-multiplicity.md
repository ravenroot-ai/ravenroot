# First-party source multiplicity

Inbound sources belong to a deployment. Starting two deployments can therefore create two source
instances even when both name the same external profile or resource. The resource's protocol and
dispatch rules determine whether those instances may coexist and whether they compete, observe
independent copies, or must own distinct addresses.

## Configurable resource ownership

`amqp.consume` and `mail.imap.consume` expose `resourceMode`:

| Value | Process-local acquisition | External behavior |
|---|---|---|
| `shared` (default) | Several shared holders may use the same authorized tenant/profile/resource tuple. | AMQP consumers compete for queue deliveries. IMAP consumers poll independently and keep separate ingress/checkpoint state according to their consumer identities. |
| `exclusive` | One holder reserves the tuple. Shared and exclusive acquisitions both fail while it is held. | The restriction is a Ravenroot process-local guard; it does not request broker or mailbox exclusivity. |

Mixed shared/exclusive acquisition is deterministic in either startup order. Stopping one shared
source releases only its lease. Exclusive mode does not coordinate separate Ravenroot processes.

AMQP fan-out requires a distinct operator-predeclared and bound queue for each subscriber. Sharing
one queue retains RabbitMQ's competing-consumer semantics. For IMAP, `resourceMode` is separate from
`consumerId`: a distinct stable consumer ID owns an isolated durable cursor, while the same stable
consumer ID continues to require exclusive checkpoint ownership even when resource mode is shared.
Legacy empty consumer IDs keep their deployment-scoped checkpoint namespaces.

## Inventory and fixed rules

The first-party source descriptors use these ownership models:

| Source behaviors | Multiplicity rule | Rationale |
|---|---|---|
| `amqp.consume`, `mail.imap.consume` | Configurable `shared` or process-local `exclusive`; default `shared`. | The protocols can support independent live consumers on the same authorized resource, while some deployments need an explicit local singleton guard. |
| `kafka.consume` | Multiple source instances; broker group configuration determines competition and partition ownership. | Kafka owns group membership and fencing. Distinct delivery requires distinct operator profiles/groups. |
| `matrix.sync` | Multiple deployment-scoped cursors subject to provider and configured capacity. | Cursor identity already includes its deployment/source scope. |
| `websocket.receive` | Multiple sessions subject to configured admission capacity. | Capacity limits concurrent connections without claiming unique resource ownership. |
| `timer`, `crontab` | Deployment/source-scoped durable schedule state. | These sources do not claim an external transport resource. |
| `discord.interactions`, `github-events-source`, `mattermost.outgoing-webhook`, `openapi.receive`, `openapi.request-reply`, `slack.commands`, `slack.events`, `teams.outgoing-webhook` | One managed HTTP route owner for each exact route. | An inbound path must dispatch to one handler. Sharing it would require an explicit routing or fan-out contract. |

Route ownership remains mandatory because Ravenroot cannot infer a target from payloads or headers.
Adding multiplicity there would change routing and acknowledgement behavior rather than only relaxing
a process-local resource guard.

## Capacity and lifecycle

Shared mode does not raise global, tenant, profile, connection, resolver, in-flight, or buffer limits.
Every source must still pass the same authority, credential, capacity, durable-ingress, and readiness
checks. Its worker, session generation, acknowledgement behavior, reconnect lifecycle, and cleanup
remain independent. Stable failure codes identify a conflicting exclusive resource lease; capacity
refusals retain their existing classifications.

## Durable admission deadlines

A durable source offer has one ten-second admission deadline starting when the source wrapper is
called. Source-lifecycle and deployment-admission lock acquisition, inbox custody, graph-definition
and execution-manifest persistence, execution creation, lease claim, and the pinned policy read all
spend from that same deadline; a later step does not receive a fresh ten seconds. If any required
operation has not resolved when the deadline expires, Ravenroot returns `Ambiguous` and the source
must keep the external event unacknowledged and re-offer the same idempotency key. Timeout never proves
that a write did not commit.

Redelivery uses the same deterministic event, process, and traversal identities. It repairs an
inbox-only or late-committed acceptance and cannot create a second execution. A deployment stop,
deactivation, or barrier that closes the admission while persistence is outstanding prevents late
dispatch; recorder and source-checkpoint ownership cleanup begins without adding another blocking
wait to the expired deadline. PostgreSQL keeps a stable consumer's advisory ownership until every
write admitted before close has settled, so a successor cannot observe uncertain custody as released.
Opening and closing that ownership performs store and JDBC work outside the source lifecycle lock.
A late open after timeout or source retirement is closed without publishing a usable handle; retirement
revokes handles first and gives asynchronous ownership cleanup one bounded wait without blocking other
admissions or retaining the lifecycle lock.

Hosted execution-policy reads happen before the deployment dispatch lock. A failed or late policy
read releases the execution recorder, and a lifecycle barrier may complete while that read is pending.
Admission, traversal recording, and AMQP acknowledgement generation fencing use locks that allow a
waiting Java 21 virtual thread to release its scheduler carrier. A blocked durable write or broker
acknowledgement therefore cannot strand another source when scheduler parallelism is one.
Durable-source runner dispatch begins on a virtual thread after the execution, graph definition,
manifest and lease are durably accepted. A synchronous first-node journal write therefore cannot
retain the deployment dispatch lock; a barrier still sees the admitted traversal and prevents a late
write from dispatching the cancelled node effect. Volatile and request/reply ingress retain their
synchronous dispatch ordering.
