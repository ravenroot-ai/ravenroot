# Configuration and deployment defaults

Configuration is environment-owned. A graph cannot select an engine, authentication mode, browser origin, credential backend, adapter, sandbox, or egress policy.

## Runtime and behavior resolution

| Variable | Default | Accepted contract |
|---|---|---|
| `RAVENROOT_ENGINE` | `pekko` | `pekko`; `akka` when its adapter is installed |
| `RAVENROOT_UNKNOWN_BEHAVIOR` | `pass-through` | `pass-through` or `refuse` |
| `RAVENROOT_AUTH_MODE` | context-sensitive | omitted is allowed only on loopback; non-loopback refuses |

Unknown-behavior pass-through is observable in `defaultedNodes`; `refuse` rejects the unresolved graph.

## Graph execution resource limits

Graph admission and execution use operator-owned limits. Graph content cannot raise or disable them;
properties declared by a node type, such as per-node concurrency, can only narrow the effective limit.
Values outside the supported ceilings refuse startup instead of silently expanding resource exposure.

| Variable | Default | Supported maximum | What it bounds |
|---|---:|---:|---|
| `RAVENROOT_GRAPHML_MAX_BYTES` | 10 MiB | 256 MiB | one graph-document budget across the served UI, HTTP ingress, core admission and recovery, structured submissions, and durable canonical definitions |
| `RAVENROOT_GRAPH_MAX_NODES` | 10,000 | 1,000,000 | nodes admitted |
| `RAVENROOT_GRAPH_MAX_EDGES` | 25,000 | 5,000,000 | edges admitted |
| `RAVENROOT_GRAPH_MAX_PROPERTIES` | 100,000 | 10,000,000 | graph, node, and edge properties |
| `RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES` | 256 KiB | 64 MiB | each input, node output, or attribute map |
| `RAVENROOT_GRAPH_MAX_FAN_OUT` | 64 | 256 | distinct targets for one routed outcome or failure route |
| `RAVENROOT_GRAPH_MAX_RESIDENT_ACTORS` | 256 | 4,096 | resident actors allocated when a runner starts |
| `RAVENROOT_GRAPH_MAX_LIVE_ACTORS_PER_TRAVERSAL` | 256 | 1,024 | demand-created worker and traversal actors alive or retiring in one traversal, with the same ceiling enforced across its runner |
| `RAVENROOT_GRAPH_MAX_IN_FLIGHT_HOPS` | 1,024 | 4,096 | admitted but incomplete messages in one traversal |
| `RAVENROOT_GRAPH_MAX_QUEUED_ADMISSIONS_PER_NODE` | 1,024 | 4,096 | messages waiting at one node gate |
| `RAVENROOT_GRAPH_MAX_TRAVERSAL_STEPS` | 100,000 | 1,000,000 | cumulative node deliveries in one live traversal |
| `RAVENROOT_GRAPH_MAX_AMPLIFIED_DELIVERIES` | 100,000 | 1,000,000 | cumulative non-root deliveries in one live traversal |
| `RAVENROOT_GRAPH_MAX_CUMULATIVE_PAYLOAD_BYTES` | 64 MiB | 256 MiB | cumulative routed bytes in one live traversal |
| `RAVENROOT_GRAPH_MAX_RECOVERY_DELIVERIES_PER_ATTEMPT` | 8 | 64 | persisted recovery delivery claims for one attempt |

Admission counts nodes, edges, properties, configured fan-out, and resident demand in one bounded
pass before actors are created. Cycles are accepted only under the finite cumulative traversal-step
policy. Step, amplification, and byte counters are shared by every branch, retry, and cycle re-entry;
a retry atomically reserves one step, its non-root delivery, and its exact payload-plus-attribute bytes
before the retry is recorded or sent. Tool-approval, human-task, and durable-pause checkpoints carry
the exact counters across restart, and malformed, unknown, or legacy checkpoints without a safe budget
state refuse re-entry instead of resetting it. Recovery redelivery has its own persisted counter.

