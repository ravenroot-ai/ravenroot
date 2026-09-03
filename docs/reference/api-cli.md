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
| `GET /v1/executions/live` | Current non-terminal executions, from process-local runtime state |
| `GET /v1/executions/{id}` | State or terminal result |
| `GET /v1/executions/inventory` | One page of the tenant's durable process inventory, read from storage and surviving a restart. Optional query parameters, each named exactly like the field the response carries: `status`, `ownerWorkerId`, `deploymentId`, `includeTerminal` (excluded by default), `limit`, `cursor`. A parameter outside that set is refused as `400 INVALID_REQUEST` rather than silently dropped. The response always carries `retainedFrom` and `maxPageSize` (this deployment's declared page-size bound). `501 PROCESS_INVENTORY_UNAVAILABLE` when no durable inventory-capable store is composed |
| `GET /v1/executions/{id}/traversals` | The durable inventory's traversals for one process instance, alongside the same tenant's `retainedFrom` that the inventory listing carries. **`{id}` here is a `processInstanceId`, not the execution/traversal ID every other `/v1/executions` route below takes** — see the callout after this table. `404 UNKNOWN_PROCESS_INSTANCE` when the instance is absent, belongs to another tenant, or aged past its terminal-retention window; `501 PROCESS_INVENTORY_UNAVAILABLE` when no durable inventory-capable store is composed |
| `POST /v1/executions/{id}/cancel` | Cancellation request |
| `POST /v1/executions/{id}/pause` | Pause after in-flight work |
| `POST /v1/executions/{id}/resume` | Resume dispatch |
| `GET /v1/events` | Live SSE |
| `GET /v1/events/recent` | Cursor-based retained events |

> **`{id}` names two different things on adjacent routes.** `GET /v1/executions/{id}` and the cancel/pause/resume trio all take an execution ID, which is a traversal ID. `GET /v1/executions/{id}/traversals` is the one exception: its `{id}` is a **process instance ID**, because a process instance can contain more than one traversal and a traversal ID could not address "this instance's traversals" at all. The two ID spaces are both UUIDs and are not interchangeable — passing a traversal ID to the `/traversals` route returns `404 UNKNOWN_PROCESS_INSTANCE`, indistinguishable from an ID that never existed.

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
| `ravenroot inventory` | List the tenant's whole durable process inventory (`GET /v1/executions/inventory`, paged to completion internally — never a partial page); unfiltered, terminal rows **included** by default; a trailing `retained-from=` line always prints, even for an idle tenant |
| `ravenroot traversals PROCESS-INSTANCE-ID` | List one process instance's traversals from the durable inventory (`GET /v1/executions/{id}/traversals`), also followed by a trailing `retained-from=` line; the argument is a process instance ID, not the execution/traversal ID `cancel` and `result` take |
| `ravenroot-server` | Start the standalone service |

CLI validation exit codes are 0 accepted, 1 refused or invalid, and 2 misuse. Authentication and ownership checks are identical to HTTP because the CLI is a client, not a privileged bypass.

`ravenroot inventory` defaults to including terminal rows, the opposite of the bare HTTP route's own default. An operator running `inventory` right after `ravenroot run` would otherwise stop seeing the instance the moment it finished — the exact run the verb was just used to start. Filtering is not exposed as a CLI flag yet.

`ravenroot inventory` follows the HTTP route's own `nextCursor` internally until the tenant's whole answer is read, so its output is never a truncated first page; there is no `--after`-style flag because there is nothing left to continue. `ravenroot traversals` lists one instance's traversals directly — that listing is not paginated on either transport.

See [Application and HTTP integration](../integrator-guide/application-http.md) and [Authentication troubleshooting](../troubleshooting/identity-browser.md).
