# Finite-automata architecture evidence

This directory validates the [accepted v1 design](../../architecture/finite-automata-v1.md) with a
bounded universal interpreter. It is **not a production bundle**, an installed `finite-automaton`
behavior, an authoring editor or a previously existing prototype. This evidence was created for the
architecture milestone and checked against base `846def1f9df0e78c106427017006a7331777fac1`.

## Reproduce

From the repository root, with Node.js 22 or later:

```sh
node --test docs/examples/finite-automata/test-program.mjs
```

The 48 tests require no external packages. They cover 14 fixed examples, invalid definitions,
all evidence resource limits, exact one-transition admission, canonical round trips and permutations,
UTF-8/scalar ordering, full-run/prefix agreement and trace modes. Independent generated oracles test
511 binary words for parity, 511 words for substring recognition and all 256 four-bit operand pairs
for addition. These tests run the program expression in a Node VM; that VM is not a security sandbox.

With Maven and a supported JDK (21 through 25), run the live adapter and routing evidence:

```sh
mvn -f ravenroot/pom.xml \
  -pl ravenroot-programming-graalvm,ravenroot-core -am \
  -Dtest=FiniteAutomataProgramEvidenceTest,FiniteAutomataDecisionEvidenceTest,NodeOutcomeDeclarationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`FiniteAutomataProgramEvidenceTest` reads the exact public source and fixtures, validates its callable
JavaScript shape, executes all 14 cases, checks the result fields and existing payload limits, and
asserts an invalid token raises a failure. It asserts **16 actual child JVM launches** through the
real GraalVM worker/adapter: one validation, 14 executions and one failed execution. `FakeSupervisor`
is the repository's direct-worker test adapter, with no prerecorded response; `TestAdmission` is a
test admission handle. This proves language execution and wire/result compatibility, **not**
production supervisor isolation, tenant admission, build approval, revocation or deployed graph
execution. No credentials or running server are needed.

`FiniteAutomataDecisionEvidenceTest` invokes the existing CEL decision for true and false results;
`NodeOutcomeDeclarationTest` verifies the current catalog's fixed `program` outcome. Combined with
the checked `ProgramNodeBehaviorFactory` source, these establish the required routing boundary.
They do not claim the separate live-worker and decision tests form a deployed end-to-end graph test.

Verified locally with Node.js 23.11.0, OpenJDK 21.0.11 and Maven 3.9.9: all 48 standalone tests and
the targeted Maven reactor passed. Elapsed times are deliberately not performance guarantees.
The later full CI and independent review remain separate repository gates.

## Evidence-only request wrapper

[program.js](program.js) is a single JavaScript function expression, the shape accepted by the
current `program` node. It accepts `request.payload` with:

| Member | Evidence meaning |
|---|---|
| `definition` | Strict JSON text for one complete v1 machine |
| `input` | Array of whole string tokens |
| `traceMode` | `none` (default), `summary` or `full` |
| `operation` | `run` (default) or `canonical` (returns canonical definition text) |
| `limits` | Optional test-only tighter ceilings; unknown, zero, fractional or raised values fail |

This payload-definition wrapper is deliberately limited to testing an interpreter. Production v1
uses governed inline node configuration and does not allow input to select a machine. The program
implements no payload paths, result insertion, activation-time machine validation, deployment policy
digest, durable step sessions, YAML import or Java typed automata exceptions. Its `FA_*` diagnostics
are error messages carried by the existing sandbox failure mechanism. Standalone JSON ingress and
production governance are not replaced by this example.

The parser, validator, normalization and executor are separate functions/sections inside the one
program expression so it can be supplied unchanged as program source. This small reference is not
a production module boundary, supported library or optimized streaming implementation.

To exercise it in an already configured Ravenroot deployment:

1. Configure a `program` node with `language=javascript` and the exact file contents as `source`.
   Use a fixture's definition serialized as JSON text and its input as `testPayload` with the wrapper
   above. Follow the existing program build/validation/test/approval/activation lifecycle for the
   tenant and runtime; an `artifactId` alone does not activate or select arbitrary content.
2. Connect its `continue` edge to `cel-decision` with `expression=payload.accepted`,
   `trueOutcome=accepted`, and `falseOutcome=rejected`.
3. Connect that node's outcomes to the desired accepted/rejected destinations. Wire the normal
   `failure.route` separately for operational failures; errors cannot take an acceptance edge.

A successful program replaces its outgoing payload with the result. Its only native outcome is
`continue` even if the result contains `accepted: false`. Do not wire accepted/rejected directly to
`program`. The optional future node will produce those outcomes itself.

## Fixtures and expected results

[fixtures.json](fixtures.json) contains complete definitions and machine-readable expectations.
All successful returns consume the full input; transducer output is retained on rejection.
`steps` counts one per token advance plus one per inspected matching transition, including epsilon.

| Case | Input | Accepted | Final states | Output | Steps |
|---|---|---|---|---|---:|
| Binary parity | `1,0,1` | true | `even` | empty | 6 |
| Empty DFA | empty | true | `even` | empty | 0 |
| Rejected total DFA | `1` | false | `odd` | empty | 2 |
| Partial DFA dead frontier | `1,0` | false | empty | empty | 2 |
| Substring NFA | `b,a,b,a` | true | `q0,q1,q2` | empty | 12 |
| Epsilon cycle | `a` | true | `q2` | empty | 4 |
| Empty epsilon closure | empty | true | `q0,q1` | empty | 2 |
| Deduplicated frontiers | `a,a` | true | `q2` | empty | 5 |
| Serial adder final carry | `11,11,end` | true | `done` | `0,1,1` | 6 |
| Serial adder requires end | `11` | false | `c1` | `0` | 2 |
| Empty Mealy | empty | false | `c0` | empty | 0 |
| Moore initial output | `a` | true | `on` | `0,1` | 2 |
| Empty Moore | empty | false | `off` | `0` | 0 |
| Unicode tokens | `👩‍💻,ab` | true | `done` | empty | 4 |

For example, the parity case in full mode returns:

```json
{
  "accepted": true,
  "finalStates": ["even"],
  "consumed": 3,
  "output": [],
  "steps": 6,
  "trace": {
    "mode": "full",
    "complete": true,
    "entries": [
      {"position": 0, "on": null, "states": ["even"], "emitted": [], "steps": 0},
      {"position": 1, "on": "1", "states": ["odd"], "emitted": [], "steps": 2},
      {"position": 2, "on": "0", "states": ["odd"], "emitted": [], "steps": 4},
      {"position": 3, "on": "1", "states": ["even"], "emitted": [], "steps": 6}
    ],
    "summary": {"maxFrontier": 1, "transitionVisits": 3, "epsilonVisits": 0, "outputTokens": 0}
  }
}
```

The negative tests expect precise `FA_*` categories rather than `accepted: false`: malformed JSON,
duplicate object members, unsupported versions/properties, unknown states, multiple deterministic
initial states, nondeterminism, duplicate transitions, incomplete total tables, illegal epsilon,
invalid output declarations, invalid Unicode and out-of-alphabet input. Limits are exercised for
source bytes/depth, state/alphabet/transition count, identifier/token bytes, input, work, frontier,
output tokens/bytes and trace entries/bytes. A missing transition in **partial** mode remains ordinary
rejection; the same incomplete table in **total** mode is a static error.

## Boundaries and future validation

The fixed evidence profile is published in the [v1 contract](../../architecture/finite-automata-v1.md).
Request data is already materialized by the host before the handler runs. Definition parsing is
bounded before recursion/collection growth; execution checks work/frontier/output growth before
append. A trace entry is assembled from already bounded state before its encoded-size check. No
trace is silently truncated. This is not an assertion that production deployment budgets or wall
clock enforcement have been implemented.

There is no determinization, minimization, visual authoring, GraphML exporter or state-per-node
implementation in this directory. The specification defines the later NFA/determinized-DFA corpus
comparison and expanded GraphML round-trip gates. Current substring-oracle checks validate the NFA
interpreter only; they are not evidence that a determinizer or graph compiler exists.
