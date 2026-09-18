# Register Machine Profile v1

The Register Machine Profile is a portable, inspectable way to express exact integer computation in
ordinary Ravenroot GraphML. It is deliberately a profile of existing nodes, not a new runtime or a
macro format. A saved graph contains every elementary arithmetic operation, comparison, branch, and
observation as an editable node.

## Machine state and control

- Registers are top-level payload fields holding canonical decimal strings: `0`, `-7`, `42`. Boolean
  comparison temporaries are top-level booleans. Profiles that model the classical non-negative
  register machine restrict register inputs to `0` or a decimal without a sign or leading zero.
- `bigint-op` performs one exact, bounded operation. Its 4,096-digit operand/result ceiling and the
  ordinary payload, traversal-step, cumulative-payload, and GraphML limits still apply. “Unbounded”
  describes the mathematical model, not an infinite host-resource promise.
- One active control path is the portable core. `cel-decision` may only read one boolean temporary as
  `payload.<field>`; its two distinct outcomes select the next edge. Cycles express repetition.
- `END` is HALT. `log` is the only optional observation action in the profile. It is an explicit side
  effect and should appear only where output is meaningful. Arithmetic never logs implicitly.
- The side-effect-free core excludes program nodes, connectors, agents, human tasks, and arbitrary
  CEL. Such nodes may surround a profile computation, but a graph containing them does not conform
  to Profile v1.

GraphML carries no hidden profile marker or macro metadata. Invoke conformance explicitly. This keeps
the persisted format ordinary and makes every operation visible to older editors and runtimes.

## Constructive translation

For a finite instruction list whose positions are numbered from zero:

| Register instruction | Ordinary GraphML expansion |
|---|---|
| `SET r, n` | one `bigint-op(copy, literal:n -> r)` |
| `INC r` | one `bigint-op(add, field:r, literal:1 -> r)` |
| `DECJZ r, z, nz` | `bigint-op(equal, field:r, literal:0 -> temporary)`, then `cel-decision`; the zero outcome goes to `z`, while the nonzero outcome alone goes through `bigint-op(subtract, field:r, literal:1 -> r)` and then to `nz` |
| `HALT` | `END` |

The test-support compiler in `ravenroot-engine-testkit` implements this construction as
`RegisterMachineProgram.compile`. It accepts initial registers separately and materializes them as
visible SET nodes. SET literals and initial values must be canonical non-negative decimals of at most
4,096 digits; the compiler and independent interpreter reject the same out-of-domain values. The
compiler also validates its completed graph against Profile v1 before returning it. It emits explicit
independent-arrival semantics where a cycle gives an instruction more than one predecessor. Its
independent instruction-level interpreter uses a caller-supplied step ceiling and reports final
registers, the halt instruction, and abstract instruction count. Differential tests compare all three
facts with execution of the serialized GraphML; initializer nodes are not abstract instructions, and
DECJZ counts once when its zero-test entry node starts.

## Static validation

Validate normal GraphML ingestion and this profile together:

```bash
./ravenroot-cli/target/ravenroot/bin/ravenroot validate --register-machine path/to/graph.graphml
```

The profile pass is static: it does not evaluate CEL, invoke a node, or follow one runtime path. It is
deterministic, inspects at most 20,000 nodes plus edges, returns at most 256 sorted diagnostics with
each diagnostic field bounded to 512 characters, and reports when that diagnostic list was truncated.
Its cycle analysis uses bounded heap-backed worklists rather than recursion, so a near-limit deep path
cannot consume the Java call stack. Errors make the profile verdict invalid; warnings do not.

Errors cover malformed `bigint-op` expansions (including literal text or digit counts the runtime
cannot execute, invalid target field names, and targets in the reserved `ravenroot.security.*`
namespace), edge outcomes an elementary node cannot emit, non-profile or side-effecting nodes,
ordinary nodes that fork or terminate the active path unexpectedly, decisions that do not read exactly one top-level
boolean, missing or ambiguous decision outcomes, and unresolved edge ends. Warnings conservatively
cover reads not proven initialized, subtract-one not proven guarded by
the nonzero branch of a zero test, comparison booleans never consumed, unreachable nodes, unlogged
cycles, arithmetic that reads one register and writes another, and noncanonical decimal literals.
Static uncertainty becomes a warning, never a claim about a runtime path.

