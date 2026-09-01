# Restricted SpEL extension

`ai.ravenroot.extensions.spel` is an optional migration package. It contributes
`spel.transform` and `spel.decision`; it is not part of Ravenroot core or the default distribution.

The expression root is exactly the incoming canonical payload: null, Boolean, signed 64-bit integer,
finite double, text, or recursively bounded maps and lists. A custom map-only accessor supports
`customer.name`; standard indexing supports `items[0]` and `map['key']`. Comparisons, Boolean
operators, literals, ternary/Elvis, inline collections, and one selection or projection are supported.
`spel.decision` requires an actual Boolean and preserves the canonical payload while choosing the
configured `trueOutcome` or `falseOutcome`.

The package deliberately does not expose variables, functions, methods, Java types, constructors,
beans, assignment, arithmetic, regex, reflection, application objects, attributes, environment,
filesystem, or network access. `class`, `getClass`, `metaClass`, `classLoader`, `declaringClass`,
`protectionDomain`, `__proto__`, `prototype`, and `constructor` are refused case-insensitively as
property names, literal map indexes, inline-map keys, and recursively as input/result keys. Null-safe
navigation/index/selection/projection, first/last selection, computed indexes, and text indexing are
outside the supported subset. Indexes are literal integers into lists or quoted safe keys into maps;
a semantic preflight checks every reached target before Spring evaluation. Evaluation uses a fresh
read-only `SimpleEvaluationContext` for every invocation and a closed structural-and-semantic AST
policy before execution.

Budgets are fixed by the package: 2,048 expression characters, 4,096 UTF-8 expression bytes, 128 AST
nodes, AST/tree depth 16, 2,048 SpEL operations, 256 entries per collection, 4,096 values, 64 KiB text,
1 MiB canonical encoding, one selection or projection, 32 global and eight per-node admitted
evaluations, and a one-second monotonic deadline. Capacity and deadline failures are fail-fast and
permits remain held until the bounded worker actually exits. Public failures contain only stable
`SPEL_*` codes.

Build and validate the private bundle:

```sh
./plugin.sh build spel
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-spel/target/plugin-bundle
```

Spring Expression and its runtime dependencies remain inside that bundle. See
[`DEPENDENCIES.md`](DEPENDENCIES.md) for the pinned inventory, licenses, and source-artifact hashes.
