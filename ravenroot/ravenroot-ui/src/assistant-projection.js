// What of the author's own work is allowed to leave the product, field by field (consent contract).
//
// ── WHY THIS IS A MODULE AND NOT A FEW LINES IN app.js ───────────────────────────────────────────
//
// These projections were correct inline, and unpinned. That is a bad combination, because the
// failure mode is not a bug — it is a REASONABLE EDIT. `properties: node.properties` is one line, it
// is what someone wanting the assistant to reason about node configuration would naturally add, and
// it puts user-authored secret bindings into a third party's payload. Nothing would have gone red.
//
// Consent is the reason the distinction matters. The user consents to a LIST OF CLASS NAMES — "open
// graph", "event stream". If the content behind a name can silently widen, they consented to a
// label rather than to a payload, and the disclosure the chips make is worth exactly nothing. So
// every projection here is an ALLOWLIST, each is exported so a test can pin it against a fixture
// that carries the dangerous field, and each says what it refuses and why.
//
// DOM-free and network-free, like `assistant-context.js` and `panel-layout.js`.
//
// ── PROJECTION NORMALIZATION IS EXPLICIT ─────────────────────────────────────────────────────────
//
// Three deliberate normalization rules shape the projected payload:
// * `pick` writes `null` for an absent optional field instead of omitting the key. Deliberate —
// a payload whose shape varies by which optional fields a node happened to carry leaks that
// fact to the provider.
// * `graphSummary` coerces a falsy display name to `""` rather than passing `undefined`.
// * `graphSummary` and `validationFindings` guard a null graph, which their only caller already
// makes unreachable. Kept because "unreachable" is a claim about today's call site.
// These rules explain why absent optional values become explicit nulls in the payload.

// ── THE OPEN GRAPH ───────────────────────────────────────────────────────────────────────────────
//
// Structure only. Node PROPERTY VALUES are refused outright, and that is the load-bearing exclusion:
// ADR 0018 permits user-authored secret bindings in graph content — a node property holding a
// `credentialRef`, or in a misconfigured graph the secret itself — so sending property values would
// send the author's credentials to a model provider. The graph's SHAPE answers the questions the
// assistant is for ("what runs after what", "which node is the bottleneck"); its property values
// answer none of them.
//
// A summary rather than the source XML for the same reason it is not `sourceXml`: a GraphML document
// is larger, and for a `graphml` document mostly presentation attributes that answer nothing.
export const GRAPH_NODE_FIELDS = Object.freeze(['id', 'name', 'type', 'behavior']);
export const GRAPH_EDGE_FIELDS = Object.freeze(['id', 'source', 'target', 'outcome']);

const SENSITIVE_CATALOG_NAME = /(?:secret|password|passwd|token|credential|api[-_.]?key)/i;

/** Proposal-safe catalog schema: declarations only, with secret-class properties absent. */
export function assistantCatalogSnapshot(catalog = []) {
  return (catalog || []).map(descriptor => ({
    behavior: String(descriptor?.behavior || ''),
    displayName: String(descriptor?.displayName || ''),
    category: String(descriptor?.category || ''),
    visualType: String(descriptor?.visualType || descriptor?.nodeType || ''),
    properties: (descriptor?.properties || [])
      .filter(property => !String(property?.type || '').toUpperCase().includes('SECRET'))
      .filter(property => !SENSITIVE_CATALOG_NAME.test(String(property?.name || '')))
      .map(property => ({
        name: String(property?.name || ''),
        type: String(property?.type || ''),
        required: property?.required === true,
        defaultValue: Object.hasOwn(property || {}, 'defaultValue')
          ? String(property.defaultValue) : null,
        allowedValues: Array.isArray(property?.allowedValues)
          ? property.allowedValues.map(String) : [],
      })),
    outcomes: (descriptor?.outcomes || []).map(outcome => ({
      name: String(outcome?.name || ''),
      fromProperty: String(outcome?.fromProperty || ''),
    })),
  }));
}

