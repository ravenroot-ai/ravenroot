# First-party bundle node examples

This page supplies a minimal graph fragment for every optional first-party node at the
[documented baseline](coverage-inventory.md). Each linked bundle page supplies the exact property
types, defaults, limits, profile schema, payload contract, outcomes, failures, effects, and recovery
rules. Use both pages: these examples deliberately set only required node properties.

Start with the complete graph in [Build your first graph](../get-started/first-graph.md). Replace its
`greet` node with one fragment below, declare every property in the fragment as a GraphML `<key>`
with `for="node"` and `attr.type="string"`, and retain the `continue` and unlabelled failure edges.
Add other outcomes named by the bundle reference, then validate, inspect, and run:

```sh
bin/ravenroot validate bundle-example.graphml
bin/ravenroot inspect bundle-example.graphml
bin/ravenroot run bundle-example.graphml '<payload from the bundle reference>'
```

Validation and Test mode perform no node effects. Run needs the named bundle enabled, a matching
operator profile, and every service grant described on its bundle page. Replace the example profile
names below with installed profiles. Inbound source nodes activate with a deployment and receive
provider events. Running a source anchor through the ordinary CLI only preserves input on
`continue`; it does not simulate provider ingress.

## AI

See the [`ai` reference](bundles/ai.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">llm-prompt</data>
  <data key="provider">local</data><data key="prompt">Reply briefly to {{payload}}</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">agent</data>
  <data key="provider">local</data><data key="instructions">Answer without tools.</data>
  <data key="objective">Describe {{payload}}</data></node>
```

With payload `example`, each emits its provider-dependent bounded answer on `continue`. Test mode
makes no model or tool call.

## AMQP 0-9-1

See the [`amqp091` reference](bundles/amqp091.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">amqp.publish</data>
  <data key="brokerProfile">example</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">amqp.consume</data>
  <data key="brokerProfile">example</data></node>
```

Run publish with `{"version":"amqp.publish.v1","bodyText":"hello"}`; a broker acknowledgement
emits `CONFIRMED`. Deploying consume creates the configured consumer; durable disposition of its
bounded event controls broker acknowledgement.

## Discord

See the [`discord` reference](bundles/discord.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">discord.interactions</data>
  <data key="discordProfile">operations</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">discord.send</data>
  <data key="discordProfile">operations</data></node>
```

Deployment activates interactions and a valid relayed command emits `discord.interaction.v1`. Run
send with the reference's `discord.message.v1` payload; provider acceptance emits its content-free
result on `continue`.

## Filesystem

See the [`filesystem` reference](bundles/filesystem.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">filesystem.read</data>
  <data key="filesystemProfile">local</data><data key="path">incoming/example.txt</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">filesystem.write</data>
  <data key="filesystemProfile">local</data><data key="path">outgoing/example.txt</data></node>
```

Read with `{"version":"filesystem.read.v1"}` to obtain the bounded document. Write with
`{"version":"filesystem.write.v1","text":"hello"}`; `create-new` emits `CREATED` when the
target is absent. The host must provide the secure directory primitive in the reference.

## Git workspace

See the [`git-workspace` reference](bundles/git-workspace.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">git-workspace</data>
  <data key="workspaceProfile">issues</data></node>
```

Run the reference's `git-workspace.v1` `provision` payload with a reachable base revision. A
successful compare-and-swap emits `git-workspace.result.v1`; a ref race follows `conflict`.

## GitHub

See the [`github` reference](bundles/github.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">github-events-source</data>
  <data key="githubProfile">automation</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">project-transition</data>
  <data key="githubProfile">automation</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">github-app-review</data>
  <data key="githubProfile">automation</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">github-workflow-watch</data>
  <data key="githubProfile">automation</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">release-prepare</data>
  <data key="githubProfile">automation</data></node>
```

Deployment activates the verified webhook source. Run each action with its versioned reference
payload. Successful effects emit bounded results, workflow watch follows its terminal workflow
outcome, and release preparation reports the exact prepared revision or classified refusal.

## JDBC

See the [`jdbc` reference](bundles/jdbc.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">jdbc.query</data>
  <data key="profile">reporting</data><data key="statement">find-user</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">jdbc.insert</data>
  <data key="profile">reporting</data><data key="statement">add-user</data></node>
```

Using the reference profile, query with
`{"contract":"jdbc.parameters.v1","parameters":{"id":42}}` to obtain the bounded row set;
insert with `{"contract":"jdbc.parameters.v1","parameters":{"name":"Ada"}}` to obtain the
bounded update count and allowed generated keys. SQL stays entirely in the operator profile. Test
opens no connection.

## Kafka

See the [`kafka` reference](bundles/kafka.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">kafka.produce</data>
  <data key="clusterProfile">events</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">kafka.consume</data>
  <data key="clusterProfile">events</data></node>
```

Produce with `{"version":"kafka.produce.v1","valueText":"hello"}`; a broker acknowledgement
emits `ACKNOWLEDGED`. Deployment activates consume; a record emits `kafka.record.v1` and only the
ordered durable safe frontier advances the offset.

## Mail

See the [`mail` reference](bundles/mail.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">mail.send</data>
  <data key="mailProfile">operations</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">mail.imap.query</data>
  <data key="profile">operations</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">mail.imap.consume</data>
  <data key="profile">operations</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">mail.imap.move</data>
  <data key="profile">operations</data><data key="destinationFolder">Processed</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">mail.imap.delete</data>
  <data key="profile">operations</data></node>
```

Use the exact send, query, move, and delete payloads from the reference. Successful actions emit
bounded results on `continue`. Deployment activates consume, whose durable custody controls progress.

## Object storage

See the [`object-storage` reference](bundles/object-storage.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">object.get</data>
  <data key="storageProfile">assets</data><data key="key">examples/hello.txt</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">object.put</data>
  <data key="storageProfile">assets</data><data key="key">examples/hello.txt</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">object.list</data>
  <data key="storageProfile">assets</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">object.delete</data>
  <data key="storageProfile">assets</data><data key="key">examples/hello.txt</data></node>
```

Use the versioned GET, PUT, LIST, or DELETE payload from the reference. Success emits the bounded
protocol result. PUT may be ambiguous after request handoff and requires reconciliation.

## OCR

See the [`ocr` reference](bundles/ocr.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">ocr.extract</data>
  <data key="ocrProfile">local</data><data key="language">eng</data></node>
```

Run with the strict `ocr.extract.v1` Base64 image payload. Successful supervised execution emits an
`EXTRACTED` result with bounded text and safe image evidence. Test starts no process.

## OpenAPI client

See the [`openapi-client` reference](bundles/openapi-client.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">openapi.call</data>
  <data key="apiProfile">orders</data><data key="operationId">getOrder</data></node>
```

Run with `{"path":{"orderId":"42"}}` for a matching installed operation schema. A 2xx JSON
response emits `openapi.call.result.v1`; the managed service enforces egress and credentials.

## OpenAPI server

See the [`openapi-server` reference](bundles/openapi-server.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">openapi.receive</data>
  <data key="apiProfile">orders</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">openapi.request-reply</data>
  <data key="apiProfile">orders</data></node>
```

Deployment activates the managed routes. A valid receive request returns HTTP 202 after durable
acceptance. Request-reply waits for the bounded graph response and follows `responded`.

## Slack

See the [`slack` reference](bundles/slack.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">slack.events</data>
  <data key="slackProfile">operations</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">slack.commands</data>
  <data key="slackProfile">operations</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">slack.post-message</data>
  <data key="slackProfile">operations</data></node>
```

Deployment activates event and command routes. Verified ingress emits a bounded payload after
durable acceptance. Run post-message with `slack.message.v1`; provider acceptance emits its
content-free result.

## Restricted SpEL

See the [`spel` reference](bundles/spel.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">spel.transform</data>
  <data key="expression">customer.name</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">spel.decision</data>
  <data key="expression">customer.name == 'Ada'</data></node>
```

With `{"customer":{"name":"Ada"}}`, transform emits `Ada`; decision preserves the payload and
follows `true`. Both are deterministic and side-effect free.

## Telegram

See the [`telegram` reference](bundles/telegram.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">telegram.send</data>
  <data key="botProfile">alerts</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">telegram.answer.callback</data>
  <data key="botProfile">alerts</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">telegram.edit.message</data>
  <data key="botProfile">alerts</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">telegram.delete.message</data>
  <data key="botProfile">alerts</data></node>
```

Run each with its matching versioned payload. Provider success emits the bounded action result;
ambiguous results after handoff require reconciliation under the caller's idempotency policy.

## WebSocket

See the [`websocket` reference](bundles/websocket.md).

```xml
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">websocket.send</data>
  <data key="websocketProfile">events</data></node>
<node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">websocket.receive</data>
  <data key="websocketProfile">events</data></node>
```

Send `{"version":"websocket.send.v1","encoding":"text","data":"hello"}`; successful handoff
emits the bounded result. Deployment activates receive, which emits bounded text or Base64 events.
