# Frontend drawing model plugins

Ravenroot Workbench can install a frontend package from a browser-selected directory and use its
layout and renderer providers without rebuilding the core UI. A drawing model composes one layout
provider with one renderer provider, including compatible providers installed from separate
packages. Version 1 loads each provider from its declared self-contained package entry and uses a
bounded declarative SVG scene. It does not give plugin code Ravenroot's DOM.

This mechanism is separate from backend JAR plugin bundles. `plugin.sh`,
`RAVENROOT_ENABLED_PLUGINS`, and the backend bundle allowlist do not install frontend packages.

## Package a provider

A directory package contains `ravenroot-frontend-plugin.json` and every declared entry file. The
manifest uses `ravenroot.frontend-plugin/v1`, API `1.0`, a semantic package version, and an empty
permissions array. Layout and renderer providers are declared independently. A package may provide
either kind or both. A drawing model can name a pair in one package; Workbench also offers compatible
cross-package layout and renderer pairs. A provider's optional `requires` list must be satisfied by
the other provider's `capabilities`, otherwise the pair is not selectable:

```json
{
  "schema": "ravenroot.frontend-plugin/v1",
  "id": "example.my-drawing-model",
  "name": "My drawing model",
  "version": "1.0.0",
  "apiVersion": "1.0",
  "permissions": [],
  "layouts": [{ "id": "layout", "name": "My layout", "entry": "plugin.js" }],
  "renderers": [{ "id": "renderer", "name": "My renderer", "entry": "plugin.js" }],
  "drawingModels": [{ "id": "model", "name": "My model", "layout": "layout", "renderer": "renderer" }],
  "integrity": {}
}
```

The entry is an ES module with a default export. `layout(snapshot)` returns stable positions.
`render({ snapshot, layout })` returns `ravenroot.scene/v1`. The scene accepts only `circle`,
`path`, `line`, and `text` primitives with finite geometry, plain labels, restricted class names,
and no markup, URL, image, style, event-handler, or link fields. See
`ravenroot/ravenroot-ui/sdk/frontend-plugin-api.d.ts` for the complete contract.

The installable `Textbook automata` example is under
`ravenroot/ravenroot-ui/public/examples/frontend-plugins/textbook-automata/`. It demonstrates
circular states, a double accepting ring, an external initial arrow, loops, opposite curved edges,
labels, selection, and operational highlighting.

## Install and manage a package

1. Open Ravenroot Workbench in a Chromium-family browser, the tested v1 profile. Other browsers are
   unverified; package installation requires directory-upload and sandboxed blob-module support.
2. Open **Drawing options**, choose **Install package**, and select the package directory. Ravenroot
   validates the manifest, API compatibility, capabilities, entries, size limits, UTF-8 source, and
   any declared SHA-256 digests before writing the package to IndexedDB.
3. Choose the installed drawing model in **Drawing options**. Selection is recorded in the GraphML
   graph property `ravenroot.frontendDrawingModel.v1` and in the workspace snapshot.
4. Choose **Manage packages** to list every installed package and disable, enable, or remove either side of
   a composition independently. A missing, disabled,
   incompatible, removed, timed-out, or failed provider leaves the document untouched and restores
   integrated Design.

Installed bytes are local to the browser profile and origin. Exporting GraphML records the package
and model identity, not executable plugin bytes. Install the same compatible package on another
browser profile before selecting that model there. Static Vite deployments and the packaged
same-origin Workbench are supported. The embedded read-only viewer does not load or install frontend
packages in v1.

## Map an executable flow to a synthetic drawing

Drawing models never infer semantic states from node ids, labels, log messages, or visual shapes.
Open **Drawing options**, choose **Edit mapping**, and author `ravenroot.presentation-mapping/v1` JSON. Every state names a real
workflow node. Every transition names an ordered, contiguous list of real workflow edge ids whose
first source and last target match the mapped states. Ravenroot validates the mapping before adding
it to the undo history and persisting it as `ravenroot.frontendPresentation.v1`.

The example directory includes `dfa-even-ones-presentation.json`. To apply it to the even-ones
workflow, open the GraphML, choose **Edit mapping**, paste that file, save, and select **Textbook DFA/NFA**.
The diagram shows `qEven` as initial and accepting, `qOdd`, both `0` loops, and both `1`
transitions. Select a state or transition to select and inspect its underlying node or ordered edge
path. Choose **Full operational flow** to return to every executable parsing, decision, consumption, routing,
logging, and terminal node. Position values in the mapping are editable in Ravenroot and survive
save, reopen, and export.

Operational highlights mean only that mapped workflow nodes or edges have current execution
evidence. They do not claim that the projection is a production finite-automaton runtime, prove
acceptance, or replace the actual execution result.

## Lifecycle and isolation

Each active plugin runs in an iframe with an opaque sandbox origin and script permission only. The
sandbox has no same-origin access to the Workbench DOM or browser storage. Its response policy sets
`connect-src 'none'`, disallows images, child frames, workers, forms and navigation, and permits blob
modules only inside that sandbox. The parent accepts only the validated declarative scene described
above. It never passes service tokens, credentials, runtime clients, browser storage, or host command
functions.

The host gives each layout/render request a generation, cancels it on document or model changes,
ignores stale results, and destroys the sandbox after a three-second request or startup timeout.
Removing or disabling a package also destroys its sandbox. A sandboxed iframe is a browser security
boundary, but its JavaScript still shares the page's UI thread; the timeout can discard or destroy a
settled or asynchronously stalled provider, while browser process scheduling is the protection
against a provider that never yields. Install code only from a source you trust.

This is code isolation for the supported message protocol. It is not a claim that a plugin has been
audited, signed, or safe to redistribute. Optional integrity entries detect changed bytes but do not
establish publisher identity.

Host-owned selection and inspection map synthetic ids back to declared real evidence. Editing the
mapping uses Ravenroot's command history and GraphML serializer. The plugin cannot directly edit the
document, bypass validation, write undo history, authorize runtime work, or change execution
configuration, routing, payloads, graph identity, or results.
