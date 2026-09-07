package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.persistence.AgentAuthorityBinding;
import ai.ravenroot.api.persistence.AgentAuthorityControl;
import ai.ravenroot.api.persistence.AgentAuthorityControlState;
import ai.ravenroot.api.persistence.AgentAuthorityGrantRegistration;
import ai.ravenroot.api.persistence.AgentAuthorityRootRegistration;
import ai.ravenroot.api.persistence.AgentBudgetOperation;
import ai.ravenroot.api.persistence.AgentBudgetReservation;
import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.api.persistence.AgentReservationState;
import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.DurableAgentAuthorityBudget;
import ai.ravenroot.api.persistence.DurableExecutionPause;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableHumanTask;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionPauseRegistration;
import ai.ravenroot.api.persistence.ExecutionResultNodes;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HumanTaskCommentRequirement;
import ai.ravenroot.api.persistence.HumanTaskConfirmationAction;
import ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskRegistration;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.IdempotencyWrite;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;
import ai.ravenroot.api.persistence.ProcessInventoryPage;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredExecutionManifest;
import ai.ravenroot.api.persistence.StoredGraphDefinition;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.TraversalInventoryEntry;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backup and restore drill: a populated shared store dumped and put back with the database's own
 * tooling, then read through the ports to show that nothing it held lost its references.
 *
 * <h2>Why the drill runs {@code pg_dump}, and not a row copier</h2>
 * <p>Backup and restore are not this adapter's operations. The single-host adapter owns a database
 * file and can therefore copy it under a maintenance lock; here the store is a database an operator
 * administers, and {@code adr/0040} and the operator documentation say so by telling that operator to
 * use PostgreSQL's own tools. A drill that walked the tables in Java would prove that a mechanism
 * nobody will ever run preserves the data, which is worth nothing: the question is whether
 * <em>{@code pg_dump}</em> preserves it, including its ordering behaviour, which is not this module's
 * to decide. So the dump is taken by {@code pg_dump} inside the server's container, and the restore is
 * performed by {@code pg_restore} into a second, empty database on the same server. Both shapes an
 * operator's restore takes are exercised: the full one, and the data-only one into a database whose
 * schema is already present.</p>
 *
 * <h2>What is in the dumped store</h2>
 * <p>{@link #populate} builds one database holding, across two tenants: graph definitions with two
 * logical bindings onto one content address; pinned execution manifests, one of them carrying node
 * packages; accepted process instances with traversals, invocations and attempts; a live lease and the
 * work claim a worker took under it; an idempotency record; journal events with an advanced outbox
 * cursor and a recorded inbox delivery; inventory rows; a recorded execution result; one of every
 * continuation the port defines — a handler, a tool approval, a human task carrying an embedded
 * confirmation, an execution pause and an agent authority budget; and a deployment with two versions,
 * a command ledger entry and a lease. All eight of the entity families the acceptance criterion names
 * are present, because a restore drill over a store that never held one of them says nothing about
 * that one.</p>
 *
 * <p>The four retention floors are moved off their initial values before the keeper population is
 * written, by purging a sacrificial population that has genuinely expired. A floor still where an
 * untouched store leaves it would compare equal after a restore that lost the watermark tables
 * entirely, so the drill would pass without them.</p>
 *
 * <h2>Why one fixture and one dump for every test here</h2>
 * <p>Populating the store and dumping it is the expensive half and it produces an immutable artifact,
 * so it is done once in {@link #populateAndDump} and each test restores it into a database of its own.
 * The tests share no mutable state: each restores separately and reads its own target.</p>
 *
 * <h2>How this drill could fail, and what proves it can</h2>
 * <p>A restore that produced an empty database would pass a test that only asked "does every read
 * succeed", because every one of these reads answers {@code Optional.empty()}, an empty page or a
 * default floor against an empty store without failing. Two things stop that here. Every observation
 * is compared against the value read from the source before the dump rather than against a shape, so
 * an empty answer is a mismatch. And {@link #aRestoreThatSkippedTheManifestPackageTableIsRefused}
 * performs a genuinely partial restore — the real {@code pg_restore}, given a table of contents with
 * one table's data removed — and asserts that the manifest whose package rows did not arrive is
 * refused with a digest mismatch rather than returned as a manifest with no packages. That is the
 * exact shape of a partial restore in this schema: {@code execution_manifest_package} is a child table
 * whose rows are covered by the parent's recorded digest, so losing them silently would hand an
 * execution a dependency set it was never pinned to.</p>
 */
class PostgresBackupRestoreDrillTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    /** Past every retention window this adapter publishes, so the sacrificial population expires. */
    private static final Duration BEYOND_EVERY_RETENTION_WINDOW = Duration.ofDays(8);

    /** One outbox destination throughout, so the drill's compaction and its cursor are the same story. */
    private static final String OUTBOX_DESTINATION = "downstream";

    private static final String TENANT_A = "acme";
    private static final String TENANT_B = "globex";

    private static final String GRAPHML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns"><graph id="a" edgedefault="directed"/></graphml>
            """;

    private static MutableClock clock;
    private static String schema;
    private static Fixture fixture;
    private static Map<String, String> observedBeforeTheDump;
    private static String dumpPathInContainer;

    /** Every identity the drill has to name again after the restore, minted once during population. */
    private record Fixture(ExecutionKey busy, ExecutionKey completed, ExecutionKey handler, UUID handlerId,
                           ExecutionKey approval, UUID approvalId, ExecutionKey humanTask, UUID taskId,
                           ExecutionKey pause, UUID pauseId, ExecutionKey budget, ExecutionKey otherTenant,
                           UUID busyTraversalId, String idempotencyKey, Instant idempotencyIssuedAt,
                           GraphDefinitionIdentity firstIdentity, GraphDefinitionIdentity aliasIdentity,
                           GraphDefinitionKey definitionKey, GraphDefinitionKey otherTenantDefinitionKey,
                           ExecutionKey manifestWithPackages, ExecutionKey manifestWithoutPackages,
                           DeploymentId deployment, DeploymentId otherTenantDeployment,
                           DeploymentRegistry.Command replayableCommand,
                           DeploymentRegistry.Desired replayedDesired,
                           DeploymentRegistry.Record recordedCommandOutcome) {
    }

    @BeforeAll
    static void populateAndDump() throws Exception {
        clock = new MutableClock(EPOCH);
        String storeId = "backup-restore-drill-" + UUID.randomUUID();
        schema = PostgresTestDatabase.schemaNameFor(storeId);
        DataSource source = PostgresTestDatabase.dataSourceFor(storeId);

        try (ExecutionStore store = new PostgresExecutionStore(source, clock);
             GraphDefinitionStore definitions =
                     new PostgresGraphDefinitionStore(source, clock, GraphDefinitionReferences.NONE);
             ExecutionManifestStore manifests =
                     new PostgresExecutionManifestStore(source, clock, ExecutionManifestReferences.NONE);
             DeploymentRegistry registry = new PostgresDeploymentRegistry(source, clock,
                     tenant -> DeploymentId.of(UUID.randomUUID().toString()))) {
            advanceEveryRetentionFloor(store);
            fixture = populate(store, definitions, manifests, registry);
        }

        observedBeforeTheDump = observe(source, fixture);
        // The comparison the drill makes is only as strong as the answers it compares, and every read
        // in observe() has a benign empty answer: Optional.empty(), an empty page, an empty list. A
        // family that silently stopped being populated would therefore be compared as "absent equals
        // absent" and the drill would keep passing while covering one family fewer than it claims.
        // The scalar answers, which report a count or a position rather than a rendered record and so
        // are invisible to the two emptiness checks above.
        final java.util.Set<String> COUNTED_ANSWERS = java.util.Set.of(
                "idempotency.count", "journal.retainedFrom", "outbox.cursor", "inbox.count");
        observedBeforeTheDump.forEach((question, answer) -> {
            assertNotEquals("absent", answer, question + " read as absent before the dump, so the "
                    + "fixture no longer populates it and the restore is not being tested for it");
            assertNotEquals("[]", answer, question + " read as empty before the dump, so the fixture "
                    + "no longer populates it and the restore is not being tested for it");
            if (question.startsWith("rows.") || COUNTED_ANSWERS.contains(question)) {
                // Extended past the rows.* prefix deliberately. These four answer with a bare number
                // rather than with a rendered record, so "absent" and "[]" cannot catch them, and a
                // fixture that stopped populating them would compare zero with zero and keep passing
                // while the drill went on naming the family in its own documentation.
                assertNotEquals("0", answer, question + " holds nothing before the dump, so comparing "
                        + "it after the restore compares zero with zero");
            }
        });

        dumpPathInContainer = "/tmp/" + storeId + ".dump";
        // -Fc so the restore is pg_restore's, which is the only form that can be given a table of
        // contents; the falsifiability test needs exactly that to leave one table's data behind.
        run("pg_dump -U " + PostgresTestDatabase.username() + " -d " + PostgresTestDatabase.databaseName()
                + " -n " + schema + " -Fc -f " + dumpPathInContainer);
    }

    /**
     * The drill itself: everything readable before the dump is readable, and identical, after it.
     *
     * <p>No flag beyond the format is passed to either tool. That is the claim being made as much as
     * the data comparison is: an operator who runs the documented {@code pg_dump} and the documented
     * {@code pg_restore}, with nothing else, gets this database back. {@code --exit-on-error} is added
     * so the restore stops at the first failure instead of continuing and applying part of the dump.
     * It is not added to make the failure visible: {@code pg_restore} already exits 1 and prints
     * {@code errors ignored on restore: N} when it continues past one. The tool that fails silently is
     * {@code psql}, which restores a plain-format dump reporting every error and exiting zero, and
     * which this drill therefore does not use.</p>
     */
    @Test
    void aDumpAndRestorePreservesEveryEntityFamilyAndTheReferencesBetweenThem() throws Exception {
        DataSource restored = restoreInto("drill_full_" + suffix(), List.of());

        Map<String, String> observedAfter = observe(restored, fixture);

        assertEquals(observedBeforeTheDump.keySet(), observedAfter.keySet(),
                "the two observations must ask the same questions, or the comparison below is vacuous");
        for (Map.Entry<String, String> expected : observedBeforeTheDump.entrySet()) {
            assertEquals(expected.getValue(), observedAfter.get(expected.getKey()),
                    "the restored store answers differently for " + expected.getKey());
        }
    }

    /**
     * The other shape an operator's restore takes — data only, into a database whose schema is already
     * there — and it needs no {@code --disable-triggers} either.
     *
     * <p>Worth asserting separately because the two restores satisfy the foreign keys for entirely
     * different reasons, and only one of those reasons is visible in the test above. A full restore is
     * safe by construction: {@code pg_dump} emits every table's data before it adds a single
     * constraint, so nothing is enforced while rows are loading and the order tables are loaded in
     * cannot matter. A data-only restore has no such freedom — the constraints are already in place
     * and enforcing — and is safe only because {@code pg_dump} orders {@code TABLE DATA} entries by
     * their foreign-key dependencies, which it can do here because this schema's references form a
     * tree with no cycle in it. A cycle is exactly the case in which that ordering does not exist and
     * {@code --disable-triggers}, and the superuser rights it needs, become unavoidable; the schema
     * having none is therefore a property worth catching if it ever changes.</p>
     *
     * <p>Two things about the target have to be true and neither is automatic, which is the second
     * reason this is worth a test rather than a paragraph. The two schema-bookkeeping tables are
     * excluded from the dump, because the target is migrated before the restore and the migration
     * writes them. And the migration <em>seeds</em> one row — the store-global authority control, which
     * is created rather than written lazily so that every reader finds one — so a target that has only
     * been migrated is not actually empty and the restore collides on that row's primary key. A
     * data-only restore therefore needs its target emptied first, which is what
     * {@link #emptyEverySeededTable} does and why the tables it clears are discovered rather than
     * listed: a future migration seeding a second row would otherwise reintroduce exactly this
     * collision.</p>
     */
    @Test
    void aDataOnlyRestoreIntoAnAlreadyMigratedDatabaseSatisfiesTheForeignKeysToo() throws Exception {
        String database = "drill_data_only_" + suffix();
        String user = PostgresTestDatabase.username();
        run("createdb -U " + user + " " + database);
        run("psql -U " + user + " -d " + database + " -v ON_ERROR_STOP=1 -c 'CREATE SCHEMA " + schema + "'");

        DataSource target = PostgresTestDatabase.dataSource(
                PostgresTestDatabase.jdbcUrlForDatabase(database), user,
                PostgresTestDatabase.password(), schema);
        // The migration is what an operator's target has already had applied to it before a data-only
        // restore, whether it was run by a tool or by the first process that opened the database.
        try (Connection connection = target.getConnection()) {
            assertEquals(PostgresSchema.currentVersion(), PostgresSchema.migrate(connection, clock));
        }
        assertFalse(emptyEverySeededTable(target).isEmpty(),
                "the migration seeds at least one row, so a freshly migrated target is not empty and "
                        + "the emptying this line performs is load-bearing rather than a precaution");

        String dataOnly = "/tmp/" + database + ".dump";
        run("pg_dump -U " + user + " -d " + PostgresTestDatabase.databaseName() + " -n " + schema
                + " -a -T " + schema + ".store_schema_version -T " + schema + ".store_schema_history"
                + " -Fc -f " + dataOnly);
        run("pg_restore -U " + user + " -d " + database + " --exit-on-error " + dataOnly);

        Map<String, String> observedAfter = observe(target, fixture);
        // The same guard the full restore carries: without it, an observation that stopped being taken
        // would drop out of both maps and the loop below would compare nothing and pass.
        assertEquals(observedBeforeTheDump.keySet(), observedAfter.keySet(),
                "the restored database answers a different set of questions than the source did, so "
                        + "the comparison below is vacuous for whatever is missing");
        for (Map.Entry<String, String> expected : observedBeforeTheDump.entrySet()) {
            assertEquals(expected.getValue(), observedAfter.get(expected.getKey()),
                    "the data-only restore answers differently for " + expected.getKey());
        }
    }

    /**
     * The command ledger still answers a replayed command with the outcome it recorded, not with the
     * aggregate's current state.
     *
     * <p>Separate from the comparison above because a replay is issued through a mutating method:
     * running it against the source would have written to the database being dumped if the replay had
     * not worked, which is the very thing under test. So it is asked only of the restored database, and
     * compared against the outcome captured when the command was first accepted.</p>
     */
    @Test
    void theRestoredCommandLedgerReplaysTheOutcomeItRecorded() throws Exception {
        DataSource restored = restoreInto("drill_ledger_" + suffix(), List.of());

        try (DeploymentRegistry registry = new PostgresDeploymentRegistry(restored, clock,
                tenant -> DeploymentId.of(UUID.randomUUID().toString()))) {
            DeploymentRegistry.Record beforeReplay =
                    await(registry.get(TENANT_A, fixture.deployment())).orElseThrow();
            DeploymentRegistry.Record replayed = await(registry.command(fixture.replayedDesired(),
                    fixture.replayableCommand()));

            assertEquals(text(fixture.recordedCommandOutcome()), text(replayed),
                    "a replayed command must return the record the ledger row carries; a ledger whose "
                            + "rows did not survive the restore would instead apply the command again "
                            + "and answer with a new generation and revision");
            assertNotEquals(beforeReplay.revision(), replayed.revision(),
                    "the lease acquired after the command moved the aggregate on, so the recorded "
                            + "outcome and the current state genuinely differ here — which is what "
                            + "makes the assertion above about the ledger rather than about the "
                            + "aggregate the registry could have read instead");
            assertEquals(text(beforeReplay),
                    text(await(registry.get(TENANT_A, fixture.deployment())).orElseThrow()),
                    "and the aggregate itself is untouched by the replay");
        }
    }

    /**
     * The drill can fail: a restore that lost one child table's rows is refused, not served.
     *
     * <h4>What a partial restore looks like here</h4>
     * <p>{@code pg_restore} takes a table of contents and restores only the entries in it, which is
     * what an operator does when a restore is resumed, filtered, or run from a list somebody edited.
     * Drop the {@code TABLE DATA} entry for {@code execution_manifest_package} from that list and the
     * restore still succeeds — fewer child rows never violate a foreign key — and every manifest row
     * arrives intact. The damage is entirely invisible at the SQL level: the parent table is complete,
     * the constraint is satisfied, nothing reports an error.</p>
     *
     * <h4>Which assertion catches it</h4>
     * <p>{@link ExecutionManifestStore#load} re-derives the manifest's digest from the fields it read,
     * packages included, and compares it against the digest the parent row recorded. A manifest that
     * was pinned with packages and read back without them hashes to something else, so the read fails
     * with {@link ExecutionManifestStoreFailure.DigestMismatch} instead of handing an execution a
     * dependency set it was never pinned to. That is the assertion in
     * {@link #aDumpAndRestorePreservesEveryEntityFamilyAndTheReferencesBetweenThem} that would have
     * failed had the full restore lost those rows — this test is the demonstration that it would.</p>
     *
     * <p>The manifest pinned <em>without</em> packages is asserted to still load in the same damaged
     * database. Without that half the test would be satisfied by a restore that produced nothing at
     * all, which is the failure mode this whole drill is built to be able to see.</p>
     */
    @Test
    void aRestoreThatSkippedTheManifestPackageTableIsRefused() throws Exception {
        DataSource damaged = restoreInto("drill_partial_" + suffix(),
                List.of("TABLE DATA " + schema + " execution_manifest_package"));

        assertEquals(0, countRows(damaged, "execution_manifest_package"),
                "the filtered restore really did leave the package rows behind");
        assertTrue(countRows(damaged, "execution_manifest") > 0,
                "while the parent rows arrived, which is what makes the damage invisible to SQL");

        try (ExecutionManifestStore manifests =
                     new PostgresExecutionManifestStore(damaged, clock, ExecutionManifestReferences.NONE)) {
            ExecutionManifestStoreFailure refused =
                    manifestFailureOf(() -> await(manifests.load(fixture.manifestWithPackages())));
            ExecutionManifestStoreFailure.DigestMismatch mismatch = assertInstanceOf(
                    ExecutionManifestStoreFailure.DigestMismatch.class, refused,
                    "a manifest whose packages did not arrive must be refused, never returned with an "
                            + "empty package list that an execution would take for its pinned set");
            assertEquals(fixture.manifestWithPackages(), mismatch.key());

            StoredExecutionManifest undamaged = await(manifests.load(fixture.manifestWithoutPackages()));
            assertEquals(observedBeforeTheDump.get("manifest.withoutPackages"), text(undamaged),
                    "the manifest that had no package rows to lose is unaffected, so this test is "
                            + "distinguishing a partial restore from an empty one");
        }
    }

    // ============================================================ the dump and the restores

    /**
     * Restores the one dump into a fresh database on the same server and returns a {@link DataSource}
     * over it, optionally leaving out the table-of-contents entries whose text contains one of
     * {@code omitting}.
     *
     * <p>The target is a separate database rather than a second schema in the same one, because a
     * restore an operator performs replaces a database and this drill should not be able to pass by
     * accidentally reading the source's rows through a search path.</p>
     */
    private static DataSource restoreInto(String database, List<String> omitting) throws Exception {
        String user = PostgresTestDatabase.username();
        run("createdb -U " + user + " " + database);
        String restore = "pg_restore -U " + user + " -d " + database + " --exit-on-error ";
        if (omitting.isEmpty()) {
            run(restore + dumpPathInContainer);
        } else {
            String list = "/tmp/" + database + ".toc";
            // grep -v over pg_restore's own list, which is the documented way to restore a subset. The
            // filter is applied to the tool's output rather than to a list written here, so the entries
            // that survive are the ones the tool itself emitted and would have restored.
            var filter = new StringBuilder("pg_restore -l " + dumpPathInContainer);
            for (String omitted : omitting) {
                filter.append(" | grep -v '").append(omitted).append("'");
            }
            run(filter + " > " + list);
            run(restore + "-L " + list + " " + dumpPathInContainer);
        }
        return PostgresTestDatabase.dataSource(PostgresTestDatabase.jdbcUrlForDatabase(database), user,
                PostgresTestDatabase.password(), schema);
    }

    /**
     * Empties every table the schema creation left rows in, and names them.
     *
     * <p>Discovered from the catalogue rather than listed, so that a migration which starts seeding
     * another row is handled here instead of surfacing as a duplicate-key failure in a restore. The two
     * schema-bookkeeping tables are kept, because they are what tells the target which version it is at
     * and they are excluded from the data-only dump for that reason.</p>
     */
    private static List<String> emptyEverySeededTable(DataSource dataSource) throws SQLException {
        var emptied = new ArrayList<String>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            var tables = new ArrayList<String>();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()"
                            + " AND table_type = 'BASE TABLE' AND table_name NOT IN "
                            + "('store_schema_version', 'store_schema_history') ORDER BY table_name")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            for (String table : tables) {
                // Quoted, and the name came from the server's own catalogue rather than from a caller.
                int removed = statement.executeUpdate("DELETE FROM \"" + table + "\"");
                if (removed > 0) {
                    emptied.add(table);
                }
            }
        }
        return List.copyOf(emptied);
    }

    /** Runs one tool inside the server's container and fails loudly with its own diagnostics. */
    private static void run(String command) throws Exception {
        Container.ExecResult result = PostgresTestDatabase.psqlTool(command);
        assertEquals(0, result.getExitCode(),
                () -> "the operator procedure failed: " + command + "\n" + result.getStdout() + "\n"
                        + result.getStderr());
    }

    private static String suffix() {
        // A database name, so lowercase and free of the hyphens a UUID carries.
        return UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    // ============================================================ what is asked of both databases

    /**
     * Every question the drill asks, labelled, answered from whichever database {@code dataSource}
     * addresses.
     *
     * <p>Deliberately one function run against both databases rather than a snapshot recorded by hand
     * and re-checked. A hand-written expectation drifts from what the reads actually return and ends up
     * asserting the fixture rather than the restore; running the identical reads against the source and
     * against the target makes the assertion "these two databases answer the same", which is the
     * property a restore has to have.</p>
     *
     * <p><strong>Every read here must be free of side effects</strong>, because this runs against the
     * source before the dump is taken and anything it wrote would be in the artifact. That is why
     * {@code claimPendingWork}, {@code ack} and the ledger replay are not here: they are mutations, and
     * the state they leave is asserted through what it can be observed by — the lease, the inventory's
     * owner column, and, for the work claim, a row count, since the port publishes no read for it.</p>
     *
     * <p>Answers are rendered as text rather than compared as values because several of these records
     * carry {@code byte[]} components — a tool approval's canonical arguments, a pause's continuation —
     * and a record's generated {@code equals} compares an array by identity. Two reads of the same row
     * would then differ for a reason that has nothing to do with the restore.</p>
     *
     * <p>Some of those renderings walk a hash-ordered collection, whose iteration order is stable
     * within one JVM and not across JVMs. That is sound here and only here: both observations are made
     * by this one test run, so the two renderings are ordered the same way. Persisting one of these
     * strings and comparing it against a later run would not be.</p>
     */
    private static Map<String, String> observe(DataSource dataSource, Fixture what) {
        var answers = new LinkedHashMap<String, String>();
        try (ExecutionStore store = new PostgresExecutionStore(dataSource, clock);
             GraphDefinitionStore definitions =
                     new PostgresGraphDefinitionStore(dataSource, clock, GraphDefinitionReferences.NONE);
             ExecutionManifestStore manifests =
                     new PostgresExecutionManifestStore(dataSource, clock, ExecutionManifestReferences.NONE);
             DeploymentRegistry registry = new PostgresDeploymentRegistry(dataSource, clock,
                     tenant -> DeploymentId.of(UUID.randomUUID().toString()))) {

            // ---- the aggregate and the inventory row beside it, for every instance in the fixture.
            //      Both, because the two carry different halves of what has to survive: load() returns
            //      the revision and the reconstructed aggregate, and the inventory row is where the
            //      port publishes the fencing token, the lifecycle generation, the origin columns and
            //      the retention deadline.
            for (Map.Entry<String, ExecutionKey> instance : Map.of(
                    "busy", what.busy(), "completed", what.completed(), "handler", what.handler(),
                    "approval", what.approval(), "humanTask", what.humanTask(), "pause", what.pause(),
                    "budget", what.budget(), "otherTenant", what.otherTenant()).entrySet()) {
                answers.put("instance." + instance.getKey(), text(await(store.load(instance.getValue()))));
                answers.put("inventory." + instance.getKey(),
                        await(store.findProcessInstance(instance.getValue()))
                                .map(PostgresBackupRestoreDrillTest::text).orElse("absent"));
            }
            answers.put("leases." + TENANT_A, await(store.leases(TENANT_A)).stream()
                    .sorted(Comparator.comparing(lease -> lease.key().processInstanceId()))
                    .map(PostgresBackupRestoreDrillTest::text).toList().toString());

            // ---- idempotency
            answers.put("idempotency.record", await(store.lookupIdempotency(TENANT_A,
                    what.idempotencyKey(), what.idempotencyIssuedAt())).map(
                            PostgresBackupRestoreDrillTest::text).orElse("absent"));
            answers.put("idempotency.count", String.valueOf(await(store.idempotencyRecordCount(TENANT_A))));

            // ---- journal, outbox and inbox
            answers.put("journal.records", readWholeJournal(store).stream()
                    .map(PostgresBackupRestoreDrillTest::text).toList().toString());
            answers.put("journal.retainedFrom", String.valueOf(await(store.journalRetainedFrom(TENANT_A))));
            answers.put("outbox.cursor", text(await(store.outboxCursor(TENANT_A, OUTBOX_DESTINATION))));
            answers.put("inbox.count", String.valueOf(await(store.inboxRecordCount(TENANT_A))));

            // ---- inventory, paged rather than listed, so the cursor is exercised too
            ProcessInventoryPage first = await(store.listProcessInstances(TENANT_A,
                    ProcessInventoryQuery.everything(3)));
            answers.put("inventory.page1", text(first));
            answers.put("inventory.page2", text(await(store.listProcessInstances(TENANT_A,
                    ProcessInventoryQuery.everything(3).after(first.nextCursor().orElseThrow())))));
            answers.put("inventory." + TENANT_B, text(await(store.listProcessInstances(TENANT_B,
                    ProcessInventoryQuery.everything(3)))));
            answers.put("inventory.traversals", await(store.listTraversals(what.busy())).stream()
                    .map(PostgresBackupRestoreDrillTest::text).toList().toString());

            // ---- results
            answers.put("result.completed", await(store.loadExecutionResult(TENANT_A,
                    what.busyTraversalId())).map(PostgresBackupRestoreDrillTest::text).orElse("absent"));

            // ---- the four retention floors
            answers.put("retention.forgottenBefore", String.valueOf(await(store.forgottenBefore(TENANT_A))));
            answers.put("retention.inventoryRetainedFrom",
                    String.valueOf(await(store.inventoryRetainedFrom(TENANT_A))));
            answers.put("retention.executionResultsRetainedFrom",
                    String.valueOf(await(store.executionResultsRetainedFrom(TENANT_A))));

            // ---- continuations, one read each
            answers.put("continuation.handler", await(store.loadHandler(what.handler(), what.handlerId()))
                    .map(PostgresBackupRestoreDrillTest::text).orElse("absent"));
            answers.put("continuation.approval",
                    await(store.loadToolApproval(what.approval(), what.approvalId()))
                            .map(PostgresBackupRestoreDrillTest::text).orElse("absent"));
            answers.put("continuation.humanTask", await(store.loadHumanTask(TENANT_A, what.taskId()))
                    .map(PostgresBackupRestoreDrillTest::text).orElse("absent"));
            answers.put("continuation.pause", await(store.loadExecutionPause(what.pause(), what.pauseId()))
                    .map(PostgresBackupRestoreDrillTest::text).orElse("absent"));
            answers.put("continuation.budget", await(store.loadAgentAuthorityBudget(what.budget()))
                    .map(PostgresBackupRestoreDrillTest::text).orElse("absent"));
            answers.put("continuation.control", text(await(store.loadAgentAuthorityControl())));

            // ---- definitions: resolved by logical identity, loaded by content address, digest verified
            answers.put("definition.resolvedByFirstIdentity",
                    text(await(definitions.resolve(TENANT_A, what.firstIdentity()))));
            answers.put("definition.resolvedByAliasIdentity",
                    text(await(definitions.resolve(TENANT_A, what.aliasIdentity()))));
            answers.put("definition.loadedByAddress", text(await(definitions.load(what.definitionKey()))));
            answers.put("definition." + TENANT_B,
                    text(await(definitions.load(what.otherTenantDefinitionKey()))));

            // ---- manifests, whose load re-derives the digest across the package rows as well
            answers.put("manifest.withPackages", text(await(manifests.load(what.manifestWithPackages()))));
            answers.put("manifest.withoutPackages",
                    text(await(manifests.load(what.manifestWithoutPackages()))));

            // ---- the deployment aggregate, its versions, its lease and the tenant beside it
            answers.put("deployment.record",
                    text(await(registry.get(TENANT_A, what.deployment())).orElseThrow()));
            answers.put("deployment.version1",
                    text(await(registry.version(TENANT_A, what.deployment(), 1)).orElseThrow()));
            answers.put("deployment.version2",
                    text(await(registry.version(TENANT_A, what.deployment(), 2)).orElseThrow()));
            answers.put("deployment.list", await(registry.list(TENANT_A, null, 10)).items().stream()
                    .map(PostgresBackupRestoreDrillTest::text).toList().toString());
            answers.put("deployment." + TENANT_B,
                    text(await(registry.get(TENANT_B, what.otherTenantDeployment())).orElseThrow()));
        }

        // ---- the tables the ports publish no read for, counted directly
        answers.put("rows.work_claim", String.valueOf(countRows(dataSource, "work_claim")));
        answers.put("rows.timer", String.valueOf(countRows(dataSource, "timer")));
        answers.put("rows.deployment_command", String.valueOf(countRows(dataSource, "deployment_command")));
        answers.put("rows.invocation_parent", String.valueOf(countRows(dataSource, "invocation_parent")));
        return answers;
    }

    // ============================================================ building the populated store

    /**
     * Moves all four retention floors off their initial value by purging a population that has really
     * expired, so that "the floors are unchanged" is a statement about four values a restore has to
     * reproduce rather than about four defaults an empty database would produce as well.
     *
     * <p>The order of the purges is not arbitrary: recorded results hang off their process instance
     * with {@code ON DELETE CASCADE}, so purging instances first would take the result rows with them
     * and the result purge would then find nothing to advance its floor with.</p>
     *
     * <p>The outbox cursor has to be advanced past the sacrificial event before compaction, because
     * the port discards a record only when it is both past its retention <em>and</em> delivered by
     * every known destination — age alone is not evidence that anybody received it. Without the
     * delivery the compaction would return zero and the journal floor would never move.</p>
     */
    private static void advanceEveryRetentionFloor(ExecutionStore store) {
        ExecutionKey doomed = new ExecutionKey(TENANT_A, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store.apply(creation(doomed, traversalId)));
        StoredProcessInstance running = await(store.apply(ExecutionBatch.to(doomed)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("expiring-key", payload("request"),
                        payload("outcome"), Duration.ofMinutes(5), clock.instant()))
                .publish(event(doomed, traversalId, "process.running"))
                .build()));
        await(store.apply(ExecutionBatch.to(doomed)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.COMPLETED))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.COMPLETED))
                .build()));
        await(store.recordExecutionResult(DurableExecutionResult.of(doomed, traversalId,
                new GraphVersionPin("graph-v1"), ProcessInstanceStatus.COMPLETED, null,
                clock.instant(), clock.instant(), Map.of("answer", 1L), ExecutionResultNodes.empty(), null,
                store.maxExecutionResultPayloadBytes())));
        List<JournalRecord> doomedEvents = readWholeJournal(store);
        assertEquals(1, doomedEvents.size(), "one sacrificial event, which is what will be compacted");
        await(store.advanceOutboxCursor(await(store.outboxCursor(TENANT_A, OUTBOX_DESTINATION)),
                doomedEvents.getLast().journalOffset()));

        Instant forgottenBefore = await(store.forgottenBefore(TENANT_A));
        long journalRetainedFrom = await(store.journalRetainedFrom(TENANT_A));
        Instant inventoryRetainedFrom = await(store.inventoryRetainedFrom(TENANT_A));
        Instant resultsRetainedFrom = await(store.executionResultsRetainedFrom(TENANT_A));

        clock.advance(BEYOND_EVERY_RETENTION_WINDOW);

        assertEquals(1L, await(store.purgeExpiredIdempotencyRecords(TENANT_A)));
        assertEquals(1L, await(store.compactJournal(TENANT_A)));
        assertEquals(1L, await(store.purgeExpiredExecutionResults(TENANT_A)));
        assertEquals(1L, await(store.purgeExpiredProcessInstances(TENANT_A)));

        assertNotEquals(forgottenBefore, await(store.forgottenBefore(TENANT_A)),
                "a floor still where it started would compare equal after a restore that lost the "
                        + "watermark rows entirely, which is the one thing this fixture exists to stop");
        assertNotEquals(journalRetainedFrom, await(store.journalRetainedFrom(TENANT_A)));
        assertNotEquals(inventoryRetainedFrom, await(store.inventoryRetainedFrom(TENANT_A)));
        assertNotEquals(resultsRetainedFrom, await(store.executionResultsRetainedFrom(TENANT_A)));

        // The purge left the forgotten-before floor at the instant it ran, and the store refuses an
        // idempotency key issued at or under that floor plus its skew budget: it cannot prove such a
        // key was never recorded rather than purged. The keeper population's key is issued after this
        // step, so the clock moves past the floor by more than the budget before it is written.
        clock.advance(Duration.ofMinutes(1));
    }

    /**
     * Every journal record the tenant still retains.
     *
     * <p>Started from one below the retained floor rather than from zero, because a read that begins
     * below the floor is refused with {@code JournalTruncated} rather than answered short — and after
     * the compaction this fixture performs, zero is below it.</p>
     */
    private static List<JournalRecord> readWholeJournal(ExecutionStore store) {
        long retainedFrom = await(store.journalRetainedFrom(TENANT_A));
        return await(store.readJournal(TENANT_A, Math.max(0L, retainedFrom - 1), 50));
    }

    /** Writes the population the drill is about, and returns every identity needed to read it again. */
    private static Fixture populate(ExecutionStore store, GraphDefinitionStore definitions,
                                    ExecutionManifestStore manifests, DeploymentRegistry registry) {
        // ---- definitions: two logical identities bound onto one content address, plus a second tenant
        byte[] canonical = GRAPHML.getBytes(StandardCharsets.UTF_8);
        var firstIdentity = new GraphDefinitionIdentity("orders", "v1");
        var aliasIdentity = new GraphDefinitionIdentity("orders", "v1-alias");
        StoredGraphDefinition definition = await(definitions.put(TENANT_A, firstIdentity,
                CanonicalGraphMl.of(canonical)));
        await(definitions.put(TENANT_A, aliasIdentity, CanonicalGraphMl.of(canonical)));
        StoredGraphDefinition otherTenantDefinition = await(definitions.put(TENANT_B,
                new GraphDefinitionIdentity("returns", "v1"),
                CanonicalGraphMl.of(("<!-- b -->" + GRAPHML).getBytes(StandardCharsets.UTF_8))));

        // ---- the busy instance: a running attempt, a timer, a lease, a claim, an idempotency record,
        //      journal events, an advanced outbox cursor and a recorded inbox delivery
        ExecutionKey busy = new ExecutionKey(TENANT_A, UUID.randomUUID());
        UUID busyTraversal = UUID.randomUUID();
        UUID busyInvocation = UUID.randomUUID();
        UUID busyAttempt = UUID.randomUUID();
        String idempotencyKey = "inbound-" + UUID.randomUUID();
        Instant idempotencyIssuedAt = clock.instant();
        StoredProcessInstance busyCreated = await(store.apply(creation(busy, busyTraversal)));
        StoredProcessInstance busyRunning = await(store.apply(ExecutionBatch.to(busy)
                .expecting(RevisionExpectation.exactly(busyCreated.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(busyTraversal, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(busyTraversal,
                        new NodeInvocation(busyInvocation, "work", Set.of(), NodeInvocationStatus.SCHEDULED,
                                List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.InvocationTransitioned(busyTraversal, busyInvocation,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(busyTraversal, busyInvocation,
                        new NodeAttempt(busyAttempt, 1, NodeAttemptStatus.SCHEDULED)))
                .scheduleTimer(new TimerSchedule(UUID.randomUUID(), clock.instant().plus(Duration.ofHours(1)),
                        busyTraversal, null, OpaquePayload.empty("application/octet-stream")))
                .recordIdempotency(new IdempotencyWrite(idempotencyKey, payload("request"),
                        payload("outcome"), Duration.ofDays(30), idempotencyIssuedAt))
                .recordOrigin(ExecutionOrigin.of("deployment-a", "workload-a", "correlation-a"))
                .publish(event(busy, busyTraversal, "process.running"))
                .build()));
        EventEnvelope second = event(busy, busyTraversal, "invocation.scheduled");
        await(store.apply(ExecutionBatch.to(busy)
                .expecting(RevisionExpectation.exactly(busyRunning.revision()))
                // A second invocation naming the first as its causal parent, so the dump carries the
                // edge table too. Every other invocation in this fixture is parentless, and a table
                // that is empty everywhere is one a restore cannot be shown to preserve.
                .apply(new ExecutionTransition.InvocationAdded(busyTraversal,
                        new NodeInvocation(UUID.randomUUID(), "downstream-work", Set.of(busyInvocation),
                                NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                .publish(second)
                .build()));
        // Deliver the first keeper event and stop there, so the dumped cursor sits strictly between
        // the journal's floor and its head rather than at either end, where an off-by-one would hide.
        await(store.advanceOutboxCursor(await(store.outboxCursor(TENANT_A, OUTBOX_DESTINATION)),
                readWholeJournal(store).getFirst().journalOffset()));
        assertTrue(await(store.recordInboxDelivery(TENANT_A, "consumer-a", second.eventId(),
                Duration.ofDays(30))), "the inbox record must be genuinely new, or nothing is stored");

        // A worker takes the lease and then claims the scheduled attempt under it, so the dump carries a
        // live lease, a fencing token that outlives it, and the claim row that makes ack idempotent.
        LeaseHandle lease = await(store.claim(busy, "worker-a", Duration.ofMinutes(5)));
        List<PendingWork> claimed = await(store.claimPendingWork(TENANT_A, "worker-a", 5,
                Duration.ofMinutes(5)));
        assertFalse(claimed.isEmpty(), "the scheduled attempt must actually be claimable, or work_claim "
                + "stays empty and the drill covers one family fewer than it says");
        assertEquals(lease.key(), busy);

        // ---- a completed instance carrying a recorded result
        ExecutionKey completed = new ExecutionKey(TENANT_A, UUID.randomUUID());
        UUID completedTraversal = UUID.randomUUID();
        StoredProcessInstance completedCreated = await(store.apply(creation(completed, completedTraversal)));
        await(store.apply(ExecutionBatch.to(completed)
                .expecting(RevisionExpectation.exactly(completedCreated.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(completedTraversal,
                        TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(completedTraversal,
                        TraversalStatus.COMPLETED))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.COMPLETED))
                .build()));
        await(store.recordExecutionResult(DurableExecutionResult.of(completed, completedTraversal,
                new GraphVersionPin("graph-v1"), ProcessInstanceStatus.COMPLETED, null,
                clock.instant(), clock.instant(), Map.of("answer", 42L), ExecutionResultNodes.empty(), null,
                store.maxExecutionResultPayloadBytes())));

        // ---- one instance per continuation family
        ExecutionKey handlerKey = new ExecutionKey(TENANT_A, UUID.randomUUID());
        UUID handlerId = registerHandler(store, handlerKey);
        ExecutionKey approvalKey = new ExecutionKey(TENANT_A, UUID.randomUUID());
        UUID approvalId = registerToolApproval(store, approvalKey);
        ExecutionKey humanTaskKey = new ExecutionKey(TENANT_A, UUID.randomUUID());
        UUID taskId = registerHumanTask(store, humanTaskKey);
        ExecutionKey pauseKey = new ExecutionKey(TENANT_A, UUID.randomUUID());
        UUID pauseId = registerExecutionPause(store, pauseKey);
        ExecutionKey budgetKey = new ExecutionKey(TENANT_A, UUID.randomUUID());
        registerAgentBudget(store, budgetKey);

        // ---- a second tenant, so tenant scoping is something the restore has to preserve too
        ExecutionKey otherTenant = new ExecutionKey(TENANT_B, UUID.randomUUID());
        await(store.apply(creation(otherTenant, UUID.randomUUID())));

        // ---- manifests, one of them carrying the node packages the digest covers
        StoredExecutionManifest withPackages = await(manifests.pin(manifest(busy,
                definition.key().contentId(), List.of(PinnedNodePackage.of("ai.ravenroot.nodes.http",
                                "1.4.0", "sdk-2"),
                        PinnedNodePackage.of("ai.ravenroot.nodes.mail", "0.9.1", "sdk-2")))));
        await(manifests.pin(manifest(completed, definition.key().contentId(), List.of())));
        assertFalse(withPackages.manifest().nodePackages().isEmpty(),
                "the manifest under the falsifiability test must really have package rows to lose");

        // ---- a deployment with two versions, a replayable command in the ledger and a lease
        DeploymentRegistry.Record deployment = await(registry.create(content("graph-1"),
                new DeploymentRegistry.CreateCommand(TENANT_A, "create-1", digest("graph-1"))));
        DeploymentRegistry.Record appended = await(registry.append(2, content("graph-2"),
                new DeploymentRegistry.Command(TENANT_A, deployment.deploymentId(), "append-1",
                        digest("graph-2"), RevisionExpectation.exactly(deployment.revision()))));
        var replayedDesired = new DeploymentRegistry.Desired(DeploymentRegistry.DesiredKind.RUNNING, 2L,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST, 0);
        var replayableCommand = new DeploymentRegistry.Command(TENANT_A, deployment.deploymentId(),
                "command-1", digest("graph-2"), RevisionExpectation.exactly(appended.revision()));
        DeploymentRegistry.Record commanded = await(registry.command(replayedDesired, replayableCommand));
        DeploymentRegistry.Record leased = await(registry.acquire("controller-a", Duration.ofMinutes(5),
                new DeploymentRegistry.Command(TENANT_A, deployment.deploymentId(), "acquire-1",
                        digest("graph-2"), RevisionExpectation.exactly(commanded.revision()))));
        assertTrue(leased.lease() != null, "the dumped deployment must actually hold a lease");
        DeploymentRegistry.Record otherTenantDeployment = await(registry.create(content("graph-b"),
                new DeploymentRegistry.CreateCommand(TENANT_B, "create-b", digest("graph-b"))));

        // The store-global authority control is the one row the migration seeds rather than a write
        // creating it, so a restore that failed to carry it would still find the seeded default sitting
        // there and every read of it would agree. Moving it off that default — killed, then revived,
        // which advances the epoch and stamps a real instant — is what makes the comparison of this row
        // able to tell a restored value from a freshly created one.
        AgentAuthorityControl killed = await(store.transitionAgentAuthorityControl(
                AgentAuthorityControlState.ACTIVE, 0L, AgentAuthorityControlState.KILLED));
        AgentAuthorityControl revived = await(store.transitionAgentAuthorityControl(
                AgentAuthorityControlState.KILLED, killed.epoch(), AgentAuthorityControlState.ACTIVE));
        assertNotEquals(0L, revived.epoch(), "the control row must differ from the seeded default");

        return new Fixture(busy, completed, handlerKey, handlerId, approvalKey, approvalId, humanTaskKey,
                taskId, pauseKey, pauseId, budgetKey, otherTenant, completedTraversal, idempotencyKey,
                idempotencyIssuedAt, firstIdentity, aliasIdentity, definition.key(),
                otherTenantDefinition.key(), busy, completed, deployment.deploymentId(),
                otherTenantDeployment.deploymentId(), replayableCommand, replayedDesired, commanded);
    }

    private static UUID registerHandler(ExecutionStore store, ExecutionKey key) {
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();
        StoredProcessInstance created = await(store.apply(creation(key, traversalId)));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "await-approval", Set.of(),
                                NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.WAITING))
                .registerHandler(new HandlerRegistration(handlerId, "approval", traversalId, invocationId,
                        "correlation-" + handlerId, "deduplication-" + handlerId,
                        new HandlerPayloadSchema("application/vnd.ravenroot.test-approval", "approval/v1",
                                1024),
                        HandlerAuthorization.ofRoles("APPROVER")))
                .build()));
        return handlerId;
    }

    private static UUID registerToolApproval(ExecutionStore store, ExecutionKey key) {
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        StoredProcessInstance running = runningAttempt(store, key, traversalId, invocationId, attemptId);
        byte[] arguments = "{\"amount\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] continuation = "checkpoint".getBytes(StandardCharsets.UTF_8);
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .registerToolApproval(new ToolApprovalRegistration(approvalId, traversalId, invocationId,
                        attemptId, UUID.randomUUID(), "work", "payments.charge", arguments,
                        digestOf(arguments), requester(key), new GraphVersionPin("graph-v1"), "policy-v1",
                        clock.instant().plus(Duration.ofMinutes(30)),
                        HandlerAuthorization.ofRoles("APPROVER"), false, 1, continuation,
                        digestOf(continuation)))
                .build()));
        return approvalId;
    }

    private static UUID registerHumanTask(ExecutionStore store, ExecutionKey key) {
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StoredProcessInstance running = runningAttempt(store, key, traversalId, invocationId, attemptId);
        byte[] continuation = {1, 2, 3};
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .registerHumanTask(new HumanTaskRegistration(taskId, traversalId, invocationId, attemptId,
                        "human-review", "correlation-" + taskId, "deduplication-" + taskId,
                        new HumanTaskMetadata("Review this request", "Confirm the public request details."),
                        new HumanTaskResponseSchema("application/json",
                                HumanTaskConfirmationPresentation.RESPONSE_SCHEMA, "1", PayloadKind.SCALAR,
                                4096),
                        HandlerAuthorization.ofRoles("REVIEWER"), requester(key),
                        new GraphVersionPin("graph-v1"),
                        Optional.of(clock.instant().plus(Duration.ofMinutes(10))),
                        clock.instant().plus(Duration.ofMinutes(30)),
                        new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"),
                        HumanTaskPolicy.DEFAULTS.executionLimits(4096), 2, continuation,
                        digestOf(continuation),
                        // An embedded confirmation, so the human-task family in the dump covers the
                        // presentation columns as well as the task itself.
                        new HumanTaskConfirmationPresentation(HumanTaskConfirmationPresentation.VERSION_1,
                                "Confirm after review.", HumanTaskCommentRequirement.OPTIONAL,
                                List.of(HumanTaskConfirmationAction.RESOLVE,
                                        HumanTaskConfirmationAction.DENY,
                                        HumanTaskConfirmationAction.CANCEL),
                                "Confirm", "Deny", "Cancel"),
                        HumanTaskPolicy.DEFAULTS.confirmationLimits()))
                .build()));
        return taskId;
    }

    private static UUID registerExecutionPause(ExecutionStore store, ExecutionKey key) {
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID pauseId = UUID.randomUUID();
        StoredProcessInstance running = runningAttempt(store, key, traversalId, invocationId, attemptId);
        StoredProcessInstance finished = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.COMPLETED))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.COMPLETED))
                .build()));
        byte[] continuation = "{\"attributes\":{},\"payload\":\"held\"}".getBytes(StandardCharsets.UTF_8);
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(finished.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.WAITING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                .registerExecutionPause(new ExecutionPauseRegistration(pauseId, traversalId, invocationId,
                        "next", "PROCESS", "process", requester(key), new GraphVersionPin("graph-v1"), 1,
                        continuation, digestOf(continuation)))
                .build()));
        return pauseId;
    }

    private static void registerAgentBudget(ExecutionStore store, ExecutionKey key) {
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        var maxima = new AgentBudgetVector(100, 100, 100, 100, 100, 100, 5, 10, 10);
        StoredProcessInstance created = await(store.apply(creation(key, traversalId)));
        StoredProcessInstance granted = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "agent", Set.of(), NodeInvocationStatus.SCHEDULED,
                                List.of(), NodeCommand.PROCESS)))
                .applyAgentBudget(new AgentBudgetOperation.RegisterRoot(
                        new AgentAuthorityRootRegistration("runtime-a", 7, requester(key), "policy-v1",
                                "rates-v1", clock.instant().plus(Duration.ofHours(2)),
                                Set.of("tenant:read"), Set.of("tool:a"), maxima, "USD"), 0))
                .applyAgentBudget(new AgentBudgetOperation.RegisterGrant(
                        new AgentAuthorityGrantRegistration(grantId, null, Set.of(), 1,
                                Set.of("tenant:read"), Set.of("tool:a"), maxima, 200,
                                clock.instant().plus(Duration.ofHours(1))),
                        new AgentAuthorityBinding(grantId, "agent", invocationId, Set.of()), 7, 0))
                .build()));
        // A held reservation as well as the grant, so the budget's reserved vector in the dump is not
        // all zeroes and a restore that lost the reservation rows would be visible in the fold.
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(granted.revision()))
                .applyAgentBudget(new AgentBudgetOperation.Hold(new AgentBudgetReservation(UUID.randomUUID(),
                        grantId, "op:" + key.processInstanceId() + ":1",
                        new AgentBudgetVector(1, 2, 3, 0, 0, 1, 0, 0, 0), AgentBudgetVector.ZERO,
                        AgentReservationState.HELD), 7, 0))
                .build()));
    }

    private static StoredProcessInstance runningAttempt(ExecutionStore store, ExecutionKey key,
                                                        UUID traversalId, UUID invocationId,
                                                        UUID attemptId) {
        StoredProcessInstance created = await(store.apply(creation(key, traversalId)));
        StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "work", Set.of(), NodeInvocationStatus.SCHEDULED,
                                List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
        return await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));
    }

    private static ExecutionBatch creation(ExecutionKey key, UUID traversalId) {
        return ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                                Map.of(traversalId, new Traversal(traversalId, "start",
                                        TraversalStatus.ACCEPTED, Map.of()))),
                        new GraphVersionPin("graph-v1")))
                .build();
    }

    private static ExecutionManifest manifest(ExecutionKey key, GraphContentId contentId,
                                              List<PinnedNodePackage> packages) {
        var profile = new ResolvedRuntimeProfile(1, 1, "STRICT", "pass-through", "1".repeat(64),
                "2".repeat(64), "3".repeat(64), "4".repeat(64));
        return new ExecutionManifest(ExecutionManifest.CURRENT_FORMAT_VERSION, key, contentId,
                new GraphDefinitionIdentity(GraphDefinitionIdentity.SUBMISSION_GRAPH_ID,
                        contentId.value()),
                profile, packages, clock.instant());
    }

    private static EventEnvelope event(ExecutionKey key, UUID traversalId, String eventType) {
        return EventEnvelope.of(UUID.randomUUID(), key.tenantId(), eventType, key.processInstanceId(),
                traversalId, null, null, UUID.randomUUID(), "request-1", "graph-v1", clock.instant(),
                OpaquePayload.of(eventType.getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    private static GraphVersion.Content content(String bytes) {
        return new GraphVersion.Content(1, bytes.getBytes(StandardCharsets.UTF_8), "alice", clock.instant());
    }

    private static SecurityContext requester(ExecutionKey key) {
        return new SecurityContext("request", key.tenantId(), "requester", PrincipalType.USER, "issuer");
    }

    private static OpaquePayload payload(String value) {
        return OpaquePayload.of(value.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    // ============================================================ rendering answers as text

    /**
     * Renders one answer as text.
     *
     * <p>{@link String#valueOf} on the record itself wherever nothing reachable from it is an array,
     * because a record's generated {@code toString} is exhaustive and stays exhaustive when a component
     * is added — which is the behaviour this comparison wants. Where an array <em>is</em> reachable it
     * would print an identity hash and two reads of the same row would differ, so those types are
     * rendered field by field with their bytes base64-encoded.</p>
     */
    private static String text(Object answer) {
        return switch (answer) {
            case StoredProcessInstance instance -> "revision=" + instance.revision()
                    + " pin=" + instance.graphVersionPin().reference()
                    + " state=" + instance.state();
            case LeaseHandle lease -> "worker=" + lease.workerId() + " fencing=" + lease.fencingToken()
                    + " claimedAt=" + lease.claimedAt() + " expiresAt=" + lease.expiresAt();
            case IdempotencyRecord record -> "key=" + record.key()
                    + " request=" + record.requestFingerprint() + " outcome=" + record.outcomeRef()
                    + " revision=" + record.recordedAtRevision() + " expiresAt=" + record.expiresAt();
            case JournalRecord record -> "offset=" + record.journalOffset()
                    + " sequence=" + record.streamSequence() + " revision=" + record.committedAtRevision()
                    + " recordedAt=" + record.recordedAt() + " event=" + record.envelope().eventId()
                    + " type=" + record.envelope().eventType()
                    + " digest=" + record.envelope().digest()
                    + " payload=" + bytes(record.envelope().payload().bytes());
            case JournalCursor cursor -> "destination=" + cursor.destination()
                    + " deliveredThrough=" + cursor.deliveredThrough();
            case ProcessInventoryPage page -> "items=" + page.items().stream()
                    .map(PostgresBackupRestoreDrillTest::text).toList()
                    + " nextCursor=" + page.nextCursor().orElse("none");
            case ProcessInventoryEntry entry -> String.valueOf(entry);
            case TraversalInventoryEntry entry -> String.valueOf(entry);
            case DurableExecutionResult result -> "fingerprint=" + result.fingerprint()
                    + " status=" + result.status() + " traversal=" + result.traversalId()
                    + " endedAt=" + result.endedAt() + " retainedUntil=" + result.retainedUntil()
                    + " payloadState=" + result.payload().state();
            case DurableHandler handler -> String.valueOf(handler);
            case DurableToolApproval approval -> "status=" + approval.status()
                    + " actor=" + approval.actor() + " revision=" + approval.revision()
                    + " tool=" + approval.request().tool()
                    + " arguments=" + bytes(approval.request().canonicalArguments())
                    + " argumentsDigest=" + approval.request().argumentsDigest()
                    + " expiresAt=" + approval.request().expiresAt()
                    + " continuation=" + bytes(approval.request().continuation())
                    + " continuationDigest=" + approval.request().continuationDigest();
            case DurableHumanTask task -> "status=" + task.status()
                    + " taskId=" + task.request().taskId()
                    + " generation=" + task.generation() + " revision=" + task.revision()
                    + " correlationKey=" + task.request().correlationKey()
                    + " metadata=" + task.request().metadata()
                    + " presentation=" + task.request().confirmationPresentation()
                    + " limits=" + task.request().confirmationLimits()
                    + " expiresAt=" + task.request().expiresAt()
                    + " continuation=" + bytes(task.request().continuation())
                    + " continuationDigest=" + task.request().continuationDigest();
            case DurableExecutionPause pause -> "status=" + pause.status() + " actor=" + pause.actor()
                    + " revision=" + pause.revision() + " pauseId=" + pause.request().pauseId()
                    + " afterInvocation=" + pause.request().afterInvocationId()
                    + " continuation=" + bytes(pause.request().continuation())
                    + " continuationDigest=" + pause.request().continuationDigest();
            case DurableAgentAuthorityBudget budget -> String.valueOf(budget);
            case StoredGraphDefinition definition -> "contentId=" + definition.key().contentId().value()
                    + " identity=" + definition.identity() + " storedAt=" + definition.storedAt()
                    + " formatVersion=" + definition.canonical().formatVersion()
                    + " document=" + bytes(definition.canonical().bytes());
            case StoredExecutionManifest manifest -> "digest=" + manifest.digest().value()
                    + " committedAt=" + manifest.committedAt()
                    + " graphContentId=" + manifest.manifest().graphContentId().value()
                    + " profile=" + manifest.manifest().runtime()
                    + " packages=" + manifest.manifest().nodePackages();
            case GraphVersion version -> "version=" + version.version()
                    + " digest=" + version.canonicalDigest() + " createdBy=" + version.createdBy()
                    + " createdAt=" + version.createdAt()
                    + " snapshot=" + bytes(version.canonicalSnapshot());
            case DeploymentRegistry.Record record -> String.valueOf(record);
            default -> String.valueOf(answer);
        };
    }

    private static String bytes(byte[] value) {
        return value == null ? "none" : Base64.getEncoder().encodeToString(value);
    }

    private static String digestOf(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String digest(String seed) {
        return digestOf(seed.getBytes(StandardCharsets.UTF_8)).substring("sha256:".length());
    }

    /**
     * The only reads done in SQL, and the four tables they cover.
     *
     * <p>Three of them — {@code work_claim}, {@code timer} and {@code invocation_parent} — have no port
     * reader at all, so a row count is the whole of what can be observed about them. The fourth,
     * {@code deployment_command}, does have one: the ledger replay drives it through the registry. It
     * is counted here as well because a count and a replay answer different questions — that the rows
     * survived, and that replaying one of them still yields the outcome it recorded.</p>
     */
    private static long countRows(DataSource dataSource, String table) {
        // The table name is a literal from this class, never a caller-supplied value, which is what
        // makes concatenating it here different from every other statement in this module.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : -1L;
        } catch (SQLException failed) {
            throw new IllegalStateException("could not count " + table, failed);
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionManifestStoreFailure manifestFailureOf(Runnable operation) {
        RuntimeException thrown = assertThrows(RuntimeException.class, operation::run);
        ExecutionManifestStoreException classified = ExecutionManifestStoreException.unwrap(thrown);
        assertTrue(classified != null, () -> "the failure must arrive classified, got " + thrown);
        return classified.failure();
    }
}
