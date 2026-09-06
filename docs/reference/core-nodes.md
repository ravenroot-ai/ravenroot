# Core node reference

These 11 node types are registered by the standard core catalog at the documented development
baseline. The running `GET /v1/node-types` response remains authoritative for a particular
deployment. Optional bundle nodes are in the [bundle reference](bundles/).

All nodes accept Ravenroot's bounded canonical payload and attribute map. Unless a row says
otherwise, attributes pass through unchanged. Test mode bypasses behavior execution and records the
node in `bypassedNodes`; expected results below describe Run mode. Failures produce no normal node
outcome and follow the graph's failure route. The engine applies graph retry settings around a node;
the node-specific repeatability and ambiguity rules below determine whether repeating an effect is
safe.

## Minimal example harness

Use the maintained complete GraphML file for the behavior you want to try:

- [`log`](../examples/nodes/log.graphml), [`delay`](../examples/nodes/delay.graphml),
  [`template`](../examples/nodes/template.graphml), [`json-parse`](../examples/nodes/json-parse.graphml),
  and [`json-path`](../examples/nodes/json-path.graphml)
- [`cel-transform`](../examples/nodes/cel-transform.graphml) and
  [`cel-decision`](../examples/nodes/cel-decision.graphml)
- [`human-task`](../examples/nodes/human-task.graphml),
  [`http-request`](../examples/nodes/http-request.graphml),
  [`program`](../examples/nodes/program.graphml), and
  [`boundary-guard`](../examples/nodes/boundary-guard.graphml)

Each file already declares its property keys, uses one consistently named `action` node, routes every
declared outcome, and has a separate unlabelled failure edge to `error`. Do not paste an `action`
fragment into the first-graph tutorial's `greet` graph without also renaming every incident edge.
Replace any operator-profile or digest placeholder and meet the behavior prerequisites described in
its section, then validate and run the downloaded file with:

```sh
ravenroot validate example.graphml
ravenroot run example.graphml 'example input'
```

Use the UI's Test action before Run when a node can perform an effect. The fragments below show the
action node's exact GraphML `data` entries for explanation; they are not standalone graphs or a
replacement syntax.

## `log`

| Field | Contract |
|---|---|
| Property | Optional `message` text, default `{% raw %}{{payload}}{% endraw %}`; also supports `{% raw %}{{attributes.name}}{% endraw %}` and `{% raw %}{{properties.name}}{% endraw %}` |
| Output | Original payload; adds Boolean attribute `ravenroot.logged=true`; outcome `continue` |
| Effect and retry | Writes one bounded INFO diagnostic. A retry writes another entry; there is no idempotency key. Cancellation has no separate action once the synchronous write begins. |
| Failures | An unresolvable template placeholder fails before an outcome. |

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">log</data>
  <data key="message">received {% raw %}{{payload}}{% endraw %}</data>
</node>
```

With payload `example input`, Run writes `received example input`, preserves the payload, adds the
logged attribute, and follows `continue`.

## `delay`

| Field | Contract |
|---|---|
| Property | Optional `durationMs` integer, default 1000, inclusive range 0–86,400,000 |
| Output | Original payload and attributes; outcome `continue` after the timer |
| Effect and retry | No external side effect. Repeating waits again. Cancellation prevents later dispatch when the engine cancels the traversal. |
| Failures | A malformed or out-of-range duration refuses composition. |

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">delay</data>
  <data key="durationMs">10</data>
</node>
```

Run completes after at least the scheduled wait and returns `example input` on `continue`.

## `template`

| Field | Contract |
|---|---|
| Property | Required `template` text with payload, attribute, and property placeholders |
| Output | Rendered text payload; original attributes; outcome `continue` |
| Effect and retry | Deterministic and side-effect free for the same inputs. Cancellation has no node-specific resource to close. |
| Failures | Missing property or an unresolvable placeholder fails. |

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">template</data>
  <data key="template">Hello, {% raw %}{{payload}}{% endraw %}.</data>
</node>
```

Payload `example input` becomes `Hello, example input.`.

## `json-parse`

| Field | Contract |
|---|---|
| Property | Optional `source` text template, default `{% raw %}{{payload}}{% endraw %}` |
| Input/output | Parses JSON text into a bounded canonical scalar, list, or map; attributes pass through; outcome `continue` |
| Effect and retry | Deterministic and side-effect free. |
| Failures | Invalid JSON, reserved keys, unresolved template values, and payload budget violations fail; no partial value is emitted. |

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">json-parse</data>
  <data key="source">{"message":"{% raw %}{{payload}}{% endraw %}"}</data>
</node>
```

