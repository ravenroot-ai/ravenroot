# Nodes, plugins, and runtime adapters

Add behavior through narrow packages whose capabilities are discoverable and whose absence fails closed.

## Integration sequence

1. Give the package a stable identity, version, compatibility declaration, and capability list.
2. Implement only the relevant node, model, agent, engine, persistence, or connector SPI; keep host secrets and policy outside the implementation contract.
3. Return classified failures instead of throwing transport- or vendor-specific details across the boundary.
4. Install into an isolated deployment, inspect `/v1/runtime` and `/v1/node-types`, then exercise the package through Test and a bounded Run.

## Authority boundary

An extension advertises behavior; it never self-grants egress, credentials, tools, deployment, or artifact approval. Operators retain installation and configuration authority.

## External I/O boundary

Every external operation needs finite limits before transport or process handoff. Managed HTTP
requests carry `ExternalIoLimits`; the runtime intersects them with operator policy, so an adapter can
only tighten request wire bytes, encoded response bytes, decoded bytes, projected output, media type,
content encoding, expansion ratio, deadline, and cooperative cancellation. Missing media type is
valid only for an empty response. The shared decoder supports identity and, only when requested, one
complete gzip member; unknown, stacked, malformed, trailing, or concatenated encodings fail closed.

The managed JDK HTTP bridge cancels the subscription on a streaming breach, signals cancellation to
the transport, and retains admission until its worker exits. It does not claim forced socket teardown
within `cancellationBound`; packages that require that stronger lifecycle guarantee must use a
runtime with an enforceable supervisor boundary. The GraalVM adapter is such a boundary: request
serialization is streaming-bounded and cancellation retains admission through bounded terminate and
reap cleanup.

Protocol-specific connectors must enforce equivalent finite profile limits at their actual client
boundary or document a narrower fail-closed capability. WebSocket bounds messages, fragments, queue,
lifetime, and idle time without negotiating compression. Mail, AMQP, Kafka, JDBC, OCR, and Telegram
use the finite limits and cleanup rules documented by their extension READMEs; graph payloads cannot
enable an unsupported encoding or unbounded projection. Runtime-neutral forced termination,
deployment fencing, and broker custody/acknowledgement redesign are outside this per-operation I/O
contract.

Conformance tests should include declared and undeclared oversized streams, slow completion and
deadline, compression expansion, large projections, cancellation before and during handoff, and
observable release of admission, worker, stream, process, file-descriptor, and temporary-storage
resources used by the adapter. A longer timeout is not evidence of cleanup.

## Linked contracts

- [First-party extension dependency pack](extension-pack.md)
- [Primary interface](../architecture/ai-extension-boundaries.md)
- [Operational or security model](../troubleshooting/ai-extensions.md)
