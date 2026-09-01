import { assertStableEdgeId, stableEdgeIdViolation } from './stable-edge-id.js';

const GRAPHML_NS = 'http://graphml.graphdrawing.org/xmlns';

export const NODE_KINDS = ['START', 'PASSTHROUGH', 'BEHAVIOR', 'END', 'ERROR'];

// THE vocabulary of visual node types, and the only one. It lived in `app.js` as a private
// `NODE_TYPES` used to build the legend and the Inspector's "Visual type" select, while
// `graph-parsers.js` carried a second, hand-written copy of the same vocabulary spelled as a chain
// of `classification === 'CONSUMER'` comparisons -- six of the ten, with `system` missing outright,
// so a node saved as System could not be read back as one no matter what the document said.
//
// `NODE_KINDS` avoids the same trap ten lines above: a canonical list on one side and a
// hand-maintained transcription of it on the other. The visual-type list
// lives here, next to NODE_KINDS, because `graph-document.js` has no imports of its own and both the
// parser and the app can therefore read it without a cycle.
export const NODE_VISUAL_TYPES = Object.freeze([
  { type: 'start', label: 'Start' }, { type: 'end', label: 'End' },
  { type: 'terminal', label: 'Terminal' }, { type: 'error', label: 'Error' },
  { type: 'consumer', label: 'Consumer' }, { type: 'handler', label: 'Handler' },
  { type: 'agent', label: 'Agent' }, { type: 'flow', label: 'Flow' },
  { type: 'actor', label: 'Actor' }, { type: 'system', label: 'System' },
].map(Object.freeze));

const NODE_VISUAL_TYPE_NAMES = new Set(NODE_VISUAL_TYPES.map(entry => entry.type));

/**
 * Whether a node's KIND owns its visual type, leaving the author no say in it.
 *
 * START, END and ERROR are fixed terminals -- `app.js` stamps their `nodeType` from the kind
 * and ignores whatever the Inspector's select holds, so for those three the kind is the truth and an
 * authored `classification` in the document must not be believed. For every other kind the visual
 * type IS an author's choice, so the saved value wins and the kind is only a fallback for documents
 * that carry no choice at all. Both sides of the round trip ask this one question rather than each
 * repeating the list of three.
 */
export function kindOwnsNodeType(kind) {
  return kind === 'START' || kind === 'END' || kind === 'ERROR';
}

/**
 * The visual type a document authored, or `null` when it authored nothing recognizable.
 *
 * `null` is what routes the caller to the legacy kind/shape heuristics, and it is deliberately
 * also the answer for a value outside the vocabulary -- an unknown string must not reach the canvas
 * as a node type nothing can draw.
 */
export function authoredNodeType(classification) {
  const normalized = String(classification || '').trim().toLowerCase();
  return NODE_VISUAL_TYPE_NAMES.has(normalized) ? normalized : null;
}

// A valid graph may contain at most this many ERROR nodes. The rule is a ceiling, not an obligation:
// a graph is not invalid merely because it lacks an Error node. Naming the constant as a maximum
// prevents a reader from mistaking the ceiling for a required count.
//
// The ceiling stays at one, and for a reason unrelated to the obligation that is gone: the JVM
// runner keys what reached a terminal by terminal KIND, one field per kind, so a second ERROR node
// would give one field two concurrent writers. See GraphDefinition.MIN_ERROR_NODES on the Java side.
//
// Named and exported, not inlined into validateWorkflow()'s comparison, because the JVM validator
// enforces its own independent copy of this same rule -- changing only one side produces an editor
// and a runtime that disagree about the same document. MinimalStructureParityTest in ravenroot-core
// is what now keeps the two honest: it parses this rule out of this file and runs the Java validator
// against it, so a divergence in either the number or the FORM of the comparison fails a test.
export const MAX_ERROR_NODE_COUNT = 1;

// The graph-level property that says how this document's fan-ins are to be read. Present:
// a node with several incoming edges is a synchronisation point only where the author declared one
// (`joinPolicy`, `joinQuorum` or `joinTimeout`). Absent: the legacy reading, where any node with
// two or more distinct predecessors was inferred to be a join and waited for all of them -- which is
// how a node drawn looping back to itself became its own second predecessor and waited for itself.
//
// Stamped on documents this editor *creates*, and never written onto one it merely opened. Writing
// it into an existing document would change what that document means without its author asking, and
// re-address a recorded file; migrating a legacy graph is a deliberate authored action, not
// something a save does on the way past. An opened document keeps whatever it had, because
// serializeGraphML edits `sourceXml` in place and never removes a graph-level <data> it did not
// write.
export const JOIN_SEMANTICS_PROPERTY = 'join.semantics';
export const JOIN_SEMANTICS_DECLARED = 'declared';

// The node-level vocabulary a join is declared with -- aliased from
// ai.ravenroot.core.graph.JoinSemantics / ai.ravenroot.core.runtime.JoinSpec rather than spelled
// again here, same reason JoinSpec itself aliases JoinSemantics: two spellings of one serialized
// name is how a control and the runtime that reads it come to disagree about what a node declares.
export const JOIN_POLICY_PROPERTY = 'joinPolicy';
export const JOIN_QUORUM_PROPERTY = 'joinQuorum';
export const JOIN_TIMEOUT_PROPERTY = 'joinTimeout';
// The legacy state-machine merge policy (JoinSemantics#EACH_POLICY): legal, and this editor's own
// serializeGraphML stamps it on EVERY SAVE of a legacy state-machine fan-in below -- not only while
// migrating one, and not only once. "Migrating" and "saving" are two different events (a save with
// no migration in it still stamps this), and this file's own read side (`wouldSaveStampEachJoinPolicy`,
// `planJoinSemanticsMigration`, `effectiveJoinArrival`) has to agree with the save that is actually
// about to happen, not with whichever one this comment's wording might suggest. The Kind-of-arrival
// control this file backs never WRITES `each` itself -- see joinKindProperties. It folds `each` into
// the same 'none' selection as an undeclared node, because under join.semantics=declared the two
// already mean the same thing (JoinSemantics#redundancies), and offering two options with one meaning
// would be a control asking the author a question the document does not.
const JOIN_EACH_POLICY = 'each';
const JOIN_PROPERTY_NAMES = [JOIN_POLICY_PROPERTY, JOIN_QUORUM_PROPERTY, JOIN_TIMEOUT_PROPERTY];

export const KNOWN_NODE_FIELDS = [
  { name: 'name', label: 'Name', type: 'string' },
  { name: 'kind', label: 'Ravenroot kind', type: 'string' },
  { name: 'behavior', label: 'Behavior', type: 'string' },
  { name: 'classname', label: 'Class name', type: 'string' },
  { name: 'classification', label: 'Visual classification', type: 'string' },
  { name: 'description', label: 'Description', type: 'string' },
];

