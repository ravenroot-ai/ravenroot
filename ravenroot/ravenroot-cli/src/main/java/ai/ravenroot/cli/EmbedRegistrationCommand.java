package ai.ravenroot.cli;

import ai.ravenroot.api.embed.AuthorizedEmbedRegistrationAdministration;
import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRevokeCommand;
import ai.ravenroot.api.embed.EmbedRevokeOutcome;
import ai.ravenroot.api.embed.EmbedTheme;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.audit.AuditTrailAuthorizationSink;
import ai.ravenroot.core.audit.AuditTrailEmbedRegistrationSink;
import ai.ravenroot.core.audit.FileAuditTrail;
import ai.ravenroot.core.embed.EmbedSnapshotProjector;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionRecord;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.graph.GraphVersionState;
import ai.ravenroot.persistence.sqlite.SqliteEmbedRegistrationStore;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ravenroot embed-registration <show|provision|revoke>} — the operator surface for embed
 * registration administration.
 *
 * <h2>Why a CLI and not an HTTP route</h2>
 * <p>An administrative HTTP route is excluded from the browser boundary. Every embed route
 * authenticates a workload; an administration endpoint sharing that listener
 * would be one authentication mistake away from letting an embedding application repoint its own
 * embed at a different graph. The repository's own precedent for an operator act on local durable
 * state is the CLI — {@code backup}, {@code restore}, {@code verify} — and this follows it exactly,
 * including being intercepted in {@link RavenrootCliMain} before an execution engine exists, because
 * it is not a {@code RavenrootApplication} use case.</p>
 *
 * <h2>It writes the same file the server reads, while the server is running</h2>
 * <p>The registration store is SQLite in WAL mode with {@code busy_timeout}, and every mutation takes
 * the write lock with {@code BEGIN IMMEDIATE}, so this process and the server contend safely: a
 * provision or revocation committed here is visible to the server's very next read, which is what
 * makes «revoke now» mean now rather than «after the next restart». The server must be on the same
 * host and pointed at the same {@code --store-dir}; that is the single-host limit the composition
 * already refuses to exceed.</p>
 *
 * <h2>No unaudited privileged act</h2>
 * <p>{@code --audit-dir} is required for {@code provision} and {@code revoke}. There is no default
 * and no «skip audit» flag: {@link AuthorizedEmbedRegistrationAdministration} refuses an operation
 * whose attempt it cannot record, and an operator surface that quietly supplied a throwaway sink
 * would be a way around that refusal rather than a convenience.</p>
 */
final class EmbedRegistrationCommand {

    private static final Set<String> SHOW_FLAGS =
            Set.of("store-dir", "tenant", "registration-id");

    /**
     * Every flag {@code provision} accepts, and therefore also the list of what it refuses.
     *
     * <p>An unknown flag is an error rather than something ignored. {@code --canonical-digest} is the
     * case that matters: silently dropping it would let an operator believe they had pinned a digest
     * while the store pinned the one computed from the document, and the two disagreeing without
     * anyone being told is the class of confusion this command prevents. There is no digest flag and
     * there must not be one — the digest is derived from {@code --graphml}.</p>
     */
    /**
     * The seven policy gates, each an explicit operator attestation with <b>no default</b>.
     *
     * <p>The previous version of this command called {@code EmbedProjectionEligibility.allowed(...)},
     * which turns a policy-revision label into seven {@code true}s. Since no evaluator for these gates
     * exists anywhere in the product, that made every provision assert seven compliance properties
     * that nobody checked and that the operator never stated — and the store, the audit trail and
     * {@code show} then displayed the registration as policy-verified. A graph under takedown or past
     * its retention was provisionable and served.</p>
     *
     * <p>Requiring the flags does not create an evaluator and does not pretend to. What it changes is
     * who is on record: an operator now attests each gate deliberately rather than by omission, which
     * is the same fail-closed rule the rest of this surface follows — an absent value refuses, it does
     * not assume the permissive one.</p>
     */
    private static final List<String> GATE_FLAGS = List.of("gate-deployment", "gate-provenance",
            "gate-classification", "gate-retention", "gate-dsr-suppression", "gate-takedown",
            "gate-eea");

    /**
     * Declared after {@link #GATE_FLAGS} and not before it: a static field initialiser runs in source
     * order, so reading the gate list from above it would read a null and fail the class's own
     * initialisation. The tests caught that; the ordering here is load-bearing, not cosmetic.
     */
    private static final Set<String> PROVISION_FLAGS = provisionFlags();

