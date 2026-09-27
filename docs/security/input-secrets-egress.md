# Graph input, credentials, and egress

Untrusted content is accepted only through bounded parsers and cannot carry the authority needed to reach secrets or networks.

## Controls

- Parse GraphML with DTD, entity, external reference, compression, and unsupported structure disabled.
- Apply payload size, depth, collection, value, text, and key budgets before behavior dispatch.
- Store secret values through the credential backend and expose only caller-owned metadata plus server-minted references.
- Resolve outbound destinations against exact operator allowlists and pass credentials only to the selected adapter invocation.
- Native [Kubernetes runners](../operator-guide/kubernetes-runners.md) keep Kubernetes/model/control-plane
  credentials in the manager, disable Agent token automount, constrain exec with admission and prove
  default-deny network isolation before Agent input. Separate pool namespaces are the RBAC isolation
  boundary; labels alone are not authorization. Pod/PVC identities are technical evidence, never
  permission to replay unknown effects or graph-selected placement.

## Residual responsibility

GraphML may name a credential reference or destination requested by a node, but server-side ownership and egress policy decide whether either is usable. Failure is classified and closed.

## Application

- [Definitions and limits](../reference/nodes-payload-limits.md)
- [Operator procedure or recovery](../operator-guide/credentials-egress.md)