export const KNOWN_EDGE_FIELDS = [
  { name: 'outcome', label: 'Outcome', type: 'string' },
  { name: 'command', label: 'Command', type: 'string' },
  { name: 'name', label: 'Name', type: 'string' },
  { name: 'status', label: 'Status', type: 'int' },
  { name: 'parallel', label: 'Parallel', type: 'boolean' },
  { name: 'trafficWeight', label: 'Traffic weight', type: 'double' },
  { name: 'description', label: 'Description', type: 'string' },
];

const NODE_KNOWN_NAMES = new Set([
  ...KNOWN_NODE_FIELDS.map(field => field.name.toLowerCase()),
  'start', 'end', 'actor', 'instances', 'nodetype',
  'layoutx', 'layouty', 'layoutwidth', 'layoutheight',
  // These three have their own dedicated Kind-of-arrival control (app.js's joinFieldHtml),
  // same reason DEFAULT_NATURE_PROPERTY is excluded here rather than left to fall into the free-form
  // "Additional properties" rows -- one property, one control, never two writing the same key.
  JOIN_POLICY_PROPERTY.toLowerCase(), JOIN_QUORUM_PROPERTY.toLowerCase(), JOIN_TIMEOUT_PROPERTY.toLowerCase(),
]);
const EDGE_KNOWN_NAMES = new Set([
  ...KNOWN_EDGE_FIELDS.map(field => field.name.toLowerCase()),
  'edgename', 'label',
]);

// The unreserved edge property through which an author declares that an edge carries a node's
// FAILURE route rather than one of its outcomes. The name, the single accepted value and the
// case-sensitive-after-strip reading are the Java side's, verbatim: see
// ravenroot-core's FailureRouteEdgeProperty, whose `declared()` is `"true".equals(value.strip())`.
// An approximate match here would make the editor state something about a document that disagrees
// with how the engine reads the same bytes, which is the one failure this feature cannot afford.
export const FAILURE_ROUTE_PROPERTY = 'failure.route';
export const FAILURE_ROUTE_TRUE = 'true';

// The outcome an edge carries when its author selected none -- GraphEdge.DEFAULT_OUTCOME on the Java
// side, where the canonical constructor collapses "nothing authored" and "the author wrote
// `continue`" into this same value. A failure route is refused unless its outcome is still exactly
// this, so the comparison uses one named constant rather than an inline literal.
export const DEFAULT_EDGE_OUTCOME = 'continue';

// Deliberately NOT added to EDGE_KNOWN_NAMES. That set has a second job in serializeGraphML:
// pruneDeletedCustomProperties never removes a <data> whose name is in it, because the known fields
// are each written back explicitly right after. `failure.route` lives in the generic property bag
// instead, so its <data> is governed by presence in that bag -- clearing the declaration deletes the
// entry and the prune pass removes the element, with no explicit write needed on the serializer
// side. This set exists only to keep the property out of the generic "Additional properties" rows,
// which would otherwise offer a second, uncoordinated way to set the same value.
const EDGE_INSPECTOR_OWNED_NAMES = new Set([FAILURE_ROUTE_PROPERTY]);
const EMPTY_NAME_SET = new Set();

/**
 * Whether an edge model carries the explicit `failure.route` declaration. Reads the generic property
 * bag, which is where parseGraphML leaves the property and where serializeGraphML picks it up again.
 *
 * This is the DECLARATION, not the question "is this edge a failure route": a default failure route
 * can exist without an explicit declaration. Use {@link edgeFailureRouteKind} for that.
 */
export function edgeDeclaresFailureRoute(edge) {
  const value = (edge && edge.properties || {})[FAILURE_ROUTE_PROPERTY];
  return value != null && String(value).trim() === FAILURE_ROUTE_TRUE;
}

/** An edge that says nothing about routing into an `ERROR` node is an implicit failure route. */
export const FAILURE_ROUTE_DECLARED = 'declared';
export const FAILURE_ROUTE_IMPLICIT = 'implicit';

/**
 * Which kind of failure route an edge is, or `null` when it is an ordinary outcome edge.
 *
 * <p>Connecting a node to Error means an unhandled error goes directly to that node unless the
 * author routes it differently. The PRECEDENCE is the load-bearing half — the default fills
 * a silence, it never overrides an author who routed explicitly:</p>
 *
 * <ol>
 * <li>an explicit outcome wins, and the edge stays an outcome edge exactly as before;</li>
 * <li>otherwise an explicit `failure.route` declaration makes it a declared failure route — the
 * case the default cannot cover, because the target is not an `ERROR` node;</li>
 * <li>otherwise an edge into an `ERROR` node is a failure route because its target already says
 * so.</li>
 * </ol>
 *
 * <p>"Explicit outcome" is the outcome differing from {@link DEFAULT_EDGE_OUTCOME}. It is the same
 * signal GraphDefinition's own refusal is built on, and the only one available: GraphEdge's
 * canonical constructor collapses "no outcome authored" and "the author wrote `continue`" into one
 * value, so an author who types the default into an edge aimed at `ERROR` gets the default
 * behaviour — which is what they asked for either way. It is also case-sensitive, like the Java
 * comparison: a legacy yEd graph whose edge label reads `CONTINUE` carries that as its outcome and
 * is an outcome edge, because `CONTINUE` is a different outcome to the engine too.</p>
 *
 * <p>The DEFAULT itself is the engine's, implemented in ravenroot-core. This function is
 * the editor's reading of the same rule, and the two must agree: an editor stating that a
 * bare edge into `ERROR` catches a failure, against an engine that has not got the default yet,
 * would be a confident and false statement.</p>
 *
 * <p>The distinction between the two kinds is NOT behavioural: both route a failure, identically.
 * It is reported so the inspector can say WHY, because an implicit route is contingent on the
 * target's kind and stops being one if the edge is retargeted, while a declared one does not.</p>
 */
export function edgeFailureRouteKind(edge, graph) {
  if (!edge) return null;
  if (String(edge.outcome ?? DEFAULT_EDGE_OUTCOME).trim() !== DEFAULT_EDGE_OUTCOME) return null;
  if (edgeDeclaresFailureRoute(edge)) return FAILURE_ROUTE_DECLARED;
  const target = graph && (graph.nodeMap?.[edge.target]
    || (graph.nodes || []).find(node => node.id === edge.target));
  return target && target.kind === 'ERROR' ? FAILURE_ROUTE_IMPLICIT : null;
}

/**
 * Stamp every edge's `edgeType` and canvas `label` with its failure-route classification.
 *
 * <p>Called from the one choke point every render passes through rather than only at parse time,
 * and that placement is the point: an implicit route depends on the TARGET NODE'S KIND, so turning
 * an ordinary node into an `ERROR` node — or retargeting an edge onto one — changes what its
 * incoming edges mean without touching the edges at all. A classification computed once at parse
 * would keep reporting the answer it got then, so it is recomputed instead of cached.</p>
 *
 * <p>Only ever converts BETWEEN the failure type and the outcome-derived one. An edge that is no
 * longer a failure route is handed back to {@link outcomeToEdgeType}, so nothing here can strand an
 * edge in a type its own outcome does not justify.</p>
 */
