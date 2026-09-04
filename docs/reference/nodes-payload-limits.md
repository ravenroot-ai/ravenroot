# Node catalog, payloads, and limits

The catalog returned by `GET /v1/node-types` is authoritative for the running deployment. The UI renders its palette and Inspector controls from this contract.

## Shipped behavior families

| Identifier | Purpose | Privileged dependency |
|---|---|---|
| `log` | Emit a bounded diagnostic entry | Logging policy |
| `template` | Interpolate payload and attributes into text | None |
| `cel-transform` | Compute a payload with CEL | CEL evaluator |
| `cel-decision` | Select an outcome with CEL | CEL evaluator |
| `boundary-guard` | Validate a publication candidate | Operator policy profile and audit sink |
| `http-request` | Perform an outbound HTTP call | Egress and credentials |
| `delay` | Resume after a bounded asynchronous delay | Scheduler |
| `human-task` | Park for a durable authorized human decision | Durable execution store |
| `json-parse` | Parse JSON text into a structured value | None |
| `json-path` | Select ordered values with RFC 9535 JSONPath | JSONPath evaluator |
| `llm-prompt` | Invoke a configured model profile | Model adapter and credential |
| `agent` | Invoke a bounded agent runtime and tool set | Agent adapter and allowlist |
| `program` | Execute an approved artifact | Sandbox supervisor |

An unavailable privileged dependency does not become available because its identifier appears in a graph.

`boundary-guard` declares the fixed outcomes `continue` and `violation`. It requires `policyId`, `policyVersion`, and `policyDigest`; the digest pins all effective immutable profile data. The default catalog includes the node but resolves no profiles, so it fails closed until an application supplies an operator-owned resolver. See [Publication boundary policies](../security/publication-boundaries.md) for candidate authoring, rule composition, provider re-evaluation, and limitations.

## Exact core properties

| Behavior | Property | Type | Default or domain |
|---|---|---|---|
| `delay` | `durationMs` | integer | default 1000; 0–86,400,000 |
| `json-parse` | `source` | string template | `{{payload}}` |
| `json-path` | `path` | string | required RFC 9535 expression |
| `template` | `template` | string | required template text |
| `human-task` | `title` / `description` | string / text | required ≤256 UTF-8 bytes / optional ≤4 KiB |
| `human-task` | `responseKind` / `maxResponseBytes` | enum / integer | `MAP` / 65,536 (maximum 262,144) |
| `human-task` | `escalateAfterSeconds` / `expiresAfterSeconds` | integer / integer | 0 (disabled) / 604,800; maximum 2,592,000 |

`delay` preserves payload and attributes and returns `continue`. `json-parse` accepts top-level scalars, arrays, or objects; 64-bit integers remain integers and fractional or exponent numbers become doubles. Invalid JSON fails. `json-path` returns an ordered array and returns `[]` when nothing matches.

## Default payload budgets

| Budget | Default |
|---|---:|
| Serialized payload | 256 KiB |
| Structural depth | 32 |
| Elements per collection | 1,000 |
| Values across the payload | 10,000 |
| Text value | 32 KiB |
| Object key | 256 characters |

JSONPath additionally limits query length to 32,768 UTF-16 code units, selectors to 256, query depth to 64, AST nodes to 512, evaluations to 10,000, total work to 100,000, regex nesting to 64, regex nodes to 4,096, intermediate results to 10,000, and final results to 1,000.

Limit violations are classified failures and never silently truncate a value or clamp a query. For route semantics see [Executions and outcomes](execution-events.md); for security ownership see [Input, secrets, and egress](../security/input-secrets-egress.md).

See [Durable human tasks](human-tasks.md) for the full response, authorization, timer, re-entry, and inbox contract.
