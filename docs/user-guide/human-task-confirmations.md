# Human Task confirmations

Use a versioned confirmation presentation when a graph needs a person to make a small, explicit
decision inside the Ravenroot workbench. The prompt is static text, and the server keeps the
presentation, task identity, generation, timers, and decision state with the durable execution.

## Author a confirmation

1. Add a **Human task** node from the server catalog and select **Confirmation presentation 1**.
2. Write a bounded prompt and choose whether a comment is disallowed, optional, or required.
3. Keep only the permitted actions in their intended order: **Resolve**, **Deny**, and **Cancel**.
   Give each retained action a short display label. Labels change the button text, not the action.
4. Save the node and run the graph. The catalog supplies the active deployment's defaults and text
   limits. The built-in confirmation response is produced by the server when Resolve succeeds; it
   does not turn the comment into execution payload.

Prompt, action-label, and comment limits are measured in UTF-8 bytes. A decision comment can span
lines; Ravenroot removes outer whitespace before applying the task's pinned comment rule and limit.
Those pinned limits continue to govern an existing task if an operator later tightens the current
server policy, so a restart cannot make its saved presentation unreadable or silently shorten its
comment field.

Selecting version 1 on a newly catalog-created node also selects the built-in confirmation response
contract. A custom response media type, schema, version, or shape is incompatible with this
presentation and is refused before execution. Leaving the presentation version empty preserves the
classic Human Task behavior.

If the catalog does not advertise confirmation presentation 1, the connected service cannot host
this interaction. Choose a capable deployment before authoring or running it; Ravenroot reports the
missing capability in the catalog and preflight rather than starting a graph that cannot present the
task.

## Choose the active execution context

A Run binds attention to the graph version and process returned by the service. For a registered
deployment, open **Deployments** and choose **Use for active graph tasks** on the exact deployment.
The server-reported graph version and deployment identifier become the context. Ravenroot does not
derive a deployment from a browser session identifier or combine tasks from different deployments.

## Decide an actionable task

An outlined node with the flag symbol and a count has actionable Human Tasks. An escalated task also
uses a triangle marker, stronger outline, and the word **Escalated**. The halo pulses unless the
system requests reduced motion; every meaning remains visible when the animation is still or colour
cannot be distinguished.

1. Select the node normally. Selection opens the ordinary Inspector and never chooses a task.
2. Read the actionable count and the bounded page of tasks for that exact node. Each row identifies
   the task and generation, process, traversal, optional deployment, creation time, expiry, and
   escalation state. The list never displays execution payloads, responses, continuations, secrets,
   or prior comments.
3. Choose one row to open the central decision dialog. Review the static prompt and exact identity,
   enter the separate comment when allowed, then choose one server-authorized action.
4. Wait for the authoritative result. A successful decision closes the dialog and refreshes the
   list and graph counts. The marker disappears only after the last task becomes terminal.

Task rows are buttons: move focus to a row and press **Enter** or **Space**. Focus enters the dialog,
**Escape** closes it, and focus returns to the current task row or task status after reconciliation.
Counts and errors are announced through live status regions. Paging, refresh, and every decision are
available without a pointer.

## Reconcile interruption and races

Ravenroot polls at the interval and bounded backoff advertised by the server. A connection error
keeps the task undecided and shows a reconnect state. Refresh the authoritative list after the
connection returns. If a decision request loses its response, Ravenroot does not submit it again:
the same request may already have committed, so the client refetches state first.

Stale generation, expiry, takeover, cancellation, and authorization changes also reconcile from the
server. A stale or terminal task is replaced or removed from the list; an authorization failure
reveals no cross-tenant task details. The browser may retain only the selected service origin, task
identifier, and generation. After a reload or server restart, re-authenticate and Ravenroot uses
that locator to fetch the task's durable presentation and current state. It never reconstructs the
row from browser-cached task data.

This release provides the confirmation inside the main workbench connected to the embedded or
single-server runtime, including SQLite-backed restart recovery. External approval pages, redirects,
callbacks, CAPTCHAs, arbitrary URLs, and third-party signing flows are outside this interaction.

- [Durable Human Task contract](../reference/human-tasks.md)
- [Test, Run, and execution control](test-run-observe.md)
- [Persistence, lifecycle, and recovery](../operator-guide/persistence-lifecycle.md)
