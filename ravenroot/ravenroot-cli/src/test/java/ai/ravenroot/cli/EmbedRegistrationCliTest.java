package ai.ravenroot.cli;

import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import ai.ravenroot.api.embed.EmbedRegistrationState;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.persistence.sqlite.SqliteEmbedRegistrationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The embed-registration operator runbook, executed rather than described.
 *
 * <p>The sequence here is the one the deployment documentation tells an operator to run: {@code show}
 * to learn the current revision, {@code provision} with that revision as the compare-and-set, then
 * {@code revoke} with the revision {@code provision} printed. A runbook whose steps have never been
 * run in order is a runbook whose second step is a guess.</p>
 */
class EmbedRegistrationCliTest {

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="embed" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="work"><data key="kind">BEHAVIOR</data><data key="behavior">embed-behavior</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="work"><data key="outcome">continue</data></edge>
                <edge id="e2" source="work" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @TempDir
    Path root;

    @Test
    void theRunbookProvisionsShowsAndRevokesInThatOrder() throws Exception {
        Path graph = writeGraph();

        var absent = run("embed-registration", "show", "--store-dir", storeDir(), "--tenant", "tenant-a",
                "--registration-id", "reg-1");
        assertEquals(0, absent.status());
        assertTrue(absent.out().contains("registration=absent"), absent.out());

        var provisioned = run(provisionArgs(graph, 0));
        assertEquals(0, provisioned.status(), provisioned.err());
        assertTrue(provisioned.out().contains("state=ACTIVE"), provisioned.out());
        assertTrue(provisioned.out().contains("revision=1"), provisioned.out());
        assertTrue(provisioned.out().contains("nodes=4"), provisioned.out());
        assertTrue(provisioned.out().contains("snapshot-lifecycle=PUBLISHED"), provisioned.out());

        var shown = run("embed-registration", "show", "--store-dir", storeDir(), "--tenant", "tenant-a",
                "--registration-id", "reg-1");
        assertEquals(0, shown.status(), shown.err());
        assertTrue(shown.out().contains("revision=1"), shown.out());
        // The console prints identity and counts, never the payload: the graph is not a thing an
        // operator surface should emit by default.
        assertFalse(shown.out().contains("\"nodes\":["), shown.out());

        var conflicted = run(provisionArgs(graph, 0));
        assertEquals(2, conflicted.status());
        assertTrue(conflicted.err().contains("revision conflict: expected=0 current=1"),
                conflicted.err());

        var revoked = run("embed-registration", "revoke", "--store-dir", storeDir(),
                "--audit-dir", auditDir(), "--tenant", "tenant-a", "--registration-id", "reg-1",
                "--expected-revision", "1");
        assertEquals(0, revoked.status(), revoked.err());
        assertTrue(revoked.out().contains("state=REVOKED"), revoked.out());
        assertTrue(revoked.out().contains("revision=2"), revoked.out());

        // Re-running the last step of an interrupted runbook is not a new failure.
        var again = run("embed-registration", "revoke", "--store-dir", storeDir(),
                "--audit-dir", auditDir(), "--tenant", "tenant-a", "--registration-id", "reg-1",
                "--expected-revision", "2");
        assertEquals(0, again.status(), again.err());
        assertTrue(again.out().contains("state=REVOKED"), again.out());

        try (var store = SqliteEmbedRegistrationStore.openUnder(Path.of(storeDir()), Clock.systemUTC(),
                EmbedProjectionBudget.DEFAULTS)) {
            assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                    store.resolveCurrent(workload(), "reg-1"),
                    "the revocation the CLI reported is the one the server's read port sees");
            assertEquals(EmbedRegistrationState.REVOKED,
                    store.currentForOperator("tenant-a", "reg-1").orElseThrow().state());
        }
    }

    /** A revocation must reach the audit trail, and must reach it without the graph. */
    @Test
    void everyMutationIsAuditedAndTheRecordsCarryNoGraphData() throws Exception {
        Path graph = writeGraph();
        assertEquals(0, run(provisionArgs(graph, 0)).status());
        assertEquals(0, run("embed-registration", "revoke", "--store-dir", storeDir(),
                "--audit-dir", auditDir(), "--tenant", "tenant-a", "--registration-id", "reg-1",
                "--expected-revision", "1").status());

        String audit = auditContents();
        assertTrue(audit.contains("embed-registration:provision"), audit);
        assertTrue(audit.contains("embed-registration:revoke"), audit);
        assertTrue(audit.contains("reg-1"), audit);
        // The central policy's own decision reaches the same trail. It used to be handed a no-op
        // sink here, so the authorization decision on the one privileged path this CLI has was
        // computed and thrown away.
        assertTrue(audit.contains("authorize:EMBED_REGISTRATION_ADMIN"),
                "the authorization decision must be audited, not discarded: " + audit);
        for (String forbidden : List.of("embed-behavior", "graphml", "sha256", "parent.example",
                "layoutX", "\"nodes\"")) {
            assertFalse(audit.contains(forbidden), forbidden + " reached the audit trail");
        }
    }

    /**
     * The digest is not an operator input. There is no flag for it, so a registration cannot be
     * pinned to a snapshot the captured payload does not have.
     */
    @Test
    void theCanonicalDigestCannotBeSuppliedOnTheCommandLine() throws Exception {
        Path graph = writeGraph();
        var withDigest = run(Stream.concat(Stream.of(provisionArgs(graph, 0)),
                Stream.of("--canonical-digest", "sha256:attacker")).toArray(String[]::new));
        assertEquals(2, withDigest.status());
        assertTrue(withDigest.err().contains("Error:"), withDigest.err());
    }

    @Test
    void anUnpublishedSnapshotStateIsRefusedRatherThanDefaulted() throws Exception {
        Path graph = writeGraph();
        var retired = run("embed-registration", "provision", "--store-dir", storeDir(),
                "--audit-dir", auditDir(), "--tenant", "tenant-a", "--registration-id", "reg-1",
                "--expected-revision", "0", "--graphml", graph.toString(), "--graph-id", "graph-a",
                "--graph-version-id", "v1", "--snapshot-state", "retired", "--issuer",
                "https://issuer.example", "--subject", "workload-1", "--parent-origin",
                "https://parent.example", "--resource-id", "resource-a", "--deployment-id",
                "deployment-a", "--deployment-version", "1", "--policy-revision", "policy-1",
                "--gate-deployment", "true", "--gate-provenance", "true", "--gate-classification", "true", "--gate-retention", "true", "--gate-dsr-suppression", "true", "--gate-takedown", "true", "--gate-eea", "true");
        assertEquals(2, retired.status());
        assertTrue(retired.err().contains("--snapshot-state must be published or active"),
                retired.err());

        var missing = run("embed-registration", "provision", "--store-dir", storeDir(),
                "--audit-dir", auditDir(), "--tenant", "tenant-a", "--registration-id", "reg-1",
                "--expected-revision", "0", "--graphml", graph.toString(), "--graph-id", "graph-a",
                "--graph-version-id", "v1", "--issuer", "https://issuer.example", "--subject",
                "workload-1", "--parent-origin", "https://parent.example", "--resource-id",
                "resource-a", "--deployment-id", "deployment-a", "--deployment-version", "1",
                "--policy-revision", "policy-1",
                "--gate-deployment", "true", "--gate-provenance", "true", "--gate-classification", "true", "--gate-retention", "true", "--gate-dsr-suppression", "true", "--gate-takedown", "true", "--gate-eea", "true");
        assertEquals(2, missing.status());
        assertTrue(missing.err().contains("--snapshot-state is required"), missing.err());
    }

    @Test
    void aForeignTenantCannotSeeOrRevokeAnotherTenantsRegistration() throws Exception {
        Path graph = writeGraph();
        assertEquals(0, run(provisionArgs(graph, 0)).status());

        var foreignShow = run("embed-registration", "show", "--store-dir", storeDir(),
                "--tenant", "tenant-b", "--registration-id", "reg-1");
        assertEquals(0, foreignShow.status());
        assertTrue(foreignShow.out().contains("registration=absent"), foreignShow.out());

        var foreignRevoke = run("embed-registration", "revoke", "--store-dir", storeDir(),
                "--audit-dir", auditDir(), "--tenant", "tenant-b", "--registration-id", "reg-1",
                "--expected-revision", "1");
        assertEquals(2, foreignRevoke.status());
        assertTrue(foreignRevoke.err().contains("no such registration for this tenant"),
                foreignRevoke.err());

        try (var store = SqliteEmbedRegistrationStore.openUnder(Path.of(storeDir()), Clock.systemUTC(),
                EmbedProjectionBudget.DEFAULTS)) {
            assertEquals(EmbedRegistrationState.ACTIVE,
                    store.currentForOperator("tenant-a", "reg-1").orElseThrow().state());
        }
    }

    /**
     * Attestation control: an operator attests each policy gate.
     *
     * <p>No evaluator for these gates exists anywhere in the product, so the values are an
     * attestation. What this test pins is that the attestation is <em>made</em> rather than assumed:
     * omitting any of the seven refuses the provision, and nothing is written. Before this, the CLI
     * called {@code EmbedProjectionEligibility.allowed(policyRevision)}, which turns a label into
     * seven trues — so every provision asserted seven compliance properties the operator never
     * stated and nobody checked, and {@code show} then displayed the result as policy-verified.</p>
     */
    @Test
    void everyPolicyGateMustBeAttestedExplicitlyAndOmittingOneRefuses() throws Exception {
        Path graph = writeGraph();
        for (String gate : List.of("gate-deployment", "gate-provenance", "gate-classification",
                "gate-retention", "gate-dsr-suppression", "gate-takedown", "gate-eea")) {
            var missing = run(without(provisionArgs(graph, 0), "--" + gate));
            assertEquals(2, missing.status(), gate);
            assertTrue(missing.err().contains("--" + gate + " is required"), missing.err());
            try (var store = SqliteEmbedRegistrationStore.openUnder(Path.of(storeDir()),
                    Clock.systemUTC(), EmbedProjectionBudget.DEFAULTS)) {
                assertTrue(store.currentForOperator("tenant-a", "reg-1").isEmpty(),
                        "an incomplete attestation must write nothing");
            }
        }
        // A gate is strictly true or false: no yes, no 1, no empty.
        for (String malformed : List.of("yes", "1", "TRUE", "")) {
            var invalid = run(replace(provisionArgs(graph, 0), "--gate-takedown", malformed));
            assertEquals(2, invalid.status(), malformed);
        }
    }

    /** A denied gate is stored as denied, is visible in `show`, and blocks the provision. */
    @Test
    void aDeniedGateRefusesTheProvisionAndAnAttestedOneIsReadBackVerbatim() throws Exception {
        Path graph = writeGraph();
        var denied = run(replace(provisionArgs(graph, 0), "--gate-takedown", "false"));
        assertEquals(2, denied.status(), denied.err());
        assertTrue(denied.err().contains("ELIGIBILITY_DENIED"), denied.err());

        assertEquals(0, run(provisionArgs(graph, 0)).status());
        var shown = run("embed-registration", "show", "--store-dir", storeDir(), "--tenant", "tenant-a",
                "--registration-id", "reg-1");
        assertTrue(shown.out().contains("attested-gates=deployment:true"), shown.out());
        assertTrue(shown.out().contains("takedown:true"), shown.out());
        assertTrue(shown.out().contains("attested-by=operator (no policy evaluator exists"),
                "the read-back must not let an attestation be mistaken for a verdict: " + shown.out());
    }

    @Test
    void usageIsPrintedForAnUnknownVerbOrAMalformedFlag() {
        assertEquals(2, run("embed-registration").status());
        assertEquals(2, run("embed-registration", "delete", "--store-dir", storeDir()).status());
        var malformed = run("embed-registration", "show", "--store-dir");
        assertEquals(2, malformed.status());
        assertTrue(malformed.err().contains("missing value for --store-dir"), malformed.err());
    }

    // ---------------------------------------------------------------- support

    private String[] provisionArgs(Path graph, long expectedRevision) {
        return new String[] {"embed-registration", "provision", "--store-dir", storeDir(),
                "--audit-dir", auditDir(), "--tenant", "tenant-a", "--registration-id", "reg-1",
                "--expected-revision", Long.toString(expectedRevision), "--graphml", graph.toString(),
                "--graph-id", "graph-a", "--graph-version-id", "v1", "--snapshot-state", "published",
                "--issuer", "https://issuer.example", "--subject", "workload-1", "--parent-origin",
                "https://parent.example", "--resource-id", "resource-a", "--deployment-id",
                "deployment-a", "--deployment-version", "1", "--policy-revision", "policy-1",
                "--gate-deployment", "true", "--gate-provenance", "true", "--gate-classification", "true", "--gate-retention", "true", "--gate-dsr-suppression", "true", "--gate-takedown", "true", "--gate-eea", "true"};
    }

    /** Drops a flag and its value, so "omitting one" is exactly what the test does. */
    private static String[] without(String[] args, String flag) {
        var kept = new java.util.ArrayList<String>();
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals(flag)) {
                index++;
                continue;
            }
            kept.add(args[index]);
        }
        return kept.toArray(String[]::new);
    }

    private static String[] replace(String[] args, String flag, String value) {
        String[] copy = args.clone();
        for (int index = 0; index < copy.length - 1; index++) {
            if (copy[index].equals(flag)) copy[index + 1] = value;
        }
        return copy;
    }

    private Path writeGraph() throws Exception {
        Path graph = root.resolve("graph.graphml");
        Files.writeString(graph, GRAPH, StandardCharsets.UTF_8);
        return graph;
    }

    private String storeDir() {
        return root.resolve("embed").toString();
    }

    private String auditDir() {
        return root.resolve("audit").toString();
    }

    /**
     * Every audit field, base64-decoded.
     *
     * <p>{@code FileAuditTrail} stores each field base64-encoded, so a naive substring search over the
     * raw file would pass for any forbidden term simply because it is never stored in the clear. That
     * would be a redaction assertion that cannot fail, which is worse than none: decoding first is
     * what makes the «no graph data» check able to catch a regression.</p>
     */
    private String auditContents() throws Exception {
        try (var files = Files.walk(root.resolve("audit"))) {
            var contents = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
                    contents.append(line).append('\n');
                    for (String field : line.split("\\|", -1)) {
                        try {
                            contents.append(new String(java.util.Base64.getDecoder().decode(field),
                                    StandardCharsets.UTF_8)).append('\n');
                        } catch (IllegalArgumentException notBase64) {
                            // Sequence numbers, digests and timestamps are stored as plain text.
                        }
                    }
                }
            }
            return contents.toString();
        }
    }

    private static RequestContext workload() {
        return new RequestContext("request", "workload-1", PrincipalType.WORKLOAD,
                "https://issuer.example", "tenant-a", Set.of(Role.VIEWER),
                Set.of("ravenroot.embed.session.create"));
    }

    private record Invocation(int status, String out, String err) {
    }

    private Invocation run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int status;
        try (var output = new PrintStream(out, true, StandardCharsets.UTF_8);
             var errors = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            status = EmbedRegistrationCommand.run(args, output, errors);
        }
        return new Invocation(status, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }
}