export function classifyFailureRoutes(graph) {
  for (const edge of graph?.edges || []) {
    const kind = edgeFailureRouteKind(edge, graph);
    if (kind) {
      edge.edgeType = 'failure';
      edge.failureRouteKind = kind;
      // Unconditional, and it costs nothing an author wrote. A failure route's outcome is the
      // default by construction, and `label` on such an edge is either that default or the
      // `failure` an earlier pass of this same function already put there: every path that sets a
      // label -- createEdge, the inspector's submit, and the parser's own fallback -- derives it
      // from the outcome, and the ONE path that could supply different words (a legacy yEd
      // <y:EdgeLabel>) also BECOMES the outcome in parseGraphML, which makes that outcome explicit
      // and the edge an outcome edge rather than a failure route. So the string replaced here is
      // never an author's, and when it is "continue" it names the one thing this edge is not.
      edge.label = 'failure';
    } else if (edge.edgeType === 'failure') {
      edge.failureRouteKind = null;
      edge.edgeType = outcomeToEdgeType(edge.outcome);
      if (String(edge.label).trim().toLowerCase() === 'failure') {
        edge.label = edge.outcome || DEFAULT_EDGE_OUTCOME;
      }
    } else {
      edge.failureRouteKind = null;
    }
  }
  return graph;
}

/**
 * Apply the failure-route declaration to an edge model's property bag, in place.
 *
 * Clearing DELETES the entry rather than writing `false`: only the exact string `true` declares a
 * failure route on the Java side, so a leftover `failure.route=false` would be a property that says
 * nothing while still occupying the document -- and it would then reappear in the generic property
 * rows, which is the confusion this control exists to remove.
 */
export function setEdgeFailureRoute(edge, declared) {
  if (!edge) return edge;
  const properties = { ...(edge.properties || {}) };
  const propertyTypes = { ...(edge.propertyTypes || {}) };
  if (declared) {
    properties[FAILURE_ROUTE_PROPERTY] = FAILURE_ROUTE_TRUE;
    propertyTypes[FAILURE_ROUTE_PROPERTY] = 'string';
  } else {
    delete properties[FAILURE_ROUTE_PROPERTY];
    delete propertyTypes[FAILURE_ROUTE_PROPERTY];
  }
  edge.properties = properties;
  edge.propertyTypes = propertyTypes;
  return edge;
}

// The document created by "New" demonstrates the recommended error-routing shape: exactly one
// START, one END and one ERROR, reached from a PASSTHROUGH node (`dosomething`) that fans out on two
// outcomes. `dosomething` is a passthrough so the fresh graph validates without the user choosing
// any behavior, exactly as START and END already do.
export function createWorkflowDocument() {
  const start = createNode('start', 'Start', 'START', { x: 120, y: 220 });
  const doSomething = createNode('dosomething', 'Do something', 'PASSTHROUGH', { x: 300, y: 220 });
  const end = createNode('end', 'End', 'END', { x: 480, y: 220 });
  const error = createNode('error', 'Error', 'ERROR', { x: 300, y: 380 });
  const edgeToDoSomething = createEdge('edge-start-dosomething', 'start', 'dosomething', 'continue');
  const edgeToEnd = createEdge('edge-dosomething-end', 'dosomething', 'end', 'continue');
  // The branch to the error terminal reports the outcome as `failed`, not `error` -- `failed`
  // is one of the outcomes the renderer already colors (red, dashed); `error` is not, and this reuses
  // that instead of introducing new render styling.
  const edgeToError = createEdge('edge-dosomething-error', 'dosomething', 'error', 'failed');
  const nodeMap = { start, dosomething: doSomething, end, error };
  return {
    format: 'graphml', sourceXml: '',
    nodes: [start, doSomething, error, end],
    edges: [edgeToDoSomething, edgeToEnd, edgeToError],
    nodeMap,
    // A drawing made from scratch has no join unless its author selects one. The marker is
    // what makes that true, and it is set here -- at creation -- rather than at save time, so that
    // every document this editor originates carries it and no document it opens acquires it.
    graphProperties: { [JOIN_SEMANTICS_PROPERTY]: JOIN_SEMANTICS_DECLARED },
  };
}

export function createNode(id, name, kind = 'PASSTHROUGH', position = {}) {
  const normalizedKind = NODE_KINDS.includes(kind) ? kind : 'PASSTHROUGH';
  return {
    id,
    name: name || id,
    kind: normalizedKind,
    behavior: '',
    nodeType: kindToNodeType(normalizedKind),
    isStart: normalizedKind === 'START',
    isEnd: normalizedKind === 'END',
    isActor: normalizedKind === 'BEHAVIOR',
    classname: '',
    description: '',
    fillColor: '#21262d',
    shapeType: 'roundrectangle',
    ox: Number(position.x) || 0,
    oy: Number(position.y) || 0,
    ow: 80,
    oh: 52,
    instances: 0,
    properties: {},
    propertyTypes: {},
  };
}

// The ERROR branch uses the `failed` outcome the renderer already colors instead of introducing an
// `error` style. An inline `outcome === 'continue' ? 'continue' : 'default'` here and in app.js's edge
// form submit handler recognizes neither 'failed' nor 'completed', so
// an edge created or edited with either outcome fell through to the uncolored 'default' edgeType and
// only picked up its red-dashed / green style after a save-and-reload round trip, which parseGraphML
// classifies correctly (graph-parsers.js's richer outcome/status classification). Same shape of bug
// as the node-kind/nodeType mismatch, just on edges: the direct-authoring path
// disagreed with the parsed one. Deliberately narrower than graph-parsers.js's full classification:
// a modern outcome string can express the canonical suffix, while VALIDATE, PING, CALLBACK_* and
// status-code classifications remain yEd/status-driven and are not inferred here.
export function outcomeToEdgeType(outcome) {
  const upper = String(outcome || '').trim().toUpperCase();
  if (upper === 'FAILED') return 'failed';
  if (upper === 'COMPLETED') return 'completed';
  if (upper === 'CONTINUE') return 'continue';
  if (upper.endsWith('_OUTCOME')) return 'outcome';
  return 'default';
}

export function createEdge(id, source, target, outcome = 'continue') {
  assertStableEdgeId(id);
  const normalizedOutcome = outcome || 'continue';
  return {
    id,
    source,
    target,
    outcome: normalizedOutcome,
    command: '',
    label: normalizedOutcome,
    edgeType: outcomeToEdgeType(normalizedOutcome),
    color: '#8b949e',
    lineWidth: 1.5,
    status: 0,
    parallel: false,
    edgeName: '',
    dashed: false,
    trafficWeight: null,
    description: '',
    properties: {},
    propertyTypes: {},
  };
}