Payload `example input` becomes the canonical map `{"message":"example input"}`.

## `cel-transform`

| Field | Contract |
|---|---|
| Property | Required checked `expression` |
| Input/output | Expression bindings expose payload, attributes, and properties; result becomes the payload; outcome `continue` |
| Effect and retry | Deterministic, bounded, and side-effect free. |
| Failures | CEL parse/type/evaluation errors and an absent result fail. |

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">cel-transform</data>
  <data key="expression">payload + " transformed"</data>
</node>
```

Payload `example input` becomes `example input transformed`.

## `cel-decision`

| Field | Contract |
|---|---|
| Properties | Required Boolean `expression`; optional `trueOutcome` default `true`; optional `falseOutcome` default `false` |
| Input/output | Payload and attributes remain unchanged; selects the configured true or false outcome |
| Effect and retry | Deterministic and side-effect free. |
| Failures | CEL parse/type/evaluation errors and an absent result fail. A non-Boolean expression is not coerced. |

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">cel-decision</data>
  <data key="expression">payload == "example input"</data>
  <data key="trueOutcome">continue</data>
  <data key="falseOutcome">mismatch</data>
</node>
```

Connect `continue` to `end` and `mismatch` to a second terminal. The example payload follows
`continue` unchanged.

## `json-path`

| Field | Contract |
|---|---|
| Property | Required `path`, one bounded RFC 9535 JSONPath query |
| Input/output | Accepts canonical structured JSON or JSON text; emits an ordered array of matches; no match emits `[]`; outcome `continue` |
| Effect and retry | Deterministic and side-effect free. |
| Failures | `INVALID_INPUT` or classified JSONPath syntax/resource reasons fail without truncation. Query/result ceilings are listed in [Node catalog, payloads, and limits](nodes-payload-limits.md). |

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">json-path</data>
  <data key="path">$.items[*].name</data>
</node>
```

Run with payload `{"items":[{"name":"a"},{"name":"b"}]}` and expect `["a","b"]`.

## `http-request`

| Field | Type | Required/default |
|---|---|---|
| `url` | URI | required HTTP(S) endpoint; resolved destination must be operator-allowlisted |
| `method` | string enum | `GET`; one of `GET`, `POST`, `PUT`, `PATCH`, `DELETE` |
| `body` | text template | empty; supports `{% raw %}{{payload}}{% endraw %}` |
| `timeoutMs` | integer | 10000, capped by the server maximum |
| `credentialRef` | secret reference | empty; resolved only by the server |
| `credentialHeader` | string | `Authorization` |
| `credentialScheme` | string | `Bearer`; empty sends only the resolved secret value |
| `successOutcome` | string | `continue` for HTTP 2xx |
| `failureOutcome` | string | `error` for non-2xx HTTP responses |
| `recovery.repeatable` | Boolean declaration | required for `POST`, `PUT`, `PATCH`, and `DELETE`; applicability is non-mutating `GET` only when absent |

Response text becomes the payload and attributes add `http.status` and `http.uri`. A non-2xx response
is a completed request and selects `failureOutcome`; DNS, egress, credential, timeout, cancellation,
media, or response-budget faults fail the node. Ravenroot performs no implicit HTTP retry. A mutating
request can have an ambiguous external result after handoff, so authors must declare repeatability
and downstream recovery must follow that declaration.

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">http-request</data>
  <data key="url">https://example.invalid/health</data>
  <data key="method">GET</data><data key="timeoutMs">1000</data>
</node>
```

Replace the reserved example host with an exact operator-allowlisted test endpoint. A 2xx response
follows `continue`; a 4xx/5xx response follows `error`. Test mode performs no request.

## `program`

| Field | Contract |
|---|---|
| `language` | Required runtime language identifier |
| `source` | Required exact UTF-8 source; server calculates content identity |
| `testPayload` | Optional smoke input; strict JSON becomes structured input, other text remains literal |
| `artifactId` | Optional read-only audit reference; never lifecycle authority |
| Output | Sandbox result payload; adds `program.artifact`, `program.sha256`, and `program.runtime`; outcome `continue` |
| Effect and retry | Runs only through the governed sandbox and artifact admission. Repeatability belongs to the approved program contract; Ravenroot does not infer it from source. Cancellation propagates to the supervised runtime. |
| Failures | Missing sandbox/runtime, validation, approval, activation, timeout, heap/output, or supervisor faults fail with the artifact/runtime contract and no unsupervised fallback. |

