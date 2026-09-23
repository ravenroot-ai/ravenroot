# Global lifecycle console

Use the Workbench **Run → Deployments** command to open the global operational console. The console
reads deployments and process instances from the connected server. It does not reconstruct either
state from browser events, and it is independent of the graph node currently selected.

## Select the intended scope

A deployment row identifies the tenant-scoped deployment, immutable graph version, local runtime
state, and durable generation when one exists. Choose **Select deployment and processes** to read the
durable process inventory filtered to that deployment. A process row separately identifies its
tenant, process instance, graph version, stored status, control state, revision, lifecycle generation,
fencing token, and recovery disposition. Select the process before using a process command.

The server advertises a versioned list of operations for every target. The console uses that list
verbatim: an unavailable operation is disabled with the server's reason, and an older server that
does not advertise the contract receives no inferred durable controls. Losing the
`ravenroot.execution.control` scope therefore removes authority from the controls even if observation
remains authorized. Ravenroot consumes the externally authenticated roles and scopes; the console
does not manage users, groups, or profiles.

## Commands and consequences

These scopes are deliberately different:

| Scope | Operation | Consequence |
| --- | --- | --- |
| Task | Resolve, Deny, Cancel | Settles one waiting Human Task generation. It is not a process command. |
| Traversal | Pause, Resume, Cancel | Controls one submitted traversal from the ordinary Run controls. |
| Process | Pause, Resume, Cancel, Drain, Stop | Controls the process instance and its contained traversals under the displayed revision. |
| Deployment | Start, Restart | Activates the selected graph version or replaces its current activation. |
| Deployment | Pause, Resume, Cancel, Drain, Stop | Controls admission or work for the displayed deployment generation. |
| Deployment | Undeploy | Stops and permanently removes the durable deployment identity, retaining its tombstone. |
| Service | Drain or shutdown | Changes admission or lifetime for the whole Ravenroot service, outside the deployment console. |

**Pause** closes or holds admission resumably. **Resume** releases that hold. **Cancel** ends the
already admitted work governed by the selected target; it does not remove the target. **Drain**
closes admission and lets accepted work settle. The console displays the advertised deployment time
bound or the process authority's settle-until-complete bound; reaching the draining state does not
mean accepted work has already completed. **Stop** releases runtime resources but preserves a
recoverable registration or durable process record. **Undeploy** is a separate deployment-only
operation that removes the registration. Global shutdown remains service-scoped and is not a synonym
for any of them.

Waiting Human Tasks, retries, timers, joins, connectors, and active invocations do not hide these
global controls. No lifecycle control appears in the Human Task dialog: task Cancel settles that exact
task generation and never proxies process or deployment Cancel.

## Reconciliation and retries

Every durable command carries the generation or revision displayed before the action, a new
idempotency identity, and a bounded operator reason when the advertised contract requires one. The
server applies authorization, deterministic command precedence, and audit recording. A stale
generation, concurrent command, refusal, timeout, authorization loss, restart, or unreadable response
does not make the browser assume success. The console reads the authoritative deployment status or
process inventory again and shows that state. If a deployment command delivery is ambiguous, the
client retries once with the identical idempotency identity and command body; it never creates a
second intent to chase an unknown response.

A workflow may call the lifecycle HTTP API through an ordinary HTTP node and operator-provisioned
credentials. That remains graph-authored application behavior. It does not acquire operator identity,
change the command's scope, or replace this global console.
