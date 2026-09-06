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

All values below are positive integers unless a row states otherwise. Blank uses the default; malformed
or invalid relationships refuse startup. Burst values must be at least their sustained rate and at
most 1,000,000.

| Variables | Defaults | Boundary |
|---|---|---|
| `RAVENROOT_RATELIMIT_ADDRESS_RPS`, `RAVENROOT_RATELIMIT_ADDRESS_BURST` | `20`, `120` | one client address before authentication |
| `RAVENROOT_RATELIMIT_TENANT_RPS`, `RAVENROOT_RATELIMIT_TENANT_BURST` | `50`, `200` | one authenticated tenant |
| `RAVENROOT_RATELIMIT_PRINCIPAL_RPS`, `RAVENROOT_RATELIMIT_PRINCIPAL_BURST` | `20`, `80` | one principal within a tenant |
| `RAVENROOT_RATELIMIT_SUBMISSION_RPS`, `RAVENROOT_RATELIMIT_SUBMISSION_BURST` | `2`, `10` | execution submissions per tenant |
| `RAVENROOT_RATELIMIT_TENANT_CONCURRENT_SUBMISSIONS`, `RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS` | `4`, `64` | in-flight submissions and process-wide executions |
| `RAVENROOT_RATELIMIT_TENANT_STREAMS`, `RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS` | `16`, `4` | concurrent SSE streams; principal cannot exceed tenant |
| `RAVENROOT_SSE_QUEUE_CAPACITY` | `256` | buffered events per stream |
| `RAVENROOT_RATELIMIT_MAX_QUERY_BYTES`, `RAVENROOT_RATELIMIT_MAX_QUERY_PARAMETERS` | `4096`, `64` | raw query representation |
| `RAVENROOT_RATELIMIT_MAX_HEADER_COUNT`, `RAVENROOT_RATELIMIT_MAX_HEADER_BYTES`, `RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES` | `64`, `16384`, `8192` | header representation; one value cannot exceed the total |
| `RAVENROOT_RATELIMIT_MAX_TRACKED_CLIENTS`, `RAVENROOT_RATELIMIT_MAX_TRACKED_TENANTS`, `RAVENROOT_RATELIMIT_MAX_TRACKED_PRINCIPALS` | `10000`, `1000`, `10000` | retained limiter identities |
| `RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS` | `60` (1–3600) | idle limiter-state retention |
| `RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS` | `3600` (1–86400) | active-execution accounting retention |
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
| `RAVENROOT_HTTP_ALLOWED_HOSTS`, `RAVENROOT_HTTP_ALLOWED_PORTS` | comma-separated exact outbound allowlists; empty denies |
| `RAVENROOT_HTTP_MAX_REQUEST_BYTES`, `RAVENROOT_HTTP_MAX_RESPONSE_BYTES` | non-negative byte ceilings; blank delegates to the managed policy's bounded default |
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
that grants a package's declared services; absent grants none. Each package reference names its exact
capabilities and required services. Both are startup-only settings.

The `RAVENROOT_ASSISTANT_*` family configures the workspace authoring assistant. It is independent of
the optional `llm-prompt` and `agent` graph nodes and their `RAVENROOT_LLM_PROFILE_*` family. Exact
types, defaults, persistence, restart behavior, local-model procedure, and failure diagnosis are in
[Configure the authoring assistant](../operator-guide/authoring-assistant.md).

For deployment procedure see [Deployment and startup](../operator-guide/deployment-startup.md). For refusal symptoms see [Startup and readiness](../troubleshooting/startup-readiness.md).
