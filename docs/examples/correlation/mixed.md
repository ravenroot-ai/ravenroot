# One startup traversal alongside four sources

[mixed-start-four-sources.graphml](mixed-start-four-sources.graphml) is an executable graph for
START work, an HTTP/delay cycle, four independent AMQP consumers and sibling email effects. The
operator supplies the HTTP endpoint, four broker profiles and one SMTP profile. The graph contains
no endpoint credential or broker topology authority.

Deploy activates `amqp-1` through `amqp-4`, without calling HTTP, sending mail or consuming the one
initial START allowance. First Run binds to that deployment and starts its initial traversal.
It fetches a declared `mail.send.v1` JSON message from `/prepare`, explicitly parses that JSON, and
fans out into three branches:

- `amqp-1` records a SOURCE boundary and stops this branch. It creates no broker event and does not
  invoke `worker-1`.
- `sibling-mail` sends the prepared message through the configured actual mail behavior.
- `loop-http` calls `/loop`, waits 20 ms, and loops. Its authored `joinPolicy=each` permits each
  iteration; the loop does not wait for an impossible second initial predecessor.

The HTTP fixture returns this bounded synthetic body at every endpoint:

```json
{"version":"mail.send.v1","to":["to@example.test"],"text":"a sibling completed despite a source boundary"}
```

The cycle is intentionally contained by an **operator traversal budget**, rather than a claim that
this is a production polling service. The executed test sets `RAVENROOT_GRAPH_MAX_TRAVERSAL_STEPS=30`
and `RAVENROOT_GRAPH_MAX_AMPLIFIED_DELIVERIES=40` through the application's explicit configuration
map. Exhaustion fails that traversal visibly. It neither restarts START nor stops the deployment's
listeners. Use a finite application condition or scheduled source for a production polling design.

## Independent broker events

Configure distinct tenant-scoped profiles `mixed-1` through `mixed-4`, each authorized for its
existing queue `mixed.1` through `mixed.4`. The example uses Consumer mode, durable custody and manual
acknowledgements. Configure the SMTP profile `mixed-mail` with an allowed sender/recipient and finite
message/concurrency limits. Replace `http://localhost:19090` with the exact authorized HTTP origin.
Follow [AMQP operator configuration](../../reference/bundles/amqp091.md) and
[mail configuration](../../reference/bundles/mail.md); the graph cannot create profiles or weaken
destination checks.

Publish one bounded message with a distinct transport `messageId` to each queue. An event from
`mixed.1` enters at `amqp-1`, calls only `worker-1`, and stops at `amqp-2`. Its sibling parse/mail branch
can still send `source-mail`. It never synthesizes an event for `amqp-2`. The other queues independently
enter at their own source and reach their own worker. The fourth path reaches END. No event revisits
START, `/prepare` or the polling loop.

Every listener remains the same activated owner; reaching a SOURCE by an edge never reopens it.
SOURCE-boundary visits and actual source emissions are distinct observable events. To identify an
actual broker emission, inspect the traversal's **entry node**, not just a node name that also appears
as a boundary in another traversal. The graph's generated business contexts remain independent;
drawing adjacent SOURCE nodes does not join their data or correlate their histories.

## Reproduce the observed run

With JDK 21–25, Maven and Docker available, run from `ravenroot/`:

```sh
mvn -pl ravenroot-extensions/ravenroot-amqp091 -am \
  -Dtest=AmqpMixedDeploymentExampleTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The public test starts RabbitMQ 4.1, a bounded HTTP fixture, SMTP, Pekko and a real SQLite store.
It executes the downloadable graph, substituting only the allocated HTTP fixture origin and
injecting operator profiles with explicit loopback destination authority. It verifies:

- four broker consumers and zero HTTP/mail/initial START effects after Deploy;
- an active HTTP/delay cycle alongside four actual broker deliveries;
- exactly one call to each source worker and two accepted SMTP messages;
- the correct actual entry source and separate traversal identities;
- source boundaries, no listener duplication, and a visibly failed budget-limited initial traversal;
- a second independent START request returns the original process/traversal IDs without calling
  `/prepare` again, even after that initial traversal failed.

The test retains `history.db` and bounded `observations.json` under
`target/mixed-deployment-example/<run>/`. SMTP acceptance and HTTP responses are observed transport
effects; the example does not claim exactly once external effects across ambiguous failures.
Use the [lakehouse walkthrough](lakehouse.md) for actual browser screenshots of history selection,
repeated pinned graphs, missing contribution inspection and a six-result continuation.
