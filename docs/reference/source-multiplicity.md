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
