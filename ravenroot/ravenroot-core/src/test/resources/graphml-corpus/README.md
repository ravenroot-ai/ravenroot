# Sanitized GraphML compatibility corpus

The `accepted` fixtures cover canonical execution fields, unknown node and edge properties, all
GraphML scalar types, defaults, scope resolution, namespace-qualified graphical metadata, parallel
edges, self-loops and edge-before-node ordering.

The `rejected` fixtures represent inputs whose property-graph meaning would otherwise be guessed:
overlapping key names, complex executable fields, undeclared endpoints, invalid scalar values,
nested graphs, document types/entities, late key declarations and orphan data. All names and
metadata are synthetic. These files are the core-side shared ambiguity-fixture contract for the UI
wave; consumers must reject each named file without resolving an entity or guessing a property scope.

Tests compare parsed XML structure and property-graph semantics. Prefix spelling, indentation and
attribute order are not semantic; namespace URIs, element/attribute names, ordered content, declared
keys, types, lexical values, IDs and topology are semantic.

## `reserved-format-version.graphml`: both sides refuse it, and why the fixture still exists

`rejected/reserved-format-version.graphml` (INT-05) is rejected by both
`ravenroot-core` and `ravenroot-ui`. Core refuses it under SEC-09, which reserves the whole
`ravenroot.` property namespace as Ravenroot's own operative state and refuses graph content that
claims it; `ravenroot-ui`'s GraphML parser carries the same guard (`rejectReservedProperties` in
`ravenroot-ui/src/graph-parsers.js`), with the same public message and the same reserved-vs-preserved
distinction. Both refusals are pinned — `GraphMlCorpusTest` on the Java side,
`ravenroot-ui/test/graphml-corpus.test.js` on the JS side — so the two can no longer say different
things about this fixture without a test failing.

Without the UI guard, the document loads and the reserved property becomes an ordinary node
property. The fixture stays in `rejected/` because it records what profile 0 does **today** with the
key a future format marker would use. Introducing that marker requires moving this file out of
`rejected/` and explicitly updating the compatibility boundary.

Note the placement: the refusal fires because the reserved property is **node** content. The same key
declared at **graph** scope is accepted today, on both sides. Core's check walks imported vertices and
edges only (pinned in `GraphMlProfileReportTest` and described in
`docs/architecture/graphml-format-profile.md`); `ravenroot-ui`'s guard mirrors exactly that shape on
purpose (pinned in `ravenroot-ui/test/graphml-corpus.test.js`) rather than refusing more than core
does, which would only flip the divergence the other way. That gap is not in this corpus, because a
file in `accepted/` would read as an endorsement of it.