export function kindToNodeType(kind) {
  if (kind === 'START') return 'start';
  if (kind === 'END') return 'end';
  if (kind === 'ERROR') return 'error';
  if (kind === 'PASSTHROUGH') return 'flow';
  return 'actor';
}

export function additionalProperties(element, scope) {
  const known = scope === 'node' ? NODE_KNOWN_NAMES : EDGE_KNOWN_NAMES;
  const owned = scope === 'node' ? EMPTY_NAME_SET : EDGE_INSPECTOR_OWNED_NAMES;
  return Object.entries(element.properties || {})
    .filter(([name]) => !known.has(name.toLowerCase()) && !owned.has(name.toLowerCase()))
    .map(([name, value]) => ({
      name,
      value: value == null ? '' : String(value),
      type: (element.propertyTypes || {})[name] || 'string',
    }));
}

// ═══════════════════════════════════════════════════════════════
// JOIN ARRIVAL
// ═══════════════════════════════════════════════════════════════
//
// A client-side mirror of ai.ravenroot.core.graph.JoinSemantics and
// ai.ravenroot.core.runtime.JoinSpec, close enough to tell an author what a node's OWN properties
// already mean without leaving the editor. It is advisory only: the runtime is still the sole
// authority that validates and executes a join (JoinSpec.validate can still refuse a document this
// module would describe without complaint, e.g. a quorum that disagrees with a policy on the same
// node) -- this only describes the two things asks the Inspector to show, and constrains what
// the dedicated control writes.

// A branch is a distinct predecessor NODE, not an incoming edge (CORE-03, mirrors
// JoinSemantics#distinctPredecessors). Two edges from the same decision node into one join is one
// branch, not two, because only one of the two outcomes can ever be taken.
export function distinctPredecessorIds(graph, nodeId) {
  const distinct = new Set();
  for (const edge of graph?.edges || []) {
    if (edge.target === nodeId) distinct.add(edge.source);
  }
  return [...distinct].sort();
}

function rawJoinProperty(node, name) {
  const raw = (node?.properties || {})[name];
  if (raw == null) return null;
  const text = String(raw).trim();
  return text === '' ? null : text;
}

/** Whether `node` carries `joinPolicy=each` (mirrors JoinSemantics#isEach). */
export function isEachJoinPolicy(node) {
  const raw = rawJoinProperty(node, JOIN_POLICY_PROPERTY);
  return raw != null && raw.toLowerCase() === JOIN_EACH_POLICY;
}

// `serializeGraphML` stamps `joinPolicy=each` onto a legacy state-machine fan-in AT
// SAVE TIME (below, "so a yEd state-machine graph does not wait for mutually-exclusive outcomes"),
// never back into `node.properties` -- the parsed, in-memory document a freshly opened legacy
// graph carries does not have it yet. Both `planJoinSemanticsMigration` and `effectiveJoinArrival`
// Reading only `node.properties` would make both `planJoinSemanticsMigration` and
// `effectiveJoinArrival` plan and report against a document that ceases to exist when saved: a
// genuinely mutually-exclusive legacy fan-in
// would be migrated to `joinPolicy=all`, and the Inspector would call that a no-op change, while
// the very next save silently overwrote `all` back to `each` -- a join that would then wait for
// every branch, though the confirmation dialog said nothing changes. This predicate asks the same
// question serializeGraphML's own each-stamp condition (below, in that function) answers when it
// runs, evaluated early instead: node exists and is not START, it has no joinPolicy of its own yet,
// the document is a legacy state machine, and it has more than one distinct predecessor. Four checks,
// not serializeGraphML's three -- the node-existence guard has no equivalent there, since that
// function is always handed a real node -- and evaluated in a different order for early-exit cost,
// not to mirror the read order of the code below. What matters is the OUTCOME, not the shape of the
// check: given the same graph and node, this must return true exactly when serializeGraphML's own
// condition would stamp `each` on the next save -- independently verified across node kind, marker
// position, joinPolicy spelling and predecessor shape, with zero disagreements found.
export function wouldSaveStampEachJoinPolicy(graph, node) {
  if (!node || node.kind === 'START') return false;
  if (Object.keys(node.properties || {}).some(name => name.toLowerCase() === JOIN_POLICY_PROPERTY.toLowerCase())) {
    return false; // an explicit joinPolicy -- 'each' included -- already wins; nothing new to stamp
  }
  const legacyStateMachine = (graph?.nodes || []).some(n => n._legacyKind);
  if (!legacyStateMachine) return false;
  return distinctPredecessorIds(graph, node.id).length > 1;
}

// The conservative rule keeps the legacy stamp active once join.semantics is declared.
// Whether declaring `node` with `joinQuorum` ALONE -- exactly what the Kind-of-arrival control's own
// 'K of N' choice writes, `joinKindProperties('quorum', n)`, never joinPolicy alongside it -- would
// leave the document in a shape serializeGraphML turns into one the engine refuses on load:
// `joinPolicy=each` stamped next to `joinQuorum` on the very same save
// (JoinConfigurationException: "joinPolicy is each, so joinQuorum and joinTimeout cannot be set").
// Unlike `wouldSaveStampEachJoinPolicy`, this does NOT check whether `node` already has a joinPolicy
// -- a 'K of N' write is precisely what would REMOVE one (properties is a whole replace), so the
// question is never "does it have one now" but "would it end up with none", which for a legacy
// multi-predecessor fan-in is unconditional.
export function quorumWouldCollideWithLegacyStamp(graph, node) {
  if (!node || node.kind === 'START') return false;
  const legacyStateMachine = (graph?.nodes || []).some(n => n._legacyKind);
  if (!legacyStateMachine) return false;
  return distinctPredecessorIds(graph, node.id).length > 1;
}

/** Whether the author wrote any of the three join properties on `node` (mirrors
 * JoinSemantics#isDeclared). */
export function hasDeclaredJoin(node) {
  return JOIN_PROPERTY_NAMES.some(name => rawJoinProperty(node, name) != null);
}

/** Whether `graph` carries `join.semantics=declared` (mirrors JoinSemantics#declaredJoinsOnly). */
export function hasDeclaredJoinSemantics(graph) {
  const marker = graph?.graphProperties?.[JOIN_SEMANTICS_PROPERTY];
  return typeof marker === 'string' && marker.trim().toLowerCase() === JOIN_SEMANTICS_DECLARED;
}

// The vocabulary JoinSpec already accepts, exposed as a Kind-of-arrival choice instead of a boolean
// because a boolean hides machinery that is already there and buys a
// second migration the day a third policy arrives.
export const JOIN_KIND_OPTIONS = [
  { value: 'none', label: 'No join — each arrival runs independently' },
  { value: 'all', label: 'Wait for all branches' },
  { value: 'first', label: 'First arrival wins' },
  { value: 'quorum', label: 'K of N branches' },
];

