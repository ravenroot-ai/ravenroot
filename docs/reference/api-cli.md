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
| `GET /v1/executions/live` | Current non-terminal executions, from process-local runtime state; each row's `paused` field distinguishes a deliberate hold from an ordinary running execution |
| `GET /v1/executions/{id}` | State or terminal result; `paused` distinguishes a held execution from an ordinary running one, always `false` once terminal. `terminationReason` and `cancelled` qualify a terminal `status` the same way `paused` qualifies `RUNNING` — always present (`null`/`false` when nothing distinguishes the termination), and carried on both the `200` body and the `410` body returned once a result has aged past retention. A cancelled execution reports `status=FAILED`; read `terminationReason` beside it, never `status` alone |
| `GET /v1/executions/inventory` | One page of the tenant's durable process inventory, read from storage and surviving a restart. Three optional query parameters are named exactly like the field each response row carries: `status`, `ownerWorkerId`, `deploymentId`. Three describe the page instead: `includeTerminal` (excluded by default) has no response counterpart, `limit` is bounded by `maxPageSize`, and `cursor` takes a previous page's `nextCursor`. A parameter outside that set is refused as `400 INVALID_REQUEST`, and so is a recognised name carrying a blank value, rather than either being silently dropped. Each row carries `terminationReason` and `cancelled` beside `status`, for the same reason and with the same always-present convention as `GET /v1/executions/{id}`. The response always carries `retainedFrom` and `maxPageSize` (this deployment's declared page-size bound). `501 PROCESS_INVENTORY_UNAVAILABLE` when no durable inventory-capable store is composed |
| `GET /v1/executions/{id}/traversals` | The durable inventory's traversals for one process instance, alongside the same tenant's `retainedFrom` that the inventory listing carries. Each traversal row also carries `terminationReason` and `cancelled` beside `status`. **`{id}` here is a `processInstanceId`, not the execution/traversal ID every other `/v1/executions` route below takes** — see the callout after this table. `404 UNKNOWN_PROCESS_INSTANCE` when the instance is absent, belongs to another tenant, or aged past its terminal-retention window; `501 PROCESS_INVENTORY_UNAVAILABLE` when no durable inventory-capable store is composed |
| `GET /v1/executions/{id}/manifest` | The identity of the dependency set one process instance was accepted against, and whether this deployment still resolves it. **`{id}` here is a `processInstanceId`**, for the same reason the `/traversals` route above takes one. Reports `manifestFormatVersion`, `manifestDigest`, the pinned `graphVersion`, `graphId`, `graphVersionId`, `pinnedAt`, a `compatible` verdict, an `incompatibleDimensions` list of dimension names and `dimensionsTruncated`. It reports no value from the pinned dependency set and no count of one: no capability sets, no limits, no node-package identity and no package count, because those describe the deployment rather than the caller's execution. The comparison's own values stay in the server-side diagnostic a refused recovery raises. `404 UNKNOWN_PROCESS_INSTANCE` when no record is pinned for the instance, when it belongs to another tenant, and when it was accepted before this deployment began recording them, all three indistinguishable; `501 PROCESS_INVENTORY_UNAVAILABLE` when no durable record store is composed, or when the stored record no longer verifies |
| `POST /v1/executions/{id}/cancel` | Cancellation request |
| `POST /v1/executions/{id}/pause` | Pause after in-flight work |
| `POST /v1/executions/{id}/resume` | Resume dispatch |
| `GET /v1/events` | Live SSE |
| `GET /v1/events/recent` | Cursor-based retained events |

> **`{id}` names two different things on adjacent routes.** `GET /v1/executions/{id}` and the cancel/pause/resume trio all take an execution ID, which is a traversal ID. `GET /v1/executions/{id}/traversals` and `GET /v1/executions/{id}/manifest` are the exceptions: their `{id}` is a **process instance ID**, because a process instance can contain more than one traversal and a traversal ID could not address "this instance's traversals" at all, and because a manifest is pinned once per process instance. The two ID spaces are both UUIDs and are not interchangeable — passing a traversal ID to either route returns `404 UNKNOWN_PROCESS_INSTANCE`, indistinguishable from an ID that never existed.

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

`GET /v1/executions/{id}` returns `visitedNodes` as unique membership, not a path or timeline. The
`ravenroot result` command presents the same membership as `visited-nodes=`. HTTP and CLI currently
sort node identifiers for deterministic presentation, but that lexical order is not visit order and
must not be interpreted as one. Use invocation or event history when chronology or repeated visits
matter.

`ravenroot result` prints `termination-reason=` only when the terminal status is qualified — the
common case leaves nothing to act on, matching the convention this command already uses for
`defaulted-nodes=`, `bypassed-nodes=`, and `handled-failure=`. `ravenroot inventory` and
`ravenroot traversals` print `termination-reason=` unconditionally on every row, matching each
command's own existing convention of always printing `disposition=` and the other row fields. On every
one of these, a cancelled execution or instance prints `status=FAILED`; `termination-reason=CANCELLED`
is the line that tells it apart from an ordinary failure, and it must be read beside `status=`, never
in place of it.

See [Application and HTTP integration](../integrator-guide/application-http.md) and [Authentication troubleshooting](../troubleshooting/identity-browser.md).
