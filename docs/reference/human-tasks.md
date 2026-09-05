# Durable human tasks

The core `human-task` node pauses one graph traversal at a decision that must be completed by a
person. The wait is stored, not kept alive: no node actor, request thread, polling loop, or join
remains attached to the task. A worker can therefore resolve the task and continue the same process
after a complete server restart.

## Authoring contract

The node publishes all of its fields through `GET /v1/node-types`, so the generic editor renders
labelled keyboard controls without a human-task-specific form. `title` is required and limited to
256 UTF-8 bytes; `description` is optional and limited to 4 KiB. Both are static graph-authored text.
They never interpolate the incoming payload or attributes, and the inbox never includes those
unrestricted execution values.

The response declaration consists of an exact media type, schema name, schema version, top-level
kind (`SCALAR`, `LIST`, or `MAP`), and a byte ceiling of at most 256 KiB. A resolving request must be
a Ravenroot `ravenroot.payload/1` envelope matching every part of that declaration. Responder roles
and scopes are comma-separated conjunctions: a responder must hold every declared token. The
original requester can cancel its own task even when it is not an authorized responder.

`expiresAfterSeconds` creates a durable expiry timer. A non-zero `escalateAfterSeconds` creates a
second durable timer that moves the task to `ESCALATED` while leaving it resolvable. Both delays are
bounded to 30 days and escalation must precede expiry. The four terminal dispositions select the
configured `resolvedOutcome`, `deniedOutcome`, `expiredOutcome`, or `cancelledOutcome`.

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

The node is always discoverable in the core catalog. It fails closed at execution when the host did
not compose a store with human-task, durable-handler, timer, and event-journal support.
