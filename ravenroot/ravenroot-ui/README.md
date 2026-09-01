# Ravenroot UI

Ravenroot UI is the framework-independent graphical surface of Ravenroot. This first modular version
preserves the behavior and appearance of the standalone viewer imported from `GraphMLEditor`, while
making its source, dependencies and compatibility contracts independently testable.

## Current capabilities

- load GraphML/XML and Graphify JSON from a local file, drag and drop, or the `file` query parameter;
- preserve and display graph attributes understood by the current parser;
- render with Cytoscape.js and the existing Dagre, ELK, Euler, COSE, preset, n8n-style and D3 elastic
  layouts;
- inspect nodes and edges, filter types, trace downstream paths, use the minimap and adjust visual
  parameters;
- create, edit and delete Ravenroot nodes and edges through the inspector;
- edit canonical fields and typed additional GraphML properties;
- undo and redo every edit — nodes, edges, properties, moves and composed operations such as a
  multi-node drag or a delete that takes incident edges with it, each of which is one step;
- see whether the workflow has unsaved changes, save it as GraphML (`Ctrl/⌘ + S`), and get a
  confirmation before a new workflow, a file load or leaving the page would discard them;
- load the installed node catalog from the service and create nodes through its typed palette/forms;
- preserve unedited complex XML extension blocks while serializing executable GraphML;
- validate and start the active graph through `ravenroot-server`;
- receive correlated Server-Sent Events in the runtime activity log;
- project active, completed, fallback and failed nodes in Cytoscape and Elastic D3 rendering.

Both formats use the same visualization pipeline after parsing. Small interactive examples live in
`public/examples`. The sanitized GraphML round-trip corpus is shared with the JVM core from
`../ravenroot-core/src/test/resources/graphml-corpus` and is exercised by both test suites.

## Authoring UI text hierarchy

Ravenroot keeps authoring guidance at three deliberately different levels:

| Level | Stays visible | Examples |
|---|---|---|
| Current state and consequence | Yes | selected/inherited values, required markers, validation errors, runtime refusals, routing warnings, unsaved state |
| Stable contextual guidance | On activation | what runtime nature controls, catalog/property descriptions, join and bypass semantics, program source/durability rules, edge routing and command meaning |
| Short action identification | Hover or focus tooltip | icon-only panel actions, compact controls and disabled-action reasons |

The authoring surfaces place the Inspector first. Node, edge, multi-node and
programmable-node forms no longer repeat long stable explanations below their controls. A real
question-mark button opens the single document-level `#contextual-help-popover`; dynamic state,
errors, warnings, required status and consequences remain in the form. The Assistant's AI
disclosure and the Credentials window's one-shot/never-returned value warnings intentionally remain
persistent: those statements qualify the action currently being taken rather than explain a stable
field concept.

`src/contextual-help.js` owns selection, one-open-at-a-time state, viewport-safe placement and
pointer/touch/keyboard dismissal. Pointer activation does not blur the editor control currently in
use. Keyboard activation moves focus into the non-modal help region; Escape and the visible Close
button return focus to the opener. Outside pointerdown dismisses without cancelling the user's
intended target. Content is copied with `textContent`, and every trigger truthfully maintains
`aria-expanded` and `aria-controls`. The component uses only existing CSS color, shadow and focus
tokens plus the inline `#i-context-help` vector mark; it adds no dependency and does not participate
in GraphML, save or runtime behavior.

## Run locally

Node.js 24 LTS with npm 11 is required. Install once and start the development server:

```sh
npm install
npm run dev
```

Then open `http://127.0.0.1:5173/`, or load an example directly:

- GraphML: `http://127.0.0.1:5173/?file=/examples/ravenroot-minimal.graphml`
- Programmable GraphML: `http://127.0.0.1:5173/?file=/examples/ravenroot-programmable.graphml`
- Graphify JSON: `http://127.0.0.1:5173/?file=/examples/graphify-minimal.json`

For live execution, start `ravenroot-server` on port 8080. Enter an access token in the command bar
and select **Authenticate**. The token exists only in page memory: it is not written to browser
storage, cookies or URLs, and **Forget token** removes it immediately. Press Play or
`Ctrl/⌘ + Enter`; the initial payload is the text in the Payload field.

The default service URL is same-origin. During `npm run dev`, Vite proxies `/v1` and `/health` to
`http://127.0.0.1:8080`; set `RAVENROOT_SERVICE_URL` before starting Vite to use another local
backend. In the executable JAR and OCI image the Java server publishes these assets directly, so no
proxy or second web server is needed.

