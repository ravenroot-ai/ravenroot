# Durable human tasks

The core `human-task` node pauses one graph traversal at a decision that must be completed by a
person. The wait is stored, not kept alive: no node actor, request thread, polling loop, or join
remains attached to the task. A worker can therefore resolve the task and continue the same process
after a complete server restart.

## Authoring contract

The node publishes all of its fields through `GET /v1/node-types`, so the generic editor renders
labelled keyboard controls without a human-task-specific form. `title` is required and limited to
the deployment's operator policy; `description` is optional and uses the same policy. Both are static
graph-authored text. They never interpolate the incoming payload or attributes, and the inbox never
includes those unrestricted execution values.

The response declaration consists of an exact media type, schema name, schema version, top-level
kind (`SCALAR`, `LIST`, or `MAP`), and a byte ceiling bounded by the deployment's operator policy. A
resolving request must be a Ravenroot `ravenroot.payload/1` envelope matching every part of that
declaration. Responder roles and scopes are comma-separated conjunctions: a responder must hold every
declared token. The original requester can cancel its own task even when it is not an authorized
responder. Both schema name and schema version use the fixed ASCII alphanumeric `PayloadEnvelope`
token grammar plus `._-:+/` and its 128-unit cap. The operator setting may narrow the schema-name
budget but cannot widen it; schema version has the fixed protocol bound and no separate operator
setting.

An embedded simple confirmation is an opt-in versioned presentation of that same durable node. A
host publishes the `embedded-confirmation-v1` catalog capability and the `confirmation*` authoring
fields only when its store and current admission policy support the complete contract. Setting
`confirmationPresentationVersion=1` through the generic editor materializes the complete classic
placeholder response tuple:

```text
application/vnd.ravenroot.payload+json
ravenroot.human-task.response
1
MAP
<the active default maxResponseBytes>
```

At admission Ravenroot converts that exact tuple to the built-in response contract
`application/json`, `ravenroot.human-task.confirmation`, version `1`, kind `SCALAR`. Authors may
instead provide that built-in tuple explicitly. A partial tuple, custom schema, changed kind, or
changed response limit is refused before traversal. Omitting `confirmationPresentationVersion`
retains the classic raw-envelope behavior and its original defaults.

`confirmationPrompt` is bounded plain text. `confirmationComment` is `DISALLOWED`, `OPTIONAL`, or
`REQUIRED`. `confirmationActions` is an ordered comma-separated subset of `RESOLVE`, `DENY`, and
`CANCEL`; Ravenroot pins that authored order and the corresponding label properties. The task also
pins its prompt, action-label, and comment byte limits, so a tighter or looser policy after restart
does not reinterpret an existing decision.

The service applies this admission contract to every current task creation. Supported durable adapters
apply the same check only after exact deduplication, so an exact replay of an already accepted request
remains idempotent after a policy change. Pre-existing tasks with stored labels that do not satisfy the
current payload-envelope rule can still be listed, read, and cancelled. Resolving one is refused because
Ravenroot cannot create a compatible response envelope; this preserves the durable record and its
cancellation path without treating an unrepresentable label as a valid current wire contract.

`expiresAfterSeconds` creates a durable expiry timer. A non-zero `escalateAfterSeconds` creates a
second durable timer that moves the task to `ESCALATED` while leaving it resolvable. Both delays are
bounded by the deployment's operator policy and escalation must precede expiry. The four terminal
dispositions select the configured `resolvedOutcome`, `deniedOutcome`, `expiredOutcome`, or
`cancelledOutcome`.

## Operator policy

