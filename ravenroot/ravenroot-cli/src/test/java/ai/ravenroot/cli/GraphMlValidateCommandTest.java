package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ravenroot validate} on the two things it has to get right: a document that
 * passes still has to say what crossed uninterpreted, and a document that fails still has to hand
 * the operator the diagnostic the public message deliberately does not carry.
 */
class GraphMlValidateCommandTest {

    private static final String ACCEPTED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns"
                     xmlns:viz="urn:example:sanitized-visual">
              <key id="n-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="n-owner" for="node" attr.name="owner" attr.type="string"/>
              <key id="n-graphics" for="node" yfiles.type="nodegraphics"/>
              <key id="p-graphics" for="port" yfiles.type="portgraphics"/>
              <graph id="g" edgedefault="directed">
                <node id="error"><data key="n-kind">ERROR</data></node>
                <node id="start">
                  <data key="n-kind">START</data>
                  <data key="n-owner">platform</data>
                  <data key="n-graphics"><viz:box width="10"/></data>
                </node>
                <node id="end"><data key="n-kind">END</data></node>
                <edge source="start" target="end"/>
              </graph>
            </graphml>
            """;

    /** A reserved property as node content: refused at ingest by SEC-09. */
    private static final String REJECTED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="n-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="n-format-version" for="node" attr.name="ravenroot.format.version" attr.type="int"/>
              <graph id="g" edgedefault="directed">
                <node id="error"><data key="n-kind">ERROR</data></node>
                <node id="start">
                  <data key="n-kind">START</data>
                  <data key="n-format-version">1</data>
                </node>
                <node id="end"><data key="n-kind">END</data></node>
                <edge id="e" source="start" target="end"/>
              </graph>
            </graphml>
            """;

    @TempDir
    Path directory;

    @Test
    void anAcceptedDocumentStillHasToSayWhatCrossedWithoutBeingUnderstood() throws IOException {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exitCode = GraphMlValidateCommand.run(
                write("accepted.graphml", ACCEPTED), stream(output), stream(errors));

        List<String> lines = lines(output);
        assertEquals(0, exitCode, errors.toString(StandardCharsets.UTF_8));
        assertTrue(lines.contains("verdict=accepted"), lines.toString());
        assertTrue(lines.contains("profile=0"), lines.toString());
        assertTrue(lines.contains("nodes=3"), lines.toString());
        assertTrue(lines.contains("edges=1"), lines.toString());
        // The whole point of the verb: three of the four keys are carried and never read, and the
        // exit code alone would have said nothing about any of them.
        assertTrue(lines.contains("uninterpreted-keys=3"), lines.toString());
        assertTrue(lines.contains(
                "key id=n-kind name=kind type=string scope=node disposition=INTERPRETED opaque-values=0"),
                lines.toString());
        assertTrue(lines.contains(
                "key id=n-owner name=owner type=string scope=node disposition=PRESERVED opaque-values=0"),
                lines.toString());
        assertTrue(lines.contains("key id=n-graphics name=n-graphics type=string scope=node "
                + "disposition=PRESERVED opaque-values=1"), lines.toString());
        assertTrue(lines.contains("key id=p-graphics name=p-graphics type=string scope=port "
                + "disposition=DECLARED_OUTSIDE_PROPERTY_GRAPH opaque-values=0"), lines.toString());
        assertTrue(lines.contains("opaque-data-values=1"), lines.toString());
        assertTrue(lines.contains("foreign-namespace-elements=1"), lines.toString());
        // The unnamed edge got an import-only handle; an operator who never wrote an id is told so
        // here rather than meeting one for the first time inside an error message.
        assertTrue(lines.contains("synthesized-edge-ids=1"), lines.toString());
    }

    @Test
    void aRefusedDocumentLosesNothingEither() throws IOException {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exitCode = GraphMlValidateCommand.run(
                write("rejected.graphml", REJECTED), stream(output), stream(errors));

        String stderr = errors.toString(StandardCharsets.UTF_8);
        assertEquals(1, exitCode);
        assertTrue(lines(output).contains("verdict=rejected"));
        assertTrue(stderr.contains("declares a property in Ravenroot's reserved namespace"), stderr);
        // FIX-03 keeps the offending property name off the public message; the operator's own
        // console is the sink that is allowed to have it, and a validate that dropped it would be
        // telling somebody their document is wrong without telling them where.
        assertTrue(stderr.contains("incident: "), stderr);
        assertTrue(stderr.contains("reservedProperty: ravenroot.format.version"), stderr);
        assertFalse(stderr.contains("Usage:"), stderr);
    }

    /** A document parsing and importing cleanly, but declaring a node kind Ravenroot does not know. */
    private static final String UNKNOWN_KIND = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="mystery"><data key="kind">SUBGRAPH</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="mystery"/>
                <edge id="e2" source="mystery" target="end"/>
              </graph>
            </graphml>
            """;

    /**
     * A document parsing and importing cleanly, but declaring two error terminals -- surplus, not
     * absence: the error terminal is optional, so a document without one is a legitimate graph today.
     * This fixture exercises the other half of the same cardinality rule, the ceiling, as the example
     * that still refuses.
     */
    private static final String TWO_ERROR_TERMINALS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="first"><data key="kind">ERROR</data></node>
                <node id="second"><data key="kind">ERROR</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="end"/>
                <edge id="e2" source="start" target="first"/>
                <edge id="e3" source="start" target="second"/>
              </graph>
            </graphml>
            """;

