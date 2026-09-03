package ai.ravenroot.cli;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.security.RequestContext;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Text adapter over exactly the same use cases exposed by the HTTP adapter.
 *
 * <p>API-05: dispatches every command to a {@link CliBackend} rather than to
 * {@link AuthorizedRavenrootApplication} directly, so the same command behavior can run against the
 * embedded application or a remote server without this class knowing which. The convenience
 * constructor below preserves the embedded-only behavior every existing caller already depends on.</p>
 */
public final class RavenrootCli {
    private final CliBackend backend;
    private final PrintStream output;
    private final PrintStream errors;

    /** The assembly supplies an explicitly trusted local bootstrap identity; this class does not elevate callers. */
    public RavenrootCli(AuthorizedRavenrootApplication application, RequestContext requestContext,
                        PrintStream output, PrintStream errors) {
        this(new EmbeddedBackend(application, requestContext), output, errors);
    }

    /** The remote-capable constructor. {@code backend} decides embedded vs. remote; this class never does. */
    public RavenrootCli(CliBackend backend, PrintStream output, PrintStream errors) {
        this.output = output;
        this.errors = errors;
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
    }

    public int run(String... args) {
        if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
            help();
            return 0;
        }
        try {
            return switch (args[0]) {
                case "status" -> status();
                case "runtime" -> runtime();
                case "node-types" -> nodeTypes();
                case "inspect" -> inspect(args);
                // The only command here that does not reach the backend, and
                // deliberately: validating a local file against the GraphML profile needs no
                // application, no request context and no server, so routing it through CliBackend
                // would force ai.ravenroot.cli.remote.RemoteBackend either to invent a server
                // endpoint that does not exist or to run the same local code behind a name that
                // says otherwise. RavenrootCliMain intercepts it before an engine is ever built, so
                // this case is the embedded-object entry point rather than the usual one.
                case "validate" -> GraphMlValidateCommand.run(args, output, errors);
                case "run" -> runGraph(args);
                case "result" -> result(args);
                case "live" -> liveExecutions();
                case "cancel" -> cancelExecution(args);
                case "drain" -> drainServer();
                // One verb, seven subcommands, mirroring 'credentials' below: a listing, a
                // register, and five id-scoped lifecycle actions. Every printed line carries scope=
                // LOCAL_PROCESS -- the same word and the same guarantee the HTTP wire states -- so an
                // operator reading this output cannot mistake it for a durable or cluster-wide claim.
                case "deployments" -> deployments(args);
                // One verb, two subcommands: 'list' reads, 'add' is the one mutating
                // act -- see credentials(String[])'s own Javadoc. The shape was borrowed from the
                // 'model-providers' verb, which moved out of the CLI with the provider-configuration
                // plane it spoke to.
                case "credentials" -> credentials(args);
                default -> invalid("Unknown command: " + args[0]);
            };
        } catch (RuntimeException | IOException error) {
            reportFailure(error, errors);
            return 1;
        }
    }

    /**
     * The operator-console rendering of a failed command, shared with {@link GraphMlValidateCommand}.
     *
     * <p>The CLI's error stream is the operator's own console, which is a server-side sink, so the
     * detail {@code GraphMlRejection} kept out of the caller-facing message belongs here (FIX-03).
     * Without this the CLI would have lost diagnosability rather than relocated it.</p>
     *
     * <p>Extracted rather than duplicated because {@code validate}'s entire contract is that a
     * refusal is fully reported: a second copy of this that fell behind would defeat the verb it
     * exists to serve.</p>
     */
    static void reportFailure(Throwable error, PrintStream errors) {
        errors.println("Error: " + sanitizeForConsole(error.getMessage()));
        if (error instanceof ai.ravenroot.core.graph.GraphMlRejectionDetail rejection) {
            errors.println("  incident: " + rejection.incidentId());
            rejection.diagnosticDetail().forEach((label, value) ->
                    errors.println("  " + sanitizeForConsole(label) + ": " + sanitizeForConsole(value)));
        }
    }

    /**
     * This is the operator's own console, not a machine-parsed format,
     * so JSON-style escaping ({@code ai.ravenroot.server.audit.JsonStrings}, used by every server-side
     * JSON-lines sink) is the wrong tool here -- a visible replacement keeps the text readable while
     * removing the one thing a document-derived value could use to forge what looks like a second,
     * unrelated diagnostic line: a newline is itself the injection, not a character that merely needs
     * quoting. {@code value} here is exactly the class of content {@code GraphMlRejectionDetail}'s own
     * contract says "must only reach a server-side sink" -- this is that sink, for the CLI.
     */
    /** Package-private so its focused test can exercise the escaping directly. */
    static String sanitizeForConsole(String value) {
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

    private int status() throws IOException {
        var status = backend.status();
        output.println("state=" + status.state());
        output.println("execution-engine=" + status.executionEngine());
        output.println("capabilities=" + String.join(",", status.capabilities()));
        return 0;
    }

    private int runtime() throws IOException {
        var snapshot = backend.runtime();
        output.println("active-executions=" + snapshot.activeExecutions());
        // activeNodeInstances (printed below as "node.<id>.active") is arrivals in flight per node,
        // not instances of the node's actor -- see RuntimeSnapshot's own Javadoc.
        snapshot.activeNodeInstances().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> output.println("node." + entry.getKey() + ".active=" + entry.getValue()));
        return 0;
    }

    private int nodeTypes() throws IOException {
        backend.nodeTypes().forEach(type -> output.printf("%s\t%s\t%s\t%s%n",
                type.behavior(), type.category(), type.visualOrAgentic(), type.description()));
        return 0;
    }

    private int inspect(String[] args) throws IOException {
        if (args.length != 2) {
            return invalid("Usage: ravenroot inspect <graph.graphml>");
        }
        var summary = backend.inspect(Files.readAllBytes(Path.of(args[1])));
        output.printf("nodes=%d%nedges=%d%nstart-nodes=%d%nend-nodes=%d%nvalid=%b%n",
                summary.nodes(), summary.edges(), summary.startNodes(), summary.endNodes(), summary.valid());
        // A document that counted like a sound graph could still be one an unknown node kind or a
        // missing/surplus terminal makes unrunnable -- see CliBackend.InspectView's
        // Javadoc. This is what tells the operator that without them having to run 'validate' too.
        for (var violation : summary.violations()) {
            output.println("violation=" + sanitizeForConsole(violation));
        }
        return 0;
    }

    private int runGraph(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            return invalid("Usage: ravenroot run <graph.graphml> [payload]");
        }
        byte[] graphMl = Files.readAllBytes(Path.of(args[1]));
        String payload = args.length == 3 ? args[2] : "";
        var submission = backend.run(graphMl, payload);
        output.println("process-instance-id=" + submission.processInstanceId());
        output.println("traversal-id=" + submission.traversalId());
        output.println("execution-id=" + submission.executionId());
        output.println("graph-version=" + submission.graphVersion());
        // The policy the submission actually ran under, printed unconditionally rather than only
        // when it is the surprising one. A line that appears only on TEST_PASSTHROUGH tells an operator
        // nothing on the run where it is absent -- they cannot distinguish "standard" from "this CLI is
        // too old to say". Whichever policy ran, the operator was told which, independent of any
        // transport default.
        //
        // Why this line is unconditional while the three signals 'result' prints below are not. The
        // two conventions are opposite and both are deliberate, so the distinction is written down
        // rather than left to look like an oversight. The precedent is 'degraded' four lines above:
        // an exceptional boolean printed unconditionally, for the same reason given here.
        // "No empty case" alone does not carry the distinction -- 'handledFailure' is single-valued
        // and empty-less too, and is printed conditionally -- so the criterion is narrower: printing
        // 'false' on every clean run gives an operator nothing to act on, while omitting the policy
        // line would mean the reader has to infer it, defeating this output's purpose.
        // The other three report an
        // exception to the normal case (two node lists that are usually empty, and a boolean that is
        // false unless a node actually failed), so for them absence IS the message, and printing them
        // on every clean run would bury the occasions they matter.
        //
        // Note this is NOT the rule RavenrootServer#executionOutcomeJson argues for the same fields on
        // the wire, where they are emitted even when empty. That is right there and would be wrong
        // here: an HTTP client can be a different version than the server, so it must be able to tell
        // "empty" from "this server does not report it", and only an always-present field lets it. A
        // person or script reading this stdout invoked this binary, so the absent-line ambiguity that
        // argument turns on does not arise.
        output.println("execution-policy=" + submission.executionPolicy());
        return 0;
    }

    /** Reads back what {@code run} started, by the {@code execution-id} it printed. */
    private int result(String[] args) throws IOException {
        if (args.length != 2) {
            return invalid("Usage: ravenroot result <execution-id>");
        }
        var view = backend.result(args[1]);
        output.println("execution-id=" + view.executionId());
        output.println("status=" + view.status());
        output.println("degraded=" + view.degraded());
        // Degraded must stay visible even when nobody thought to ask: unresolved pass-through defaults
        // are a correctness signal, not a footnote, so they get their own line whenever there are any.
        // That sentence was a false reassurance on the remote path, in two distinct ways, and
        // both are fixed here rather than restated. It named only ONE of the four node lists the
        // outcome carries -- so a run that executed nothing (bypassed) or that survived a node crash
        // (handledFailure) printed exactly what a clean run prints, since both leave status=COMPLETED
        // and degraded=false. And the remote transport was not reading either list off the wire at
        // all, so this command could not have printed them even had it tried (see RemoteBackend#result).
        if (!view.defaultedNodes().isEmpty()) {
            output.println("defaulted-nodes=" + String.join(",", view.defaultedNodes()));
        }
        // This line now has two possible meanings and prints them identically. Either the run
        // was a rehearsal -- which the caller asked for and already knows -- or the graph's author
        // left `execution.bypass=true` on a node and this otherwise real run skipped it. The second
        // is the one worth seeing, and it is indistinguishable here.
        //
        // Not fixed by printing something extra, because nothing on this view carries the cause:
        // ExecutionOutcome#bypassedNodes is a flat set, deliberately (see its own javadoc), and the
        // distinction lives only in the NODE_BYPASSED events' publicReason. Printing a guess derived
        // from the submission's mode would be wrong in exactly the case that matters -- a `run`
        // submission is where an authored bypass shows up. Surfacing it here therefore means carrying
        // the cause through ExecutionOutcome, the outcome JSON and both backends, which is a change
        // to a public record and not something this line can do on its own.
        if (!view.bypassedNodes().isEmpty()) {
            output.println("bypassed-nodes=" + String.join(",", view.bypassedNodes()));
        }
        // Printed whenever true, and the list with it: unlike bypassing, a handled failure is a fault
        // that occurred, so the boolean is the statement and the list is the detail.
        if (view.handledFailure()) {
            output.println("handled-failure=true");
            output.println("handled-failure-nodes=" + String.join(",", view.handledFailureNodes()));
        }
        // Not a fifth node list -- bypassed-nodes above says WHICH nodes were bypassed, this
        // says what each one's bypass cost in edges its own hardcoded "continue" outcome could never
        // select. Gated on emptiness like defaulted/bypassed-nodes, not on a boolean like
        // handled-failure, because untaken edges are a fact about a bypass the operator already asked
        // to see (bypassed-nodes is printed on the very same condition), not a fault.
        if (!view.untakenEdges().isEmpty()) {
            output.println("untaken-edges=" + String.join(",", view.untakenEdges()));
        }
        output.println("visited-nodes=" + String.join(",", view.visitedNodes()));
        if (view.payload() != null) {
            output.println("payload=" + view.payload());
        }
        return 0;
    }

    /**
     * Lists this tenant's live executions with the identifiers {@code cancel} needs -- the read
     * exposed by the API but previously unreachable from this CLI. One line per execution, so
     * an operator (or a script) can find a traversal-id without having recorded anything {@code run}
     * printed earlier; a stalled execution -- one that has stopped publishing anything -- appears here
     * exactly as long as it stays cancellable, because both this listing and {@code cancel} read the
     * same runtime bookkeeping. An idle tenant gets no lines at all, not an empty-listing message: the
     * absence of output IS the answer, matching every other verb's convention of printing only what
     * there is to print.
     */
    private int liveExecutions() throws IOException {
        for (var execution : backend.live()) {
            output.println("traversal-id=" + execution.traversalId()
                    + "\tprocess-instance-id=" + execution.processInstanceId()
                    + "\texecution-id=" + execution.executionId()
                    + "\tgraph-version=" + sanitizeForConsole(execution.graphVersion())
                    + "\tstarted-at=" + execution.startedAt());
        }
        return 0;
    }

    /**
     * API-02. Pause and resume remain outside this command; cancel and drain are the two pieces
     * of {@code control} it exposes.
     *
     * <h2>This echoes {@code CancelResult.note()} rather than asserting its own claim</h2>
     * <p>The three lines below are the outcome the backend recorded ({@code outcome}, an enum with no
     * "requested" value -- it is CANCELLED, ALREADY_CANCELLED or ALREADY_COMPLETED, never merely
     * "accepted"), the traversal it applies to, and the server's own qualifying note verbatim. This
     * method adds no fourth line of its own claiming the traversal has stopped: the CLI has no way to
     * observe that independently -- it does not poll, and this call returns as soon as the request is
     * processed -- so printing anything beyond what the backend actually reported back would be exactly
     * an unmeasured claim. {@code note}'s content is not decided by this method:
     * {@link ai.ravenroot.api.application.CancelResult#cancelled} is what
     * decides what "stopped" is allowed to mean.</p>
     *
     * <p>Two distinct facts back that decision, from two distinct places, kept separate rather than
     * blended into one claim. {@code GraphRunner#cancelTraversal} adds the traversal id to
     * {@code cancelledTraversals} synchronously before returning, and {@code GraphRunner#run} checks
     * that same set before dispatching each hop -- so the refusal is armed, for any hop not yet
     * dispatched, by the time this method's call to the backend returns. That is a fact about the
     * mechanism, not about elapsed time: it says nothing about a hop already in flight, and nothing
     * about how long a real traversal takes to go quiescent. {@code RunawayLoopCancellationTest}
     * supplies the other half -- it does not prove "before this call returns": its own assertion is
     * eventual quiescence of a passthrough self-loop within a 30-second
     * bound, sampled twice a second apart so a merely-slowed loop would still fail it. Together the two
     * are what make the note's claim true, not one of them alone standing in for the other.</p>
     */
    private int cancelExecution(String[] args) throws IOException {
        if (args.length != 2) {
            return invalid("Usage: ravenroot cancel <traversal-id>");
        }
        var result = backend.cancel(args[1]);
        output.println("outcome=" + result.outcome());
        output.println("traversal-id=" + result.traversalId());
        output.println("note=" + result.note());
        return 0;
    }

    private int drainServer() throws IOException {
        var result = backend.drain();
        output.println("outcome=" + result.outcome());
        return 0;
    }

    /**
     * The process-local deployment lifecycle: {@code list} takes no id, {@code register} takes
     * a fresh id and a graph document, and the five remaining verbs are id-scoped reads and commands.
     * Dispatched on the second token the same way {@code credentials} is dispatched below, and for the
     * same reason -- one CLI verb, several related actions, rather than seven top-level commands.
     */
    private int deployments(String[] args) throws IOException {
        if (args.length == 2 && "list".equals(args[1])) {
            return listDeployments();
        }
        if (args.length == 4 && "register".equals(args[1])) {
            return printDeployment(backend.registerDeployment(args[2], Files.readAllBytes(Path.of(args[3]))));
        }
        if (args.length == 3 && "inspect".equals(args[1])) {
            return printDeployment(backend.deployment(args[2]));
        }
        if (args.length == 3 && "start".equals(args[1])) {
            return printDeployment(backend.startDeployment(args[2]));
        }
        if (args.length == 3 && "stop".equals(args[1])) {
            return printDeployment(backend.stopDeployment(args[2]));
        }
        if (args.length == 3 && "restart".equals(args[1])) {
            return printDeployment(backend.restartDeployment(args[2]));
        }
        if (args.length == 3 && "undeploy".equals(args[1])) {
            return printDeployment(backend.undeployDeployment(args[2]));
        }
        return invalid("Usage: ravenroot deployments <list"
                + "|register <deployment-id> <graph.graphml>"
                + "|inspect <deployment-id>|start <deployment-id>|stop <deployment-id>"
                + "|restart <deployment-id>|undeploy <deployment-id>>");
    }

    private int listDeployments() throws IOException {
        for (var deployment : backend.deployments()) {
            printDeploymentLine(deployment);
        }
        return 0;
    }

    private int printDeployment(CliBackend.DeploymentView deployment) {
        printDeploymentLine(deployment);
        return 0;
    }

    /**
     * One line per deployment, tab-separated like {@code live}'s own lines. {@code scope} is printed
     * unconditionally -- never omitted, never abbreviated -- because every reader must see that this
     * deployment's guarantees end at this process. {@code diagnostic}
     * is printed only when the backend actually returned one, matching every other conditional field
     * on this CLI (e.g. {@code result}'s {@code defaulted-nodes}).
     */
    private void printDeploymentLine(CliBackend.DeploymentView deployment) {
        output.println("deployment-id=" + deployment.deploymentId()
                + "\tstate=" + deployment.state()
                + "\tsource-count=" + deployment.sourceCount()
                + "\tscope=" + deployment.scope()
                + (deployment.diagnostic() == null ? ""
                        : "\tdiagnostic=" + sanitizeForConsole(deployment.diagnostic())));
    }

    /**
     * The credential-management command surface: a listing and one mutating act, dispatched on the
     * second token. There is deliberately no {@code delete} here: {@code /v1/credentials} has none
     * (see that route's own note in {@code RouteTable} on why a delete that forgot a graph naming the
     * reference would be worse than no delete at all), so an attempt lands in the usage error below.
     */
    private int credentials(String[] args) throws IOException {
        if (args.length == 2 && "list".equals(args[1])) {
            return listCredentials();
        }
        if (args.length >= 2 && "add".equals(args[1])) {
            return addCredential(java.util.Arrays.copyOfRange(args, 2, args.length));
        }
        return invalid("Usage: ravenroot credentials <list|add --label <text> --scheme "
                + "<api-key|basic|oauth-token> [--username <text>] --value-file <path>>");
    }

    /** Never a value: {@link CliBackend.CredentialView} has no field that could carry one. */
    private int listCredentials() throws IOException {
        for (var credential : backend.credentials()) {
            output.println("reference=" + credential.reference()
                    + "\tlabel=" + sanitizeForConsole(credential.label())
                    + "\tscheme=" + credential.scheme()
                    + "\tusername=" + sanitizeForConsole(credential.username())
                    + "\tcreated-at=" + credential.createdAt());
        }
        return 0;
    }

    /**
     * <b>The value never appears on this process's own command line.</b> {@link CredentialAddArgs#parse}
     * reads it from {@code --value-file <path>} (or stdin, for {@code --value-file -}) and rejects
     * {@code --value} by name -- see that class's own Javadoc, which states the rule this method relies
     * on rather than re-enforcing it here. {@code System.in} is passed rather than read directly by
     * {@link CredentialAddArgs} so production wiring is the only caller that ever touches the real
     * process stdin; a test supplies its own stream instead.
     *
     * <p>A malformed argument list throws {@link IllegalArgumentException} out of {@code parse} and is
     * deliberately not caught here: it reaches {@link #run}'s own catch-all, which reports it exactly
     * like every other refused command on this CLI, and {@link CliBackend#addCredential} is never
     * called for a request that was already wrong.</p>
     */
    private int addCredential(String[] addArgs) throws IOException {
        var parsed = CredentialAddArgs.parse(addArgs, System.in);
        var credential = backend.addCredential(parsed.label(), parsed.scheme(), parsed.username(), parsed.value());
        output.println("reference=" + credential.reference());
        output.println("label=" + sanitizeForConsole(credential.label()));
        output.println("scheme=" + credential.scheme());
        output.println("username=" + sanitizeForConsole(credential.username()));
        output.println("created-at=" + credential.createdAt());
        return 0;
    }

    private int invalid(String message) {
        errors.println(message);
        help();
        return 2;
    }

    private void help() {
        output.println("Usage: ravenroot [--server <url> --token-file <path>] "
                + "<status|runtime|node-types|inspect <graph.graphml>|run <graph.graphml> [payload]"
                + "|result <execution-id>|live|cancel <traversal-id>|drain|credentials|deployments>");
        // Listed separately, like 'credentials' is: seven related actions under one verb rather
        // than seven top-level commands. 'run' above starts a transient traversal that ends when it
        // completes or is cancelled; a deployment is the opposite shape -- a long-lived, addressable,
        // startable/stoppable unit, and one with no effective SOURCE is still registrable and
        // controllable as one (sourceCount can legitimately read 0). Every line 'deployments' prints
        // carries scope=LOCAL_PROCESS: no durability, lease, fencing, failover or cross-host claim --
        // see docs/architecture/local-deployment-lifecycle.md for the full model. The durable,
        // cluster-wide lifecycle is a separate decision.
        output.println("       ravenroot deployments <list"
                + "|register <deployment-id> <graph.graphml>"
                + "|inspect <deployment-id>|start <deployment-id>|stop <deployment-id>"
                + "|restart <deployment-id>|undeploy <deployment-id>>");
        output.println("       'deployments register' reserves the id and validates the graph but "
                + "starts nothing (state=REGISTERED); 'start' is the separate call that serves it and "
                + "answers only once the deployment has reached READY, or the truthful FAILED state if "
                + "startup rolled back. 'stop' leaves the id registered and re-startable; 'undeploy' "
                + "stops it and then removes the registration -- the two are deliberately distinct. "
                + "An unknown id, a sibling tenant's id, and an id already undeployed all report the "
                + "identical not-found failure, on both transports.");
        // Listed separately because it is answered locally whether or not --server is
        // given -- the document is on this machine and so is the profile that reads it.
        output.println("       ravenroot validate <graph.graphml>");
        // Listed separately for the opposite reason to 'validate': this REQUIRES
        // --server, because stored credentials live in a running server's own store, not in anything
        // the embedded (no --server) CLI composes -- BehaviorEnvironment.safeDefaults() has no
        // credential store at all. The embedded path refuses rather than silently answering nothing;
        // see EmbeddedBackend's own comment on that decision.
        output.println("       ravenroot credentials <list|add --label <text> --scheme "
                + "<api-key|basic|oauth-token> [--username <text>] --value-file <path>>   "
                + "(requires --server)");
        // Named here, not only in CredentialAddArgs's own Javadoc, because this is where an operator
        // who reaches for --value first actually looks.
        output.println("       'credentials add' has no --value flag: pass --value-file <path> "
                + "(or --value-file - for stdin) instead. A flag value lands in shell history and in "
                + "every other process's view of the command line -- the identical reason "
                + "--token does not exist either, only RAVENROOT_TOKEN / --token-file. 'credentials "
                + "list' prints reference, label, scheme, username, created-at for the caller's own "
                + "credentials -- never a value, in any form. There is no 'credentials delete': see "
                + "the route's own contract for why.");
        // Backup/restore are not RavenrootApplication use cases (see
        // RavenrootCliMain) and are dispatched before this class ever runs, but they belong in the
        // help an operator actually reads -- "a backup procedure nobody can find is a backup
        // procedure nobody runs."
        output.println("       ravenroot <backup|verify|restore> <directory>");
        // Listed here for the same reason backup/restore are: it is dispatched before this
        // class runs, and an operator surface nobody can find is an operator surface nobody uses.
        output.println("       ravenroot embed-registration <show|provision|revoke> "
                + "--store-dir <dir> ...   (local store, no --server)");
        output.println("       'embed-registration' is the only way to provision or revoke an embed "
                + "registration: there is no HTTP route, by design. provision and revoke require "
                + "--audit-dir, because an unrecorded privileged act is refused rather than "
                + "performed, and both take --expected-revision as a compare-and-set with no "
                + "override. Run 'embed-registration show' first to read the current revision.");
        // No --token flag exists anywhere (Rule 29) -- RAVENROOT_TOKEN or --token-file only.
        output.println("       --server requires a token: set RAVENROOT_TOKEN or pass --token-file <path>");
        // Named here, not only in RemoteBackend#run's own Javadoc, because this is where an
        // operator who hits it actually looks. 'run's payload is refused above 32 KiB on EITHER
        // transport now (PAYLOAD_TEXT_TOO_LONG), but --server refuses it much earlier than that in
        // practice: the payload travels as a URL query parameter, and the pre-authentication
        // request-shape guard every route is bound by caps the encoded query at 4 KiB, well before the
        // 32 KiB payload budget is ever consulted. "4 KiB" is the encoded query, not the operator's
        // text, and percent-encoding is not uniform. Direct measurements show that plain ASCII passes
        // at 4 000 characters and is refused at 4 100;
        // structured content has no single boundary -- compact JSON is refused at 1 570, sparser
        // JSON (longer keys and values, less punctuation per character) not until 2 600, because
        // it is the punctuation that encodes to three characters. The worst case is what an
        // operator can rely on. Accented text -- Italian included -- hits
        // the wall around 680 characters, well under a tenth of the 4 KiB the guard names. Past
        // roughly 350 000-400 000 characters the failure is not even that clean rejection -- the HTTP
        // request itself breaks before a structured rejection. None of this applies without --server.
        output.println("       'run's payload is capped at 32 KiB of text on both transports, but "
                + "--server refuses it far earlier in practice: it travels as a URL query parameter, "
                + "and the request-shape guard every route is bound by caps the ENCODED query at "
                + "4 KiB before authentication -- not the operator's text. Percent-encoding is not "
                + "uniform: plain ASCII text is refused around 4 100 characters, structured (JSON) "
                + "content from about 1 500 -- compact JSON, dense with punctuation, is the worst "
                + "case; sparser JSON survives to about 2 500 -- and accented text -- Italian "
                + "included -- around 680. Past "
                + "roughly 350-400 thousand characters the request "
                + "may fail at the HTTP request line before a structured rejection.");
        // Named here because an operator who has only ever seen STANDARD has no reason to know
        // the line exists, and therefore no reason to look at it on the one run where it says the
        // other thing. 'result' gets the same treatment two lines down for its own new node lists.
        output.println("       'run' prints execution-policy=STANDARD|TEST_PASSTHROUGH, and 'result' "
                + "prints bypassed-nodes / handled-failure-nodes when they apply. Under "
                + "TEST_PASSTHROUGH no node behavior is constructed: the graph is traversed but "
                + "nothing it declares is performed, a decision node takes no outcome edge so the "
                + "traversal stops there, and the run still reports COMPLETED with degraded=false. "
                + "The nodes reached that way are the ones bypassed-nodes lists.");
        // Deliberately NOT a repeat of the passthrough pacing caveat, which belongs to the web
        // editor and the HTTP route: 'run' here is STANDARD on both transports and there is no flag
        // that selects otherwise (see RemoteBackend.run for why there is no --mode), so a reader of
        // this help cannot reach the mode that skips pacing and telling them about it would only
        // suggest they can. What they CAN reach is a cycle that does not stop, and the id they need
        // to stop it, which this line is about instead.
        // This used to say "This CLI has no listing of running executions" and send the reader
        // to GET /v1/executions/live directly -- true when written, false now that 'live' is that same
        // route reached from this binary instead of from curl.
        output.println("       Nothing bounds a repetition: a node with an edge back to itself repeats "
                + "for as long as that edge's outcome keeps being selected, and a graph that never "
                + "takes its exit edge never stops. 'cancel <traversal-id>' stops one; the id is the "
                + "traversal-id 'run' printed, or, if that was not kept, whatever 'live' lists right "
                + "now -- both this listing and cancel read the same runtime bookkeeping, so a stalled "
                + "execution stays listed for exactly as long as it stays cancellable.");
        // 'result' prints visited-nodes always, defaulted-nodes and bypassed-nodes when they are
        // non-empty, and handled-failure-nodes when handled-failure is true -- gated on the boolean the
        // outcome carries, not on the list's emptiness, which is the same condition stated the way the
        // code states it. All four HTTP arrays have a printed counterpart, and this sentence covers
        // the same set it names below. All are printed as sets -- a
        // node reached more than once in one traversal still prints once, not once per visit, same
        // as the HTTP body's visitedNodes/defaultedNodes/bypassedNodes/handledFailureNodes arrays
        // (see GraphExecutionResult#visitedNodes()). This CLI has no command that reports visit
        // counts. The retention statement must remain conditional: ExecutionMonitor
        // .HISTORY_LIMIT (2048 events) and ExecutionResultRegistry.DEFAULT_MAX_RESULTS (256 results)
        // are both process-wide counts, so the break-even is about eight events per execution --
        // below it (a one- or two-node graph) the event window outlives the result; above it, events
        // evict first. The schema therefore says "can be shorter", not "is shorter". The source
        // discriminator makes "where this deployment has a durable event journal" checkable
        // -- /v1/events/recent's own response names its source, and that is the only place on this
        // API where it is observable at all. GET /v1/events is the only route that carries a node id
        // under both its sources, and NODE_DEFAULTED -- the event defaultedNodes' count depends on --
        // was previously published only to the in-memory one, never journalled, so a
        // deployment with a durable event journal had no way to answer defaultedNodes' count over
        // this API at all. NODE_DEFAULTED is now journalled, as detailed below;
        // /v1/events/recent's durable response carries the type but never a node id, so on that
        // source it cannot filter by node regardless of what is journalled. The claim that "even on
        // /v1/events, nodeId is a best-effort join" does not hold for the whole
        // route; it only holds for that route's durable source. On the ring source nodeId is an
        // ordinary ExecutionEvent field (ExecutionEvent.java:77), passed straight through by
        // ExecutionMonitor.publish from the id the runner already holds -- no join, never absent for
        // the three types this line sends a reader to filter on. The word "regardless" scopes only
        // to the durable-response sentence's subject; it does not mean both sources, because the ring
        // response carries both fields. Placed here, not on the result line above, on
        // purpose: this is a fact about the API, not about this run's output, and this line sits
        // among usage-form lines where a sentence of prose would otherwise read as one more
        // invocation to type. NODE_DEFAULTED is now journalled as its own type
        // (GraphRunner.ExecutionState#nodeCompleted carries the decision and the bypass precedent
        // that settled it), so all four node lists have a per-visit event under either source. The
        // best-effort-join caveat and the /v1/events/recent node-id absence remain because neither
        // was ever about this type. A journal written by an older build has no NODE_DEFAULTED rows
        // and nothing says so
        // -- envelopeVersion correctly stays 1, because the envelope's shape did not change -- so a
        // count across that upgrade undercounts silently, the same failure mode the nodeId join
        // already had. The schema's twin sentence, which must keep saying the same thing, is
        // RouteTable's "/v1/executions/{id}" entry.
        output.println("       'result' node lists are sets: a repeat visit still prints once. This CLI "
                + "reports no visit counts. GET /v1/events over HTTP, read directly (no filter "
                + "parameter exists) and matched client-side to a traversal and node id, carries one "
                + "NODE_STARTED/NODE_BYPASSED/NODE_DEFAULTED/NODE_FAILED event per visit -- except "
                + "on a node with an orchestration retry policy, where a visit is one NODE_STARTED "
                + "per attempt and every attempt but the last settles as NODE_RETRY_SCHEDULED, so "
                + "counting NODE_STARTED overcounts visits there and this response's node lists, "
                + "being sets, do not. Under "
                + "either of that stream's sources -- with two silent-undercount caveats where a "
                + "journal serves it. NODE_DEFAULTED rows exist only in a journal written by a build "
                + "that emits them, and envelopeVersion does not change to say so (the envelope's "
                + "shape did not), so counting defaults across that upgrade undercounts with no "
                + "signal; and nodeId is a best-effort join that can undercount silently too. "
                + "From the in-memory ring the node id is carried on the event itself. "
                + "Which applies is observable: /v1/events/recent's own response names its "
                + "source, 'source=DURABLE' or 'source=RING' -- the only place on this API that "
                + "surfaces it. The durable response carries the type but never a node id, so on "
                + "that source it cannot filter by node regardless of what is journalled. Without a "
                + "journal, /v1/events keeps a 2048-entry window reset on every restart, which can be "
                + "shorter-lived than a 'result' answer, or longer, depending on how many events one "
                + "execution produces.");
    }
}
