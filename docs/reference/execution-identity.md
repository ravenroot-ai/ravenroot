# Deployment, traversal and business identity

| Term | Meaning and identity |
|---|---|
| Graph | Authored topology, properties and drawing layout. A document can contain unsaved edits. |
| Graph version | Immutable accepted definition and digest. Monitoring and recovery use that pin. |
| Deployment | Long-lived host of one exact graph version and its listeners. Tenant and logical deployment ID scope its authority. |
| Registration identity | Durable, non-reusable UUID of the logical deployment registration. It survives host restart; tombstoned/replaced registrations cannot inherit it. |
| Incarnation | Current runtime activation identity. A restart can change it without creating another registration or initial START allowance. |
| Initial START | At most one accepted START traversal per deployment. The permanent marker retains the original request/input and process/traversal IDs. |
| SOURCE | Activated listener and exact event entry node. An edge reaching it records a boundary; only an actual accepted source event emits downstream. |
| Process instance | Durable owning aggregate. Its UUID is independent of a business identity. A process may contain more than one actual traversal. |
| Traversal | One actual admitted path through the pinned graph, including parallel branches, loops, waits and outcomes. Its UUID is also used by execution control/result routes. |
| Visit / invocation | One real arrival at a node. A loop can produce multiple visits to the same node; causal parent IDs distinguish them. |
| Attempt | One try within a visit. A retry has another attempt ID/ordinal, not another contribution or join round. |
| Business history | Scoped operation identified by `applicationCorrelationId` within tenant, logical deployment and exact version. It groups actual traversals without replacing their IDs. |
| Contribution | Explicit distinct business result such as `part-4`. Multiple message deliveries or attempts cannot invent additional contributions. |
| Join round | Explicit application `roundId` at one correlated join node. A later round cannot consume old-round contributions. |
| Public reference | Optional bounded non-secret label, such as `sales.csv`. Two ingestions of that filename can be different business histories. |
| Transport identity | Broker message ID, HTTP request/reply waiter, SMTP Message-ID, mailbox UID or explicit consumer stream. It keeps its protocol meaning and never implicitly establishes business context. |

Deploy opens eligible listeners and runs no initial work. First **Run** creates or reconciles the
originating document's deployment association, pins the deployed version and admits its one initial
START. A second Run reconciles the same marker, even after failure or completion. Reload and history
selection are read operations. Editing the draft does not silently redeploy it. An explicit new
deployment of identical GraphML has an independent registration and initial START allowance.

END settles the current traversal. It does not stop the deployment or close an entire business
history. When no traversal runs but external work remains, an open history reports
`AWAITING_EXTERNAL_EVENT`. A declared close can report `CLOSING` while admitted work settles;
missing contributions, failures and incomplete/expired evidence remain explicit.

Source receipt custody retains bounded original input independently of activity capture. Recovery
keeps the accepted entry/context, elapsed deadline, consumed budgets and causal identities; it does
not authorize replay of ambiguous external effects. Explicit stable consumer IDs preserve their
transport checkpoint stream across deployment replacement while each newly accepted event's
business context remains scoped to its actual deployment/version. An old receipt cannot move its
pending work or create a history in a successor scope.

Follow the [SOURCE contract](source-entry-contract.md),
[business context operations](business-correlation-operations.md),
[upgrade and rollback guide](../operator-guide/correlation-migration.md),
[API/CLI reference](api-cli.md) and [monitoring guide](../user-guide/process-monitoring.md).
