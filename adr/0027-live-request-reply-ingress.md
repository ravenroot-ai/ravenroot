# ADR 0027: Live request/reply ingress

- Status: Accepted
- Date: 2026-08-27
- Supersedes: Using admission receipts or persistence callbacks as request/reply completion
- Superseded by: None
- Public references: [Application, HTTP, SSE, and CLI integration](../docs/integrator-guide/application-http.md), [HTTP API and CLI](../docs/reference/api-cli.md)

## Context

Some trusted inbound sources keep a client connection open until one graph traversal reaches a
terminal result. An admission receipt cannot represent terminal completion, deadline, cancellation,
or a reply, while an execution-store callback would turn persistence into an in-process waiter bus.

## Decision

A deployed graph exposes a framework-neutral request/reply ingress contract. The deployment owns
tenant, principal, route, and traversal identity; a source supplies a bounded request payload and
deadline but cannot manufacture identity or complete another request. Completion settles once with
a terminal response, timeout, cancellation, or classified refusal.

Ingress may apply a trusted pre-dispatch projection before the payload reaches the graph. The first
contract is live and process-local: it does not promise that a waiting socket survives a process or
pod failure.

## Consequences

- Request/reply behavior does not expose actor references or misuse durable storage as a waiter bus.
- Deployment identity and authorization remain structural across the ingress boundary.
- Durable continuation and cross-pod connection survival require separate contracts.