The repository's executable example is
[`ravenroot-programmable.graphml`](https://github.com/ravenroot-ai/ravenroot/blob/f58cd7c7d98cd370c89199829d5436c6a7e8eb8b/ravenroot/ravenroot-ui/public/examples/ravenroot-programmable.graphml).
Validate it first; Run requires the configured language runtime and sandbox supervisor. Expected
output and exact lifecycle are in [Model, agent, and program integration](../integrator-guide/ai-programs.md).

## `boundary-guard`

| Field | Contract |
|---|---|
| `policyId` | Required operator-owned immutable profile ID |
| `policyVersion` | Required exact immutable version |
| `policyDigest` | Required canonical SHA-256 binding |
| Input/output | A typed publication candidate. Passing preserves the payload and follows `continue`; refusal emits the bounded decision map and follows `violation`. Audit attributes contain only the policy/result evidence defined by the contract. |
| Effect and retry | Validates and audits; never publishes, rewrites, or silently redacts. Evaluation is repeatable against the pinned immutable profile. |
| Failures | Missing/mismatched profile, digest, candidate, provider evidence, or audit capability fails closed. |

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">boundary-guard</data>
  <data key="policyId">release-policy</data>
  <data key="policyVersion">1</data>
  <data key="policyDigest">sha256:0000000000000000000000000000000000000000000000000000000000000000</data>
</node>
```

Replace all placeholders with an operator-installed immutable profile and its canonical digest.
Connect both `continue` and `violation`. The default catalog exposes the node descriptor but resolves
no policy profiles, so this example fails closed until the host supplies one. See
[Publication boundary policies](../security/publication-boundaries.md).

## `human-task`

`human-task` creates durable, authorized work and has a larger contract. The fields below are the
base task contract. A production registry backed by a confirmation-capable durable store also
publishes the seven `confirmation*` presentation fields in the
[generated descriptor table](node-contracts.md#node-human-task); a registry without that capability
omits them. Their admission and settlement semantics are in the
[durable Human Task authoring contract](human-tasks.md#authoring-contract).

| Property | Required/default |
|---|---|
| `title` | required static text, at most 256 UTF-8 bytes |
| `description` | empty; static text, at most 4 KiB |
| `responseContentType` | `application/vnd.ravenroot.payload+json` |
| `responseSchema` / `responseSchemaVersion` | `ravenroot.human-task.response` / `1` |
| `responseKind` | `MAP`; one of `SCALAR`, `LIST`, `MAP` |
| `maxResponseBytes` | 65,536; inclusive range 1–262,144 |
| `authorizedRoles` / `authorizedScopes` | empty comma-separated sets; every listed value is required |
| `escalateAfterSeconds` | 0 disables; when set, earlier than expiry |
| `expiresAfterSeconds` | 604,800; range 1–2,592,000 |
| `correlationSource` / `deduplicationSource` | fixed `task-id` / `attempt-id` |
| `resolvedOutcome` / `deniedOutcome` / `expiredOutcome` / `cancelledOutcome` | `resolved` / `denied` / `expired` / `cancelled` |

The node emits no external side effect before creating the durable task. Resolution is
generation-fenced and deduplicated; restart resumes from the pinned graph. Cancellation and expiry
have distinct outcomes. Title/description do not interpolate payloads.

```xml
<node id="action">
  <data key="kind">BEHAVIOR</data><data key="behavior">human-task</data>
  <data key="title">Approve example</data>
  <data key="responseKind">MAP</data>
  <data key="resolvedOutcome">continue</data>
</node>
```

Connect `continue`, `denied`, `expired`, and `cancelled` to explicit terminals. Run creates one inbox
task; submitting a valid map response follows `continue`. A deployment without a durable human-task
store fails closed. Follow [Durable human tasks](human-tasks.md) for the response document,
authorization, timers, cancellation, and verification API.

## Cross-cutting limits and recovery

Payload, graph, fan-out, concurrency, traversal, and JSONPath ceilings are in
[Node catalog, payloads, and limits](nodes-payload-limits.md). Test/Run evidence and failure routing
are in [Executions, outcomes, and events](execution-events.md). Operator credentials, egress, tools,
profiles, and sandbox services cannot be supplied or widened by these node properties.