    /** One list, so the accepted set and the required set cannot drift apart. */
    private static Set<String> provisionFlags() {
        var flags = new java.util.LinkedHashSet<>(List.of("store-dir", "audit-dir", "tenant",
                "registration-id", "expected-revision", "graphml", "graph-id", "graph-version-id",
                "snapshot-state", "issuer", "subject", "parent-origin", "resource-id", "deployment-id",
                "deployment-version", "policy-revision", "theme", "operator"));
        flags.addAll(GATE_FLAGS);
        return Set.copyOf(flags);
    }

    private static final Set<String> REVOKE_FLAGS = Set.of("store-dir", "audit-dir", "tenant",
            "registration-id", "expected-revision", "operator");

    private final PrintStream output;
    private final PrintStream errors;

    EmbedRegistrationCommand(PrintStream output, PrintStream errors) {
        this.output = output;
        this.errors = errors;
    }

    static int run(String[] args, PrintStream output, PrintStream errors) {
        return new EmbedRegistrationCommand(output, errors).dispatch(args);
    }

    int dispatch(String[] args) {
        if (args.length < 2) return usage();
        Map<String, String> options;
        try {
            options = options(args, 2);
        } catch (IllegalArgumentException malformed) {
            errors.println("Error: " + RavenrootCli.sanitizeForConsole(malformed.getMessage()));
            return 2;
        }
        try {
            return switch (args[1]) {
                case "show" -> {
                    requireKnownFlags(options, SHOW_FLAGS);
                    yield show(options);
                }
                case "provision" -> {
                    requireKnownFlags(options, PROVISION_FLAGS);
                    yield provision(options);
                }
                case "revoke" -> {
                    requireKnownFlags(options, REVOKE_FLAGS);
                    yield revoke(options);
                }
                default -> usage();
            };
        } catch (IllegalArgumentException refused) {
            errors.println("Error: " + RavenrootCli.sanitizeForConsole(refused.getMessage()));
            return 2;
        } catch (AuthorizationDeniedException denied) {
            errors.println("Error: refused: " + RavenrootCli.sanitizeForConsole(denied.getMessage()));
            return 2;
        } catch (RuntimeException failure) {
            errors.println("Error: the embed registration store is unavailable");
            return 1;
        }
    }

    // ---------------------------------------------------------------- verbs

    private int show(Map<String, String> options) {
        try (var store = open(options)) {
            Optional<EmbedRegistrationAggregate> current =
                    store.currentForOperator(required(options, "tenant"),
                            required(options, "registration-id"));
            if (current.isEmpty()) {
                output.println("registration=absent");
                // Absent is an answer, not a failure: it is what an operator sees before the first
                // provision, and exit 0 lets a script branch on the printed line instead of on a code
                // it would share with a store that could not be read.
                return 0;
            }
            print(current.get());
            return 0;
        }
    }