The bundled production UI defaults to same-origin. Manually entering another service origin requires
exact confirmation before a bearer token can be sent. When the UI is bundled, that destination must
also be present in the server's `RAVENROOT_UI_CONNECT_ORIGINS` CSP allowlist; the default is only
`'self'`. Ravenroot Server independently controls allowed browser callers through
`RAVENROOT_BROWSER_ALLOWED_ORIGINS`. The CSP destination allowlist and CORS caller allowlist are
opposite-direction trust boundaries and do not implicitly expand each other.

The source now uses standard ES modules and therefore must be served over HTTP; opening
`index.html` through `file://` is no longer supported. Loading by file picker and drag-and-drop is
unchanged.

## Verify and publish

```sh
npm ci
npm audit --audit-level=moderate
npm test
npm run build
npm run preview
```

Run this validation with Node.js 24 LTS and npm 11, the toolchain used by CI and the container build. The
full audit intentionally includes development dependencies: `nanoid`, `postcss`, and `undici` are
currently reached only through the dev-only Vite and jsdom toolchains. A separate
`npm audit --omit=dev --audit-level=moderate` confirms the runtime dependency tree.

The root `allowScripts` policy in `package.json` approves only the exact installed versions of
`esbuild` (its postinstall selects the platform binary) and the optional macOS `fsevents` watchers
used by Vite/Rollup and Playwright. After a dependency update, inspect
`npm approve-scripts --allow-scripts-pending` and approve each expected package explicitly; do not
bulk-approve unreviewed install scripts.

`npm run build` writes self-contained static assets to `dist`. The CI image assembles that already
built artifact with `Dockerfile.ci` at `/opt/ravenroot/ui-resources/ui/`; neither the static publish
path nor the final image contains build-time `node_modules`. Consequently the final-image Buildx SBOM
and provenance remain useful for its runtime contents, but do not cover build-only Node modules: keep
the lockfile SCA audit above as a separate release gate. No Node.js or application server is required
to publish `dist` as static assets.

## Source structure

- `index.html` contains the existing page structure and toolbar markup;
- `src/styles.css` contains the existing visual rules, extracted without redesign;
- `src/graph-parsers.js` owns GraphML/XML and Graphify JSON detection and normalization;
- `src/graph-document.js` owns workflow creation, validation and DOM-backed GraphML serialization;
- `src/graph-commands.js` owns the reversible command model, the bounded undo/redo stack and the
  dirty state. Every edit is a command applied to the canonical document; the renderer is rebuilt
  from the document afterwards and never owns a mutation. Deleting captures the element objects and
  their array positions and patching records the previous value of exactly the keys it writes, so a
  GraphML round trip is unchanged after any undo/redo sequence;
- `src/graph-editing.js` builds those commands from user gestures and owns Modify-mode guards;
- `src/ui-text.js` owns the dependency-free UI message catalog and locale fallback used by the
  application command model. The mechanism, accessibility contract and deliberately limited
  coverage are documented in `../../docs/architecture/ui-internationalization.md`;
- `src/contextual-help.js` owns the one shared, non-modal long-help surface used by authoring forms;
  persistent state and validation remain at their render sites, while stable explanatory content is
  selected by the activating button and rendered as text;
- `src/panes.js` owns the shared splitter arithmetic for side-by-side graph panes, the two side
  zones and the bottom dock. Every separator supports pointer capture and arrow keys, reports its
  live relative position through `aria-valuenow`, and preserves the measured 360px graph-width and
  204px graph-height floors. A side splitter with an open panel remains operable at its collapsed
  28px rail: an outward pointer or arrow gesture expands and resizes it, while an inward gesture is
  a no-op. If every panel in that zone is closed, the visible boundary is disabled and removed from
  the tab order until a rail mark or the Panels index reopens one;
- `src/assistant-*.js` own the authoring assistant panel (ADR 0025), a read-only capability.
  `assistant-context.js` composes the six context classes in ONE pass so a chip reading *attached*
  and a payload carrying the class cannot disagree; `assistant-session.js` owns the panel states
  and their distinguished inert reasons; `assistant-client.js` reaches this product's own service
  and only ever its two `/v1/assistant` paths. `assistantUrl` refuses any other **path**; the
  **host** is the base URL passed in, so "no provider call originates in the browser" rests on the
  single construction site — pinned by a test that reads `app.js` rather than left to habit. The
  runtime-event tail the `events` class attaches is a view of the ACTIVE document's record
  (`execution.events`), because a workspace-scoped buffer carried one graph's executionIds into
  context sent about another — a provenance defect the attachment claim cannot see, since it
  constrains presence, not provenance. The panel ships **closed** because a second open
  unbounded panel reproduces a previously fixed right-column overflow — see the `defaultClosed`
  comment in `src/panel-layout.js` for the measurement; it opens from the rail mark or the Panels
  index. There is no server-side assistant service yet, so the panel reports
  `INERT(service-unavailable)` rather than simulating a reply;
