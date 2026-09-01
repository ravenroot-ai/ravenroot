# Ravenroot documentation

Ravenroot is a graph-governed platform for designing, executing, and operating backend workflows. This manual is the release contract for authors, operators, integrators, and contributors.

## Choose your path

- [Get started](get-started/index.md) — Install Ravenroot, validate one GraphML document, and complete a first Test and Run.
- [User guide](user-guide/index.md) — Author graph documents in the workspace, control execution deliberately, and understand every visible result.
- [Operator guide](operator-guide/index.md) — Run Ravenroot as a controlled service with explicit identity, storage, deployment, and embed authority.
- [Integrator guide](integrator-guide/index.md) — Embed Ravenroot, call its APIs, and add adapters without crossing application and operator boundaries.
- [Architecture and concepts](architecture/index.md) — Understand the invariants connecting graph semantics, actor execution, durability, AI, and deployment control.
- [Security guide](security/index.md) — Apply trust boundaries and controls for identity, untrusted graphs, secrets, code, AI, and embeds.
- [Developer guide](developer-guide/index.md) — Build, test, extend, and document Ravenroot while preserving its public contracts.
- [Reference](reference/index.md) — Look up exact GraphML, node, execution, API, configuration, embed, extension, and limit contracts.
- [Troubleshooting and runbooks](troubleshooting/index.md) — Move from observable symptoms through bounded diagnosis and action to an explicit verification.
- [Governance and releases](governance/index.md) — Understand compatibility, support, disclosure, licensing, and documentation commitments.

## Contract model

GraphML carries executable graph intent. The runtime validates that intent, dispatches node behavior through an actor engine, and emits execution evidence. Credentials, network access, plugins, model adapters, code execution, deployment, and embedded access remain operator-controlled capabilities; graph content cannot grant them to itself.

Read the [editorial guide](editorial-guide.md) for the rules that keep this manual precise.