// The Kind-of-arrival `<select>`'s fifth, non-vocabulary option -- selected
// whenever `declaredJoinKind` reports `recognized: false`, and never written by this control itself
// (same convention as `each`, see JOIN_EACH_POLICY above). It exists so the control can show an
// author a value it does not understand instead of quietly mapping it onto 'none' and then erasing
// it on the next unrelated save.
export const JOIN_KIND_UNRECOGNIZED = 'unrecognized';

/**
 * The Kind-of-arrival selection `node`'s OWN properties currently spell out, independent of what
 * `join.semantics` makes that mean in effect (see `effectiveJoinArrival` for that). `joinPolicy=each`
 * is read back as 'none': seeing the same option the control just never writes for it (see
 * `joinKindProperties`) is what makes reopening a node this control already touched idempotent.
 */
// A recognized return never carries `recognized`/`raw` at all -- only the shape
// every existing caller and test already expects. An UNRECOGNIZED one (a `joinPolicy` this file's
// vocabulary does not define, or a `joinQuorum` that is not a positive integer) adds both, so a
// caller can tell "the document declares nothing" apart from "the document declares something this
// control cannot speak" -- the two used to collapse into the same `{ kind: 'none' }`, which is how a
// value this control does not represent got silently reinterpreted as absent, and then erased for
// real the next time that same control's submit materialised 'none' back into the document. Preserve
// what is not represented; never overwrite it.
export function declaredJoinKind(node) {
  const policy = rawJoinProperty(node, JOIN_POLICY_PROPERTY);
  const quorum = rawJoinProperty(node, JOIN_QUORUM_PROPERTY);
  if (policy != null) {
    const normalized = policy.toLowerCase();
    if (normalized === 'all') return { kind: 'all', quorum: null };
    if (normalized === 'any') return { kind: 'first', quorum: null };
    if (normalized === JOIN_EACH_POLICY) return { kind: 'none', quorum: null };
    return { kind: 'none', quorum: null, recognized: false, raw: policy };
  }
  if (quorum != null) {
    if (/^\d+$/.test(quorum)) return { kind: 'quorum', quorum: Number(quorum) };
    return { kind: 'none', quorum: null, recognized: false, raw: quorum };
  }
  return { kind: 'none', quorum: null };
}

/**
 * The properties one Kind-of-arrival selection writes: nothing for 'none', exactly one property for
 * a declared choice. Never `joinPolicy=each` -- writing the
 * legacy redundant spelling instead of leaving the document untouched is exactly the "materialises a
 * default into a document" pattern this repository has refused twice (JoinSemantics class javadoc).
 */
export function joinKindProperties(kind, quorum) {
  if (kind === 'all') return { [JOIN_POLICY_PROPERTY]: 'all' };
  if (kind === 'first') return { [JOIN_POLICY_PROPERTY]: 'any' };
  if (kind === 'quorum') {
    const parsed = Number(quorum);
    return Number.isInteger(parsed) && parsed >= 1 ? { [JOIN_QUORUM_PROPERTY]: String(parsed) } : {};
  }
  return {};
}

/**
 * What is ALREADY true of `node` right now: whether it is even a candidate (mirrors
 * JoinSemantics#isJoin: START is excluded before the branch count is consulted, and fewer than two
 * distinct predecessors is not a fan-in), and if so, which kind is in effect and why --
 * `source` names which rule answered, for callers (the END-terminal warning) that only care about one
 * of them.
 */
export function effectiveJoinArrival(graph, node) {
  const branches = distinctPredecessorIds(graph, node.id);
  if (node.kind === 'START' || branches.length < 2) {
    return { applicable: false, branchCount: branches.length, kind: null, quorum: null, source: 'not-a-fan-in' };
  }
  // `wouldSaveStampEachJoinPolicy` catches the node that does not carry `each` YET but
  // will the moment this document is saved (legacy state-machine fan-in, see that function's
  // comment) -- reported here with the SAME source as an already-explicit `each`, because by the
  // time the author reads this, save is about to make them the same document.
  if (isEachJoinPolicy(node) || wouldSaveStampEachJoinPolicy(graph, node)) {
    return { applicable: true, branchCount: branches.length, kind: 'none', quorum: null, source: 'each' };
  }
  const markerDeclared = hasDeclaredJoinSemantics(graph);
  const declared = hasDeclaredJoin(node);
  if (declared) {
    const selection = declaredJoinKind(node);
    return { applicable: true, branchCount: branches.length, ...selection, source: 'declared' };
  }
  if (markerDeclared) {
    // The error terminal keeps an implicit quorum of one even with nothing authored, so
    // two branches failing at once still fires it once instead of racing over the shared result field
    // (JoinSpec#defaultQuorum). Every other undeclared fan-in is genuinely not a join under the
    // marker: each arrival invokes it independently.
    if (node.kind === 'ERROR') {
      return { applicable: true, branchCount: branches.length, kind: 'quorum', quorum: 1, source: 'default-error' };
    }
    return { applicable: true, branchCount: branches.length, kind: 'none', quorum: null, source: 'undeclared' };
  }
  // No marker: the legacy reading. Every multi-predecessor node is an inferred join waiting for
  // every branch, except the error terminal, which keeps the same implicit quorum of one.
  return {
    applicable: true, branchCount: branches.length,
    kind: node.kind === 'ERROR' ? 'quorum' : 'all',
    quorum: node.kind === 'ERROR' ? 1 : null,
    source: 'legacy-inferred',
  };
}

/**
 * The plan for migrating `graph` to declared join semantics -- a client-side mirror of
 * JoinSemantics.migrate. Materialises the currently-inferred policy onto every inferred join:
 * `joinPolicy=all` on an ordinary fan-in, `joinQuorum=1` on the error terminal. Never writes `each`
 * itself (that would remove the coordination instead of recording it -- see JoinSpec#defaultQuorum's
 * javadoc on the race this exists to keep closed). Nodes that already declare a policy are left
 * exactly as they are.
 *
 * Unknown join declarations are preserved rather than replaced: "never
 * `each`" is true of what THIS function writes, not of what the migrated document ends up carrying.
 * A legacy state-machine fan-in `wouldSaveStampEachJoinPolicy` catches is skipped here on purpose
 * (see the comment above that predicate) -- its plan entry is simply absent, not `each` -- but
 * serializeGraphML still stamps `each` onto it on save regardless of the marker this migration just
 * wrote, so the node that "keeps" `each` through a migration is a real, currently-accepted outcome of
 * this function leaving it alone, not a bug in the function itself. `serializeGraphML` stamps
 * `each` regardless of the marker, so the caveat is part of the current behavior.
 *
 * Pure and side-effect free: it reports what would change, it does not change it -- app.js shows
 * `changes` to the author before they confirm, and graph-editing.js turns an accepted plan into one
 * undoable command.
 */
