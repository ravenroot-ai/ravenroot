# First-party bundle node examples

This page supplies a complete admission-ready GraphML file for every optional first-party node at the
[documented baseline](coverage-inventory.md). Each linked bundle page supplies the exact property
types, defaults, limits, profile schema, payload contract, outcomes, failures, effects, and recovery
rules. Use both pages: these examples deliberately set only required node properties.

Each linked file is a whole graph with one `start`, one `action`, one `end`, one `error`, unique
IDs, every descriptor-declared outcome edge, and a separate failure route. Download one file rather
than combining alternatives. Validate and inspect it before Run:

```sh
bin/ravenroot validate downloaded-example.graphml
bin/ravenroot inspect downloaded-example.graphml
bin/ravenroot run downloaded-example.graphml '<payload from the bundle reference>'
```

Validation and Test mode perform no node effects. Run needs the named bundle enabled, every
`replace-with-operator-profile` value replaced by a matching profile, and every service grant
specified by the bundle reference. Inbound source nodes activate with a deployment and receive
provider events; ordinary CLI Run does not simulate provider ingress.

## AI

See the [`ai` reference](bundles/ai.md).

Complete admission-ready graphs:

- [`llm-prompt`](../examples/nodes/llm-prompt.graphml)
- [`agent`](../examples/nodes/agent.graphml)

With payload `example`, each emits its provider-dependent bounded answer on `continue`. Test mode
makes no model or tool call.

## AMQP 0-9-1

See the [`amqp091` reference](bundles/amqp091.md).

Complete admission-ready graphs:

- [`amqp.publish`](../examples/nodes/amqp.publish.graphml)
- [`amqp.consume`](../examples/nodes/amqp.consume.graphml)

Run publish with `{"version":"amqp.publish.v1","bodyText":"hello"}`; a broker acknowledgement
emits `CONFIRMED`. Deploying consume creates the configured consumer; durable disposition of its
bounded event controls broker acknowledgement.

## Discord

See the [`discord` reference](bundles/discord.md).

Complete admission-ready graphs:

- [`discord.interactions`](../examples/nodes/discord.interactions.graphml)
- [`discord.send`](../examples/nodes/discord.send.graphml)

Deployment activates interactions and a valid relayed command emits `discord.interaction.v1`. Run
send with the reference's `discord.message.v1` payload; provider acceptance emits its content-free
result on `continue`.

## Filesystem

See the [`filesystem` reference](bundles/filesystem.md).

Complete admission-ready graphs:

- [`filesystem.read`](../examples/nodes/filesystem.read.graphml)
- [`filesystem.write`](../examples/nodes/filesystem.write.graphml)

Read with `{"version":"filesystem.read.v1"}` to obtain the bounded document. Write with
`{"version":"filesystem.write.v1","text":"hello"}`; `create-new` emits `CREATED` when the
target is absent. The host must provide the secure directory primitive in the reference.

## Git workspace

See the [`git-workspace` reference](bundles/git-workspace.md).

Complete admission-ready graphs:

- [`git-workspace`](../examples/nodes/git-workspace.graphml)

Run the reference's `git-workspace.v1` `provision` payload with a reachable base revision. A
successful compare-and-swap emits `git-workspace.result.v1`; a ref race follows `conflict`.

## GitHub

See the [`github` reference](bundles/github.md).

Complete admission-ready graphs:

- [`github-events-source`](../examples/nodes/github-events-source.graphml)
- [`project-transition`](../examples/nodes/project-transition.graphml)
- [`github-app-review`](../examples/nodes/github-app-review.graphml)
- [`github-workflow-watch`](../examples/nodes/github-workflow-watch.graphml)
- [`release-prepare`](../examples/nodes/release-prepare.graphml)

Deployment activates the verified webhook source. Run each action with its versioned reference
payload. Successful effects emit bounded results, workflow watch follows its terminal workflow
outcome, and release preparation reports the exact prepared revision or classified refusal.

## JDBC

See the [`jdbc` reference](bundles/jdbc.md).

Complete admission-ready graphs:

- [`jdbc.query`](../examples/nodes/jdbc.query.graphml)
- [`jdbc.insert`](../examples/nodes/jdbc.insert.graphml)

Using the reference profile, query with
`{"contract":"jdbc.parameters.v1","parameters":{"id":42}}` to obtain the bounded row set;
insert with `{"contract":"jdbc.parameters.v1","parameters":{"name":"Ada"}}` to obtain the
bounded update count and allowed generated keys. SQL stays entirely in the operator profile. Test
opens no connection.

## Kafka

See the [`kafka` reference](bundles/kafka.md).

Complete admission-ready graphs:

- [`kafka.produce`](../examples/nodes/kafka.produce.graphml)
- [`kafka.consume`](../examples/nodes/kafka.consume.graphml)

Produce with `{"version":"kafka.produce.v1","valueText":"hello"}`; a broker acknowledgement
emits `ACKNOWLEDGED`. Deployment activates consume; a record emits `kafka.record.v1` and only the
ordered durable safe frontier advances the offset.

## Mail

See the [`mail` reference](bundles/mail.md).

Complete admission-ready graphs:

- [`mail.send`](../examples/nodes/mail.send.graphml)
- [`mail.imap.query`](../examples/nodes/mail.imap.query.graphml)
- [`mail.imap.consume`](../examples/nodes/mail.imap.consume.graphml)
- [`mail.imap.move`](../examples/nodes/mail.imap.move.graphml)
- [`mail.imap.delete`](../examples/nodes/mail.imap.delete.graphml)

