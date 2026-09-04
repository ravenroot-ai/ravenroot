# Ravenroot external node starter

A minimal third-party node package. Copy this module, rename it, and replace the behavior. See the
[extension development guide](../../docs/developer-guide/extension-development.md) for a step-by-step
version of everything below, including a verified test run and both installation routes.

This module depends on `ravenroot-application-api` and on nothing else. That is the point of it: a
node type can be written and conformance-tested without depending on the graph engine.

## What a node package is

Two interfaces, both in `ai.ravenroot.api.node`:

- **`NodeBehavior`** — one node type. It returns a `NodeTypeDescriptor` describing its properties and
  capabilities, and builds a `NodeAction` for each graph node that uses it.
- **`NodePackage`** — the deployable unit. It names itself, reports its own version, declares the SDK
  contract it was built against, and lists its behaviors.

See `GreetingNodeBehavior` and `ExampleNodePackage`. The five things every package has to get right
are marked `(1)`–`(5)` in `GreetingNodeBehavior`.

## Declaring the SDK contract

```java
@Override
public String sdkContract() {
    return NodeSdk.CONTRACT;
}
```

Return the constant, never a string literal. `NodeSdk.CONTRACT` is a compile-time constant, so its
value is baked into your class file when you compile. A runtime that hosts a different contract can
therefore tell that your package was built against something else, and refuses it at startup with a
message naming both versions. A literal would say the same thing today and would silently stop being
a statement about your build the moment it was copied into another package.

Refusal happens at registration, before any graph is admitted — not when a traversal reaches one of
your nodes.

## Declare every property you read

The descriptor is what puts your node under the runtime's validation. Properties you declare are
type-checked, required-ness is enforced, and allowed values are enforced — all before any node is
spawned. A property you read but do not declare is one a graph can set to anything.

Graph content supplies property *values* only. It cannot introduce a property, change a property's
type, or claim a capability; none of that is ever read from the graph.

Capabilities are read by the runtime, not merely displayed. Declaring one the runtime treats as
generative causes your node's output to be marked with a machine-readable synthetic-provenance
marker. Declare what is true.

## Installing it

Both routes are a decision made by the deployment. **No graph can name a class, a package or an
implementation to load, and nothing is discovered by scanning the classpath.**

**Embedding application:**

```java
var behaviors = NodePackages.register(BehaviorRegistry.standard(environment), new ExampleNodePackage());
var application = new DefaultRavenrootApplication(engine, monitor, behaviors,
        environment.artifacts(), environment.programRuntime());
```

**Shipped server or CLI:**

```sh
RAVENROOT_NODE_PACKAGES=com.example.ravenroot.nodes.ExampleNodePackage
```

with the jar on the classpath. Both steps are required — being on the classpath is not enough,
because classpath presence is not a decision anyone made about your package in particular. Listing
several packages is comma-separated, and they are installed in the order written.

Anything wrong — an unknown class, a class that is not a `NodePackage`, no public no-argument
constructor, an unsupported SDK contract, a behavior name that is already taken, a property declared
under the reserved `ravenroot.` prefix — aborts startup with a message naming the offending entry. An
operator who asked for a node package and did not get one is told.

## Conformance

`ExampleNodePackageConformanceTest` extends `NodeBehaviorContract` from `ravenroot-api-testkit` and
supplies one method. Every assertion is inherited, so a package gets the same structural checks the
built-in catalog is held to without having to track what they are.

The contract needs no engine and no `ravenroot-core` on the test classpath. Passing it says a package
is well-formed and can be trusted to register; it says nothing about whether a behavior does what it
claims.

The contract also discovers every property declared with
`NodePropertyDescriptor.adapterId(...)`. Such a binding may be blank while a graph is being
configured, so the behavior must still construct its action. If traversal later reaches the node,
the action must return an exceptionally completed stage without a `NodeResult`. The shared contract
checks that refusal for each adapter-binding property; package-specific tests should additionally
assert the classified failure and the configured success path.

## External I/O limits

Every external operation must have finite limits before it crosses its transport or process
boundary. For managed HTTP, attach `ExternalIoLimits` to `OutboundHttpRequest`. The runtime
intersects caller limits with operator policy; request code can tighten a ceiling but cannot widen
one. Keep wire bytes, decoded bytes, and the final projected/canonical output as separate limits.
Declare accepted media types and content encodings explicitly. A missing media type is accepted only
for an empty response, and the shipped decoder accepts identity plus, when requested, one complete
gzip member under a finite expansion ratio. Unknown, stacked, malformed, trailing, or concatenated
encodings fail closed.

The managed HTTP bridge bounds materialized request bodies before admission and response bytes while
they arrive, cancels the body subscription on refusal, applies one total deadline, and retains its
admission permit until the transport worker exits. `OutboundCall.cancel()` cooperatively interrupts
the worker and cancels the transport. The `cancellationBound` field communicates the requested
cleanup bound, but the JDK HTTP bridge does not claim a forced socket-teardown deadline; its narrower
guarantee is prompt terminal cancellation plus permit retention until cleanup. Runtime-neutral forced
termination and deployment fencing are separate lifecycle responsibilities.

First-party adapters follow the same invariant at their actual boundary:

| Boundary | Shipped limit behavior |
|---|---|
| AI, MCP, Assistant, GitHub, OpenAPI client | bounded JSON wire/decoded/output, explicit media type, identity or single bounded gzip, total deadline, cooperative transport cancellation |
| Object storage | bounded request/response/output, profile media allowlist for reads, identity encoding only, total deadline, cancellation propagated to the managed call |
| GraalVM programs | streaming request ceiling, supervisor wire/output ceiling, total deadline, admission held through bounded terminate/reap cleanup |
| WebSocket | bounded handshake, message bytes, fragments, queue, lifetime and idle time; compression is not negotiated |
| Mail, AMQP, Kafka, JDBC, OCR, Telegram | protocol-specific finite profile limits and admission/cancellation rules documented in each extension README; unsupported compression or unbounded projections are refused rather than delegated to graph data |

Tests for a package that performs I/O should cover declared and undeclared oversized streams, slow
completion/deadline, cancellation before and during handoff, representation expansion, projection
size, and release of every admission or worker resource. Do not substitute a larger timeout for an
observable cleanup assertion.

## Status

The Node SDK has not been published as a release artifact, and this repository makes no compatibility
commitment about it yet. `NodeSdk.CONTRACT` is the mechanism by which such a commitment could later
be stated and enforced; it is not itself a promise that a given contract version will be supported for
any particular period.
