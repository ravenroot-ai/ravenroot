# ADR 0015: Structured payloads and a versioned error contract

- Status: Accepted
- Date: 2026-08-02
- Supersedes: Unbounded `Object` payload boundaries and ad hoc error JSON
- Superseded by: None
- Public references: [Payloads, outcomes, and routing](../docs/user-guide/payload-routing.md), [node catalog, payloads, and limits](../docs/reference/nodes-payload-limits.md), [HTTP API and CLI](../docs/reference/api-cli.md)

## Context

Payloads crossed public boundaries without a closed shape or budget, while individual handlers built
error JSON and could interpolate exception messages. Existing text-payload clients could not be
broken while adding a structured contract.

## Decision

Public structured payloads use a closed scalar, list, and map model. Ravenroot owns and validates the
payload contract version; caller-owned schema identifiers are carried but not interpreted. Limits
are enforced both while decoding and for values supplied by embedded callers. The legacy text
request remains a text scalar and is selected by its existing media type.

All public errors use one envelope with a closed error-code vocabulary, server-created correlation
data, and a message derived from the code rather than from arbitrary exception text. Construction
does not expose a parameter that could place an exception message into the public envelope.

## Consequences

- HTTP and embedded bindings must satisfy the same payload transport contract.
- Canonical map encoding is deterministic but does not promise source member order.
- Untrusted values outside the model are classified rejections rather than best-effort strings.
- Backward compatibility is selected explicitly by media type rather than by guessing payload shape.
