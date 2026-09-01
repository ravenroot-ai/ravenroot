# Test, run, and inspect

Use the graph from the previous tutorial to compare simulated traversal with an execution that may perform effects.

## Test from the UI

Load `hello.graphml`, choose **Test**, and provide `world` as the payload. Test is the UI default and submits `mode=test`; behaviors are bypassed while traversal and routing remain observable. Use it to check shape and routes without calling connectors, models, tools, or programs.

## Run deliberately

Choose **Run**, review the effect confirmation, and submit the same payload. Run uses `mode=run` and may perform every operator-authorized effect in the path. The remote API contract is:

```bash
cd ravenroot-quickstart/ravenroot
curl --fail -X POST \
  'http://127.0.0.1:8080/v1/executions?mode=run&payload=world' \
  -H 'Content-Type: application/graphml+xml' \
  --data-binary @hello.graphml
```

Run the block from the source-checkout root; its first command enters the installation directory
that contains both the CLI and `hello.graphml`. The server returns HTTP 202 with an execution
identifier. `bin/ravenroot run` always requests real Run semantics; it is not a shortcut for Test.

## Inspect terminal evidence

Read `GET /v1/executions/{id}` until the execution is terminal. The result distinguishes unique sets of `visitedNodes`, `defaultedNodes`, `bypassedNodes`, and `handledFailureNodes`; `untakenEdges` explains routes not selected. These sets are evidence, not an ordered trace.

Use `GET /v1/events` for the live SSE stream or `GET /v1/events/recent` to resume after a cursor. A retention gap is explicit; Ravenroot does not silently pretend the stream is complete.

## Embedded Java check

The embedded sample is a separate Maven project outside the main reactor. In a new shell at the
source-checkout root, install the reactor and then enter the sample directory before invoking Maven:

```bash
cd ravenroot
mvn --batch-mode --no-transfer-progress install
cd ../ravenroot-sample
RAVENROOT_ENGINE=pekko mvn --batch-mode --no-transfer-progress \
  -DskipTests compile exec:java \
  -Dexec.args="hello ravenroot"
```

The sample reports `Ravenroot embedded result: HELLO RAVENROOT` and the visited node set; set ordering is not stable.

For execution fields see [Executions and events](../reference/execution-events.md). For failures see [Graph validation and execution](../troubleshooting/graph-execution.md).
