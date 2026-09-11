# Configuration and deployment defaults

Configuration is environment-owned. A graph cannot select an engine, authentication mode, browser origin, credential backend, adapter, sandbox, or egress policy.

## Helm values compatibility

The Helm values schema is closed over the fields published in `values.yaml`. Older chart revisions
allowed additional nested keys under `resources`, `podSecurityContext`, `securityContext`, and
`probes` to pass through `toYaml` without a chart contract. Those undocumented extensions are no
longer accepted. Before upgrading, remove such keys or apply the required Kubernetes fields with a
post-renderer or maintained chart/template customization. Supported resource quantities, pod and container
identity fields, probe timing, storage, image, Service, OIDC, and runtime-policy carriers remain
available as named values and reject malformed input before a workload is rendered. Quote resource
quantities in values files and use `--set-string` for unitless quantities, such as
`--set-string resources.limits.cpu=2`; numeric YAML quantities that older charts passed through must
be changed to strings before upgrading.

## Runtime and behavior resolution

| Variable | Default | Accepted contract |
|---|---|---|
| `RAVENROOT_ENGINE` | `pekko` | `pekko`; `akka` when its adapter is installed |
| `RAVENROOT_UNKNOWN_BEHAVIOR` | `pass-through` | `pass-through` or `refuse` |
| `RAVENROOT_AUTH_MODE` | context-sensitive | omitted is allowed only on loopback; non-loopback refuses |

Unknown-behavior pass-through is observable in `defaultedNodes`; `refuse` rejects the unresolved graph.

## Execution runtime and engine limits

The local server and embedded CLI resolve these limits once when they compose their execution engine.
The remote CLI does not parse them. Blank or absent values select the Java-owned defaults; every
explicit value is a positive whole number within the supported environment range. These ranges are
operator-facing tightening bounds chosen to preserve the existing safe defaults, rather than limits
on direct Java composition. Changing a value requires a process restart.

| Variable | Default | Supported range | What it bounds |
|---|---:|---:|---|
| `RAVENROOT_ENGINE_MAX_STASHED_COMMANDS_PER_NODE` | 10,000 | 1–10,000 | commands one busy engine node may retain |
| `RAVENROOT_ENGINE_LIFECYCLE_STEP_SECONDS` | 10 seconds | 1–10 seconds | each bounded engine spawn, stop, cancellation, drain, and termination step |
| `RAVENROOT_ENGINE_TERMINAL_HISTORY_CAPACITY` | 1,024 | 1–1,024 | terminal node observations retained by one engine instance |
| `RAVENROOT_GRAPH_RUNNER_SHUTDOWN_STEP_SECONDS` | 10 seconds | 1–10 seconds | graph-runner stop and cancellation waits during cleanup |

The stash and engine-lifecycle settings affect the engine compatibility fingerprint stored in an
execution manifest; recovery under a different fingerprint refuses with an `ENGINE` mismatch.
Terminal history changes only observation retention and is excluded from that fingerprint. The
graph-runner shutdown setting is also outside the manifest: changing it affects cleanup begun after
the restart and does not reject an existing execution as drift. Direct Java composition retains its
positive-value API and is not restricted to these environment ceilings.

## Graph execution resource limits

Graph admission and execution use operator-owned limits. Graph content cannot raise or disable them;
properties declared by a node type, such as per-node concurrency, can only narrow the effective limit.
Values outside the supported ceilings refuse startup instead of silently expanding resource exposure.

