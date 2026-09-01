# HTTP API and CLI

The standalone server exposes JSON resources, GraphML inspection and submission, and SSE events under the same origin as the workspace.

## Service and graph resources

| Method and path | Result |
|---|---|
| `GET /health` | Process liveness |
| `GET /ready` | Traffic readiness |
| `GET /v1/status` | Service status |
| `GET /v1/runtime` | Selected engine and runtime capabilities |
| `GET /v1/node-types` | Effective node catalog |
| `POST /v1/graphs/inspect` | Validate and inspect GraphML without executing it |
| `POST /v1/drain` | Stop admission and drain accepted work |

## Execution and events

| Method and path | Result |
|---|---|
| `POST /v1/executions?mode=test\|run` | HTTP 202 plus execution ID |
| `GET /v1/executions/live` | Current non-terminal executions |
| `GET /v1/executions/{id}` | State or terminal result |
| `POST /v1/executions/{id}/cancel` | Cancellation request |
| `POST /v1/executions/{id}/pause` | Pause after in-flight work |
| `POST /v1/executions/{id}/resume` | Resume dispatch |
| `GET /v1/events` | Live SSE |
| `GET /v1/events/recent` | Cursor-based retained events |

## Governed resources

| Method and path | Contract |
|---|---|
| `GET, POST /v1/credentials` | List caller-owned metadata or write a secret and mint its reference |
| `GET, POST /v1/model-providers` | List owned profiles or create one |
| `POST /v1/model-providers/{id}/verify` | Verify adapter, credential, egress, and provider reachability |
| `GET, POST /v1/program-artifacts` | List owned artifacts or create one |
| `POST /v1/program-artifacts/{id}/validate` | Validate source under the runtime contract |
| `POST /v1/program-artifacts/{id}/test` | Execute the bounded test phase |
| `POST /v1/program-artifacts/{id}/approve` | Record approval authority |
| `POST /v1/program-artifacts/{id}/activate` | Make an approved artifact selectable |
| `POST /v1/program-artifacts/{id}/retire` | Withdraw an artifact from active use |
| `GET /v1/program-languages` | List effective program-language support |
| `GET /v1/assistant` | Read assistant status |
| `GET, POST, DELETE /v1/assistant/connection` | Read, establish, or remove the caller’s connection |
| `POST /v1/assistant/messages` | Submit a message through the established connection |
| `POST /v1/embed/sessions` | Create an authorized embedded session request |
| `POST /v1/embed/acknowledgements` | Record the browser acknowledgement |
| `GET /v1/embed/launch` | Serve the launch boundary |
| `POST /v1/embed/exchange` | Exchange a one-time launch value |
| `POST /v1/embed/projection` | Read the authorized projection |

Credential reads never return secret material. Governed resources remain scoped by caller ownership and role.

## CLI mapping

| Command | Purpose |
|---|---|
| `ravenroot status` | Read service status |
| `ravenroot node-types` | Read effective catalog |
| `ravenroot inspect FILE` | Inspect without effects |
| `ravenroot validate FILE` | Validate the GraphML profile |
| `ravenroot run FILE` | Execute with Run semantics |
| `ravenroot-server` | Start the standalone service |

CLI validation exit codes are 0 accepted, 1 refused or invalid, and 2 misuse. Authentication and ownership checks are identical to HTTP because the CLI is a client, not a privileged bypass.

See [Application and HTTP integration](../integrator-guide/application-http.md) and [Authentication troubleshooting](../troubleshooting/identity-browser.md).