- `src/assistant-disclosure.js` is the **Article 50 disclosure capability**, and it is
  deliberately not part of the panel. It is DOM-free, network-free and imports nothing from the
  editor, because Art. 50(1) binds whoever builds and places a conversational system on the market
  — on the current qualification (ADR 0017) that is the **integrator**, not this project, so the
  capability has to be reusable by them rather than wired into a surface they will not use.
  `admitTurn(transcript, turn, append, options)` is the admission path: it emits the disclosure
  ahead of the first AI-origin turn and only ahead of the first, so the ordering Art. 50(5)
  requires is the function's return value rather than something a renderer must remember to draw.
  Both seams — `append` and `options.disclosed` — exist so a caller keeps **its own** transcript
  shape and storage; the second was added because the demonstration in
  `test/assistant-disclosure.test.js` failed without it, re-disclosing before every reply for any
  caller whose entries lack this project's `role` field. That test builds a second conversational
  endpoint sharing no code with this UI and is the documented requirements's "an integrator can compose it without
  reimplementing it". `describedById` is what makes the disclosure *programmatically associated*
  with the content it discloses rather than merely adjacent to it. It is **not consent**: it has no
  control, no stored answer, and does not gate egress (the consent gate is separate).
  **Panel budget:** with the Assistant **closed** — the shipped default, and the guarantee that
  matters — the Modify-mode Inspector overflow is **0px at both 1280x800 and 1440x900**, unchanged.
  With it **open** the Inspector overflows by **229px / 166px**, against **223px / 159px** on the
  baseline: the disclosure costs ~6px by wrapping the composer sentence onto a second line.
  That open-panel overflow is pre-existing and is precisely the measured reason ADR 0025 §9 ships
  the panel closed — the disclosure does not introduce it and does not materially worsen it. Measured with a
  positive control reporting 522px in every configuration, because a zero from a measurement that
  cannot report overflow is worth nothing;
- `src/panel-layout.js` owns the versioned panel-layout descriptor: zone, order, open/collapsed
  state and normalized dimensions. It never stores pixel positions or sizes; invalid descriptors
  fall back as a whole. The two explicit compatibility exceptions are a string id from a panel no
  longer present in this build, which is ignored, and a known panel absent from storage, which is
  appended at its default. The Panels index always exposes **Reset layout**. Only the bottom dock
  can be maximised because column panels already occupy their available vertical axis at rest;
- `src/runtime-client.js` owns authenticated HTTP execution commands and fetch-based SSE streaming.
  Bearer credentials remain in the `Authorization` header, cookies are explicitly omitted, SSE
  frames and reconnect attempts are bounded, and reconnects resume with `Last-Event-ID`. A 401 may
  invoke the configured external token provider once; a 403 is terminal and exposes a revoked state;
- `src/app.js` owns Cytoscape rendering and the existing interactions;
- `public/examples` contains small, real, server-executable examples: every GraphML file here is
  admitted by `POST /v1/executions` and names only behaviors the standard catalog registers, checked
  by `ravenroot-server`'s `ShippedExampleCorpusTest` and this module's `shipped-examples.test.js`.
  It is not a place for compatibility fixtures anymore: one used to live here
  (`ravenroot-minimal.graphml`'s yEd-style `name`/`start`/`end` keys, graph id `compatibility`) and
  was a new user's first, broken contact with the product. That fixture now lives at
  `test/fixtures/legacy-yed-compatibility.graphml`, exercised by `graph-parsers.test.js` for what it
  actually tests: backward-compatible parsing, not a graph the server accepts;
- `test` protects both input contracts independently from rendering, and `test/fixtures` holds
  fixtures shared with `ravenroot-core`'s GraphML corpus (`graphml-corpus.test.js`) as well as
  UI-only ones.

## Architectural direction

The UI remains deployable as static assets and embeddable without requiring a server-side UI
framework. Vite and Vitest are development/build tools only; the shipped application does not depend
on a server-side framework. Subsequent work can introduce autonomous Web Components around the now
stable document and runtime contracts for:

- graph documents and independent view state;
- reusable contextual toolbars;
- multiple simultaneous graph viewports with maximize/minimize support;
- undo/redo and drag-and-drop connections;
- artifact review, approval and activation views for governed programmable nodes;
- cancellation, timeouts and structured payloads.

The runtime integration correlates monitoring data by engine, graph version, execution and graph
node identity. Persistence integrations remain outside the UI boundary.
