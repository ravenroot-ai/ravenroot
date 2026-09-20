# Human Task confirmations

Use a versioned confirmation presentation when a graph needs a person to make a small, explicit
decision inside the Ravenroot workbench. The prompt is static text, and the server keeps the
presentation, task identity, generation, timers, and decision state with the durable execution.

## Author a confirmation

1. Add a **Human task** node from the server catalog and select **Confirmation presentation 1**.
2. Write a bounded prompt and choose whether a comment is disallowed, optional, or required.
3. Keep only the permitted actions in their intended order: **Resolve**, **Deny**, and **Cancel**.
   Give each retained action a short display label. The decision button always names its disposition,
   followed by the custom label when it differs, so a label never obscures the action being committed.
   For new tasks, ASCII case variants, full-width ASCII forms, and Ravenroot's fixed separator set
   count as the same label; other Unicode characters remain distinct across supported runtimes.
4. Save the node and run the graph. The catalog supplies the active deployment's defaults and text
   limits. The built-in confirmation response is produced by the server when Resolve succeeds; it
   does not turn the comment into execution payload.

To review incoming mail before deciding, select **Review content 1**, set **Review text source** to
`payload.body`, and keep the byte limit at or below the server-advertised maximum. For an input such
as `{"from":"sender@example.test","subject":"Release","body":"Deploy 2.4?\nReply requested."}`,
the dialog shows exactly the two body lines as inert plain text. It does not interpret HTML or
Markdown, activate links, run scripts, or build a form. If the body is absent, is not text, or exceeds
the byte limit, Ravenroot refuses creation instead of shortening or converting it.

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

For a multi-field response, select `FORM` and author the closed version-one form schema from the
catalog controls. The Workbench uses native labelled single- and multiline text, checkbox, bounded
number, select, date, and
date-time controls, preserves keyboard/focus behavior and reduced-motion preferences, and sends a
typed map only after browser and server validation. Unknown fields, markup, nested schemas, and
executable content are not accepted.

`CUSTOM` and `EXTERNAL` are available only when the configuration response says
`registeredPresentationsEnabled: true`. Choose an opaque operator-provided profile ID and version;
do not paste a URL or credential into the graph. The dialog loads that registered host in an
isolated sandbox and supplies the exact task, schema, allowed actions, bounded review content, theme,
and accessibility settings through the versioned host protocol. The host receives no Ravenroot
bearer. Closing or timing out leaves the task open for recovery.

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
3. Choose one row to fetch its authorized exact-generation detail and open the central decision
   dialog. Review the static prompt, exact identity, and any pinned plain-text review content,
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
connection returns. If a normal decision request loses its response, the client refetches state
before deciding whether anything remains actionable. Registered capabilities are replay-safe: a
host may retry the same bounded callback and receives the already-recorded outcome when the first
request committed.

Stale generation, expiry, takeover, cancellation, and authorization changes also reconcile from the
server. A stale or terminal task is replaced or removed from the list; an authorization failure
reveals no cross-tenant task details. The browser may retain only the selected service origin, task
identifier, and generation. After a reload or server restart, re-authenticate and Ravenroot uses
that locator to fetch the task's durable presentation and current state. It never reconstructs the
row or review content from browser-cached task data.

A server restart does not reload an already open browser page, so that page keeps the deployment or
process context the user selected earlier. Close the recovered decision and select its Human Task
node again to refresh the authoritative task list for that context. A browser reload restores the
exact selected task from the opaque locator, but a broader task list still requires a deployment or
run context selected from the service; Ravenroot does not infer one from the recovered row.

Ravenroot supports built-in confirmation and forms plus operator-registered custom hosts and external
providers. Graph-authored executable pages, arbitrary URLs/redirects, graph credentials, CAPTCHA,
and identity management remain outside the interaction.

- [Durable Human Task contract](../reference/human-tasks.md)
- [Test, Run, and execution control](test-run-observe.md)
- [Persistence, lifecycle, and recovery](../operator-guide/persistence-lifecycle.md)