`RAVENROOT_GRAPHML_MAX_BYTES` is an integer number of bytes: the default 10 MiB is `10485760`, and
the hard 256 MiB ceiling is `268435456`. A document of exactly the configured size is accepted by the
byte boundary; one additional byte is refused before the whole request is buffered. The server
publishes the resolved value to its authenticated, same-service-origin workspace as typed
`GET /v1/configuration` JSON. The browser applies that value before reading GraphML or a Graphify JSON
document, while HTTP, core parsing, restart recovery, and new durable definition writes enforce the
same budget independently. Graphify JSON remains a view-only import and does not become executable or
durable graph content. Existing durable definitions remain readable after an operator lowers the
limit, but recovery refuses to execute one that now exceeds the configured budget.

Blank or absent values use the default. Zero, negative, malformed, and over-ceiling values refuse
startup; they never silently widen or disable the limit. Compose passes the host value through to the
same composition root.

A refusal exposes a closed code such as `GRAPH_LIMIT_FAN_OUT_EXCEEDED` or
`GRAPH_LIMIT_TRAVERSAL_STEPS_EXCEEDED`, never graph content or payload values.

## Human Task operational policy

Human Task limits are one immutable server policy shared by graph authoring, catalog projection,
HTTP admission, persistence, and recovery. Set a JVM property
`ravenroot.human-task.<suffix>` or its environment counterpart
`RAVENROOT_HUMAN_TASK_<SUFFIX>`; a non-blank JVM property takes precedence. An absent or blank
property falls through to the environment variable, and an absent or blank environment variable uses
the default below. Values are read at process startup and require a restart to change.

| Server property / environment variable | Default | Valid range | Scope |
|---|---:|---|---|
| `ravenroot.human-task.default-response-bytes` / `RAVENROOT_HUMAN_TASK_DEFAULT_RESPONSE_BYTES` | 65,536 | 1–67,108,864; no greater than `max-response-bytes` | default graph response ceiling |
| `ravenroot.human-task.max-response-bytes` / `RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES` | 262,144 | 1–67,108,864; no greater than `max-decision-body-bytes` | largest graph response ceiling |
| `ravenroot.human-task.default-escalation-seconds` / `RAVENROOT_HUMAN_TASK_DEFAULT_ESCALATION_SECONDS` | 0 | 0–2,147,483,646; zero or below `default-expiry-seconds`; no greater than `max-escalation-seconds` | default escalation delay; zero disables it |
| `ravenroot.human-task.max-escalation-seconds` / `RAVENROOT_HUMAN_TASK_MAX_ESCALATION_SECONDS` | 2,591,999 | 0–2,147,483,646; below `max-expiry-seconds` | largest graph escalation delay |
| `ravenroot.human-task.default-expiry-seconds` / `RAVENROOT_HUMAN_TASK_DEFAULT_EXPIRY_SECONDS` | 604,800 | 1–2,147,483,647; no greater than `max-expiry-seconds` | default expiry delay |
| `ravenroot.human-task.max-expiry-seconds` / `RAVENROOT_HUMAN_TASK_MAX_EXPIRY_SECONDS` | 2,592,000 | 1–2,147,483,647; above `max-escalation-seconds` | largest graph expiry delay |
| `ravenroot.human-task.max-title-bytes` / `RAVENROOT_HUMAN_TASK_MAX_TITLE_BYTES` | 256 | 1–67,108,864 | UTF-8 title budget |
| `ravenroot.human-task.max-description-bytes` / `RAVENROOT_HUMAN_TASK_MAX_DESCRIPTION_BYTES` | 4,096 | 1–67,108,864 | UTF-8 description budget |
| `ravenroot.human-task.max-response-schema-bytes` / `RAVENROOT_HUMAN_TASK_MAX_RESPONSE_SCHEMA_BYTES` | 128 | 1–128 | ASCII `PayloadEnvelope` schema-name budget; schema version uses the fixed protocol bound |
| `ravenroot.human-task.max-authorization-tokens` / `RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKENS` | 16 | 1–256 | required responder role/scope tokens |
| `ravenroot.human-task.max-authorization-token-bytes` / `RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKEN_BYTES` | 256 | 1–4,096 | UTF-8 bytes in one authorization token |
| `ravenroot.human-task.max-decision-body-bytes` / `RAVENROOT_HUMAN_TASK_MAX_DECISION_BODY_BYTES` | 262,144 | 1–67,108,864; at least `max-response-bytes` | raw encoded PayloadEnvelope HTTP decision body |
| `ravenroot.human-task.default-page-size` / `RAVENROOT_HUMAN_TASK_DEFAULT_PAGE_SIZE` | 50 | 1–1,000; no greater than `max-page-size` | inbox page size when `limit` is omitted |
| `ravenroot.human-task.max-page-size` / `RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE` | 100 | 1–1,000 | largest requested inbox page |
| `ravenroot.human-task.response-max-depth` / `RAVENROOT_HUMAN_TASK_RESPONSE_MAX_DEPTH` | 32 | 1–256 | structured response nesting depth |
| `ravenroot.human-task.response-max-collection-size` / `RAVENROOT_HUMAN_TASK_RESPONSE_MAX_COLLECTION_SIZE` | 1,024 | 1–1,000,000 | entries in one response collection |
| `ravenroot.human-task.response-max-value-count` / `RAVENROOT_HUMAN_TASK_RESPONSE_MAX_VALUE_COUNT` | 4,096 | 1–5,000,000 | values across one response |
| `ravenroot.human-task.response-max-text-length` / `RAVENROOT_HUMAN_TASK_RESPONSE_MAX_TEXT_LENGTH` | 16,384 | 1–67,108,864 | UTF-16 code units in one text value |
| `ravenroot.human-task.response-max-key-length` / `RAVENROOT_HUMAN_TASK_RESPONSE_MAX_KEY_LENGTH` | 256 | 1–4,096 | UTF-16 code units in one object key |
| `ravenroot.human-task.write-attempts` / `RAVENROOT_HUMAN_TASK_WRITE_ATTEMPTS` | 3 | 1–32 | durable Human Task write retries |

