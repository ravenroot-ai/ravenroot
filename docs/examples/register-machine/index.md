# Register-machine examples

These are ordinary executable GraphML documents for [Register Machine Profile v1](../../reference/register-machine-profile.md).
Open them in the Ravenroot editor to inspect or change every operation, or validate one with
`ravenroot validate --register-machine FILE`.

| File | Expected observation |
|---|---|
| `counter-decjz.graphml` | exercises both DECJZ branches and halts with `counter="0"` |
| `addition.graphml` | visible DECJZ-style unit-increment loop computes `37 + 23`, leaving `sum="60"` |
| `multiplication.graphml` | visible repeated-addition loop computes `12345 × 7`, leaving `product="86415"` |
| `gcd.graphml` | Euclid’s loop halts with `a="21"` |
| `beyond-long.graphml` | `wide="922337203685477580812345678901234567891"` survives GraphML, execution, payload JSON, and durable SQLite result storage as text |
| `observation.graphml` | the only log is `counter=41`; arithmetic nodes emit no output |
| `division.graphml` | `quotient="12"`, `remainder="3"` |
| `encoded-stack.graphml` | two base-100 pushes and one pop leave `popped="42"`, `stack="17"` |
| `nonterminating.graphml` | increments until an external cancellation; no iteration begins after cancellation settles |
| `incremental-pi.graphml` | graph-authored spigot logs successive digits `3, 1, 4, 1, 5, 9, …`; cancel it externally |

The conformance tests execute every finite graph, validate its exact output, cancel both infinite
graphs, and assert the first Pi digits. The reusable compiler/oracle tests also generate addition
programs from SET/INC/DECJZ/HALT instructions and compare final registers, halt location, and step
count over generated inputs.

Two further patterns need no special node:

- Euclidean division is a `floor-divide` node for the quotient followed by a `modulo` node for the
  remainder. Both consume the same visible dividend and nonzero divisor registers.
- An encoded stack can use one non-negative integer register with base `B`: push `x` as
  `stack = stack * B + x`; pop as `x = stack modulo B` then `stack = stack floor-divide B`. A small
  instruction interpreter stores the program counter and encoded stack in registers and uses visible
  equality tests and decisions for dispatch. Values must remain within the 4,096-digit ceiling.

These patterns are examples, not hidden Pi-, stack-, or interpreter-specific runtime code.