export function planJoinSemanticsMigration(graph) {
  if (hasDeclaredJoinSemantics(graph)) {
    return { alreadyDeclared: true, changes: [] };
  }
  const changes = [];
  for (const node of graph?.nodes || []) {
    const branches = distinctPredecessorIds(graph, node.id);
    // a legacy state-machine fan-in that is ABOUT TO BE `each` on the very save this
    // plan is computed for (`wouldSaveStampEachJoinPolicy`) is excluded exactly like one that
    // already carries `each` explicitly -- planning `joinPolicy=all` against it would migrate the
    // document parseGraphML handed us, not the one serializeGraphML is about to write, and the two
    // disagree about whether two mutually-exclusive branches wait for each other.
    const isJoin = node.kind !== 'START' && !isEachJoinPolicy(node)
      && !wouldSaveStampEachJoinPolicy(graph, node) && branches.length >= 2;
    if (!isJoin || hasDeclaredJoin(node)) continue;
    changes.push(node.kind === 'ERROR'
      ? { nodeId: node.id, property: JOIN_QUORUM_PROPERTY, value: '1' }
      : { nodeId: node.id, property: JOIN_POLICY_PROPERTY, value: 'all' });
  }
  return { alreadyDeclared: false, changes };
}

export function validateWorkflow(graph) {
  const violations = [];
  if (!graph || graph.format === 'graphify') {
    return ['Graphify JSON is a knowledge graph view, not an executable Ravenroot workflow'];
  }
  const ids = new Set();
  for (const node of graph.nodes || []) {
    if (!node.id || !String(node.id).trim()) violations.push('Every node needs an ID');
    if (ids.has(node.id)) violations.push(`Duplicate node ID: ${node.id}`);
    ids.add(node.id);
    if (!NODE_KINDS.includes(node.kind)) violations.push(`Node ${node.id} has an invalid kind`);
    if (node.kind === 'BEHAVIOR' && !String(node.behavior || '').trim()) {
      violations.push(`Behavior node ${node.id} needs a behavior name`);
    }
  }
  const starts = (graph.nodes || []).filter(node => node.kind === 'START');
  const ends = (graph.nodes || []).filter(node => node.kind === 'END');
  // Error-terminal routing contract: the error terminal is bounded, not required. A designer may
  // route failures into `Error`, into `End`, or nowhere, and the editor accepts each choice. The ERROR
  // node kind remains available, and the default document from "New" still includes the recommended
  // error terminal.
  const errors = (graph.nodes || []).filter(node => node.kind === 'ERROR');
  if (starts.length !== 1) violations.push('The graph must contain exactly one START node');
  if (ends.length !== 1) violations.push('The graph must contain exactly one END node');
  // The comparison is a CEILING, not an equality, and the shape of it is load-bearing:
  // MinimalStructureParityTest reads the operator as written and refuses to absorb a change of form,
  // so `>` here and MIN_ERROR_NODES=0 in GraphDefinition are two halves of one statement. A second
  // ERROR node is still refused, on both sides, for the payload-writer reason recorded on the
  // constant above. Removing the minimum does not change the maximum.
  if (errors.length > MAX_ERROR_NODE_COUNT) {
    violations.push(MAX_ERROR_NODE_COUNT === 1
      ? 'The graph must contain at most one ERROR node'
      : `The graph must contain at most ${MAX_ERROR_NODE_COUNT} ERROR nodes`);
  }
  for (const edge of graph.edges || []) {
    const edgeIdViolation = stableEdgeIdViolation(edge.id);
    if (edgeIdViolation) violations.push(`Edge id ${edgeIdViolation}`);
    if (!ids.has(edge.source)) violations.push(`Edge ${edge.id} has an unknown source`);
    if (!ids.has(edge.target)) violations.push(`Edge ${edge.id} has an unknown target`);
    if (!String(edge.outcome || '').trim()) violations.push(`Edge ${edge.id} needs an outcome`);
    // Mirrors GraphDefinition.validate(): an edge is a failure route OR an outcome edge,
    // never both, and the only signal that tells the two apart is the outcome still being the
    // default. The edge inspector is built so that it cannot produce this combination -- it parks
    // the outcome at the default while the declaration is on, and drops a declaration the author
    // has overridden with an outcome -- but a document can arrive here from an import or a
    // hand-edited file, and without this check the refusal would land at load time in the engine
    // instead of in the editor, which is the error relocation exists to close.
    if (edgeDeclaresFailureRoute(edge)
        && String(edge.outcome || DEFAULT_EDGE_OUTCOME).trim() !== DEFAULT_EDGE_OUTCOME) {
      violations.push(`Edge ${edge.id} declares ${FAILURE_ROUTE_PROPERTY}=${FAILURE_ROUTE_TRUE}`
        + ` together with the explicit outcome '${edge.outcome}';`
        + ' an edge is a failure route or an outcome edge, never both');
    }
  }
  return violations;
}

