# ADR 0004: OCI image and deployment modes

- Status: Accepted
- Date: 2026-07-21
- Supersedes: Packaging Ravenroot only as a developer-built application
- Superseded by: None
- Public references: [Deployment and startup](../docs/operator-guide/deployment-startup.md), [configuration reference](../docs/reference/configuration.md), [install and start](../docs/get-started/install-start.md)

## Context

Ravenroot must support direct Java use and repeatable container deployment without making one
container tool the product boundary. Packaging only a ZIP would leave users to assemble the
classpath and coordinate the server and UI themselves.

## Decision

Ravenroot supports embedded-library, local-process, distribution-archive, and OCI-image operation
over the same application and graph contracts. The published container format follows OCI; a
multi-stage build produces a minimal, non-root runtime image. The standard image uses the default
open-source execution engine and does not silently include optional licensed dependencies.

The server, UI, and runtime remain separate composition concerns even when packaged into one process
or image. Deployment configuration selects adapters and infrastructure without changing graph
semantics.

## Consequences

- Users can choose a Java, archive, or container workflow without selecting a different product.
- Image construction and runtime defaults must be reproducible and testable.
- Distributed scaling is not implied by the existence of an OCI image; deployment ownership and
  placement are governed by later ADRs.
