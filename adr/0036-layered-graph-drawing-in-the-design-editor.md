# ADR 0036: Layered graph drawing in the design editor

- Status: Accepted
- Date: 2026-09-04
- Amended: 2026-09-05 — the second arrangement is the top-down drawing rather than a second
  left-to-right one, on the evidence recorded in Context; nothing had been released under the
  earlier name
- Supersedes: None
- Superseded by: None
- Public references: [ADR 0003](0003-graph-editor-and-live-execution-events.md)

## Context

The design editor arranges a workflow with Cytoscape. Its automatic arrangements hand node
positions to Cytoscape and then re-route every edge from its two endpoints alone, one edge at a
time: `Hierarchical` runs ELK's layered algorithm through the `cytoscape-elk` plugin, which keeps
the node coordinates and discards the edge sections ELK computed; `Flow` runs dagre and discards
dagre's edge points the same way; `Organic` runs a force-directed layout that has no notion of
flow direction at all. None of the three reserves room for a node's label when it places the node,
and none treats an edge that runs backwards differently from one that runs forwards.

On a realistic workflow — a coordinator delegating to twelve peers that fan back into one join,
with escalation, audit, error and recovery nodes reachable from almost everywhere — the result does
not read. Edges become long curves that pile up in the middle of the canvas, failure edges cut back
over the whole drawing, and neighbouring labels collide. Measured on that graph with the same
sampler for every arrangement: `Hierarchical` draws 425 edge crossings, 52 edges through node
bodies and 75 through labels; `Flow` 402 crossings and 20 edges through labels; `Organic` 585
crossings and 13 colliding labels in one sample — it is an unseeded force-directed layout, and a
later sample measured 507 and 9. Larger spacing constants enlarge the canvas without changing the
topology of the drawing. A different class of solution was needed, and the owner required it to be
purely additive: the existing arrangements keep their engines, options, routing, command ids,
labels, order and tests, and the new ones are added beside them under distinguishing names.

Three engines were already dependencies and were the candidates: ELK (`elkjs`, EPL-2.0), dagre
(MIT) and Euler (force-directed, MIT). Euler is disqualified by the problem statement. Dagre lays
out layers well but its routing yields only edge control points with no obstacle or label
awareness, and it is unmaintained. ELK's layered algorithm returns edge sections, understands
port sides, outside labels, layer constraints and spacing between edges, nodes and layers, and is
the engine behind the drawings this editor is being compared with. Its spline router was tried and
rejected on evidence: 541 crossings on the test bench with edges through bodies and labels, against
191 for polyline routing with rounded corners. Its feedback-edge routing was also tried and
rejected: it runs a backward edge through the channel between node rows, which is precisely what
the drawing must not do.

The second arrangement was first drawn left to right like the first, differing only in edge routing
(polyline instead of orthogonal), spacing and corner radius. On the test bench the two are the same
drawing: the same engine, the same cycle breaking, layering, crossing minimisation and node
placement, and both painted as rounded segments. Every node moves — up to 313 px — and the second
is 5% narrower, but at the zoom that fits the graph the two are not told apart, and 187 crossings
against 191 says the same thing. A menu offering both offers one drawing twice.

The alternatives were measured on the same bench before choosing. ELK's `mrtree` draws 116
crossings but 144 edges straight through node bodies: it has no obstacle awareness, so it is not a
drawing of a workflow. `radial` exhausts the stack on a graph with cycles. The spline router,
already rejected above, was re-measured through the whole pipeline and confirmed at 477 crossings
with edges through bodies and labels. What is left is the axis: the same layered algorithm run top
to bottom is a genuinely different drawing of the same quality — 179 crossings and no edge through
a node body in the raw engine result — and it is the shape a reader of vertical process diagrams
expects. It carries one condition. With the node's name painted under its card, as the editor
paints it, every one of the 82 edges leaves its source straight through its own name, because the
channel a top-down drawing routes through is exactly where the name sits. Moving the name beside
the card for that arrangement removes all 82 without changing anything else.

## Decision

**Two additive arrangements, `Hierarchical (new)` and `Layered (top-down)`, are drawn by ELK's
layered algorithm called directly, consuming both its node coordinates and its edge sections.**
They differ in the axis they flow along, which is the one difference a reader can name from the
menu and see on the canvas; a second arrangement that differed only in routing and spacing was
drawn, measured, and rejected as the same drawing twice. The `cytoscape-elk` plugin stays where it
is for the existing `Hierarchical`; the new arrangements do not go through it because it discards
the routing this decision depends on. No new dependency is introduced.