| Variable | Default | Supported maximum | What it bounds |
|---|---:|---:|---|
| `RAVENROOT_GRAPHML_MAX_BYTES` | 10 MiB | 256 MiB | one graph-document budget across the served UI, HTTP ingress, core admission and recovery, structured submissions, and durable canonical definitions |
| `RAVENROOT_GRAPHML_MAX_DEPTH` | 64 | 1,024 | GraphML XML nesting levels |
| `RAVENROOT_GRAPHML_MAX_STRING_LENGTH` | 1 MiB | 64 MiB | UTF-16 code units in one GraphML string value |
| `RAVENROOT_GRAPHML_MAX_KEYS` | 4,096 | 100,000 | distinct GraphML key declarations |
| `RAVENROOT_GRAPHML_MAX_ELEMENTS` | 250,000 | 10,000,000 | XML elements in one GraphML document |
| `RAVENROOT_GRAPHML_MAX_ATTRIBUTES` | 500,000 | 20,000,000 | XML attributes in one GraphML document |
| `RAVENROOT_GRAPHML_MAX_NAMESPACE_DECLARATIONS` | 10,000 | 1,000,000 | XML namespace declarations in one GraphML document |
| `RAVENROOT_GRAPH_MAX_NODES` | 10,000 | 1,000,000 | nodes admitted |
| `RAVENROOT_GRAPH_MAX_EDGES` | 25,000 | 5,000,000 | edges admitted |
| `RAVENROOT_GRAPH_MAX_PROPERTIES` | 100,000 | 10,000,000 | graph, node, and edge properties |
| `RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES` | 256 KiB | 64 MiB | each input, node output, or attribute map |
| `RAVENROOT_GRAPH_MAX_PAYLOAD_DEPTH` | 32 | 256 | structured payload nesting levels |
| `RAVENROOT_GRAPH_MAX_PAYLOAD_COLLECTION_SIZE` | 1,000 | 1,000,000 | members in one payload list or map |
| `RAVENROOT_GRAPH_MAX_PAYLOAD_VALUE_COUNT` | 10,000 | 5,000,000 | values across one payload tree, including containers |
| `RAVENROOT_GRAPH_MAX_PAYLOAD_TEXT_LENGTH` | 32 KiB | 64 MiB | UTF-16 code units in one payload text value |
| `RAVENROOT_GRAPH_MAX_PAYLOAD_KEY_LENGTH` | 256 | 4,096 | UTF-16 code units in one payload map key |
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

An authenticated response also carries `workspace: { "tenantId": "…" }`. `tenantId` is the exact,
opaque tenant of the principal authenticated for that request; clients compare it byte-for-byte and
must not trim, parse, display, or use it as a new authorization input. The server derives this field
from request-local principal state, so concurrent requests authenticated as different principals do
not share identity. Older servers may omit `workspace`; the UI then keeps documents in the current
session and does not read or write a tenant persistence namespace.

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
| `ravenroot.human-task.max-confirmation-prompt-bytes` / `RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_PROMPT_BYTES` | 4,096 | 1–65,536 | UTF-8 bytes in a built-in confirmation prompt |
| `ravenroot.human-task.max-confirmation-action-label-bytes` / `RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_ACTION_LABEL_BYTES` | 64 | 1–256 | UTF-8 bytes in one built-in action label |
| `ravenroot.human-task.max-decision-comment-bytes` / `RAVENROOT_HUMAN_TASK_MAX_DECISION_COMMENT_BYTES` | 4,096 | 1–16,384 | UTF-8 bytes in separately stored decision metadata |
| `ravenroot.human-task.attention-poll-millis` / `RAVENROOT_HUMAN_TASK_ATTENTION_POLL_MILLIS` | 1,000 | 250–300,000 | client attention refresh base interval |
| `ravenroot.human-task.attention-poll-backoff-max-millis` / `RAVENROOT_HUMAN_TASK_ATTENTION_POLL_BACKOFF_MAX_MILLIS` | 10,000 | 250–300,000; no lower than `attention-poll-millis` | client attention refresh backoff ceiling |
| `ravenroot.human-task.default-attention-page-size` / `RAVENROOT_HUMAN_TASK_DEFAULT_ATTENTION_PAGE_SIZE` | 20 | 1–100; no greater than `max-attention-page-size` | default embedded-attention page size |
| `ravenroot.human-task.max-attention-page-size` / `RAVENROOT_HUMAN_TASK_MAX_ATTENTION_PAGE_SIZE` | 100 | 1–100 | largest embedded-attention page |

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