Set the Human Task policy before startup through the documented server properties or environment
variables. It covers graph defaults and ceilings, authorization and display metadata, the decision
HTTP body, inbox pagination, structured-response parsing, and durable write retries. A graph can only
narrow the configured response ceiling. The server pins resolved limits on registration, so a later
policy change does not change an existing durable task after recovery: this includes its separately
pinned raw-envelope decision-body cap, parser budgets, and write-retry budget. See [Configuration
and deployment defaults](configuration.md#human-task-operational-policy) for every setting, default,
range, precedence rule, and deployment mapping. Text and object-key parser budgets count UTF-16 code
units; all `*-bytes` settings remain UTF-8 byte budgets.

Correlation and deduplication are deliberately fixed. The task and handler share a deterministic
task ID derived from the original tenant, process, traversal, invocation, and attempt. The attempt
also supplies the deduplication identity. Retrying registration cannot create a second task.

## Inbox and decisions

`GET /v1/human-tasks` lists a bounded tenant-scoped page. Outstanding tasks are the default;
`includeTerminal=true` includes retained terminal tasks. `limit` is bounded by the store and
`cursor` is an exclusive task ID. Results expose task/process/node identity, bounded display and
response-schema metadata, status, generation, and timer instants. They do not expose the incoming
execution payload, a previous response, handler outcome bytes, credentials, or continuation state.

Decisions use `POST /v1/human-tasks/{taskId}/{resolve|deny|cancel}?generation=N`. Generation is
mandatory. A stale generation, duplicate terminal request, conflicting terminal request,
unauthorized principal, late timer, and cross-tenant or unknown ID each produce a deterministic
result. Unknown and cross-tenant IDs are indistinguishable. Only `resolve` accepts a body, using the
task's declared media type and bounded payload envelope.

Embedded confirmations have a separate attention projection. `GET /v1/configuration` advertises it
as `humanTasks` schema version `1`, including supported presentation versions, current authoring
limits, polling bounds, and the effective default and maximum attention page sizes. These values
remain present while the durable store can query and decide already pinned confirmations, even when
the current admission policy is too narrow to create another one. In that state the behavior catalog
omits the embedded confirmation capability and its authoring controls.

An aggregate attention query requires the exact `graphVersion` and exactly one of `deploymentId` or
`processInstanceId`:

```http
GET /v1/human-tasks/attention?graphVersion=<sha256>&deploymentId=release&limit=20
GET /v1/human-tasks/attention?graphVersion=<sha256>&processInstanceId=<uuid>&nodeId=approval&limit=20
```

Optional `traversalId`, `nodeId`, `taskId`, and `generation` fields narrow the selected durable
context. The response is `{schemaVersion,items,nextCursor,counts,nodeCounts}`. Counts cover every
authorized actionable match, independently of the current item page. Graph-level `nodeCounts` are
complete and ordered by node ID; a node-filtered query returns an empty `nodeCounts` array. The opaque
cursor carries its immutable `(createdAt,taskId)` boundary and is bound to the tenant, exact query,
actor, roles, and scopes. Deleting or settling a row therefore does not invalidate later boundaries,
while a context or authority change requires a fresh first page.

Browser restart recovery can use the smaller exact locator without cached graph or process context:

```http
GET /v1/human-tasks/attention?taskId=<uuid>&generation=1
```

The server authenticates and authorizes before projecting the durable row and derives its graph,
deployment, process, traversal, and node pins from storage. Unknown, cross-tenant, unauthorized,
stale, and terminal locators all return an empty actionable page. Exact and node-filtered responses
return `nodeCounts: []`, so they disclose no unrelated graph attention.

Each attention item carries only task and durable context identities, lifecycle and timer values,
the pinned presentation, its three pinned byte limits, and the actions currently available to the
caller. It omits request payload and attributes, requester and responder authority, response schema
and bytes, comments and actors, handler state, continuation bytes, and credentials.

The embedded decision route is:

```http
POST /v1/human-tasks/<taskId>/confirmation/resolve?generation=1
Content-Type: application/json; charset=utf-8

{"schemaVersion":1,"comment":"Reviewed by release operations"}
```

`deny` and `cancel` use the same strict two-field JSON object. Ravenroot authorizes the task before
applying its pinned body and comment limits. It trims the comment, rejects malformed Unicode and
unknown JSON fields, enforces the pinned comment rule, and stores the comment as a separate
attributable transition. `resolve` supplies a fixed server-authored boolean `true` payload envelope;
caller JSON never becomes the graph response.

A successful first decision returns HTTP 200 with
`{"schemaVersion":1,"outcome":"APPLIED","task":...}`. An exact retry with the same generation,
action, actor, and normalized comment returns HTTP 200 with `ALREADY_APPLIED`; changed action or
comment and a stale or conflicting generation return HTTP 409. An authorized malformed request or
presentation-rule violation returns HTTP 400. Unknown, cross-tenant, unauthorized, unsupported, and
non-embedded task IDs share the nondisclosing HTTP 404 response. The returned terminal task uses the
same safe projection and has no available actions.

A terminal decision atomically completes the old waiting invocation and traversal, updates the
first-class task, resolves its reserved `human-task` handler projection, cancels its timers, and
creates one fresh accepted traversal. Recovery loads the exact immutable graph version pinned at
registration, records a fresh synthetic completion for the human-task node without executing its
registration behavior again, and routes the selected outcome. Downstream payload contains the task
ID, generation, disposition, schema metadata, and—only for `RESOLVED`—the validated response value.
The traversal retains the original requester's execution identity; the responder is audit identity,
not replacement execution authority.

## What this is not

- **Pause** is an operator hold on one traversal, taken between two nodes. It is not a human inbox
  and creates no task: nobody is asked for anything, and nothing arrives. A hold taken at a boundary
  the runtime can write down outlives the process that took it and is continued by an authorized
  resume, which rebuilds the runtime from the pinned graph rather than reviving the traversal that
  was running; a hold taken anywhere else is process-local and is lost with its process. Either way
  the traversal keeps its own identity and its own requester, where a human task's re-entry is a
  fresh traversal of the same process instance.
- **Cancel** terminates a live traversal. Human-task cancellation is instead a declared graph
  disposition that starts the configured fresh re-entry route.
- **Drain** stops the server from accepting work while existing work settles.
- **Stop** tears down a deployment and its runtime resources.
- **Delay** is a bounded asynchronous in-process wait. It keeps no thread asleep, but it is not a
  durable external decision and does not provide an inbox, authorization, or response contract.

The classic node is always discoverable in the core catalog. A missing durable Human Task capability
or a manually supplied embedded presentation on an incapable host fails catalog admission before a
traversal, handler, timer, or task can be created.
