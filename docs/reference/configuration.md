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
| `RAVENROOT_GRAPHML_MAX_BYTES` | 10 MiB | 256 MiB | imported GraphML bytes |
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

A refusal exposes a closed code such as `GRAPH_LIMIT_FAN_OUT_EXCEEDED` or
`GRAPH_LIMIT_TRAVERSAL_STEPS_EXCEEDED`, never graph content or payload values.

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