| Variable | Type and default |
|---|---|
| `RAVENROOT_BIND_ADDRESS` | IP literal or `localhost`; `127.0.0.1` |
| `RAVENROOT_AUTH_MODE` | `disabled`, `local-token`, or `oidc`; absent selects `disabled` only on loopback and otherwise refuses startup |
| `RAVENROOT_AUTH_LOCAL_TOKEN` | secret string required by `local-token`; at least 32 characters |
| `RAVENROOT_AUTH_ISSUER`, `RAVENROOT_AUTH_AUDIENCE`, `RAVENROOT_AUTH_JWKS_URI` | required nonblank OIDC values in `oidc` mode |
| `RAVENROOT_AUTH_PRINCIPAL_TYPE_CLAIM` | claim name; `token_kind` |
| `RAVENROOT_AUTH_CLOCK_SKEW_SECONDS`, `RAVENROOT_AUTH_JWKS_CACHE_SECONDS` | whole seconds; `30`, `300` |
| `RAVENROOT_BROWSER_ALLOWED_ORIGINS` | comma-separated exact origins; defaults to loopback `http://127.0.0.1:<port>` and `http://localhost:<port>` |
| `RAVENROOT_CONTAINER_LOOPBACK_ONLY`, `RAVENROOT_LOCAL_HOST_BIND_ADDRESS` | explicit container-proxy proof; `false` and absent. The latter must be exactly `127.0.0.1` when used. |
| `RAVENROOT_TRUSTED_TLS_TERMINATOR` | strict Boolean; `false` |
| `RAVENROOT_PUBLIC_ORIGIN` | exact HTTPS origin required only with a trusted TLS terminator; blank |
| `RAVENROOT_UI_CONNECT_ORIGINS` | comma-separated exact additional UI connect origins; blank |
| `RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS` | positive whole seconds; `30` |

## Programmable artifacts

| Setting | Default | Safety behavior |
|---|---|---|
| `RAVENROOT_PROGRAM_RUNTIME` | `graalvm` | `graalvm` or `disabled`; other values refuse startup |
| `RAVENROOT_GRAAL_SANDBOX_SUPERVISOR` | unset | Program execution refuses without an absolute usable supervisor |
| `RAVENROOT_GRAAL_JAVA` | current Java launcher | Operator-pinned worker launcher |
| `RAVENROOT_GRAAL_RESOURCE_CACHE_DIR` | `/opt/ravenroot/data/cache` | Worker resource-cache directory |
| `RAVENROOT_PROGRAM_TIMEOUT_MS` | `5000` (100–300000) | Supervisor terminates the bounded attempt |
| `RAVENROOT_PROGRAM_MAX_HEAP_MB` | `64` (32–1024) | Supervisor enforces the memory budget |
| `RAVENROOT_ARTIFACT_STORE_DIR` | `/opt/ravenroot/data/artifact-store` | Durable artifact lifecycle store |
| `RAVENROOT_ARTIFACT_DUAL_CONTROL` | strict Boolean `false` | `true` requires a second approval authority |
| `RAVENROOT_ARTIFACT_PROVENANCE` | `refusing` | `unverified` is an explicit unsafe-development opt-out; other values refuse |

The Java baseline for `RAVENROOT_PROGRAM_TIMEOUT_MS` is `5000` ms. The Helm chart deliberately sets
`programTimeoutMs: 15000` as its F30 cold-start bridge, while Compose uses a `30000` ms local-development
profile. Helm validates configured integers from `100` through `300000`; an explicit blank Helm overlay
delegates to the Java baseline. The program deadline participates in the execution compatibility
fingerprint, so retain `15000` explicitly when work must keep the Helm profile rather than assuming a
changed deadline can resume it.

