# ADR 0044: Encapsulated finite automata

- Status: Accepted contract; production implementation deferred
- Date: 2026-09-26

## Context

Finite automata require explicit language acceptance, frontier deduplication and epsilon closure.
Graph fan-out and ordinary traversal completion do not supply those semantics. A reusable extension
must also distinguish rejection from invalid configuration, invalid input and exhausted budgets.
The serial binary adder is a Mealy example, not a separate product behavior.

## Decision

Adopt the [v1 contract](../docs/architecture/finite-automata-v1.md) for one optional
`finite-automaton` node covering DFA, NFA, epsilon-NFA, deterministic Mealy and deterministic Moore.
No production node is shipped by this decision. The public `program` example is bounded executable
evidence; its input wrapper is not the future extension API.

The first production version uses governed inline canonical JSON. Immutable artifact references
are deferred: the existing executable-program registry does not establish a generic immutable,
tenant-owned data-artifact contract. Authoring imports may embed validated bytes instead.
Payload-supplied definitions are disabled until a separately authorized deployment mode defines
its admission, validation and budget contract.

V1 defines string-token input and ordered string-token output, exact Unicode identity, explicit
initial/accepting sets, deterministic total/partial policy and visited-set epsilon closure. Only
Mealy and Moore emit output, and both are deterministic. No branch chooses among nondeterministic
outputs. All kinds use the same final-state acceptance rule; emitted output survives ordinary
rejection. Moore emits its initial output; Mealy final carry requires a modeled end token.

Keep parsing, validation, canonical model and execution separate. Deployments bound configuration,
input, work, frontier, output and trace; full-trace overflow fails rather than silently truncating.
The node declares two fixed outcomes agreeing with the result boolean. Errors use existing failed
attempt and failure-route semantics. No traversal-engine change is required.

Authoring owns the editable machine document, normalization, simulation, GraphML compilation,
determinization and minimization. Its first visual/export representation is one encapsulated node.
Optional deterministic expansion follows a general SDK/catalog contract for alphabet-derived
outcomes, with explicit symbol mappings and provenance. `automaton-state` is deferred until that
contract exists. Bounded subset construction precedes NFA expansion and refuses exponential growth
before exceeding limits. Neither determinization nor minimization belongs inside runtime execution.

## Consequences

All five machine kinds have one execution and diagnostic boundary, with deterministic testable
results and no dependency on traversal join semantics. Definitions are portable canonical data.
Static outcomes fit the current SDK, while configuration is validated before actions run concurrently.

The graph initially exposes one node rather than every internal automaton state. Output is limited
to string tokens, data-artifact reuse requires later work, and large traces may fail under explicit
limits. These constraints preserve a small initial implementation boundary.

The [evidence guide](../docs/examples/finite-automata/index.md) distinguishes standalone semantic
checks, live program-adapter execution, decision routing and deferred authoring test gates. It does
not attest production sandbox enforcement or imply that an earlier prototype existed.
