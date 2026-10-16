# Education with executable graphs

Ravenroot can help learners inspect explicit control flow, state transitions, loops, branching, and
execution evidence. The same GraphML remains subject to ordinary validation and runtime policy. This
path is a set of bounded exercises, not a learning-management system or a claim that every visual
model is a formal simulator.

## Start here

1. Complete [Build your first graph](../get-started/first-graph.md) to learn nodes, outcomes, and
   GraphML saving.
2. Run [Execute a register-machine loop](register-machine-exercise.md) to validate and execute a
   visible loop with an exact expected result.
3. Follow [Visualize a two-state DFA](finite-state-exercise.md) to compare the complete operational
   flow with a textbook projection.
4. Open the [register-machine examples](../examples/register-machine/) to study visible counters,
   loops, DECJZ-style branching, cancellation, and a graph-authored Pi spigot.
5. Use the [finite-automata architecture evidence](../examples/finite-automata/) when teaching the
   difference between an interpreter experiment, a production behavior, and visual presentation.

## Learning boundaries

| Activity | What it demonstrates | What it does not prove |
|---|---|---|
| GraphML inspection | Explicit nodes, edges, outcomes, and persisted presentation metadata | Runtime execution or external effects |
| Textbook drawing model | A projection of real workflow nodes and edge paths | A new automata runtime or acceptance result |
| Test or Run | Governed execution under the connected service's catalog and policy | Unbounded computation, performance, or formal equivalence |
| Register-machine example | Construction from elementary visible operations within documented limits | A practical universal language or unrestricted resources |
| Architecture evidence | Reproducible parser/interpreter and adapter tests | A shipped finite-automaton bundle unless the running catalog exposes one |

Teachers should pin the Ravenroot version, distribute sanitized examples, use synthetic inputs, and
record expected results before class. Learners should be able to state which observation came from
the visual projection, which came from execution evidence, and which remains an architectural claim.