export function graphSummary(graphData, displayName = '') {
  if (!graphData) return null;
  const nodes = Array.isArray(graphData.nodes) ? graphData.nodes : [];
  const edges = Array.isArray(graphData.edges) ? graphData.edges : [];
  return {
    name: String(displayName || ''),
    format: graphData.format,
    nodeCount: nodes.length,
    edgeCount: edges.length,
    // `pick`, never a spread-and-delete: a spread that removes known-bad keys admits every field a
    // future parser adds, which is the opposite of an allowlist.
    nodes: nodes.map(node => pick(node, GRAPH_NODE_FIELDS)),
    edges: edges.map(edge => pick(edge, GRAPH_EDGE_FIELDS)),
  };
}

// ── THE EVENT STREAM ─────────────────────────────────────────────────────────────────────────────
//
// METADATA ONLY, and `detail` is deliberately absent. This is the same ruling `execution` already
// carries, applied here for a reason that was traced rather than assumed:
//
// `ExecutionEvent.detail` is built in exactly one place (`ExecutionMonitor.publish`), and for
// EXECUTION_FAILED and NODE_FAILED it is `message(error)` — the ROOT CAUSE's `getMessage()`,
// verbatim, unbounded, with no redaction anywhere in core. For NODE_COMPLETED it is
// `"outcome=" + outcome`, and `outcome` is behavior-controlled and, for the CEL-decision and
// HTTP-request built-ins, read straight from GRAPH-AUTHORED NODE PROPERTIES.
//
// So `detail` is a channel through which an exception message — which may quote the payload that
// caused it — or a string the graph author wrote reaches a third-party provider, behind a chip that
// says only "Event stream". An unbounded free-text field is not something a user can consent to by
// name. The shape of the run is what the assistant needs to reason about congestion; the failure
// text is not, and it is the part that carries data. The authenticated Runtime activity surface may
// now receive bounded `message`/`output` projections with redaction metadata; those author-only fields
// are excluded here exactly like legacy `detail`, and `RUNTIME_EVENT_FIELDS` remains a closed list.
//
// The refusal here is about EGRESS. Legacy `detail` is absent from HTTP and SSE; the authenticated
// author instead receives a separate bounded diagnostics projection, which remains excluded here.
// The panel also renders `publicReason`, a classifier
// restricted to letters, digits and `. _ - :`, and that classifier is deliberately NOT projected to
// the assistant either: on NODE_COMPLETED it is the graph author's own outcome name, which is
// egress of an authored string for no gain to the congestion question this projection exists for.
export const RUNTIME_EVENT_FIELDS = Object.freeze(['type', 'nodeId', 'executionId', 'occurredAt']);

export function runtimeEventProjection(event) {
  if (!event || typeof event !== 'object') return null;
  return {
    type: String(event.type || ''),
    nodeId: event.nodeId || null,
    executionId: event.executionId || null,
    occurredAt: event.occurredAt || null,
  };
}

// Structure only, and it says which checks it ran. The UI has no aggregate graph validator, so
// attaching a class called "validation errors" without naming its boundary would overstate it.
// Node ids and edge endpoints are structural identifiers, never property values.
export const VALIDATION_CHECKS = Object.freeze([
  'dangling-edge-source', 'dangling-edge-target', 'unnamed-node',
]);

export function validationFindings(graphData) {
  if (!graphData) return null;
  const nodes = Array.isArray(graphData.nodes) ? graphData.nodes : [];
  const edges = Array.isArray(graphData.edges) ? graphData.edges : [];
  const nodeMap = graphData.nodeMap || {};
  const findings = [];
  for (const edge of edges) {
    if (!nodeMap[edge.source]) {
      findings.push({ kind: 'dangling-edge-source', edge: `${edge.source}→${edge.target}`, missing: edge.source });
    }
    if (!nodeMap[edge.target]) {
      findings.push({ kind: 'dangling-edge-target', edge: `${edge.source}→${edge.target}`, missing: edge.target });
    }
  }
  for (const node of nodes) {
    if (!String(node.name || '').trim()) findings.push({ kind: 'unnamed-node', node: node.id });
  }
  return { checks: [...VALIDATION_CHECKS], findings };
}

// Copies exactly the named fields and nothing else. A key absent from the source becomes `null`
// rather than being omitted, so the shape a provider receives does not itself leak which optional
// fields a given node happened to carry.
function pick(source, fields) {
  const target = {};
  for (const field of fields) target[field] = source?.[field] ?? null;
  return target;
}