Allowed hosts and allowed agent tools are operator allowlists. Empty or absent privileged configuration does not expand access.

## Agent authority and budgets

The packaged server composes finite process-rooted agent accounting whenever its execution store
advertises `AGENT_AUTHORITY_BUDGETS`. The baseline rate card is explicit and conservative rather than
treating unknown pricing as free. Operators can pin a different finite policy with these variables:

| Variable family | Baseline default |
|---|---|
| `RAVENROOT_AGENT_RUNTIME_INSTANCE`, `RAVENROOT_AGENT_POLICY_VERSION` | `ravenroot-server`, `server-finite-v1` |
| `RAVENROOT_AGENT_RATE_CARD_VERSION`, `RAVENROOT_AGENT_COST_CURRENCY` | `builtin-conservative-v1`, `USD` |
| `RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS` | `3600` |
| `RAVENROOT_AGENT_MAX_TURNS`, `RAVENROOT_AGENT_MAX_INPUT_TOKENS`, `RAVENROOT_AGENT_MAX_OUTPUT_TOKENS` | `1024`, `20000000`, `2000000` |
| `RAVENROOT_AGENT_MAX_ELAPSED_MILLIS`, `RAVENROOT_AGENT_MAX_COST_MICROS`, `RAVENROOT_AGENT_MAX_TOOL_CALLS` | `3600000`, `100000000`, `4096` |
| `RAVENROOT_AGENT_MAX_DELEGATION_DEPTH`, `RAVENROOT_AGENT_MAX_TEAM_CUMULATIVE`, `RAVENROOT_AGENT_MAX_TEAM_ACTIVE` | `8`, `64`, `16` |
| `RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN`, `RAVENROOT_AGENT_MAX_OUTPUT_TOKENS_PER_TURN` | `128000`, `32000` |
| `RAVENROOT_AGENT_INPUT_TOKEN_RATE_MICROS`, `RAVENROOT_AGENT_OUTPUT_TOKEN_RATE_MICROS` | `10`, `30` |
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

## HTTP rate and representation limits

The server resolves this immutable policy once at startup. An absent or Java-whitespace-only value
uses the Java-owned default. A nonblank value must be a positive integer; malformed values and invalid
sibling relationships refuse startup. Compose and raw Kubernetes carry quoted strings. Helm accepts
canonical YAML integers or Java-blank strings and carries no numerical defaults.