For example, use
`-Dravenroot.human-task.max-response-bytes=524288` or
`RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES=524288`; the property form wins when both are present.
Docker Compose forwards this environment family, and the Helm chart exposes the same values under
`humanTask`; a blank Helm string deliberately selects the server default. Helm validates the listed
individual technical ranges, while the server validates the relational constraints before it opens a
listener. A malformed, overflowed, or inconsistent non-blank value refuses startup without echoing
the supplied value. The direct `ravenroot/scripts/server.sh` launcher inherits the same environment;
Ravenroot ships no tracked environment-file template or environment generator.

The UTF-8 unit applies only to fields named `*-bytes`. Response text and key lengths instead count
UTF-16 code units, including two units for one supplementary Unicode code point. The resource caps
on inbox pages, write attempts, and authorization tokens bound SQLite page materialization,
conflict-path retry work, and persisted authorization material respectively. Each responder axis
(roles and scopes) may carry 256 tokens; at 4,096 bytes per token, the two sets together are bounded
to 2 MiB of raw token text before delimiter and collection overhead.

The Human Task policy must also compose with graph execution limits at startup. Its
`max-response-bytes` cannot exceed `RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES`, and its value plus a
256-byte Human Task metadata reserve cannot exceed `RAVENROOT_GRAPH_MAX_CUMULATIVE_PAYLOAD_BYTES`.
Raise the paired graph limit before raising a valid Human Task response limit; an incompatible
combination refuses startup before the listener opens.

A graph may narrow a configured response ceiling but cannot widen the server policy. The resolved
response limit, raw-envelope decision-body cap, parser budgets, and write-retry budget are pinned
with a durable task, so changing a deployment policy cannot silently reinterpret a task created under
an earlier policy. Human Task retention remains part of durable execution-store retention and cascade
policy; there is no separate Human Task retention or outstanding-task quota.

Response media-type identity, schema name/version, payload-envelope identity, deterministic task ID,
and generation fencing are per-task wire or persistence contracts. Schema names and schema versions
use the existing ASCII alphanumeric `PayloadEnvelope` token grammar plus `._-:+/` and its fixed
128-unit cap, so bytes and units are identical. The configured schema-name budget may narrow that
bound; schema version has no separate operator setting. The former larger policy maximum was never a
usable wire label. These contracts are deliberately not global operator settings because changing them
would make existing clients or durable records ambiguous.

## Identity and browser controls