export function serializeGraphML(graph) {
  const parser = new DOMParser();
  const base = graph.sourceXml && graph.sourceXml.trim().startsWith('<')
    ? graph.sourceXml
    : `<?xml version="1.0" encoding="UTF-8"?><graphml xmlns="${GRAPHML_NS}"><graph id="ravenroot-workflow" edgedefault="directed"/></graphml>`;
  const doc = parser.parseFromString(base, 'application/xml');
  if (doc.querySelector('parsererror')) throw new Error('Cannot serialize an invalid GraphML source document');
  const root = doc.documentElement;
  if (!root || root.localName !== 'graphml' || root.namespaceURI !== GRAPHML_NS) {
    throw new Error(`GraphML root must be {${GRAPHML_NS}}graphml`);
  }
  const namespace = root.namespaceURI;
  const graphElements = Array.from(root.children)
    .filter(element => element.localName === 'graph' && element.namespaceURI === GRAPHML_NS);
  if (graphElements.length !== 1) {
    throw new Error(`GraphML must contain exactly one top-level graph; found ${graphElements.length}`);
  }
  const graphElement = graphElements[0];

  // Which of two duplicate keys is canonical has no product-visible consequence, only a
  // document-correctness one. A malformed
  // document CAN declare two <key> elements that share one scope+attr.name; before this, the LATER
  // one silently won here (Map#set overwrites), so ensureKey below could resolve to a key id that
  // NO existing <data> in the document actually uses -- and then write a second, duplicate <data>
  // for the same logical property instead of finding and reusing the first one. FIRST registered now
  // wins deterministically, same convention this file already uses for the FIRST unclaimed element a
  // synthetic-id edge matches (graph-parsers.js): whichever key an existing <data> was actually
  // written against (necessarily the first one processed, absent some other document producer's own
  // divergent convention) is the one ensureKey resolves to as well, so ITS existing <data> is found
  // and reused rather than shadowed by a second write. Does not merge or remove the shadowed key's own
  // <data>, if any already exists under it -- deleting content this function was not asked to delete
  // is the pattern this file's `setGraphProperty` comment already refuses for the exact same reason.
  const keyDefinitions = new Map();
  const keysById = new Map();
  Array.from(root.children)
    .filter(element => element.localName === 'key' && element.namespaceURI === GRAPHML_NS)
    .forEach(key => {
    const id = key.getAttribute('id') || '';
    const name = key.getAttribute('attr.name') || key.getAttribute('attrname') || id;
    const scope = key.getAttribute('for') || 'all';
    const scopedName = `${scope}:${name.toLowerCase()}`;
    if (!keyDefinitions.has(scopedName)) keyDefinitions.set(scopedName, { id, key });
    if (scope === 'all' && !keyDefinitions.has(`all:${name.toLowerCase()}`)) {
      keyDefinitions.set(`all:${name.toLowerCase()}`, { id, key });
    }
    keysById.set(id, { name, scope });
  });

  function ensureKey(scope, name, type = 'string') {
    const lookup = keyDefinitions.get(`${scope}:${name.toLowerCase()}`)
      || keyDefinitions.get(`all:${name.toLowerCase()}`);
    if (lookup) return lookup.id;
    const used = new Set(Array.from(root.getElementsByTagNameNS('*', 'key'))
      .map(key => key.getAttribute('id')));
    const baseId = `rr-${scope}-${name.replace(/[^a-zA-Z0-9_-]/g, '-').toLowerCase() || 'property'}`;
    let id = baseId;
    let suffix = 2;
    while (used.has(id)) id = `${baseId}-${suffix++}`;
    const key = doc.createElementNS(namespace, 'key');
    key.setAttribute('id', id);
    key.setAttribute('for', scope);
    key.setAttribute('attr.name', name);
    key.setAttribute('attr.type', normalizeType(type));
    root.insertBefore(key, graphElement);
    keyDefinitions.set(`${scope}:${name.toLowerCase()}`, { id, key });
    keysById.set(id, { name, scope });
    return id;
  }

  // Namespace-qualified, matching every other interpreted-name lookup in this function
  // (ensureKey, setData, removeData, pruneDeletedCustomProperties). parseGraphML's
  // INTERPRETED_ELEMENT_NAMES guard is the actual control against a foreign-namespace element
  // spoofing "node"/"edge" -- this and the <data> lookups above are defense in depth for a `graph`
  // object that reaches this function without having passed through that guard. That path is real,
  // not hypothetical: serializeGraphML re-parses graph.sourceXml with its own DOMParser call and
  // never routes through parseGraphML, which is exactly why the corpus fixture that pins the
  // read-side guard cannot also exercise this write-side code -- see
  // graphml-corpus.test.js's "serializeGraphML matches <data> by qualified name..." test, which
  // drives this seam directly.
  function directChildren(localName) {
    return Array.from(graphElement.children)
      .filter(child => child.localName === localName && child.namespaceURI === namespace);
  }

  // A graph-level <data> is written as the first child of <graph>, before the nodes and
  // edges. GraphML's content model does not order them, but every reader and every diff in this
  // repository reads the document top-down, and a statement about the whole graph buried after two
  // hundred nodes is one nobody finds. Never removes or overwrites a value the opened document
  // already carried: the marker is stamped by whoever created the document, and a save is not a
  // migration.
  function setGraphProperty(name, value) {
    const keyId = ensureKey('graph', name);
    const existing = Array.from(graphElement.children)
      .find(child => child.localName === 'data' && child.namespaceURI === namespace
        && child.getAttribute('key') === keyId);
    if (existing) return;
    const data = doc.createElementNS(namespace, 'data');
    data.setAttribute('key', keyId);
    data.appendChild(doc.createTextNode(String(value)));
    graphElement.insertBefore(data, graphElement.firstChild);
  }

  Object.entries(graph.graphProperties || {}).forEach(([name, value]) => {
    if (value != null && value !== '') setGraphProperty(name, value);
  });

  function findById(localName, id) {
    return directChildren(localName).find(element => element.getAttribute('id') === id);
  }

  function setData(element, scope, name, value, type = 'string') {
    if (value == null || value === '') return removeData(element, scope, name);
    const keyId = ensureKey(scope, name, type);
    let data = Array.from(element.children)
      .find(child => child.localName === 'data' && child.namespaceURI === namespace
        && child.getAttribute('key') === keyId);
    if (!data) {
      const key = Array.from(root.children)
        .find(child => child.localName === 'key' && child.getAttribute('id') === keyId);
      const defaultElement = key && Array.from(key.children)
        .find(child => child.localName === 'default' && child.children.length === 0);
      if (defaultElement && defaultElement.textContent.trim() === String(value).trim()) return;
    }
    if (data && data.children.length > 0) {
      throw new Error(`Cannot overwrite complex GraphML data for key '${keyId}'`);
    }
    if (data && data.textContent.trim() === String(value).trim()) return;
    if (!data) {
      data = doc.createElementNS(namespace, 'data');
      data.setAttribute('key', keyId);
      element.appendChild(data);
    }
    while (data.firstChild) data.removeChild(data.firstChild);
    data.appendChild(doc.createTextNode(String(value)));
  }

  function removeData(element, scope, name) {
    const definition = keyDefinitions.get(`${scope}:${name.toLowerCase()}`)
      || keyDefinitions.get(`all:${name.toLowerCase()}`);
    if (!definition) return;
    Array.from(element.children)
      .filter(child => child.localName === 'data' && child.namespaceURI === namespace
        && child.getAttribute('key') === definition.id)
      .forEach(child => {
        if (child.children.length > 0) {
          throw new Error(`Cannot remove complex GraphML data for key '${definition.id}'`);
        }
        child.remove();
      });
  }

  function pruneDeletedCustomProperties(element, scope, presentProperties) {
    const known = scope === 'node' ? NODE_KNOWN_NAMES : EDGE_KNOWN_NAMES;
    const present = new Set(Object.keys(presentProperties || {}).map(name => name.toLowerCase()));
    Array.from(element.children)
      .filter(child => child.localName === 'data' && child.namespaceURI === namespace
        && child.children.length === 0)
      .forEach(data => {
        const definition = keysById.get(data.getAttribute('key'));
        if (!definition) return;
        const name = definition.name.toLowerCase();
        if (!known.has(name) && !present.has(name)) data.remove();
      });
  }

  function setCanonicalData(element, scope, name, value, type, sourceCanonical) {
    if (sourceCanonical && Object.hasOwn(sourceCanonical, name)
        && sameGraphValue(sourceCanonical[name], value)) return;
    setData(element, scope, name, value, type);
  }

  function sameGraphValue(left, right) {
    if (left == null && right == null) return true;
    return String(left) === String(right);
  }

  // GraphML leaves the <edge> id optional (FIX-01), so parseGraphML hands unnamed edges a
  // synthesized model id. That id is an internal handle and must not be written back into the
  // document, which means such an edge cannot be found by id: it is matched to its source element
  // by document position instead. Resolve those matches first, before anything is added or removed,
  // while the positions still describe the document the edges were parsed from.
  const sourceEdgeElements = directChildren('edge');
  const elementByEdge = new Map();
  const matchedElements = new Set();
  for (const edge of graph.edges || []) {
    if (!edge || !edge._syntheticId) continue;
    const element = sourceEdgeElements[edge._sourceEdgeIndex];
    if (!element || matchedElements.has(element)) continue;
    if ((element.getAttribute('id') || '') !== '') continue;
    elementByEdge.set(edge, element);
    matchedElements.add(element);
  }

  const nodeIds = new Set((graph.nodes || []).map(node => node.id));
  const edgeIds = new Set((graph.edges || []).map(edge => edge.id));
  const legacyStateMachine = (graph.nodes || []).some(node => node._legacyKind);
  const predecessorIds = new Map();
  for (const edge of graph.edges || []) {
    if (!predecessorIds.has(edge.target)) predecessorIds.set(edge.target, new Set());
    predecessorIds.get(edge.target).add(edge.source);
  }
  directChildren('edge')
    .filter(edge => !matchedElements.has(edge) && !edgeIds.has(edge.getAttribute('id')))
    .forEach(edge => edge.remove());
  directChildren('node').filter(node => !nodeIds.has(node.getAttribute('id'))).forEach(node => node.remove());

  for (const node of graph.nodes || []) {
    let element = findById('node', node.id);
    if (!element) {
      element = doc.createElementNS(namespace, 'node');
      element.setAttribute('id', node.id);
      const firstEdge = directChildren('edge')[0];
      graphElement.insertBefore(element, firstEdge || null);
    }
    pruneDeletedCustomProperties(element, 'node', node.properties);
    Object.entries(node.properties || {}).forEach(([name, value]) =>
      setData(element, 'node', name, value, (node.propertyTypes || {})[name]));
    setCanonicalData(element, 'node', 'name', node.name || node.id, undefined, node._sourceCanonical);
    // `kind` may have been inferred from legacy start/end/actor booleans rather than read from a
    // canonical key. An immutable execution snapshot must materialise the operative field even when
    // the inferred model value did not change, otherwise the Java runtime sees zero START/END nodes.
    setData(element, 'node', 'kind', node.kind || 'PASSTHROUGH');
    // The prototype graph model used converging transitions as an OR-merge: whichever transition
    // was selected invoked the target. Modern Ravenroot interprets an unqualified multi-input node
    // as an ALL join. Materialise the legacy intent in the immutable execution snapshot so a yEd
    // state-machine graph does not wait for mutually-exclusive outcomes (or classify feedback into
    // START as a join). Authored modern join settings always win.
    if (legacyStateMachine && node.kind !== 'START'
        && (predecessorIds.get(node.id)?.size || 0) > 1
        && !Object.keys(node.properties || {}).some(name => name.toLowerCase() === 'joinpolicy')) {
      setData(element, 'node', 'joinPolicy', 'each');
    }
    setCanonicalData(element, 'node', 'behavior',
      node.kind === 'BEHAVIOR' ? node.behavior : '', undefined, node._sourceCanonical);
    setCanonicalData(element, 'node', 'classname', node.classname || '', undefined, node._sourceCanonical);
    setCanonicalData(element, 'node', 'classification',
      node.nodeType || kindToNodeType(node.kind), undefined, node._sourceCanonical);
    setCanonicalData(element, 'node', 'description',
      node.description || '', undefined, node._sourceCanonical);
    setCanonicalData(element, 'node', 'layoutX',
      Number(node.ox) || 0, 'double', node._sourceCanonical);
    setCanonicalData(element, 'node', 'layoutY',
      Number(node.oy) || 0, 'double', node._sourceCanonical);
    setCanonicalData(element, 'node', 'layoutWidth',
      Number(node.ow) || 80, 'double', node._sourceCanonical);
    setCanonicalData(element, 'node', 'layoutHeight',
      Number(node.oh) || 52, 'double', node._sourceCanonical);
    if (keyDefinitions.has('node:start') || keyDefinitions.has('all:start')) {
      setCanonicalData(element, 'node', 'start',
        node.kind === 'START', 'boolean', node._sourceCanonical);
    }
    if (keyDefinitions.has('node:end') || keyDefinitions.has('all:end')) {
      setCanonicalData(element, 'node', 'end',
        node.kind === 'END', 'boolean', node._sourceCanonical);
    }
  }

  for (const edge of graph.edges || []) {
    let element = elementByEdge.get(edge) || findById('edge', edge.id);
    if (!element) {
      element = doc.createElementNS(namespace, 'edge');
      // An edge that arrived without an id is re-emitted without one: the synthesized handle is
      // ours, not something the author wrote, and GraphML does not require it.
      if (!edge._syntheticId) element.setAttribute('id', edge.id);
      graphElement.appendChild(element);
    }
    pruneDeletedCustomProperties(element, 'edge', edge.properties);
    element.setAttribute('source', edge.source);
    element.setAttribute('target', edge.target);
    Object.entries(edge.properties || {}).forEach(([name, value]) =>
      setData(element, 'edge', name, value, (edge.propertyTypes || {})[name]));
    // Legacy graphs encode routing in labels/status values. Always write the canonical outcome the
    // runtime selects on; preserving only the inferred in-memory value makes every route default to
    // `continue` after submission.
    setData(element, 'edge', 'outcome', edge._legacyOutcome
      ? (edge.outcome || 'continue').toLowerCase()
      : (edge.outcome || 'continue'));
    setCanonicalData(element, 'edge', 'command',
      edge.command || '', undefined, edge._sourceCanonical);
    setCanonicalData(element, 'edge', 'name',
      edge.edgeName || '', undefined, edge._sourceCanonical);
    setCanonicalData(element, 'edge', 'status',
      Number(edge.status) || 0, 'int', edge._sourceCanonical);
    setCanonicalData(element, 'edge', 'parallel',
      Boolean(edge.parallel), 'boolean', edge._sourceCanonical);
    setCanonicalData(element, 'edge', 'trafficWeight',
      edge.trafficWeight, 'double', edge._sourceCanonical);
    setCanonicalData(element, 'edge', 'description',
      edge.description || '', undefined, edge._sourceCanonical);
  }

  return new XMLSerializer().serializeToString(doc);
}

function normalizeType(type) {
  const normalized = type || 'string';
  if (!['boolean', 'int', 'long', 'float', 'double', 'string'].includes(normalized)) {
    throw new Error(`Unsupported GraphML scalar type '${normalized}'`);
  }
  return normalized;
}