| Variable | Default | Scalar range and unit | Boundary or relationship |
|---|---:|---|---|
| `RAVENROOT_RATELIMIT_ADDRESS_RPS` | `20` | 1–1,000,000 requests/s | one pre-authentication address key: one IPv4 address or IPv6 /64; maximum is the largest rate with a valid burst |
| `RAVENROOT_RATELIMIT_ADDRESS_BURST` | `120` | 1–1,000,000 requests | must be at least address RPS |
| `RAVENROOT_RATELIMIT_TENANT_RPS` | `50` | 1–1,000,000 requests/s | one authenticated tenant; maximum is the largest rate with a valid burst |
| `RAVENROOT_RATELIMIT_TENANT_BURST` | `200` | 1–1,000,000 requests | must be at least tenant RPS |
| `RAVENROOT_RATELIMIT_PRINCIPAL_RPS` | `20` | 1–1,000,000 requests/s | one principal within a tenant; maximum is the largest rate with a valid burst |
| `RAVENROOT_RATELIMIT_PRINCIPAL_BURST` | `80` | 1–1,000,000 requests | must be at least principal RPS |
| `RAVENROOT_RATELIMIT_SUBMISSION_RPS` | `2` | 1–1,000,000 submissions/s | execution submissions per tenant; maximum is the largest rate with a valid burst |
| `RAVENROOT_RATELIMIT_SUBMISSION_BURST` | `10` | 1–1,000,000 submissions | must be at least submission RPS |
| `RAVENROOT_RATELIMIT_TENANT_CONCURRENT_SUBMISSIONS` | `4` | 1–2,147,483,647 submissions | in-flight submissions for one tenant |
| `RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS` | `64` | 1–2,147,483,647 executions | process-wide active-execution ceiling |
| `RAVENROOT_RATELIMIT_TENANT_STREAMS` | `16` | 1–2,147,483,647 streams | concurrent SSE streams for one tenant |
| `RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS` | `4` | 1–2,147,483,647 streams | cannot exceed tenant streams |
| `RAVENROOT_SSE_QUEUE_CAPACITY` | `256` | 1–2,147,483,647 events | buffered events per stream before a slow consumer is dropped |
| `RAVENROOT_RATELIMIT_MAX_QUERY_BYTES` | `4096` | 1–2,147,483,647 Java string code units | raw query string length; the current limiter uses `String.length()`, despite the variable's historical `BYTES` name |
| `RAVENROOT_RATELIMIT_MAX_QUERY_PARAMETERS` | `64` | 1–2,147,483,647 segments | one for a nonempty raw query plus each `&` separator |
| `RAVENROOT_RATELIMIT_MAX_HEADER_COUNT` | `64` | 1–2,147,483,647 headers | request header count |
| `RAVENROOT_RATELIMIT_MAX_HEADER_BYTES` | `16384` | 1–2,147,483,647 Java string code units | sum of each header name and value's `String.length()`; the variable's historical `BYTES` name does not imply UTF-8 measurement |
| `RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES` | `8192` | 1–2,147,483,647 Java string code units | one value's `String.length()`; cannot exceed the total header representation budget |
| `RAVENROOT_RATELIMIT_MAX_TRACKED_CLIENTS` | `10000` | 1–2,147,483,647 entries | retained per-address limiter identities |
| `RAVENROOT_RATELIMIT_MAX_TRACKED_TENANTS` | `1000` | 1–2,147,483,647 entries | retained per-tenant limiter identities |
| `RAVENROOT_RATELIMIT_MAX_TRACKED_PRINCIPALS` | `10000` | 1–2,147,483,647 entries | retained per-principal limiter identities |
| `RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS` | `60` | 1–3,600 seconds | idle limiter-state retention |
| `RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS` | `3600` | 1–86,400 seconds | active-execution accounting retention |

The Helm Draft-07 schema enforces each scalar range. It cannot compare sibling fields, so the four
burst/rate relationships, principal-stream/tenant-stream relationship, and single-value/total-header
relationship are checked by `RateLimitConfiguration` when the server starts.

| Variables | Defaults | Boundary |
|---|---|---|
| `RAVENROOT_TRUSTED_PROXY_HOPS`, `RAVENROOT_TRUSTED_PROXY_ADDRESSES` | `0`, empty | exact trusted suffix length (0–32) and IP-literal peers; both must be configured together |

## Server process and readiness

| Variable | Type and default |
|---|---|
| `RAVENROOT_PORT` | integer TCP port; `8080` |
| `RAVENROOT_UI_DIR` | optional static UI directory; blank uses packaged resources |
| `RAVENROOT_MAX_ACTIVE_DEPLOYMENTS` | non-negative integer; `8`; zero refuses every long-lived activation |
| `RAVENROOT_READY_STORE_CHECK_TIMEOUT_MS` | positive milliseconds; `500` |
| `RAVENROOT_READY_DRAIN_GRACE_MS` | non-negative milliseconds; `6000` |
| `RAVENROOT_SERVER_STOP_DELAY_SECONDS` | non-negative whole seconds; `10` |

These values are immutable for a process. Raising the active-deployment cap also requires reviewing
the deployment termination-grace formula in the persistence lifecycle runbook.

## Tool and approval policy

