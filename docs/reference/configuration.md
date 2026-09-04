# Configuration and deployment defaults

Configuration is environment-owned. A graph cannot select an engine, authentication mode, browser origin, credential backend, adapter, sandbox, or egress policy.

## Runtime and behavior resolution

| Variable | Default | Accepted contract |
|---|---|---|
| `RAVENROOT_ENGINE` | `pekko` | `pekko`; `akka` when its adapter is installed |
| `RAVENROOT_UNKNOWN_BEHAVIOR` | `pass-through` | `pass-through` or `refuse` |
| `RAVENROOT_AUTH_MODE` | context-sensitive | omitted is allowed only on loopback; non-loopback refuses |

Unknown-behavior pass-through is observable in `defaultedNodes`; `refuse` rejects the unresolved graph.

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

The runtime kill service is not an unauthenticated HTTP control. It requires a `PLATFORM_ADMIN` with
the `agent:kill` scope and applies to the composed runtime instance across tenants; the request tenant
is audit identity, not target selection. Trip and reset never revive an already-issued grant or a
suspended approval permit. A restarted server uses a new boot epoch and re-evaluates fresh admissions.

## Secret handling

API keys and tokens never belong in GraphML. Credential POST writes secret material to the configured backend and returns a server-minted reference; reads return metadata, never the secret value.

For deployment procedure see [Deployment and startup](../operator-guide/deployment-startup.md). For refusal symptoms see [Startup and readiness](../troubleshooting/startup-readiness.md).