## Visual authoring

The editor’s **Register machine** toolbox provides Set register, Increment register, Zero test, and
DECJZ expansion. DECJZ inserts its three ordinary nodes and four edges as one undoable command. There
is no opaque macro node: after insertion every property and edge remains editable. Exact decimal
values are text areas, never JavaScript numbers, and a 4,096-digit value round-trips through GraphML
as a string. The palette adds no semantic visual-group metadata; authors may apply the existing
lossless visual grouping feature if a large expansion needs presentation-level folding.

## Execution, cancellation, and recovery boundary

Existing execution events already identify the current node, routed outcome, and per-attempt duration;
they do not copy register payloads into event details. A `log` node alone places its deliberately
rendered value in `authorOutput`. The runtime checks cancellation between nodes. One `bigint-op`
invocation is one synchronous `BigInteger` calculation and is not preempted halfway through.

Ordinary in-flight arithmetic is not a restart checkpoint. Durable recovery applies at the engine’s
documented suspension/park boundaries (for example a durable human task), not between arbitrary
register nodes. The examples therefore test durable final-result JSON and external cancellation, and
do not simulate a stronger mid-instruction recovery contract.

## Conformance and example library

[`docs/examples/register-machine/`](../examples/register-machine/) contains admission-ready graphs,
expected outputs, visible iterative addition and repeated-addition multiplication loops, both DECJZ
branches, a cancellable nonterminating loop, explicit observation, exact
beyond-`long` arithmetic, and a real graph-authored incremental Pi spigot. The Pi graph logs only a
new digit; its arithmetic and state transition are individual `bigint-op` nodes.

## Opt-in measurement and counter-instruction decision

Ordinary tests do not run extra worker JVMs or the measurement matrix. Run it explicitly:

```bash
mvn -pl ravenroot-server -am -Dravenroot.bigint.measurement=true \
  -Dtest=BigIntProgramOverheadMeasurementTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The measurement reports warm in-process increments at 10, 100, 1,000, and 10,000 operations; exact
arithmetic and payload-JSON serialization at 10, 100, 1,000, and 4,096 digits; one explicit log; a
cold handler operation; bounded 10/100-iteration real graph traversal samples; and separately timed
cold governed-program and same-runtime reuse-probe rows. The latter proves that warm governed worker
reuse is unavailable today: the same runtime and artifact start a fresh isolated worker for each
invocation, so reporting a “warm program” row would mislabel another cold start. The 1,000/10,000
traversal rows are deliberately not run: spawning an
actor for every elementary hop makes those opt-in samples disproportionately long, while their node
rows still cover those counts. Output always names this as a bounded local measurement, not a host
performance promise.

On the 2026-09-18 development run, warm node execution fell from roughly 12.1 μs/op at 10 iterations
to 3.1 μs/op at 10,000; 10- and 100-increment graph traversal samples were roughly
2.1–2.4 ms/increment; 4,096-digit addition was about 2.1 ms and its JSON projection about 0.6 ms.
The cold governed-program row was about 685 ms and the same-runtime reuse probe about 549 ms; each
started one worker, which is the structural result even though host timings vary. The gap is traversal and
actor lifecycle, not `BigInteger` arithmetic or a program worker: `bigint-op` starts zero program
workers. Profile v1 therefore does **not** add a counter instruction. The separate governed rows do
not change that decision: both are isolated worker starts rather than a reusable warm execution path,
and neither preserves a per-elementary-step cancellation boundary. A fused counter would remove the
very per-step cancellation and observation boundary the profile promises, while these measurements do
not demonstrate an end-to-end workload benefit that preserves it. Revisit only with governed cold and
warm end-to-end evidence for a separately specified node contract.
