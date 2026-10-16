# Textbook DFA projection reference

This page preserves the optional frontend drawing-model example shipped with Ravenroot. It is a
client-side presentation reference for plugin authors and reviewers. It is not a guided execution
exercise, an automata simulator, or evidence that the supplied GraphML implements a runnable DFA.

## What the example contains

The files live under
`ravenroot/ravenroot-ui/public/examples/frontend-plugins/textbook-automata/`:

- `ravenroot-frontend-plugin.json` and `plugin.js` define an installable presentation package;
- `dfa-even-ones-sanitized.graphml` is a 22-node, 24-edge inspection fixture whose generic
  `BEHAVIOR` nodes have no behavior identifiers or runtime configuration; and
- `dfa-even-ones-presentation.json` maps two synthetic states and four transitions to real node and
  edge identifiers in that fixture.

The package renders a bounded SVG overlay. Selecting a projected state or transition selects its
mapped workflow evidence. **Full operational flow** returns to the underlying GraphML.

## Inspect the projection

These steps exercise browser-side package installation and mapping only:

1. Open `dfa-even-ones-sanitized.graphml` in Workbench.
2. Open **Drawing options**, choose **Install package**, and select the `textbook-automata`
   directory.
3. Choose **Edit mapping**, paste `dfa-even-ones-presentation.json`, and save it.
4. Select **Textbook DFA/NFA**.
5. Confirm that the overlay shows `qEven`, `qOdd`, two `0` loops, two opposing `1` transitions, an
   incoming initial marker, and an accepting ring around `qEven`.
6. Select projected elements and confirm that the Inspector identifies the mapped workflow node or
   ordered edge path.
7. Choose **Full operational flow** and confirm that the underlying 22-node, 24-edge fixture returns.

The overview minimap and graph statistics describe the underlying operational graph, even while the
drawing-model overlay shows two synthetic states. Keep both views visible only when the distinction
is explained; they are different presentations of one document, not competing node counts.

## Evidence boundary

Ravenroot's browser tests verify package installation, the two-state/four-transition scene, mapped
selection, full-flow restoration, and simulated activity highlighting. Those tests use the UI
fixture server and stub runtime routes. They do not prove that the supplied GraphML is executable or
that a real server catalog is connected.

The fixture intentionally carries no executable behavior mapping. **Test** or **Run** is outside
this example's contract. Use an independently validated executable graph and a connected runtime
when teaching execution, and use terminal runtime output when making an acceptance claim.

The package adds no network, storage, DOM, backend behavior, automata execution, determinization,
minimization, or DOT import authority. Removing or disabling it leaves the GraphML unchanged and
restores integrated Design.

For the API, package lifecycle, sandbox and mapping contracts, read
[Frontend drawing model plugins](../developer-guide/frontend-drawing-models.md). For a guided
exercise with an observed runtime result, use
[Execute a register-machine loop](register-machine-exercise.md).
