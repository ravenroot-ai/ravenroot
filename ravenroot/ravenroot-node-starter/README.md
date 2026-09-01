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

## Status

The Node SDK has not been published as a release artifact, and this repository makes no compatibility
commitment about it yet. `NodeSdk.CONTRACT` is the mechanism by which such a commitment could later
be stated and enforced; it is not itself a promise that a given contract version will be supported for
any particular period.
