# Execute a register-machine loop

This exercise runs an ordinary Ravenroot GraphML workflow that initializes a counter to `3`, tests
whether it is zero, decrements it, and loops. The terminal payload contains `counter: "0"`. Every
operation and branch is visible in the graph; there is no hidden register-machine interpreter.

## Learning objectives

After the exercise, a learner can identify initialization, zero testing, DECJZ-style branching,
decrement, and halt; predict the final register value; validate the bounded Register Machine Profile;
and distinguish profile conformance from a successful governed runtime execution.

## Prerequisites

Build or install Ravenroot with JDK 21 through 25. Use the exact
[`counter-decjz.graphml`](../examples/register-machine/counter-decjz.graphml) file from the source
version you are running. Start the standalone server on its loopback default with the repository's
documented local runner:

```sh
./ravenroot/scripts/server.sh start
```

The helper defaults to loopback and disabled authentication for local evaluation. Use the operator
identity configuration instead on any non-loopback deployment.

## Inspect and predict

Open the GraphML and find these operations:

1. `set` copies literal `3` into `counter`.
2. `zero-test` compares `counter` with literal `0` and writes `isZero`.
3. `branch` selects `zero` or `nonzero` from `payload.isZero`.
4. `decrement` subtracts literal `1` and loops to `zero-test`.
5. The `zero` outcome reaches `end`.

Predict four zero tests, three decrements, and a terminal `counter` value of `0`.

## Validate the profile

From the source root, run the CLI produced by the release build:

```sh
./ravenroot/ravenroot-cli/target/ravenroot/bin/ravenroot \
  validate --register-machine docs/examples/register-machine/counter-decjz.graphml
```

Expected profile evidence includes:

```text
register-machine-profile=1
register-machine-verdict=accepted
register-machine-diagnostics-truncated=false
```

Validation checks GraphML and the bounded static profile. It does not execute the loop.

## Run the graph

Build a structured submission so the initial payload is a map. The legacy `?payload=` parameter is
text, even when its characters happen to be `{}`; `bigint-op` deliberately refuses that scalar
shape. Node.js 24 is already a source-build prerequisite:

```sh
mkdir -p ravenroot/target
node --input-type=module <<'JS'
import { readFileSync, writeFileSync } from 'node:fs';

const graphml = readFileSync('docs/examples/register-machine/counter-decjz.graphml', 'utf8');
const payload = {
  contract: 'ravenroot.payload/1',
  schema: 'ravenroot.unspecified',
  schemaVersion: '1',
  kind: 'MAP',
  value: {}
};
writeFileSync(
  'ravenroot/target/register-machine-submission.json',
  JSON.stringify({ contract: 'ravenroot.execution/1', graphml, payload })
);
JS
```

Submit Run semantics to the loopback service. Keep the returned execution ID:

```sh
curl --fail --silent --show-error -X POST \
  'http://127.0.0.1:8080/v1/executions?mode=run' \
  -H 'Content-Type: application/vnd.ravenroot.execution.v1+json' \
  --data-binary @ravenroot/target/register-machine-submission.json
```

Read `GET /v1/executions/{executionId}` until it is terminal. The final response has a completed
status and a payload whose `counter` field is the decimal string `"0"`. The exact integer behavior
stores register values as decimal strings so values can exceed the Java `long` range without loss.

This procedure was live-run against the pinned `0.7.0-alpha.1` source baseline on 16 October 2026.
The observed terminal evidence was:

```json
{
  "status": "COMPLETED",
  "visitedNodes": ["branch", "decrement", "end", "set", "start", "zero-test"],
  "defaultedNodes": [],
  "bypassedNodes": [],
  "handledFailureNodes": [],
  "payload": {"counter": "0", "isZero": true}
}
```

Execution and process IDs are intentionally omitted because each run creates new identifiers.

Use the execution's events when reconstructing chronology. `visitedNodes` is unique membership, not
an ordered trace, so its lexical presentation cannot prove the loop order or iteration count.

## Explain the evidence

The successful result establishes that the current runtime executed supported `bigint-op` and
`cel-decision` behaviors and reached End with the expected payload. The static validator establishes
that the same graph conforms to Register Machine Profile v1. Neither result claims unlimited time,
memory, digits, or iterations. Ravenroot enforces the profile and runtime resource limits documented
in the [Register Machine Profile](../reference/register-machine-profile.md).

## Diagnose failures

- `register-machine-verdict=invalid`: read every diagnostic; do not run a graph with nodes outside the
  profile or ambiguous control flow.
- Unknown `bigint-op`: the server and CLI are from different baselines or the running catalog is
  incomplete. Verify `node-types` before retrying.
- HTTP 401 or 403: use the configured identity and execution-start authority. Disabled local auth is
  never a deployment recommendation.
- Terminal failure: inspect the qualified termination reason and events. A cancellation is reported
  separately from an ordinary node failure.
- Result remains non-terminal: confirm the execution ID, server readiness, and loop edges; use the
  bounded cancellation procedure rather than killing unrelated processes.

Stop only the helper-managed local server when finished:

```sh
./ravenroot/scripts/server.sh stop
```