**Placement and routing are one result.** Each node is declared with its rendered body size and its
rendered label as an outside label, so ELK turns the label into a margin: spacing reserves room for
it, routing keeps clear of it, and edges still attach to the body. Nodes of one layer share one
centre line. `START` nodes are pinned to the first layer and `END` nodes to the last, and cycle
breaking is depth-first from the sources so the edges reversed are the ones that actually run
backwards in a workflow. Both arrangements route orthogonally with small rounded corners, the form
yEd and Graphviz readers expect. One exception is stated rather than hidden: two edges leaving one node from adjacent ports towards
distant targets, or arriving at one node from adjacent ports, run together for a stretch by
construction — a fan, which the acceptance criteria allow. The check for piled edges exempts only
pairs that share their source node or share their target node; two edges chained head to tail
through a node are judged like any other pair. The exemption is bounded, not open: the tests pin
the fan count and the longest fan run at what each drawing produces plus a margin — none for either
arrangement on the test bench, and one on a 200-node, 400-edge graph, of at most 5600 px
left to right (5035 px measured) and 7300 px top-down (6683 px measured, the taller drawing
carrying the longer fan).

**The name is painted on the side the axis leaves free, and the drawing is told where.** The
left-to-right arrangement keeps the editor's own placement, under the card. The top-down
arrangement paints the name beside the card while it is displayed and puts it back underneath as
soon as another arrangement or render mode is chosen, because a name under the card sits in the
channel that drawing routes through. The arrangement asks the editor to move the names before it
measures anything, so the geometry ELK is given is the geometry the canvas paints; a restyle of the
nodes restates the placement rather than dragging the names back. This is the one place where an
arrangement reaches into how a node is drawn, and it is stated here rather than left implicit.

**Backward edges are routed by the editor, outside the band.** An edge whose target sits on the
same or an earlier layer than its source leaves the source's outgoing side from a port of its own,
steps into a track beyond the band of node rows, runs back outside everything, and returns into a
port of its own on the target's incoming side — east out and west in, under the drawing, when the
flow runs left to right; south out and north in, beside the drawing, when it runs top to bottom.
Shorter spans take the tracks nearest the band, so nested back edges never cross one another, and
the stubs sit closer to a layer than anything ELK draws there, so they overlap nothing. The
geometry is written once against the axis of flow and read on either. ELK still layers those edges;
it just does not draw them.

**The routes are applied relative to their endpoints and fall back per edge.** A route becomes a
`round-segments` Cytoscape edge with explicit endpoint offsets, so it stays attached to its nodes.
After the arrangement, an edge the drawing still describes is repainted from it; an edge it cannot
vouch for — authored since, or with an endpoint moved by hand — takes the editor's existing dynamic
route, and every other edge stays exactly as drawn. Self-loops keep the editor's loop rendering.

**The acceptance criteria are code.** A pure metrics module counts proper crossings, edges through
bodies or labels, colliding labels, collinear runs and back edges inside the band, on plain
geometry, and judges layer discreteness against a layering computed from the graph alone — a
depth-first cycle break from the START nodes and longest-path layers — so that a scatter of nodes
fails the check rather than clustering into as many columns as it has nodes. The same functions judge the drawing computed in a unit test and the one
sampled from the rendered canvas, and the browser suite holds the new arrangements to them on the
committed test-bench graph while reporting the same numbers for the established ones.

**The engine runs on the main thread, as the existing `Hierarchical` already does.** Engine time on
the test bench is about 0.19 s left to right and 0.15 s top-down; a 200-node, 400-edge workflow
takes about 0.5–0.7 s in either mode. Node placement is Brandes–Köpf in both modes: network-simplex
placement drew the same crossings and cost six times the engine time on the large graph. The busy state and the cancellation contract of the existing
asynchronous layouts apply unchanged: one engine run per document at a time, cancelled before it
starts, otherwise allowed to settle, and a superseded run moves nothing. Moving the run to a Web
Worker is a separate decision. The served page's policy admits a same-origin worker as it stands
(`worker-src` falls back to `script-src 'self'`); what the hand-off adds is a failure mode of its
own — a worker that does not load must not leave an arrangement pending — and a build-time worker
import that the editor's unit tests, which import the editor module directly, would have to
support.

## Consequences

On the test bench, measured on the rendered canvas: `Hierarchical (new)` and `Layered (top-down)`
each draw 187 crossings, against 425 for `Hierarchical`, 402 for `Flow` and 507 for `Organic`, and
both have no label collision, no edge through a body or a label, no two unrelated edges on the same
line, one layer per structural layer with every edge running forward across them, every back edge
outside the band, and every failure edge reaching the error node at a port of its own through at
most two sides.

The existing arrangements, their command ids, labels, relative order and tests are unchanged; the
two new commands form a sibling group after `Keep positions`, and the menu-ordering assertion
grew from six entries to eight. Each new arrangement records one undo entry, writes node geometry to
GraphML on save exactly as the existing ones do, and leaves a Graphify document's positions
unrecorded as before.

Two costs are accepted. The first is that a manual move after the arrangement leaves the moved
node's edges on the dynamic route while the rest of the drawing stays put, so a heavily edited
drawing is a mixture until it is arranged again; re-running the whole engine on every drag was
judged worse. The second is that edge labels are placed by Cytoscape at the middle of each route
rather than by ELK, so a label on a long channelled edge sits on the channel rather than beside its
source; declaring edge labels to ELK widens every layer gap by a label and was judged not worth its
cost for this decision.

Removing or replacing the established arrangements is a separate decision, to be taken only after
these drawings have been reviewed on real documents.