| Variable | Type and default |
|---|---|
| `RAVENROOT_ALLOWED_TOOLS` | comma-separated built-in tool allowlist; empty denies all |
| `RAVENROOT_TOOL_APPROVAL_TTL_SECONDS` | whole seconds 1–86400; `300` |
| `RAVENROOT_TOOL_APPROVAL_REQUESTER_MAY_APPROVE` | strict Boolean; `false` |
| `RAVENROOT_TOOL_APPROVAL_REQUIRED_ROLES` | comma-separated roles; `APPROVER` |
| `RAVENROOT_TOOL_APPROVAL_REQUIRED_SCOPES` | comma-separated scopes; empty |
| `RAVENROOT_TOOL_POLICY_VERSION` | policy identifier; `environment-v1` |
| `RAVENROOT_TOOL_APPROVAL_RECOVERY_TENANTS` | comma-separated bounded tenant IDs; `local` |

Recovery interval, lease, and batch are fixed by this baseline at 1 second, 30 seconds, and 32. A
missing policy or approval service denies rather than approving by default.

## Observability

| Variable | Type and default |
|---|---|
| `RAVENROOT_OTEL_ENABLED` | Boolean-like; only case-insensitive `true` enables, otherwise disabled |
| `RAVENROOT_OTEL_EXPORTER` | `logging` or `otlp`; `logging` when enabled |
| `RAVENROOT_OTEL_ENDPOINT` | nonblank OTLP endpoint required only for `otlp` |
| `RAVENROOT_OTEL_SERVICE_NAME` | string; `ravenroot` |

Telemetry changes require restart. Disabled mode constructs no exporter or meter and ignores exporter
and endpoint settings.

## Embedded viewer

| Variable | Type and default |
|---|---|
| `RAVENROOT_EMBED_ENABLED` | strict Boolean; `false` |
| `RAVENROOT_EMBED_VIEWER_ORIGIN` | required exact origin when enabled |
| `RAVENROOT_EMBED_TICKET_TTL_SECONDS`, `RAVENROOT_EMBED_EXCHANGE_TTL_SECONDS` | positive seconds; `60`, `60` |
| `RAVENROOT_EMBED_BEARER_TTL_SECONDS`, `RAVENROOT_EMBED_PROOF_TTL_SECONDS` | positive seconds; `120`, `60` |
| `RAVENROOT_EMBED_TICKET_CAPACITY`, `RAVENROOT_EMBED_SESSION_CAPACITY`, `RAVENROOT_EMBED_REPLAY_CAPACITY` | positive integers; `4096`, `4096`, `16384` |
| `RAVENROOT_REPLICAS` | positive integer; `1` |
| `RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED` | strict Boolean; `false`; must be `true` to enable the process-local stores explicitly |
| `RAVENROOT_EMBED_REGISTRATION_DIR` | directory for the registration database; required when enabled, no default; see [embed operations](../operator-guide/embed-operations.md) |

The embed contract is disabled by default and all changes require restart or recreation. Registration
records persist in their store; tickets, exchanges, proofs, replay entries, and bearer sessions are
process-local as detailed in [Embed and extension contracts](embed-extension-contracts.md).

## Secret handling

API keys and tokens never belong in GraphML. Credential POST writes secret material to the configured backend and returns a server-minted reference; reads return metadata, never the secret value.

| Variable or family | Type and default |
|---|---|
| `RAVENROOT_CREDENTIAL_DIR` | user credential database directory; `./data/credentials` |
| `RAVENROOT_CREDENTIAL_<REFERENCE_UTF8_HEX>` | legacy operator secret family for exact opaque references; absent means unavailable |
| `RAVENROOT_HTTP_ALLOWED_HOSTS` | comma-separated exact outbound hosts; empty denies every host |
| `RAVENROOT_HTTP_ALLOWED_PORTS` | comma-separated ports; blank selects the bounded default `80,443` |
| `RAVENROOT_HTTP_MAX_REQUEST_BYTES` | request-body byte ceiling; blank, zero, or negative selects 1 MiB (`1048576`) |
| `RAVENROOT_HTTP_MAX_RESPONSE_BYTES` | response-body byte ceiling; blank, zero, or negative selects 8 MiB (`8388608`) |
| `RAVENROOT_EGRESS_RESERVED_EXCEPTIONS` | comma-separated reviewed reserved-network exceptions; empty |
| `RAVENROOT_TOKEN` | remote CLI bearer token when `--token-file` is absent; no default |