    private int provision(Map<String, String> options) {
        Path graphml = Path.of(required(options, "graphml"));
        byte[] document;
        try {
            document = Files.readAllBytes(graphml);
        } catch (java.io.IOException unreadable) {
            errors.println("Error: cannot read " + RavenrootCli.sanitizeForConsole(graphml.toString()));
            return 2;
        }
        var key = new GraphVersionKey(required(options, "graph-id"), required(options, "graph-version-id"));
        GraphVersionSnapshot snapshot;
        try (var manager = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(document))) {
            snapshot = GraphVersionSnapshot.create(key, manager.definition());
        } catch (RuntimeException rejected) {
            RavenrootCli.reportFailure(rejected, errors);
            return 2;
        }
        // The digest is computed from the document, never accepted from the command line. An operator
        // who could pass one could pin a registration to a digest the payload does not have, which is
        // precisely the incoherent pairing the aggregate exists to make impossible.
        var graphGrant = new VerifiedEmbedGraphGrant(required(options, "tenant"),
                required(options, "resource-id"), required(options, "deployment-id"),
                positiveLong(options, "deployment-version"), key.graphId(), key.versionId(),
                snapshot.canonicalHash(), required(options, "policy-revision"));
        var eligibility = eligibility(options);
        var projected = EmbedSnapshotProjector.project(
                new GraphVersionRecord(snapshot, lifecycle(options)), graphGrant, eligibility,
                EmbedProjectionBudget.DEFAULTS);
        if (!(projected instanceof EmbedSnapshotProjector.Result.Projected rendered)) {
            errors.println("Error: the snapshot cannot be projected: "
                    + projected.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT));
            return 2;
        }
        var command = new EmbedProvisionCommand(required(options, "registration-id"),
                nonNegativeLong(options, "expected-revision"), required(options, "issuer"),
                required(options, "subject"), required(options, "tenant"),
                required(options, "parent-origin"), Set.of(EmbedCapability.GRAPH_READ), theme(options),
                graphGrant, rendered.lifecycle(), eligibility, rendered.projection());

        try (var store = open(options); var trail = auditTrail(options)) {
            var administration = administration(store, trail);
            EmbedProvisionOutcome outcome = administration.provision(operator(options), command);
            return switch (outcome) {
                case EmbedProvisionOutcome.Provisioned provisioned -> {
                    print(provisioned.aggregate());
                    yield 0;
                }
                case EmbedProvisionOutcome.Conflict conflict -> {
                    errors.println("Error: revision conflict: expected=" + conflict.expectedRevision()
                            + " current=" + conflict.currentRevision());
                    yield 2;
                }
                case EmbedProvisionOutcome.Rejected rejected -> {
                    errors.println("Error: refused: " + rejected.reason().name());
                    yield 2;
                }
                case EmbedProvisionOutcome.Unavailable ignored -> {
                    errors.println("Error: nothing was written: "
                            + "the embed registration store or its audit trail is unavailable");
                    yield 1;
                }
                // Exit 3, not 1: the previous exit-code table promised "nothing was written" for
                // code 1, and this is the case where something was. An operator who reads 1 after a
                // revoke and believes it did not happen goes looking for another way to stop an
                // embed that is already stopped -- or re-provisions one that is already live.
                case EmbedProvisionOutcome.AppliedUnrecorded applied -> {
                    output.println("registration=" + command.registrationId());
                    output.println("state=ACTIVE");
                    output.println("revision=" + applied.revision());
                    errors.println("Error: the registration WAS written at revision "
                            + applied.revision() + " and its audit record was NOT. Do not re-run "
                            + "this command; verify with 'show' and record the change out of band.");
                    yield 3;
                }
            };
        }
    }

    private int revoke(Map<String, String> options) {
        var command = new EmbedRevokeCommand(required(options, "registration-id"),
                required(options, "tenant"), positiveLong(options, "expected-revision"));
        try (var store = open(options); var trail = auditTrail(options)) {
            var administration = administration(store, trail);
            EmbedRevokeOutcome outcome = administration.revoke(operator(options), command);
            return switch (outcome) {
                case EmbedRevokeOutcome.Revoked revoked -> {
                    output.println("registration=" + command.registrationId());
                    output.println("state=REVOKED");
                    output.println("revision=" + revoked.revision());
                    yield 0;
                }
                case EmbedRevokeOutcome.AlreadyRevoked revoked -> {
                    output.println("registration=" + command.registrationId());
                    output.println("state=REVOKED");
                    output.println("revision=" + revoked.revision());
                    // Exit 0: the operator asked for a state the store is already in. A non-zero code
                    // here would make a re-run of an interrupted runbook look like a new failure.
                    yield 0;
                }
                case EmbedRevokeOutcome.Conflict conflict -> {
                    errors.println("Error: revision conflict: expected=" + conflict.expectedRevision()
                            + " current=" + conflict.currentRevision());
                    yield 2;
                }
                case EmbedRevokeOutcome.NotFound ignored -> {
                    errors.println("Error: no such registration for this tenant");
                    yield 2;
                }
                case EmbedRevokeOutcome.Unavailable ignored -> {
                    errors.println("Error: nothing was written: "
                            + "the embed registration store or its audit trail is unavailable");
                    yield 1;
                }
                case EmbedRevokeOutcome.AppliedUnrecorded applied -> {
                    output.println("registration=" + command.registrationId());
                    output.println("state=REVOKED");
                    output.println("revision=" + applied.revision());
                    errors.println("Error: the revocation WAS committed at revision "
                            + applied.revision() + " and its audit record was NOT. The embed is "
                            + "stopped; verify with 'show' and record the change out of band.");
                    yield 3;
                }
            };
        }
    }

    // ---------------------------------------------------------------- support

    private void print(EmbedRegistrationAggregate aggregate) {
        // Identity and lifecycle only. The captured payload is not printed: it is the graph, and an
        // operator console is not the place it should arrive by default.
        output.println("registration=" + aggregate.registrationId());
        output.println("state=" + aggregate.state());
        output.println("revision=" + aggregate.revision());
        output.println("tenant=" + aggregate.tenantId());
        output.println("issuer=" + RavenrootCli.sanitizeForConsole(aggregate.sessionGrant().workloadIssuer()));
        output.println("subject=" + RavenrootCli.sanitizeForConsole(aggregate.sessionGrant().workloadSubject()));
        output.println("parent-origin="
                + RavenrootCli.sanitizeForConsole(aggregate.sessionGrant().parentOrigin()));
        output.println("graph-id=" + RavenrootCli.sanitizeForConsole(aggregate.graphGrant().graphId()));
        output.println("graph-version-id="
                + RavenrootCli.sanitizeForConsole(aggregate.graphGrant().graphVersionId()));
        output.println("digest=" + RavenrootCli.sanitizeForConsole(aggregate.graphGrant().canonicalDigest()));
        output.println("snapshot-lifecycle=" + aggregate.snapshotLifecycle());
        output.println("policy-revision="
                + RavenrootCli.sanitizeForConsole(aggregate.eligibility().policyRevision()));
        // Printed individually, and labelled as attested, so an operator reading a registration back
        // sees what was claimed rather than a single "eligible" that reads like a verdict.
        var eligibility = aggregate.eligibility();
        output.println("attested-gates=deployment:" + eligibility.deploymentAllowed()
                + " provenance:" + eligibility.provenanceAllowed()
                + " classification:" + eligibility.classificationAllowed()
                + " retention:" + eligibility.retentionAllowed()
                + " dsr-suppression:" + eligibility.dsrSuppressionClear()
                + " takedown:" + eligibility.takedownClear()
                + " eea:" + eligibility.eeaDeployment());
        output.println("attested-by=operator (no policy evaluator exists; these are not checks)");
        output.println("nodes=" + aggregate.projection().nodes().size());
        output.println("edges=" + aggregate.projection().edges().size());
        output.println("provisioned-at=" + aggregate.provisionedAt());
    }

    /**
     * The reference monitor this command acts through, with both of its audit sinks real.
     *
     * <p>The authorization sink used to be {@code event -> { }}: the central policy's own decision on
     * an operator command was computed and then discarded, so a denial on this path reached neither
     * the audit trail nor anywhere else. It writes to the same {@code FileAuditTrail} as the
     * registration records, which is what lets a reader reconstruct one operator action from one
     * file.</p>
     */
    private static AuthorizedEmbedRegistrationAdministration administration(
            SqliteEmbedRegistrationStore store, FileAuditTrail trail) {
        return new AuthorizedEmbedRegistrationAdministration(
                new DefaultAuthorizationService(new AuditTrailAuthorizationSink(trail)), store,
                new AuditTrailEmbedRegistrationSink(trail), EmbedProjectionBudget.DEFAULTS,
                Clock.systemUTC());
    }

    private static SqliteEmbedRegistrationStore open(Map<String, String> options) {
        return SqliteEmbedRegistrationStore.openUnder(Path.of(required(options, "store-dir")),
                Clock.systemUTC(), EmbedProjectionBudget.DEFAULTS);
    }

    private static FileAuditTrail auditTrail(Map<String, String> options) {
        return new FileAuditTrail(Path.of(required(options, "audit-dir")), Clock.systemUTC(),
                Duration.ofHours(24));
    }

    /**
     * The operator identity this invocation acts under.
     *
     * <p>{@link PrincipalType#USER}, which is what makes it acceptable to
     * {@code EMBED_REGISTRATION_ADMIN} at all — a workload principal is refused by the central policy
     * and again by the administration monitor. {@code --operator} names the subject that lands in the
     * audit record; running the command is the authorization, exactly as it is for {@code backup} and
     * {@code restore}, because whoever can execute this binary against this file could edit the file.</p>
     */
    private static RequestContext operator(Map<String, String> options) {
        return new RequestContext("cli-" + UUID.randomUUID(),
                options.getOrDefault("operator", "local-cli"), PrincipalType.USER,
                "urn:ravenroot:cli", required(options, "tenant"), Set.of(Role.OPERATOR),
                Set.of(AuthorizationAction.EMBED_REGISTRATION_ADMIN.requiredScope()));
    }

    /**
     * Builds the attestation from seven required flags.
     *
     * <p>{@code allowed(...)} is deliberately not used here; see {@link #GATE_FLAGS}. Each flag is
     * strictly {@code true} or {@code false} — no {@code yes}, no {@code 1}, no absence — because a
     * lenient parser on a compliance attestation is a way to attest something by accident.</p>
     */
    private static EmbedProjectionEligibility eligibility(Map<String, String> options) {
        // Read through GATE_FLAGS so the declared order, the accepted-flag set and the constructor
        // argument order are one thing rather than three that agree today.
        var attested = GATE_FLAGS.stream().map(flag -> gate(options, flag)).toList();
        return new EmbedProjectionEligibility(required(options, "policy-revision"),
                attested.get(0), attested.get(1), attested.get(2), attested.get(3),
                attested.get(4), attested.get(5), attested.get(6));
    }

    private static boolean gate(Map<String, String> options, String name) {
        return switch (required(options, name)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("--" + name + " must be true or false");
        };
    }

    private static Optional<EmbedTheme> theme(Map<String, String> options) {
        String value = options.get("theme");
        return value == null ? Optional.empty() : Optional.of(EmbedTheme.fromWire(value));
    }

    /**
     * Only {@code published} and {@code active} are accepted, and the flag is required.
     *
     * <p>No default: defaulting it would let an operator provision from a version whose lifecycle
     * nobody stated, and «assume it is published» is the assumption this whole gate exists to stop.</p>
     */
    private static GraphVersionState lifecycle(Map<String, String> options) {
        return switch (required(options, "snapshot-state")) {
            case "published" -> GraphVersionState.PUBLISHED;
            case "active" -> GraphVersionState.ACTIVE;
            default -> throw new IllegalArgumentException("--snapshot-state must be published or active");
        };
    }

    private static Map<String, String> options(String[] args, int from) {
        var options = new LinkedHashMap<String, String>();
        for (int index = from; index < args.length; index++) {
            String flag = args[index];
            if (!flag.startsWith("--") || flag.length() == 2) {
                throw new IllegalArgumentException("expected a --flag, found " + flag);
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            if (options.put(flag.substring(2), args[++index]) != null) {
                throw new IllegalArgumentException("repeated flag " + flag);
            }
        }
        return options;
    }

    private static void requireKnownFlags(Map<String, String> options, Set<String> known) {
        for (String flag : options.keySet()) {
            if (!known.contains(flag)) throw new IllegalArgumentException("unknown flag --" + flag);
        }
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value.trim();
    }

    private static long positiveLong(Map<String, String> options, String name) {
        long value = nonNegativeLong(options, name);
        if (value < 1) throw new IllegalArgumentException("--" + name + " must be positive");
        return value;
    }

    private static long nonNegativeLong(Map<String, String> options, String name) {
        try {
            long value = Long.parseLong(required(options, name));
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("--" + name + " must be a non-negative integer");
        }
    }

    private int usage() {
        errors.println("Usage: ravenroot embed-registration show --store-dir <dir> --tenant <id> "
                + "--registration-id <id>");
        errors.println("       ravenroot embed-registration provision --store-dir <dir> "
                + "--audit-dir <dir> --tenant <id> --registration-id <id> --expected-revision <n> "
                + "--graphml <file> --graph-id <id> --graph-version-id <id> --snapshot-state "
                + "<published|active> --issuer <id> --subject <id> --parent-origin <https-origin> "
                + "--resource-id <id> --deployment-id <id> --deployment-version <n> "
                + "--policy-revision <id> --gate-deployment <true|false> "
                + "--gate-provenance <true|false> --gate-classification <true|false> "
                + "--gate-retention <true|false> --gate-dsr-suppression <true|false> "
                + "--gate-takedown <true|false> --gate-eea <true|false> "
                + "[--theme dark|light] [--operator <subject>]");
        errors.println("       ravenroot embed-registration revoke --store-dir <dir> "
                + "--audit-dir <dir> --tenant <id> --registration-id <id> --expected-revision <n> "
                + "[--operator <subject>]");
        errors.println("       --expected-revision is a compare-and-set: 0 for a registration that "
                + "must not exist yet, otherwise the revision 'show' printed. There is no 'force'.");
        errors.println("       the canonical digest is computed from --graphml and can never be "
                + "supplied, so a registration cannot be pinned to a snapshot it does not carry.");
        errors.println("       the seven --gate-* flags are YOUR attestation, not a check this "
                + "product performs: no evaluator for deployment, provenance, classification, "
                + "retention, DSR suppression, takedown or EEA residency exists yet. They have no "
                + "default because attesting seven compliance properties by omission is not "
                + "attesting them. --policy-revision is a label stored verbatim and compared only "
                + "with itself.");
        return 2;
    }
}