Local-token mode requires a token of at least 32 characters. OIDC configuration names issuer, audience, and JWKS URI. Published container deployments use OIDC as the external authentication contract.

Allowed browser origins and allowed HTTP hosts are exact values; wildcards are not accepted. UI and API share one origin. An SSE authentication context is revalidated every 30 seconds by default, so revoked or expired authority closes a long-lived stream.

## Programmable artifacts

| Setting | Default | Safety behavior |
|---|---|---|
| Program runtime | `graalvm` | Execution refuses without a usable supervisor |
| Program timeout | 5,000 ms | Supervisor terminates the bounded attempt |
| Program heap | 64 MiB | Supervisor enforces the memory budget |
| Dual control | `true` | Exact boolean; approval and execution authority remain separated |

Allowed hosts and allowed agent tools are operator allowlists. Empty or absent privileged configuration does not expand access.

## Agent authority and budgets

The packaged server composes finite process-rooted agent accounting whenever its execution store
advertises `AGENT_AUTHORITY_BUDGETS`. The shipped rate card is explicit and conservative rather than
treating unknown pricing as free. Operators can pin a different finite policy with these variables:

| Variable family | Shipped default |
|---|---|
| `RAVENROOT_AGENT_RUNTIME_INSTANCE`, `RAVENROOT_AGENT_POLICY_VERSION` | `ravenroot-server`, `server-finite-v1` |
| `RAVENROOT_AGENT_RATE_CARD_VERSION`, `RAVENROOT_AGENT_COST_CURRENCY` | `builtin-conservative-v1`, `USD` |
| `RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS` | `3600` |
| `RAVENROOT_AGENT_MAX_TURNS`, `MAX_INPUT_TOKENS`, `MAX_OUTPUT_TOKENS` | `1024`, `20000000`, `2000000` |
| `RAVENROOT_AGENT_MAX_ELAPSED_MILLIS`, `MAX_COST_MICROS`, `MAX_TOOL_CALLS` | `3600000`, `100000000`, `4096` |
| `RAVENROOT_AGENT_MAX_DELEGATION_DEPTH`, `MAX_TEAM_CUMULATIVE`, `MAX_TEAM_ACTIVE` | `8`, `64`, `16` |
| `RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN`, `MAX_OUTPUT_TOKENS_PER_TURN` | `128000`, `32000` |
| `RAVENROOT_AGENT_INPUT_TOKEN_RATE_MICROS`, `OUTPUT_TOKEN_RATE_MICROS` | `10`, `30` |
| `RAVENROOT_AGENT_DATA_SCOPES` | empty |
| `RAVENROOT_AGENT_AUTHORITY_SCOPES` | `runtime:delegate` |

All numeric maxima are positive integers; rates are non-negative integers, so an explicit zero is a
known free rate. Currency is an uppercase three-letter code. Scope variables are bounded comma-separated
opaque tokens. Omitting `runtime:delegate` disables child delegation without disabling top-level agents.

The runtime kill service is available only through authenticated `POST /v1/agent-authority/trip` and
`POST /v1/agent-authority/reset`. It requires a `PLATFORM_ADMIN` with the
`ravenroot.agent.authority.control` scope and applies to the store-global control domain across
tenants; the request tenant is audit identity, not target selection. Trip atomically advances the
durable epoch, refuses new grant/reservation/dispatch operations, releases held work, and records
already-dispatched work as indeterminate. Reset advances the epoch again but never revives an old
grant or suspended approval permit. An ordinary server restart at an unchanged control epoch preserves
a compatible suspended approval and its exact reservation; policy or rate-card drift fails closed.

When OpenTelemetry is enabled, agent accounting publishes numeric aggregate counters labeled only by
the fixed budget-dimension and outcome enums. Tenant, principal, process, traversal, invocation, node,
provider, profile, scope, prompt, arguments, credential, endpoint, and checkpoint values are never
metric labels.

## Secret handling

API keys and tokens never belong in GraphML. Credential POST writes secret material to the configured backend and returns a server-minted reference; reads return metadata, never the secret value.

For deployment procedure see [Deployment and startup](../operator-guide/deployment-startup.md). For refusal symptoms see [Startup and readiness](../troubleshooting/startup-readiness.md).
