package ai.ravenroot.core.graph;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.ReadOnlyStrategy;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.graphml.GraphMLReader;
import org.apache.tinkerpop.gremlin.structure.io.graphml.GraphMLWriter;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Single access point for graph import/export and controlled Gremlin traversals.
 * An instance owns one graph, and separate instances share no state, so independent workflow
 * definitions do not interfere with one another.
 *
 * <p><strong>An instance is not immutable, and this class is not thread-safe for concurrent use of
 * the same instance.</strong> {@link #query(GraphQuery)} declares no mutation API and refuses
 * step-based mutations, but that refusal is a speed bump rather than a boundary: TinkerPop's
 * {@code GraphTraversalSource#getGraph()} is public and no strategy closes it, so a caller can still
 * reach the underlying graph and mutate it. Read {@link #query(GraphQuery)} before relying on any
 * property of this class — in particular before assuming that concurrent callers cannot observe one
 * another's writes.</p>
 *
 * <p>Mutations reached through {@code query()} land on the instance's own graph and persist there.
 * ARC-05 isolates separate manager instances; it does not make concurrent access to one instance
 * safe.</p>
 */
public final class GraphManager implements AutoCloseable {
    public static final String KIND = "kind";
    public static final String BEHAVIOR = "behavior";
    public static final String OUTCOME = "outcome";
    public static final String COMMAND = "command";

    private static final System.Logger LOGGER = System.getLogger("ai.ravenroot.core.graph.GraphManager");

    /**
     * What {@link #semanticViolations()} reports in place of an uncategorized
     * {@link IllegalArgumentException}'s own message.
     *
     * <p>Fixed text, not the exception's message: the exception this branch catches is a program
     * defect, not a rule about the document, and {@code POST /v1/graphs/inspect} publishes every
     * string in this list to an authenticated caller as a document violation. An internal message
     * flowing out that channel would read as a real verdict on the file this caller submitted, and be
     * indistinguishable from one. The real exception is not discarded — see the log call at the catch
     * site — only kept off this specific, remotely-readable surface.</p>
     *
     * <p>Package-private, not private: {@code GraphManagerSemanticViolationsUnclassifiedTest}
     * asserts against this exact constant rather than a copy of its text, so the two
     * cannot drift apart.</p>
     */
    static final String UNCLASSIFIED_VIOLATION_MESSAGE =
            "This document could not be validated because of an internal error; the failure has been "
                    + "logged for an operator to review.";

    private final TinkerGraph graph;
    private final byte[] importedGraphMl;
    private final GraphState importedGraphState;

    /**
     * What the document declared about the graph as a whole.
     *
     * <p>Held beside the {@link TinkerGraph} rather than in it, because a property graph has no place
     * for a datum belonging to the graph itself and TinkerPop's reader drops such a {@code <data>}
     * silently. {@link GraphMlDocument#graphProperties()} lifts it off the validated DOM; this field
     * is how it reaches {@link #definition()}.</p>
     *
     * <p><strong>It does not survive {@link #writeGraphMl(OutputStream)} on the non-imported
     * path</strong>, and that is a limitation of TinkerPop's {@code GraphMLWriter}, which serialises
     * vertices and edges and has no graph-level {@code <data>} to write. An <em>imported</em> document
     * is unaffected — it is written back verbatim, marker and all. A definition assembled in Java,
     * exported through {@code GraphMLWriter}, loses it. No production path does that: the editor
     * authors GraphML text itself and every server ingress is an imported document, so today the gap
     * is confined to {@code GraphManager.from(...)} followed by an export. It is pinned by a test
     * rather than left to be discovered.</p>
     */
    private final Map<String, Object> graphProperties;

    /**
     * Package-private, not private: every public factory below reaches this same constructor,
     * and {@code GraphManagerSemanticViolationsUnclassifiedTest} (same package) needs it too, to build
     * a manager over a hand-crafted {@link TinkerGraph} that no public factory here could ever produce
     * -- see that test's class javadoc for why. Package-private is exactly enough for that caller and
     * adds no public surface; the alternative, reflection into a private constructor, would fail with
     * an opaque {@code NoSuchMethodException} instead of a readable compile error the day this
     * constructor's signature changes.
     */
    GraphManager(TinkerGraph graph) {
        this(graph, null, Map.of());
    }

    private GraphManager(TinkerGraph graph, byte[] importedGraphMl, Map<String, Object> graphProperties) {
        this.graph = graph;
        this.importedGraphMl = importedGraphMl;
        this.importedGraphState = importedGraphMl == null ? null : graphState(graph);
        this.graphProperties = Map.copyOf(graphProperties);
    }

    public static GraphManager from(GraphDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        var graph = TinkerGraph.open();
        var vertices = new LinkedHashMap<String, Vertex>();
        for (var node : definition.nodes()) {
            var vertex = graph.addVertex(T.id, node.id(), T.label, "ravenroot-node");
            vertex.property(KIND, node.kind().name());
            if (node.behavior() != null) {
                vertex.property(BEHAVIOR, node.behavior());
            }
            node.properties().forEach((key, value) -> setProperty(vertex, key, value));
            vertices.put(node.id(), vertex);
        }
        for (var edge : definition.edges()) {
            var created = vertices.get(edge.source()).addEdge("ravenroot-edge", vertices.get(edge.target()),
                    T.id, edge.id());
            created.property(OUTCOME, edge.outcome());
            edge.properties().forEach((key, value) -> setProperty(created, key, value));
        }
        return new GraphManager(graph, null, definition.properties());
    }

    public static GraphManager readGraphMl(InputStream input) {
        return readGraphMl(input, GraphMlLimits.DEFAULTS);
    }

    public static GraphManager readGraphMl(InputStream input, GraphMlLimits limits) {
        Objects.requireNonNull(limits, "limits");
        byte[] bytes = SecureGraphMlParser.readAndValidate(input, limits);
        return readValidatedGraphMl(bytes);
    }

    public static ParsedGraphMl readGraphMlDocument(InputStream input) {
        return readGraphMlDocument(input, GraphMlLimits.DEFAULTS);
    }

    /** Reads and retains a GraphML document under caller-selected resource budgets. */
    public static ParsedGraphMl readGraphMlDocument(InputStream input, GraphMlLimits limits) {
        Objects.requireNonNull(limits, "limits");
        byte[] bytes = SecureGraphMlParser.readAndValidate(input, limits);
        return new ParsedGraphMl(readValidatedGraphMl(bytes), bytes);
    }

    /**
     * Reads a document exactly as {@link #readGraphMl(InputStream)} would and reports what it
     * declared, instead of returning the graph (INT-05).
     *
     * <p>It runs the full ingest — security limits, compatibility layer, property-graph mapping and
     * the SEC-09 reserved-namespace refusal — rather than a cheaper structural check, because a
     * validation verb whose verdict can differ from import's verdict is worse than none: it would
     * certify documents that then fail to load. The manager is built and closed here; the caller
     * gets the report and nothing that has to be released.</p>
     *
     * <p>The returned report carries strings taken from the document. See
     * {@link GraphMlProfileReport} for which channel they may go to.</p>
     */
    public static GraphMlProfileReport validateGraphMl(InputStream input) {
        return validateGraphMl(input, GraphMlLimits.DEFAULTS);
    }

    /** {@link #validateGraphMl(InputStream)} under caller-chosen document budgets. */
    public static GraphMlProfileReport validateGraphMl(InputStream input, GraphMlLimits limits) {
        Objects.requireNonNull(limits, "limits");
        byte[] bytes = SecureGraphMlParser.readAndValidate(input, limits);
        Imported imported = importValidatedGraphMl(bytes);
        try {
            // Parsing and importing say nothing about whether the document is a valid graph --
            // no semantic rule GraphDefinition.validate() enforces (terminal cardinality, a dangling
            // edge among nodes the import already accepted) and no semantic rule GraphManager itself
            // enforces while building nodes (an unknown kind, a BEHAVIOR node without its name) is
            // consulted by the two lines above. semanticViolations() runs that check and reports it on
            // the profile instead of throwing, so a well-formed but non-executable document is named
            // as invalid and stays inspectable in the same call. A document whose edge
            // is dangling in the GraphML sense -- naming a node id that was never declared -- is
            // refused earlier still, by the import itself (see graphml-corpus/rejected/dangling-edge.graphml);
            // it never reaches this method with a profile to attach violations to.
            // Redundancies alongside violations, never merged into them. A redundant
            // `joinPolicy=each` is legal and the document is valid; folding it into violations would
            // make valid() false for a document nothing is wrong with. Computed only when the
            // definition builds, because a document that does not build has no definition to ask.
            List<String> violations = imported.manager().semanticViolations();
            List<String> redundancies = violations.isEmpty()
                    ? JoinSemantics.redundancies(imported.manager().definition())
                    : List.of();
            return imported.report().withFindings(violations, redundancies);
        } finally {
            imported.manager().close();
        }
    }

    /**
     * The semantic violations a {@link #definition()} build against this manager's current graph
     * would raise, captured instead of thrown.
     *
     * <p>{@link #validateGraphMl} and {@code DefaultRavenrootApplication.inspectGraphMl} both need
     * this same answer without paying {@link #definition()}'s normal price: an exception that ends
     * the caller's ability to read anything else about the document. Routed through one method so the
     * two verbs cannot drift into checking different rules.</p>
     *
     * <h2>One caught type was not the complete set</h2>
     * <p>Catching only {@link GraphValidationException} misses a
     * real case: a node explicitly declaring {@code kind=BEHAVIOR} without a {@code behavior} name is
     * refused by {@link GraphNode}'s own canonical constructor with a plain
     * {@link IllegalArgumentException}, and an uncaught one climbed out of both callers into the
     * branch reserved for documents parsing/import itself refuses -- exactly the profile-erasing
     * collapse this method must prevent. {@link #toNode(Vertex)} now refuses that specific case as
     * a {@link GraphValidationException} before it ever reaches {@link GraphNode}, for the same reason
     * the unknown-kind refusal already does (one exception type for every "this node is not something
     * Ravenroot will run" refusal). The catch below still widens to the plain supertype on top of that:
     * every invariant {@link GraphNode} or {@link GraphEdge} enforces on a value it was actually given
     * is expressed as an {@link IllegalArgumentException} (of which {@link GraphValidationException} is
     * one) -- the null-guards a few lines up are the deliberate exception, and they stay outside this
     * net on purpose, because a null reaching here is a fault in this class and not in the document, so
     * catching the family, not only the subtype already named above, is what excludes by construction
     * -- rather than by remembering to keep this list exhaustive -- an unclassified future invariant
     * reaching the same collapse. It is a narrower net than catching {@code RuntimeException}: a bug
     * that is not a construction invariant (a {@code NullPointerException}, say) still propagates
     * loudly instead of being reported as a document violation it is not.</p>
     *
     * <h2>The caught message never leaves this method</h2>
     * <p>The plain {@link IllegalArgumentException} branch used to return {@code invalid.getMessage()}
     * as though it were a violation the document earned. {@code DefaultRavenrootApplication.inspectGraphMl}
     * and, through it, {@code POST /v1/graphs/inspect} publish every string this method returns to
     * whichever caller is authenticated for that endpoint -- not necessarily the author of the file --
     * with a negative verdict indistinguishable from a real one. An exception this branch catches is
     * always a program defect (every rule the document itself can violate already has its own
     * {@link GraphValidationException} type), so its message is exactly the kind of detail
     * {@code GraphMlRejectionDetail#diagnosticDetail()} already treats as server-side-only elsewhere in
     * this package. It is logged here, through the class logger, and {@link #UNCLASSIFIED_VIOLATION_MESSAGE}
     * is returned in its place -- never {@code null} either, which closes the {@code violation=null}
     * line the same defect could otherwise print.</p>
     */
    public List<String> semanticViolations() {
        try {
            definition();
            return List.of();
        } catch (GraphValidationException invalid) {
            return invalid.violations();
        } catch (IllegalArgumentException invalid) {
            // The message we compose is escaped (see sanitizeForLog); invalid itself is not rewritten
            // and is passed through as the record's own thrown exception, both so an operator gets the
            // real stack trace and so this class's own test can confirm the real exception -- not a
            // stand-in -- is what reached the log. That still leaves this LogRecord's *thrown* field
            // carrying the exception's raw, un-escaped message once the platform formatter renders its
            // stack trace: a limit of composing with java.util.logging's own Throwable rendering, not
            // something this method's own text can additionally guard. What this method controls is
            // the string it authors itself, and that string never carries unescaped document content.
            LOGGER.log(System.Logger.Level.WARNING,
                    "ravenroot_graph_semantic_violation_uncategorized: an IllegalArgumentException not "
                            + "classified as a document rule was caught while computing semantic "
                            + "violations; reported to the caller as a fixed message instead of this one "
                            + "-- caught.message=" + sanitizeForLog(invalid.getMessage()),
                    invalid);
            return List.of(UNCLASSIFIED_VIOLATION_MESSAGE);
        }
    }

    /**
     * Escapes a string before it becomes part of this class's own log line.
     *
     * <p>The net above exists precisely for an invariant not yet named, and this project's own
     * explicit checks already show the shape such an invariant tends to take: {@link #toNode(Vertex)}'s
     * BEHAVIOR-without-a-name refusal and {@link #nodeKind} unknown-kind refusal both interpolate
     * document-supplied text (a node id, a raw kind string) straight into their message. A future
     * invariant caught by the plain {@link IllegalArgumentException} branch could do the same, and that
     * text can carry a literal newline (GraphML preserves one written as a character reference). Nothing
     * downstream escapes this method's own log line the way {@code GraphMlRejectionDetail}'s
     * server-side sinks escape a rejection's diagnostic detail, so it is done here, once, the same way
     * {@code RavenrootCli#sanitizeForConsole} does it for the console: a visible replacement, not
     * removal, because a newline is the injection itself, not a character that merely needs quoting.
     * Duplicated rather than shared with that method because core does not depend on the CLI module.</p>
     */
    private static String sanitizeForLog(String value) {
        if (value == null) {
            return "";
        }
        var sanitized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            sanitized.append(character < 0x20 || character == 0x7f ? '?' : character);
        }
        return sanitized.toString();
    }

    /**
     * Compatibility import of bytes that already passed the {@link SecureGraphMlParser} limits.
     * Untrusted input must never reach this method directly.
     */
    private static GraphManager readValidatedGraphMl(byte[] bytes) {
        return importValidatedGraphMl(bytes).manager();
    }

    /**
     * The single import path, returning the graph and the account of what the document declared.
     * Both callers go through it so a report can never describe a reading the graph did not get.
     */
    private static Imported importValidatedGraphMl(byte[] bytes) {
        GraphMlDocument document = GraphMlDocument.read(bytes);
        var graph = TinkerGraph.open();
        try (var propertyGraphInput = document.propertyGraphInput()) {
            GraphMLReader.build()
                    .strict(true)
                    .xmlInputFactory(SecureGraphMlParser.secureInputFactory())
                    .create()
                    .readGraph(propertyGraphInput, graph);
            rejectReservedProperties(graph);
            var manager = new GraphManager(graph, document.original(), document.graphProperties());
            return new Imported(manager, document.report(manager.nodeCount(), manager.edgeCount()));
        } catch (IOException | RuntimeException exception) {
            graph.close();
            if (exception instanceof GraphMlParseException rejection) {
                throw rejection;
            }
            if (exception instanceof GraphMlCompatibilityException compatibilityException) {
                throw compatibilityException;
            }
            throw GraphMlRejection.compatibilityFailure(
                    GraphMlRejection.Sentence.SCALAR_MAPPING_FAILED, null, exception);
        }
    }

    public void writeGraphMl(OutputStream output) {
        Objects.requireNonNull(output, "output");
        if (importedGraphMl != null) {
            if (!importedGraphState.equals(graphState(graph))) {
                throw new IllegalStateException(
                        "Imported GraphML was mutated through a traversal; exporting would ambiguously "
                                + "combine graph changes with preserved XML extensions");
            }
            try {
                output.write(importedGraphMl);
                return;
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot write preserved GraphML", exception);
            }
        }
        try {
            GraphMLWriter.build().create().writeGraph(output, graph);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write GraphML", exception);
        }
    }

    /**
     * Creates a short-lived read-only traversal. Callers must not retain it.
     *
     * <p>The traversal carries {@link ReadOnlyStrategy}, so a mutating step is refused with a
     * {@code VerificationException} when the traversal is iterated (ARC-05). Before that
     * strategy was applied the write was not refused and was not isolated either: it landed on this
     * manager's own graph and stayed there, with no error returned to the caller — a graph documented
     * as exposing no mutation API silently accepted mutations.</p>
     *
     * <p><strong>This is a speed bump, not a boundary.</strong> {@code GraphTraversalSource#getGraph()}
     * is public in TinkerPop 3.8.1 and no strategy closes it, so a caller who reaches for the
     * underlying {@link TinkerGraph} can still mutate this manager freely. What the strategy buys is
     * that <em>accidental</em> silent corruption through ordinary Gremlin steps becomes a loud failure
     * at the call site. It does not make this manager immutable, and nothing here should be read as a
     * guarantee that the graph is protected. Closing the {@code getGraph()} escape is a separate
     * architectural question and is not settled by this method.</p>
     *
     * <p>{@link #writeGraphMl(OutputStream)} therefore keeps its own state-comparison guard (CORE-01)
     * as the last-line integrity check: that guard, not this strategy, is what still catches a
     * mutation which arrived through the escape.</p>
     */
    public <TResult> TResult query(GraphQuery<TResult> query) {
        Objects.requireNonNull(query, "query");
        var traversal = graph.traversal().withStrategies(ReadOnlyStrategy.instance());
        try {
            return query.execute(traversal);
        } finally {
            try {
                traversal.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot close Gremlin traversal", exception);
            }
        }
    }

    public GraphDefinition definition() {
        return query(traversal -> {
            List<GraphNode> nodes = traversal.V().toList().stream().map(this::toNode).toList();
            List<GraphEdge> edges = traversal.E().toList().stream().map(this::toEdge).toList();
            return new GraphDefinition(nodes, edges, graphProperties);
        });
    }

    public GraphNode start() {
        return query(traversal -> traversal.V().toList().stream()
                .map(this::toNode)
                .filter(node -> node.kind() == NodeKind.START)
                .findFirst()
                .orElseThrow(() -> new GraphValidationException(List.of("A graph must contain a start node"))));
    }

    /** Standard path-selection operation used by the runtime; implemented as a Gremlin traversal. */
    public List<GraphNode> next(String source, String outcome) {
        return query(traversal -> traversal.V(source).outE().toList().stream()
                .filter(edge -> edgeOutcome(edge).equals(outcome))
                .map(Edge::inVertex)
                .map(this::toNode)
                .toList());
    }

    public int predecessorCount(String target) {
        return Math.toIntExact(query(traversal -> traversal.V(target).inE().count().next()));
    }

    public long nodeCount() {
        return query(traversal -> traversal.V().count().next());
    }

    public long edgeCount() {
        return query(traversal -> traversal.E().count().next());
    }

    @Override
    public void close() {
        graph.close();
    }

    /**
     * A document declaring {@code kind=BEHAVIOR} without a {@code behavior}
     * name -- the single most common authoring mistake this format admits -- used to reach
     * {@link GraphNode}'s canonical constructor unguarded, which refuses it with a plain
     * {@link IllegalArgumentException}. That is a different exception type from every other semantic
     * refusal in this class, all of which are {@link GraphValidationException}, and
     * {@link #semanticViolations()} caught only that one type: the plain exception escaped it, and
     * with it {@code validateGraphMl}/{@code inspectGraphMl}'s ability to still answer with the
     * profile the document earned by importing cleanly.
     *
     * <p>Checked here, before construction, for the same reason the unknown-kind refusal a few lines
     * below already is: {@link #nodeKind} promises its caller a single exception type for "this
     * document declares something about a node that Ravenroot will not run", and that promise should
     * not depend on which invariant {@link GraphNode} happens to enforce itself.</p>
     */
    private GraphNode toNode(Vertex vertex) {
        Map<String, Object> properties = properties(vertex);
        String behavior = rawStringProperty(properties, BEHAVIOR);
        NodeKind kind = nodeKind(vertex.id().toString(), properties, behavior);
        if (kind == NodeKind.BEHAVIOR && (behavior == null || behavior.isBlank())) {
            throw new GraphValidationException(List.of("Node '" + vertex.id()
                    + "' declares kind 'BEHAVIOR' without a behavior name"));
        }
        properties.remove(KIND);
        properties.remove(BEHAVIOR);
        return new GraphNode(vertex.id().toString(), kind, kind == NodeKind.BEHAVIOR ? behavior : null, properties);
    }

    private GraphEdge toEdge(Edge edge) {
        Map<String, Object> properties = properties(edge);
        String outcome = rawStringProperty(properties, OUTCOME);
        properties.remove(OUTCOME);
        return new GraphEdge(edge.outVertex().id().toString(), edge.inVertex().id().toString(), outcome,
                properties, edge.id().toString());
    }

    private static String edgeOutcome(Edge edge) {
        return edge.property(OUTCOME).isPresent() ? edge.property(OUTCOME).value().toString() : "continue";
    }

    /**
     * Resolves a vertex's {@link NodeKind}, refusing a kind that was declared and is not known.
     *
     * <h2>What changed and why</h2>
     * <p>This method used to swallow the failure — {@code catch (IllegalArgumentException ignored)}
     * with the note "unknown node types remain executable through the default behavior" — and fall
     * through to the inference below, so any kind Ravenroot did not recognise became a passage. That
     * was survivable while every terminal was {@code END}: an unrecognised kind was almost certainly
     * a typo, and running it as a passage ran a graph that did roughly what its author meant.</p>
     *
     * <p>{@code ERROR} ends that. A kind now carries whether a node is a terminal, and reading an
     * unrecognised one as a passage turns "this run stops here" into "this run continues" — the graph
     * executes, reports success, and does something its author never drew. Since the reader cannot
     * tell a typo from a kind a newer Ravenroot writes, the honest answer to both is the same one:
     * refuse, and say which node and which word.</p>
     *
     * <h2>Declared-and-unknown, never merely absent</h2>
     * <p>The refusal is confined to a {@code kind} the document actually declares. GraphML lets an
     * author omit an attribute, {@code defaults-and-scopes.graphml} does exactly that, and the
     * inference below — behavior name present or not — is how such a node has always been read. That
     * path is untouched: silence is not a claim, so there is nothing to disbelieve.</p>
     *
     * <h2>Where the refusal lands</h2>
     * <p>Thrown as a {@link GraphValidationException}, from {@link #definition()} rather than from
     * {@code readGraphMl}, because that is where every other semantic rule about what a graph may
     * declare is enforced and where {@link GraphDefinition}'s own structural rules already are. A
     * document therefore parses and refuses at the same boundary as a graph missing its terminals,
     * with one exception type for the caller to handle.</p>
     */
    private static NodeKind nodeKind(String nodeId, Map<String, Object> properties, String behavior) {
        String raw = stringProperty(properties, KIND);
        if (raw != null && !raw.isEmpty()) {
            try {
                return NodeKind.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new GraphValidationException(List.of("Node '" + nodeId + "' declares an unknown kind '"
                        + raw + "'; the known kinds are "
                        + Arrays.stream(NodeKind.values()).map(Enum::name).collect(Collectors.joining(", "))));
            }
        }
        return behavior == null ? NodeKind.PASSTHROUGH : NodeKind.BEHAVIOR;
    }

    private static String stringProperty(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        return value == null ? null : value.toString().trim();
    }

    private static String rawStringProperty(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        return value == null ? null : value.toString();
    }

    /**
     * Refuses a parsed graph that declares a property in the reserved namespace on any node or edge
     * (SEC-07, widened to the whole operative namespace by SEC-09 — see
     * {@link ReservedGraphProperties}).
     *
     * <p>Enforced here, at ingest, rather than at node execution. Rejecting later would mean a
     * poisoned graph is accepted, hashed into a {@code graphVersion}, recorded in the execution store
     * and partly run before anything objects — and by then the rejection is a mid-flight failure
     * rather than a refused submission.</p>
     *
     * <p>Only the reserved namespace is refused. Every other property, known or unknown, passes
     * through untouched: SEC-09's preservation requirement is as load-bearing as its rejection
     * requirement, and a guard that refused unknown properties would violate that requirement.</p>
     *
     * <p>This is a semantic rule about what a graph may <em>declare</em>, which is why it lives here
     * and not in {@link SecureGraphMlParser}: that class owns XML-level safety and document limits
     * (SEC-08) and has no notion of Ravenroot property namespaces.</p>
     */
    private static void rejectReservedProperties(TinkerGraph graph) {
        graph.vertices().forEachRemaining(
                vertex -> rejectReservedKeys(vertex, GraphMlRejection.Term.NODE, vertex.id()));
        graph.edges().forEachRemaining(
                edge -> rejectReservedKeys(edge, GraphMlRejection.Term.EDGE, edge.id()));
    }

    /**
     * The element id reaches the diagnostic channel and not the message, which settles two separate
     * leaks at once under one rule (FIX-03).
     *
     * <p>The reserved property name is attacker-supplied: only its {@code ravenroot.} prefix is
     * fixed, and everything after it is text the submitter wrote, so echoing it was the same
     * information disclosure this protection addresses — occurring in the layer that was already supposed
     * to be the sanitised one.</p>
     *
     * <p>The element id runs the other way. This guard is applied after
     * {@code GraphMlDocument#resolveEdgeIdentity}, so an id-less edge arrives carrying a synthesized
     * {@code ravenroot-synthesized-edge-N} handle (FIX-01) that the author never wrote and
     * cannot look up. Naming it disclosed an internal handle rather than untrusted content. Both are
     * details, so neither is public and the ordering no longer changes what the caller sees.</p>
     */
    private static void rejectReservedKeys(org.apache.tinkerpop.gremlin.structure.Element element,
                                           GraphMlRejection.Term elementKind, Object elementId) {
        element.keys().stream()
                .filter(ReservedGraphProperties::isReserved)
                .findFirst()
                .ifPresent(reserved -> {
                    throw GraphMlRejection.parseFailure(GraphMlParseException.Reason.INVALID_GRAPH,
                            GraphMlRejection.Sentence.RESERVED_PROPERTY, elementKind,
                            GraphMlRejection.detail("elementKind", elementKind.text()),
                            GraphMlRejection.detail("elementId", elementId),
                            GraphMlRejection.detail("reservedProperty", reserved));
                });
    }

    private static Map<String, Object> properties(org.apache.tinkerpop.gremlin.structure.Element element) {
        var result = new LinkedHashMap<String, Object>();
        element.keys().forEach(key -> result.put(key, element.property(key).value()));
        return result;
    }

    private static void setProperty(org.apache.tinkerpop.gremlin.structure.Element element, String key, Object value) {
        if (!KIND.equals(key) && !BEHAVIOR.equals(key) && !OUTCOME.equals(key) && value != null) {
            element.property(key, value);
        }
    }

    private static GraphState graphState(TinkerGraph graph) {
        var vertices = new ArrayList<ElementState>();
        graph.vertices().forEachRemaining(vertex -> vertices.add(new ElementState(
                vertex.id().toString(), vertex.label(), sortedProperties(vertex))));
        vertices.sort(Comparator.comparing(ElementState::id));

        var edges = new ArrayList<EdgeState>();
        graph.edges().forEachRemaining(edge -> edges.add(new EdgeState(
                edge.id().toString(),
                edge.label(),
                edge.outVertex().id().toString(),
                edge.inVertex().id().toString(),
                sortedProperties(edge))));
        edges.sort(Comparator.comparing(EdgeState::id));
        return new GraphState(List.copyOf(vertices), List.copyOf(edges));
    }

    private static Map<String, Object> sortedProperties(
            org.apache.tinkerpop.gremlin.structure.Element element) {
        var properties = new TreeMap<String, Object>();
        element.keys().forEach(key -> properties.put(key, element.property(key).value()));
        return Map.copyOf(properties);
    }

    private record GraphState(List<ElementState> vertices, List<EdgeState> edges) {
    }

    private record ElementState(String id, String label, Map<String, Object> properties) {
    }

    private record EdgeState(
            String id, String label, String source, String target, Map<String, Object> properties) {
    }

    /** One import: the graph it produced and the account of what the document declared (INT-05). */
    private record Imported(GraphManager manager, GraphMlProfileReport report) {
    }

    @FunctionalInterface
    public interface GraphQuery<TResult> {
        TResult execute(GraphTraversalSource traversal);
    }

    /** One validated immutable source snapshot and its parsed graph. */
    public record ParsedGraphMl(GraphManager manager, byte[] bytes) implements AutoCloseable {
        public ParsedGraphMl {
            Objects.requireNonNull(manager, "manager");
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public void close() {
            manager.close();
        }
    }
}
