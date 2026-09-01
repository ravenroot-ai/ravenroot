package ai.ravenroot.cli;

import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphMlProfileReport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code ravenroot validate &lt;graph.graphml&gt;} — reads a document exactly as an import would and
 * says what it found (INT-05).
 *
 * <h2>Exit 0 is not the answer, it is half of it</h2>
 * <p>Ravenroot keeps everything it does not understand: an unrecognised key survives a round trip
 * byte-for-byte and is never interpreted. That is the right contract and it is also the one that
 * makes a mistake invisible — a misspelled {@code behavior}, a property an editor wrote under a name
 * this runtime never reads, a {@code <data>} carrying a subtree instead of a scalar. None of those
 * produce an error, so the only way to learn about them is to be told. This command therefore always
 * prints what crossed without being understood, and a caller that only checks the exit code has
 * deliberately chosen not to look.</p>
 *
 * <h2>Three verdicts, not two</h2>
 * <p>{@code accepted} means the document is a valid graph. {@code rejected} means parsing or import
 * itself refused it, and there is no profile to show — a dangling edge (a node id an edge names but
 * no {@code <node>} ever declares) is one of these: the import rejects it before a profile exists to
 * report. {@code invalid} is the case in between: the document parsed and imported cleanly but is not
 * a graph {@code GraphManager.definition()} would build — an unknown node kind, a missing or surplus
 * terminal, a {@code BEHAVIOR} node with no name — and it still prints every profile line
 * {@code accepted} would, because a well-formed but non-executable document is exactly the case this
 * command exists to let an operator inspect rather than merely refuse.</p>
 *
 * <h2>Not a backend command</h2>
 * <p>It takes no {@link CliBackend}. Validation is a function of a local file and of the profile the
 * reading binary implements, so there is nothing for a server to answer; {@link RavenrootCliMain}
 * intercepts the verb before an execution engine is constructed, for the same reason backup and
 * restore are intercepted there. What it cannot tell you is whether some <em>other</em> Ravenroot
 * would accept the document — mapping a product version to a format profile is release policy and
 * outside this command's scope.</p>
 *
 * <h2>Output channel</h2>
 * <p>The report repeats key ids, names, types and scopes taken from the document. That is the
 * channel {@code GraphMlRejectionDetail#diagnosticDetail()} describes as server-side-only, and this
 * is that side: the operator running the command is the holder of the file. Values still pass
 * through {@link RavenrootCli#sanitizeForConsole(String)}, so a document cannot forge a line.</p>
 */
final class GraphMlValidateCommand {

    private GraphMlValidateCommand() {
    }

    static int run(String[] args, PrintStream output, PrintStream errors) {
        if (args.length != 2) {
            errors.println("Usage: ravenroot validate <graph.graphml>");
            return 2;
        }
        return run(Path.of(args[1]), output, errors);
    }

    static int run(Path file, PrintStream output, PrintStream errors) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException unreadable) {
            // The path came from this operator's own command line, so naming it is not disclosure;
            // the exception message is not, so it is not echoed.
            errors.println("Error: cannot read " + RavenrootCli.sanitizeForConsole(file.toString()));
            return 2;
        }
        GraphMlProfileReport report;
        try {
            report = GraphManager.validateGraphMl(new java.io.ByteArrayInputStream(bytes));
        } catch (RuntimeException rejection) {
            // Same rendering as every other refused document, incident id and diagnostic detail
            // included: "no silent loss" has to hold on the refusing side too, or the verb would
            // only be honest about the documents that pass. Reserved for documents parsing/import
            // itself refuses; a document that parses but is not a valid graph never reaches here --
            // see the branch below: its profile must stay readable.
            RavenrootCli.reportFailure(rejection, errors);
            output.println("verdict=rejected");
            return 1;
        }
        if (!report.valid()) {
            // The document parsed and imported cleanly, but is not a graph GraphDefinition
            // would accept -- an unknown node kind, a missing or surplus terminal, a BEHAVIOR node
            // with no name. (A dangling edge is not an example here: the import refuses it earlier,
            // via the RuntimeException branch above, with no profile -- it never reaches this check.)
            // The verdict is negative and names the violation, and the profile prints anyway: a
            // well-formed but non-executable document must stay inspectable, not merely rejected.
            output.println("verdict=invalid");
            for (var violation : report.violations()) {
                output.println("violation=" + RavenrootCli.sanitizeForConsole(violation));
            }
            print(report, output);
            return 1;
        }
        output.println("verdict=accepted");
        print(report, output);
        return 0;
    }

    private static void print(GraphMlProfileReport report, PrintStream output) {
        output.println("profile=" + report.profile());
        output.println("nodes=" + report.nodes());
        output.println("edges=" + report.edges());
        output.println("declared-keys=" + report.keys().size());
        output.println("uninterpreted-keys=" + report.uninterpretedKeys().size());
        for (var key : report.keys()) {
            output.println("key"
                    + " id=" + RavenrootCli.sanitizeForConsole(key.id())
                    + " name=" + RavenrootCli.sanitizeForConsole(key.name())
                    + " type=" + RavenrootCli.sanitizeForConsole(key.type())
                    + " scope=" + RavenrootCli.sanitizeForConsole(key.scope())
                    + " disposition=" + key.disposition()
                    + " opaque-values=" + key.opaqueValues());
        }
        output.println("opaque-data-values=" + report.opaqueDataValues());
        output.println("foreign-namespace-elements=" + report.foreignNamespaceElements());
        output.println("synthesized-edge-ids=" + report.synthesizedEdgeIds());
        // Printed after the counts and separately from any violation, because a redundancy is
        // not a verdict on the document: the exit code is unaffected and the document is valid. It is
        // printed at all because "legal but says nothing" is only useful to an author who is told.
        for (var redundancy : report.redundancies()) {
            output.println("redundant " + RavenrootCli.sanitizeForConsole(redundancy));
        }
    }
}