    /**
     * A node declaring {@code kind=BEHAVIOR} without a {@code behavior}
     * name -- the single most common authoring mistake this format admits. Before the regression fix,
     * {@code semanticViolations()} caught only {@code GraphValidationException}; this document's
     * refusal came from {@code GraphNode}'s own constructor as a plain
     * {@code IllegalArgumentException} instead, escaped uncaught, and collapsed this command's
     * verdict to {@code rejected} with no profile at all. Previously, this same document was
     * {@code accepted} with the full profile.
     */
    private static final String BEHAVIOR_WITHOUT_NAME = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="mystery"><data key="kind">BEHAVIOR</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="mystery"/>
                <edge id="e2" source="mystery" target="end"/>
              </graph>
            </graphml>
            """;

    /**
     * Before this fix, {@code validate} answered {@code verdict=accepted}
     * for this document -- {@code GraphManager.validateGraphMl} never built a {@code GraphDefinition},
     * so the unknown kind was never consulted. This is the wrong-verdict-before / right-verdict-after
     * pin for the unknown-kind document.
     */
    @Test
    void anUnknownNodeKindReceivesANegativeVerdictThatNamesTheViolation() throws IOException {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exitCode = GraphMlValidateCommand.run(
                write("unknown-kind.graphml", UNKNOWN_KIND), stream(output), stream(errors));

        List<String> lines = lines(output);
        assertEquals(1, exitCode, errors.toString(StandardCharsets.UTF_8));
        assertFalse(lines.contains("verdict=accepted"), lines.toString());
        assertTrue(lines.contains("verdict=invalid"), lines.toString());
        assertTrue(lines.contains("violation=Node 'mystery' declares an unknown kind 'SUBGRAPH'; "
                + "the known kinds are START, PASSTHROUGH, BEHAVIOR, END, ERROR"), lines.toString());
        // The negative verdict does not take the profile down with it.
        assertTrue(lines.contains("nodes=3"), lines.toString());
        assertTrue(lines.contains("edges=2"), lines.toString());
    }

    /** The surplus-terminal document, measured against today's rules. */
    @Test
    void aSurplusOfErrorTerminalsReceivesANegativeVerdictThatNamesTheViolation() throws IOException {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exitCode = GraphMlValidateCommand.run(
                write("two-error-terminals.graphml", TWO_ERROR_TERMINALS), stream(output), stream(errors));

        List<String> lines = lines(output);
        assertEquals(1, exitCode, errors.toString(StandardCharsets.UTF_8));
        assertFalse(lines.contains("verdict=accepted"), lines.toString());
        assertTrue(lines.contains("verdict=invalid"), lines.toString());
        assertTrue(lines.contains(
                "violation=A graph must contain at most one error node, and 2 are declared: first, second"),
                lines.toString());
        // The negative verdict does not take the profile down with it.
        assertTrue(lines.contains("nodes=4"), lines.toString());
        assertTrue(lines.contains("edges=3"), lines.toString());
    }

    /** Pins the negative verdict, named violation and retained profile for a nameless behavior. */
    @Test
    void aBehaviorNodeWithoutANameReceivesANegativeVerdictThatNamesTheViolationAndKeepsTheProfile()
            throws IOException {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exitCode = GraphMlValidateCommand.run(
                write("behavior-without-name.graphml", BEHAVIOR_WITHOUT_NAME), stream(output), stream(errors));

        List<String> lines = lines(output);
        assertEquals(1, exitCode, errors.toString(StandardCharsets.UTF_8));
        assertFalse(lines.contains("verdict=accepted"), lines.toString());
        // The pre-regression fix regression: this document does not reach the parse/import-failure branch,
        // so it must never print verdict=rejected either -- that would mean the profile is gone again.
        assertFalse(lines.contains("verdict=rejected"), lines.toString());
        assertTrue(lines.contains("verdict=invalid"), lines.toString());
        assertTrue(lines.contains("violation=Node 'mystery' declares kind 'BEHAVIOR' without a behavior name"),
                lines.toString());
        // The profile must still print, not just the verdict.
        assertTrue(lines.contains("nodes=3"), lines.toString());
        assertTrue(lines.contains("edges=2"), lines.toString());
        assertTrue(lines.contains("profile=0"), lines.toString());
    }

    @Test
    void refusesToGuessWhenTheArgumentsAreNotAFileToValidate() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        assertEquals(2, GraphMlValidateCommand.run(
                new String[]{"validate"}, stream(output), stream(errors)));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Usage: ravenroot validate"));

        var missingOutput = new ByteArrayOutputStream();
        var missingErrors = new ByteArrayOutputStream();
        assertEquals(2, GraphMlValidateCommand.run(
                directory.resolve("absent.graphml"), stream(missingOutput), stream(missingErrors)));
        assertTrue(missingErrors.toString(StandardCharsets.UTF_8).contains("cannot read"));
    }

    private Path write(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static PrintStream stream(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private static List<String> lines(ByteArrayOutputStream sink) {
        return sink.toString(StandardCharsets.UTF_8).lines().toList();
    }
}