Environment credentials are startup configuration. User credentials stored through the governed API
persist in the credential database and can rotate without rebuilding GraphML. See
[Credentials, connectors, and egress](../operator-guide/credentials-egress.md).

## Persistence paths

| Variable | Type and default |
|---|---|
| `RAVENROOT_EXECUTION_STORE_ENABLED` | enabled unless set to `false`, `off`, `0`, or `no` |
| `RAVENROOT_EXECUTION_STORE_DIR` | execution database directory; `./data/execution-store` |
| `RAVENROOT_AUDIT_DIR` | tamper-evident audit directory; `./data/audit` |
| `RAVENROOT_CREDENTIAL_DIR` | credential database directory; `./data/credentials` |
| `RAVENROOT_ARTIFACT_STORE_DIR` | artifact database directory; `/opt/ravenroot/data/artifact-store` |

Directory changes require restart and do not migrate existing data. Stop the service before offline
backup or restore and use the [persistence lifecycle](../operator-guide/persistence-lifecycle.md).

## Plugins and authoring assistant

`RAVENROOT_ENABLED_PLUGINS` is a comma-separated set of exact installed manifest IDs. Unset or blank
activates none. Changing bundle bytes requires an image rebuild; changing the allowlist requires a
restart or container recreation. See the [bundle lifecycle](../operator-guide/plugin-bundles.md) and
[first-party bundle reference](bundles/).

`RAVENROOT_NODE_PACKAGES` is the separate classpath-extension mechanism: a comma-separated list of
fully qualified `NodePackage` implementation class names already installed on the application
classpath. Unset or blank loads none. A missing class, non-package class, inaccessible no-argument
constructor, duplicate node ID, incompatible contract, or unavailable required service refuses
startup. It does not discover Maven modules and does not install or enable plugin bundles. Change it
only with the matching classpath deployment, then restart or recreate the application.

`RAVENROOT_PLUGINS_INSTALL_DIR` selects the installed bundle directory and defaults to
`/opt/ravenroot/plugins`. `RAVENROOT_NODE_PACKAGE_SERVICES_<PACKAGE_KEY>` is the strict dynamic family
that grants a package's declared services; absent or blank grants none. `PACKAGE_KEY` is the uppercase
hex encoding of the package ID's UTF-8 bytes. The value is canonical Base64 of strict JSON with a
nonempty `capabilities` array. Supported capability names are `credential-resolution`,
`outbound-http`, `outbound-websocket`, `tool-authorization`, and `agent-resources`. The optional JSON
members are `origins`, `httpMethods`, `requestHeaders`, `responseHeaders`,
`webSocketSubprotocols`, `credentialBindings`, `awsSigV4Bindings`, `credentialReferences`, and
`limits`; unknown members and malformed, noncanonical, or empty grants refuse startup. Exact schema,
encoding commands, package recipes, and Compose propagation are in the
[bundle lifecycle](../operator-guide/plugin-bundles.md#grant-required-runtime-services). Both settings
are startup-only.

The `RAVENROOT_ASSISTANT_*` family configures the workspace authoring assistant. It is independent of
the optional `llm-prompt` and `agent` graph nodes and their `RAVENROOT_LLM_PROFILE_*` family. Exact
types, defaults, persistence, restart behavior, local-model procedure, and failure diagnosis are in
[Configure the authoring assistant](../operator-guide/authoring-assistant.md).

For deployment procedure see [Deployment and startup](../operator-guide/deployment-startup.md). For refusal symptoms see [Startup and readiness](../troubleshooting/startup-readiness.md).