Use the exact send, query, move, and delete payloads from the reference. Successful actions emit
bounded results on `continue`. Deployment activates consume, whose durable custody controls progress.

## Matrix

See the [`matrix` reference](bundles/matrix.md).

Complete admission-ready graphs:

- [`matrix.send`](../examples/nodes/matrix.send.graphml)
- [`matrix.sync`](../examples/nodes/matrix.sync.graphml)

Run send with the reference's `matrix.message.v1` payload; provider acceptance emits its
content-free result. Deployment activates sync, which emits bounded `matrix.event.v1` events only
after durable admission and advances its cursor without skipping an uncommitted event.

## Mattermost

See the [`mattermost` reference](bundles/mattermost.md).

Complete admission-ready graphs:

- [`mattermost.send`](../examples/nodes/mattermost.send.graphml)
- [`mattermost.outgoing-webhook`](../examples/nodes/mattermost.outgoing-webhook.graphml)

Run send with the reference's `mattermost.message.v1` payload; provider acceptance emits its
content-free result. Deployment activates the outgoing-webhook route, which authenticates and
narrows the callback before emitting `mattermost.outgoing-webhook.v1` after durable admission.

## Object storage

See the [`object-storage` reference](bundles/object-storage.md).

Complete admission-ready graphs:

- [`object.get`](../examples/nodes/object.get.graphml)
- [`object.put`](../examples/nodes/object.put.graphml)
- [`object.list`](../examples/nodes/object.list.graphml)
- [`object.delete`](../examples/nodes/object.delete.graphml)

Use the versioned GET, PUT, LIST, or DELETE payload from the reference. Success emits the bounded
protocol result. PUT may be ambiguous after request handoff and requires reconciliation.

## OCR

See the [`ocr` reference](bundles/ocr.md).

Complete admission-ready graphs:

- [`ocr.extract`](../examples/nodes/ocr.extract.graphml)

Run with the strict `ocr.extract.v1` Base64 image payload. Successful supervised execution emits an
`EXTRACTED` result with bounded text and safe image evidence. Test starts no process.

## OpenAPI client

See the [`openapi-client` reference](bundles/openapi-client.md).

Complete admission-ready graphs:

- [`openapi.call`](../examples/nodes/openapi.call.graphml)

Run with `{"path":{"orderId":"42"}}` for a matching installed operation schema. A 2xx JSON
response emits `openapi.call.result.v1`; the managed service enforces egress and credentials.

## OpenAPI server

See the [`openapi-server` reference](bundles/openapi-server.md).

Complete admission-ready graphs:

- [`openapi.receive`](../examples/nodes/openapi.receive.graphml)
- [`openapi.request-reply`](../examples/nodes/openapi.request-reply.graphml)

Deployment activates the managed routes. A valid receive request returns HTTP 202 after durable
acceptance. Request-reply waits for the bounded graph response and follows `responded`.

## Slack

See the [`slack` reference](bundles/slack.md).

Complete admission-ready graphs:

- [`slack.events`](../examples/nodes/slack.events.graphml)
- [`slack.commands`](../examples/nodes/slack.commands.graphml)
- [`slack.post-message`](../examples/nodes/slack.post-message.graphml)

Deployment activates event and command routes. Verified ingress emits a bounded payload after
durable acceptance. Run post-message with `slack.message.v1`; provider acceptance emits its
content-free result.

## Restricted SpEL

See the [`spel` reference](bundles/spel.md).

Complete admission-ready graphs:

- [`spel.transform`](../examples/nodes/spel.transform.graphml)
- [`spel.decision`](../examples/nodes/spel.decision.graphml)

With `{"customer":{"name":"Ada"}}`, transform emits `Ada`; decision preserves the payload and
follows `true`. Both are deterministic and side-effect free.

## Microsoft Teams

See the [`teams` reference](bundles/teams.md).

Complete admission-ready graphs:

- [`teams.send`](../examples/nodes/teams.send.graphml)
- [`teams.outgoing-webhook`](../examples/nodes/teams.outgoing-webhook.graphml)

Run send with the reference's `teams.message.v1` payload; provider acceptance emits its
content-free result. Deployment activates the outgoing-webhook route, which authenticates and
narrows the callback before emitting `teams.outgoing-message.v1` after durable admission.

## Telegram

See the [`telegram` reference](bundles/telegram.md).

Complete admission-ready graphs:

- [`telegram.send`](../examples/nodes/telegram.send.graphml)
- [`telegram.answer.callback`](../examples/nodes/telegram.answer.callback.graphml)
- [`telegram.edit.message`](../examples/nodes/telegram.edit.message.graphml)
- [`telegram.delete.message`](../examples/nodes/telegram.delete.message.graphml)

Run each with its matching versioned payload. Provider success emits the bounded action result;
ambiguous results after handoff require reconciliation under the caller's idempotency policy.

## WebSocket

See the [`websocket` reference](bundles/websocket.md).

Complete admission-ready graphs:

- [`websocket.send`](../examples/nodes/websocket.send.graphml)
- [`websocket.receive`](../examples/nodes/websocket.receive.graphml)

Send `{"version":"websocket.send.v1","encoding":"text","data":"hello"}`; successful handoff
emits the bounded result. Deployment activates receive, which emits bounded text or Base64 events.
