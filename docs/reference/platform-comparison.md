# Platform comparison by operational dimension

This guide helps evaluators choose an orchestration category. It compares documented product shapes,
not feature-name parity. Every statement about another project is bounded to the linked official
documentation reviewed on **2026-10-16**. Verify the edition and version you plan to operate.

## Orientation

| Project | Primary model | Documented emphasis | Edition or scope used here |
|---|---|---|---|
| Ravenroot | Visual graph and executable GraphML, with JVM application contracts | Graph-governed backend work with explicit policy, evidence, extension and deployment boundaries | Apache-2.0 alpha; current `0.7.0-alpha.1` source |
| [Temporal](https://docs.temporal.io/) | Code-first workflows through language SDKs | Durable execution that resumes application workflows after failures | Official platform documentation; Cloud and self-hosted responsibilities differ |
| [Apache Airflow](https://airflow.apache.org/docs/apache-airflow/stable/index.html) | Python workflows as code | Batch-oriented data, analytics, ML and event-triggered pipelines | Stable documentation, version 3.3.2 |
| [Camunda](https://docs.camunda.io/docs/components/) | BPMN, DMN, FEEL and forms | Business-process orchestration across people and systems | Camunda 8.10; SaaS, Self-Managed and component licenses differ |
| [n8n](https://docs.n8n.io/) | Visual workflow automation | App/API automation with business and AI nodes | Official fair-code documentation; features and deployment vary by edition |
| [Node-RED](https://nodered.org/docs/user-guide/editor/) | Browser-authored JSON flows | Event-driven applications on Node.js | Open-source runtime/editor and its installed-node ecosystem |

## Eleven-dimension reading map

This map makes the comparison coverage explicit. Each row links the detailed Ravenroot contract and
the primary competitor documentation used below; the links are evidence sources, not parity claims.

| Dimension | Ravenroot contract | Primary comparison sources |
|---|---|---|
| Visual and code authoring | [Workspace authoring](../user-guide/workspace-authoring.md) separates visual type, behavior and code-owned extension boundaries | [Temporal Develop](https://docs.temporal.io/develop), [Airflow stable overview](https://airflow.apache.org/docs/apache-airflow/stable/index.html), [Camunda components](https://docs.camunda.io/docs/components/), [n8n workflows](https://docs.n8n.io/workflows/), [Node-RED editor](https://nodered.org/docs/user-guide/editor/) |
| Executable model and interchange | [GraphML](graphml.md) is Ravenroot's executable interchange contract; drawing projections remain presentation | [Camunda Desktop Modeler](https://docs.camunda.io/docs/components/modeler/desktop-modeler/), [n8n export/import](https://docs.n8n.io/workflows/export-import/), [Node-RED flow structure](https://nodered.org/docs/api/admin/types) |
| Embedding and deployment | [Deployment/startup](../operator-guide/deployment-startup.md), [Kubernetes](../operator-guide/kubernetes-installation.md), and [embed contracts](embed-extension-contracts.md) | [Temporal self-hosting](https://docs.temporal.io/self-hosted-guide), [Airflow executors](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/executor/index.html), [Camunda Self-Managed](https://docs.camunda.io/docs/self-managed/about-self-managed/), [n8n hosting](https://docs.n8n.io/hosting/), [Node-RED embedding](https://nodered.org/docs/user-guide/runtime/embedding) |
| Durable state and recovery | [Persistence lifecycle](../operator-guide/persistence-lifecycle.md) and [PostgreSQL](postgresql-persistence.md) state exact store and topology limits | [Temporal workflow execution](https://docs.temporal.io/workflow-execution), [Airflow DAG runs](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dag-run.html), [Camunda components](https://docs.camunda.io/docs/components/), [n8n queue mode](https://docs.n8n.io/hosting/scaling/queue-mode/), [Node-RED context](https://nodered.org/docs/user-guide/context) |
| Human Tasks | [Human Tasks](human-tasks.md) define durable task, presentation, responder and revision contracts | [Camunda user tasks](https://docs.camunda.io/docs/components/modeler/bpmn/user-tasks/), [Airflow HITL operators](https://airflow.apache.org/docs/apache-airflow/stable/tutorial/hitl.html), [n8n approval before AI tool calls](https://docs.n8n.io/build/integrate-ai/ai-examples/human-in-the-loop-for-tools.md) |
| Orchestration and AI | [AI/program integration](../integrator-guide/ai-programs.md) and [governed runners](../operator-guide/governed-runners.md) distinguish shipped nodes, configured providers and integrator runtimes | [Camunda agentic orchestration](https://docs.camunda.io/docs/components/agentic-orchestration/agentic-orchestration-overview/), [n8n AI](https://docs.n8n.io/advanced-ai/), [n8n human approval for AI tools](https://docs.n8n.io/build/integrate-ai/ai-examples/human-in-the-loop-for-tools.md), [Temporal Develop](https://docs.temporal.io/develop) |
| Extensibility and integrations | [Plugin bundles](../operator-guide/plugin-bundles.md) and [saga/outbox](../operator-guide/saga-outbox.md) bound installation and external effects | [Airflow plugins](https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/plugins.html), [Camunda connectors](https://docs.camunda.io/docs/components/connectors/introduction-to-connectors/), [n8n custom nodes](https://docs.n8n.io/integrations/creating-nodes/), [Node-RED nodes](https://nodered.org/docs/creating-nodes/) |
| Observability | [Execution events](execution-events.md) and [test/run/observe](../user-guide/test-run-observe.md) distinguish streams, durable records and projections | [Temporal monitoring](https://docs.temporal.io/self-hosted-guide/monitoring), [Airflow monitoring](https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/logging-monitoring/index.html), [Camunda Operate](https://docs.camunda.io/docs/components/operate/operate-introduction/), [n8n executions](https://docs.n8n.io/workflows/executions/), [Node-RED logging](https://nodered.org/docs/user-guide/runtime/logging) |
| Operational governance | [Graph source control](../operator-guide/graph-source-control.md), [lifecycle console](../operator-guide/lifecycle-console.md), and [trust/identity](../security/trust-identity.md) define publication, authority and audit boundaries | [Temporal security](https://docs.temporal.io/self-hosted-guide/security), [Airflow access control](https://airflow.apache.org/docs/apache-airflow/stable/security/access-control.html), [Camunda users and roles](https://docs.camunda.io/docs/components/hub/organization/users-and-roles/) |
| Licensing | [Support and legal](../governance/support-legal.md) records Ravenroot's Apache-2.0 boundary | [Temporal server license](https://github.com/temporalio/temporal/blob/main/LICENSE), [Airflow core license](https://github.com/apache/airflow/blob/main/LICENSE), [Camunda licenses](https://docs.camunda.io/docs/reference/licenses/), [n8n license](https://github.com/n8n-io/n8n/blob/master/LICENSE.md), [Node-RED license](https://github.com/node-red/node-red/blob/main/LICENSE) |
| Education | [Education](../education/) provides a bounded executable register-machine exercise with an exact expected result and explicit evidence limits | [Temporal tutorials](https://learn.temporal.io/), [Airflow tutorials](https://airflow.apache.org/docs/apache-airflow/stable/tutorial/), [Camunda tutorials](https://docs.camunda.io/docs/guides/), [n8n courses](https://docs.n8n.io/courses/), [Node-RED tutorials](https://nodered.org/docs/tutorials/) |

## Authoring and executable interchange

Ravenroot's browser editor and external tooling read and write the same GraphML document. Visual
types describe presentation; installed behavior descriptors determine execution. A drawing-model
projection is not execution authority. See [workspace authoring](../user-guide/workspace-authoring.md)
and the [GraphML contract](graphml.md).

- Temporal defines workflows in supported SDK code; its [developer guide](https://docs.temporal.io/develop)
  is the relevant authoring entry point.
- Airflow defines DAGs in Python and calls itself workflows-as-code in its
  [stable overview](https://airflow.apache.org/docs/apache-airflow/stable/index.html).
- Camunda models BPMN/DMN/FEEL and deploys diagrams through its modelers; the
  [component overview](https://docs.camunda.io/docs/components/) describes the surrounding runtime.
- n8n authors workflows in its visual editor and exports workflow JSON; consult its
  [workflow documentation](https://docs.n8n.io/workflows/) for current import/export behavior.
- Node-RED edits JSON flows in the browser and installs nodes through the
  [palette manager](https://nodered.org/docs/user-guide/editor/palette/manager).

These formats and code models are different contracts. The table does not imply lossless conversion.

## Embedding and deployment

Ravenroot exposes a framework-neutral Java application API, a standalone server/JAR, CLI, static UI,
OCI and Helm shapes, and explicit embed-viewer authority. SQLite is the default local store;
PostgreSQL is an optional shared durable adapter with its own configuration and topology contracts.
The [deployment and startup guide](../operator-guide/deployment-startup.md),
[Kubernetes guide](../operator-guide/kubernetes-installation.md), and
[embed contract](embed-extension-contracts.md) state the supported boundaries.

- Temporal offers Cloud and self-hosted server operation while workers run application code through
  SDKs; start from the [self-hosted guide](https://docs.temporal.io/self-hosted-guide).
- Airflow documents several executors and deployment choices in
  [Executors](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/executor/index.html).
- Camunda provides SaaS and Self-Managed editions; Self-Managed operators own infrastructure,
  security, scaling and upgrades as described in its
  [deployment overview](https://docs.camunda.io/docs/self-managed/about-self-managed/).
- n8n documents npm/Docker self-hosting and hosted n8n Cloud under
  [hosting](https://docs.n8n.io/hosting/).
- Node-RED runs as a Node.js application and can be embedded in another application using its
  [embedding API](https://nodered.org/docs/user-guide/runtime/embedding).

## Durable state and recovery

Ravenroot records accepted definitions, manifests, process/traversal state, attempts, timers, Human
Tasks, saga/outbox state and results when a durable capable store is composed. Recovery verifies the
pinned definition and manifest, fences ownership, and repeats an ambiguous external effect only when
its descriptor and graph declare it repeatable. SQLite coordination is host-local. PostgreSQL enables
shared durable storage but does not erase the documented ownership, source and runner topology limits.
See [persistence and recovery](../operator-guide/persistence-lifecycle.md) and
[durable events](../architecture/durability-events.md).

- Temporal's defining contract is durable execution and workflow replay; its
  [Workflow Execution](https://docs.temporal.io/workflow-execution) page explains that model.
- Airflow persists DAG-run and task-instance state in its metadata database and exposes retry,
  backfill and rerun controls; see [DAG runs](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dag-run.html).
- Camunda's Zeebe engine, Operate and Tasklist provide durable process and incident/task operation in
  the Camunda 8 platform; see [components](https://docs.camunda.io/docs/components/).
- n8n records executions according to deployment settings; queue mode uses Redis and database-backed
  execution state as documented in [queue mode](https://docs.n8n.io/hosting/scaling/queue-mode/).
- Node-RED context defaults to memory and can use the configured local filesystem or a custom store;
  see [context storage](https://nodered.org/docs/user-guide/context).

## Human work and operational governance

Ravenroot Human Tasks are durable, tenant-scoped and revision-fenced. Presentation, responder
authorization, assignment and settlement are distinct from graph intent. Deployments use immutable
graph versions, reviewed publication, fenced lifecycle commands and audit. See
[Human Tasks](human-tasks.md), [graph source control](../operator-guide/graph-source-control.md), and
[process monitoring](../user-guide/process-monitoring.md).

- Camunda ships user-task and Tasklist concepts within its process platform; current authorization
  is administered separately from organization roles, as its
  [users and roles guide](https://docs.camunda.io/docs/components/hub/organization/users-and-roles/)
  explains for 8.10.
- Airflow 3.3 documents `HITLOperator`, `ApprovalOperator`, `HITLEntryOperator`, and
  `HITLBranchOperator`. Their `awaiting_input` state is scheduler-managed without holding a worker or
  triggerer slot. The [HITL tutorial](https://airflow.apache.org/docs/apache-airflow/stable/tutorial/hitl.html)
  defines that DAG-operator and UI/API surface; it does not establish Ravenroot's tenant, revision,
  response-schema, and settlement contract.
- n8n includes a human-approval step specifically before selected AI Agent tool calls, with response
  channels configured by the workflow author. See [human approval for AI tools](https://docs.n8n.io/build/integrate-ai/ai-examples/human-in-the-loop-for-tools.md).
  Its documented scope differs from Ravenroot's general durable Human Task resource.
- Temporal and Node-RED applications can coordinate human interaction through their programming and
  integration models. No Human Task parity is inferred here without an equivalent primary contract.

## Orchestration, AI, extensions and integrations

Ravenroot includes a standard catalog plus optional first-party bundles. `llm-prompt` requires an
installed and configured external provider. `agent` requires an integrator-supplied runtime.
Governed Agent and Workspace execution adds explicit definitions, authority budgets, runner policy,
artifacts and lifecycle; Kubernetes-native runners require positive platform attestation. Sagas bind
only documented JDBC, AMQP and HTTP participant contracts and do not create a distributed
transaction. See [AI/program integration](../integrator-guide/ai-programs.md),
[governed runners](../operator-guide/kubernetes-runners.md), [bundle lifecycle](../operator-guide/plugin-bundles.md),
and [saga/outbox](../operator-guide/saga-outbox.md).

- Temporal extends through SDK activities, workers and integrations rather than a visual connector
  catalog; see [Develop](https://docs.temporal.io/develop).
- Airflow packages operators, hooks and providers and supports plugins; see
  [Plugins](https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/plugins.html).
- Camunda offers built-in and custom connectors through a connector runtime; secrets and versioned
  templates remain deployment concerns in its
  [connector documentation](https://docs.camunda.io/docs/components/connectors/introduction-to-connectors/).
- Camunda 8.10 documents AI agents inside agentic BPMN subprocesses and external-agent integration;
  see [agentic orchestration](https://docs.camunda.io/docs/components/agentic-orchestration/agentic-orchestration-overview/).
- n8n provides a broad node ecosystem, custom nodes, AI Agent workflows, and built-in approval before
  selected AI tool calls; see [creating nodes](https://docs.n8n.io/integrations/creating-nodes/) and
  [human approval for AI tools](https://docs.n8n.io/build/integrate-ai/ai-examples/human-in-the-loop-for-tools.md).
- Node-RED installs community nodes through the palette and supports custom nodes; see
  [creating nodes](https://nodered.org/docs/creating-nodes/).

## Observability

Ravenroot exposes structured events, durable event replay where a durable store is composed,
activity capture, process inventory, manifests, audit, metrics and OpenTelemetry integration. An SSE
stream and `visitedNodes` are not substitutes for an ordered durable history. See
[execution events](execution-events.md) and [test, run and observe](../user-guide/test-run-observe.md).

The other platforms also expose their own UIs, logs, metrics and operational views. Compare the
exact retention source, identifier model and failure semantics you need rather than counting screen
names: [Temporal monitoring](https://docs.temporal.io/self-hosted-guide/monitoring),
[Airflow logging and monitoring](https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/logging-monitoring/index.html),
[Camunda Operate](https://docs.camunda.io/docs/components/operate/operate-introduction/),
[n8n executions](https://docs.n8n.io/workflows/executions/), and
[Node-RED logging](https://nodered.org/docs/user-guide/runtime/logging).

## Licensing and education

Ravenroot is Apache-2.0. Its education pathway includes executable register-machine graphs, exact
expected results, a separate DFA drawing-model exercise and explicit evidence boundaries. See
[Education](../education/) and [support and legal](../governance/support-legal.md).

- The selected [Temporal server repository](https://github.com/temporalio/temporal/blob/main/LICENSE)
  uses the MIT License. SDKs, Cloud terms, and dependencies remain separately scoped.
- [Apache Airflow core](https://github.com/apache/airflow/blob/main/LICENSE) uses Apache License 2.0.
  Provider packages and bundled dependencies can carry their own notices and licenses.
- Camunda 8.10 licenses component **source** and compiled production use separately: the licensing
  page identifies Camunda License v1 source for core components, while compiled core software uses
  the proprietary non-production or Enterprise terms and production use requires Enterprise. Other
  components have separate licenses; use the [component licensing table](https://docs.camunda.io/docs/reference/licenses/).
- n8n describes itself as fair-code. Its repository [Sustainable Use License](https://github.com/n8n-io/n8n/blob/master/LICENSE.md)
  and enterprise terms determine the selected deployment's permitted use.
- [Node-RED core](https://github.com/node-red/node-red/blob/main/LICENSE) uses Apache License 2.0;
  independently installed nodes can use different licenses.

Education material across projects varies in goal and format. Ravenroot's exercises establish only
the recorded structural, validation and execution results; they do not claim broader curriculum
coverage or superiority.
