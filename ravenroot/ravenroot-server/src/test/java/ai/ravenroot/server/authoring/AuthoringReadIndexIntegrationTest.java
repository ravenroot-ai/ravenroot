package ai.ravenroot.server.authoring;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Actual SQL backends with deterministic provider fixtures. Never contacts a production provider.
 */
@org.junit.jupiter.api.Timeout(120)
class AuthoringReadIndexIntegrationTest {
    static PostgreSQLContainer<?> postgres;
    @TempDir Path temporary;
    static final Actor ACTOR = new Actor("tenant-a", "fixture-reader");

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void allFourCombinationsIncrementallyReopenAndStressReaders() throws Exception {
        for (String database : List.of("sqlite", "postgresql"))
            for (boolean github : List.of(true, false))
                for (int count : new int[] {10, 100, 1000}) {
                    var stores = stores(database);
                    var fixture = new Fixture(github, count);
                    long beforeMemory = usedMemory();
                    long beforeCpu = processCpu();
                    long cold = System.nanoTime();
                    String originalCursor;
                    try (var repository = fixture.repository(stores.get())) {
                        var first = repository.list(ACTOR, "").toCompletableFuture().join();
                        originalCursor = first.nextCursor();
                        long coldNanos = System.nanoTime() - cold;
                        int coldCalls = fixture.calls();
                        assertEquals(count, fixture.blobs());
                        assertEquals(count, all(repository).size());
                        int blobs = fixture.blobs();
                        benchmark(repository, fixture, database, count, "warm");
                        assertEquals(
                                blobs,
                                fixture.blobs(),
                                "indexed lists/open must reuse exact content");
                        // Exact constant validation cost per independent first page: repository
                        // metadata + two refs.
                        int before = fixture.calls();
                        repository.list(ACTOR, "").toCompletableFuture().join();
                        assertEquals(3, fixture.calls() - before);
                        for (int readers : new int[] {1, 10, 50}) {
                            concurrentBenchmark(repository, fixture, database, count, readers);
                        }
                        System.out.printf(
                                "INDEX_FIXTURE db=%s provider=%s graphs=%d phase=cold samples=1"
                                    + " list_ms=%.3f provider_calls=%d bodies=%d catalog_entries=0"
                                    + " heap_used_delta_bytes=%d retained_bytes=%d"
                                    + " process_cpu_ms=%.3f index_storage_bytes=%d"
                                    + " process_rss_bytes=%d%n",
                                database,
                                fixture.provider(),
                                count,
                                coldNanos / 1e6,
                                coldCalls,
                                blobs,
                                usedMemory() - beforeMemory,
                                fixture.index.retainedBytes(),
                                (processCpu() - beforeCpu) / 1e6,
                                stores.physicalBytes().getAsLong(),
                                processRss());
                    }
                    int calls = fixture.calls(), blobs = fixture.blobs();
                    try (var reopened = fixture.repository(stores.get())) {
                        var page = reopened.list(ACTOR, "").toCompletableFuture().join();
                        assertEquals(3, fixture.calls() - calls);
                        assertEquals(blobs, fixture.blobs());
                        if (!originalCursor.isEmpty())
                            assertFalse(
                                    reopened.list(ACTOR, originalCursor)
                                            .toCompletableFuture()
                                            .join()
                                            .items()
                                            .isEmpty());
                        benchmark(reopened, fixture, database, count, "restart");
                        int previous = fixture.blobs();
                        fixture.change("g0000.graphml", graph("g0", "changed"));
                        long update = System.nanoTime();
                        var changed = reopened.list(ACTOR, "").toCompletableFuture().join();
                        assertEquals(
                                previous + 1,
                                fixture.blobs(),
                                "one-document change must download exactly one blob");
                        long updateNanos = System.nanoTime() - update;
                        assertEquals(count, all(reopened).size());
                        System.out.printf(
                                "INDEX_FIXTURE db=%s provider=%s graphs=%d phase=one-document"
                                    + " samples=1 list_ms=%.3f new_bodies=%d retained_bytes=%d%n",
                                database,
                                fixture.provider(),
                                count,
                                updateNanos / 1e6,
                                fixture.blobs() - previous,
                                fixture.index.retainedBytes());
                        // Continuation remains pinned despite insertion/deletion and a newly
                        // published generation.
                        String cursor = changed.nextCursor();
                        var expected = all(reopened);
                        fixture.change("g0001.graphml", null);
                        fixture.change("aaa.graphml", graph("added", "inserted"));
                        reopened.list(ACTOR, "").toCompletableFuture().join();
                        if (!cursor.isEmpty()) {
                            var pinned = new ArrayList<String>();
                            changed.items().forEach(value -> pinned.add(value.documentId()));
                            while (!cursor.isEmpty()) {
                                var next =
                                        reopened.list(ACTOR, cursor).toCompletableFuture().join();
                                next.items().forEach(value -> pinned.add(value.documentId()));
                                cursor = next.nextCursor();
                            }
                            assertEquals(expected, pinned);
                            assertEquals(pinned.size(), new HashSet<>(pinned).size());
                        }
                        assertEquals(count, all(reopened).size());
                        fixture.unauthorized(true);
                        assertFailure(
                                GraphAuthoringException.Failure.UNAUTHORIZED,
                                () ->
                                        reopened.list(ACTOR, page.nextCursor())
                                                .toCompletableFuture()
                                                .join());
                        assertFailure(
                                GraphAuthoringException.Failure.UNAUTHORIZED,
                                () ->
                                        reopened.open(ACTOR, "g0000.graphml", false)
                                                .toCompletableFuture()
                                                .join());
                    }
                }
    }

    @Test
    void independentPostgresInstancesPublishAtomicallyUnderOverlap() throws Exception {
        var stores = stores("postgresql");
        var fixture = new Fixture(true, 100);
        try (var one = fixture.repository(stores.get());
                var two = fixture.repository(stores.get())) {
            var tasks = new ArrayList<CompletableFuture<?>>();
            for (int i = 0; i < 50; i++)
                tasks.add((i % 2 == 0 ? one : two).list(ACTOR, "").toCompletableFuture());
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                    .get(60, TimeUnit.SECONDS);
            assertEquals(100, all(one).size());
            assertEquals(100, all(two).size());
            fixture.change("g0000.graphml", graph("g0", "new"));
            var a = one.list(ACTOR, "").toCompletableFuture();
            var b = two.list(ACTOR, "").toCompletableFuture();
            CompletableFuture.allOf(a, b).join();
            assertEquals(a.join().items(), b.join().items());
            assertFailure(
                    GraphAuthoringException.Failure.UNAUTHORIZED,
                    () ->
                            two.list(new Actor("tenant-unknown", "reader"), "")
                                    .toCompletableFuture()
                                    .join());
        }
    }

    @Test
    void interruptedGenerationAndSchemaFailurePreservePriorProjection() throws Exception {
        for (String database : List.of("sqlite", "postgresql")) {
            var stores = stores(database);
            var fixture = new Fixture(true, 10);
            try (var repository = fixture.repository(stores.get())) {
                var page = repository.list(ACTOR, "").toCompletableFuture().join();
                String repositoryKey =
                        new AuthoringIndexReader(
                                        fixture.index,
                                        GithubAuthoringRepositoryTest.configuration())
                                .repository();
                var before = fixture.index.latest(repositoryKey, ACTOR.tenantId()).orElseThrow();
                var entries = fixture.index.entries(repositoryKey, ACTOR.tenantId(), before.id());
                var duplicate = new ArrayList<>(entries);
                duplicate.add(entries.getFirst());
                assertThrows(
                        GraphAuthoringException.class,
                        () ->
                                fixture.index.publish(
                                        repositoryKey,
                                        ACTOR.tenantId(),
                                        new GraphAuthoringReadIndex.Generation(
                                                UUID.randomUUID().toString(),
                                                "new",
                                                "",
                                                "absent",
                                                System.currentTimeMillis()),
                                        duplicate));
                assertEquals(
                        before,
                        fixture.index.latest(repositoryKey, ACTOR.tenantId()).orElseThrow());
                assertEquals(
                        page.items(),
                        fixture.index
                                .page(repositoryKey, ACTOR.tenantId(), before.id(), 0, 50)
                                .items());
                fixture.change(
                        "g0000.graphml",
                        "malformed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertFailure(
                        GraphAuthoringException.Failure.INVALID_DOCUMENT,
                        () -> repository.list(ACTOR, "").toCompletableFuture().join());
                assertEquals(
                        before,
                        fixture.index.latest(repositoryKey, ACTOR.tenantId()).orElseThrow());
                fixture.change("g0000.graphml", graph("g0", "repaired"));
                assertEquals(10, all(repository).size());
            }
        }
    }

    @Test
    void mixedWritesKeepExpectedRevisionChecksAcrossIndependentInstances() throws Exception {
        for (String database : List.of("sqlite", "postgresql"))
            for (boolean github : List.of(true, false)) {
                var stores = stores(database);
                var fixture = new Fixture(github, 10);
                try (var one = fixture.repository(stores.get());
                        var two = fixture.repository(stores.get())) {
                    var current =
                            one.open(ACTOR, "g0000.graphml", false).toCompletableFuture().join();
                    var readers = new ArrayList<CompletableFuture<?>>();
                    for (int i = 0; i < 50; i++)
                        readers.add((i % 2 == 0 ? one : two).list(ACTOR, "").toCompletableFuture());
                    var request =
                            new SaveRequest(
                                    "g0000.graphml",
                                    graph("g0", "mixed-write"),
                                    current.summary().revision(),
                                    "mixed-write-key-0000001");
                    one.save(ACTOR, request).toCompletableFuture().join();
                    CompletableFuture.allOf(readers.toArray(CompletableFuture[]::new))
                            .get(30, TimeUnit.SECONDS);
                    assertFailure(
                            GraphAuthoringException.Failure.CONFLICT,
                            () ->
                                    two.save(
                                                    ACTOR,
                                                    new SaveRequest(
                                                            "g0000.graphml",
                                                            graph("g0", "stale"),
                                                            current.summary().revision(),
                                                            "mixed-stale-key-000001"))
                                            .toCompletableFuture()
                                            .join());
                    assertTrue(
                            new String(
                                            two.open(ACTOR, "g0000.graphml", false)
                                                    .toCompletableFuture()
                                                    .join()
                                                    .graphMl())
                                    .contains("mixed-write"));
                    assertEquals(10, all(two).size());
                }
            }
    }

    @Test
    void successfulRemoteWriteRepairsAfterUnavailableProjectionWithoutReportingWriteFailure()
            throws Exception {
        for (String database : List.of("sqlite", "postgresql")) {
            for (boolean github : List.of(true, false)) {
                var stores = stores(database);
                var fixture = new Fixture(github, 10);
                var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
                var stored = stores.get();
                var failing =
                        new GraphAuthoringReadIndex() {
                            private void check() {
                                if (unavailable.get())
                                    throw new GraphAuthoringException(
                                            GraphAuthoringException.Failure.UNAVAILABLE);
                            }

                            public Optional<Generation> latest(String r, String t) {
                                check();
                                return stored.latest(r, t);
                            }

                            public List<Entry> entries(String r, String t, String g) {
                                check();
                                return stored.entries(r, t, g);
                            }

                            public Page<DocumentSummary> page(
                                    String r, String t, String g, int p, int z) {
                                check();
                                return stored.page(r, t, g, p, z);
                            }

                            public void publish(String r, String t, Generation g, List<Entry> e) {
                                check();
                                stored.publish(r, t, g, e);
                            }

                            public Optional<byte[]> content(String r, String i) {
                                check();
                                return stored.content(r, i);
                            }

                            public void retainContent(String r, String i, byte[] b) {
                                check();
                                stored.retainContent(r, i, b);
                            }

                            public long retainedBytes() {
                                return stored.retainedBytes();
                            }

                            public void close() {
                                stored.close();
                            }
                        };
                try (var repository = fixture.repository(failing)) {
                    repository.list(ACTOR, "").toCompletableFuture().join();
                    var original =
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join();
                    unavailable.set(true);
                    var saved =
                            repository
                                    .save(
                                            ACTOR,
                                            new SaveRequest(
                                                    "g0000.graphml",
                                                    graph("g0", "remote-success"),
                                                    original.summary().revision(),
                                                    "projection-failure-key-0001"))
                                    .toCompletableFuture()
                                    .join();
                    assertTrue(new String(saved.graphMl()).contains("remote-success"));
                    assertFailure(
                            GraphAuthoringException.Failure.UNAVAILABLE,
                            () -> repository.list(ACTOR, "").toCompletableFuture().join());
                }
                try (var repaired = fixture.repository(stores.get())) {
                    assertEquals(10, all(repaired).size());
                    assertTrue(
                            new String(
                                            repaired.open(ACTOR, "g0000.graphml", false)
                                                    .toCompletableFuture()
                                                    .join()
                                                    .graphMl())
                                    .contains("remote-success"));
                }
            }
        }
    }

    @Test
    void forcePushAndBranchDeletionReconcileAndExpiredGenerationsFailExplicitly() throws Exception {
        for (String database : List.of("sqlite", "postgresql")) {
            var stores = stores(database);
            var fixture = new Fixture(true, 100);
            try (var repository = fixture.repository(stores.get())) {
                var before = repository.list(ACTOR, "").toCompletableFuture().join();
                String original = before.items().getFirst().revision().draft().split(":")[0];
                fixture.change("g0000.graphml", graph("g0", "rewritten"));
                repository.list(ACTOR, "").toCompletableFuture().join();
                fixture.gh.resetDraft(original);
                assertEquals(
                        before.items(),
                        repository.list(ACTOR, "").toCompletableFuture().join().items());
                fixture.gh.deleteDraftBranch();
                assertTrue(
                        repository.list(ACTOR, "").toCompletableFuture().join().items().isEmpty());
                assertFalse(
                        repository
                                .list(ACTOR, before.nextCursor())
                                .toCompletableFuture()
                                .join()
                                .items()
                                .isEmpty());
            }
            try (var index = stores.get()) {
                String first = UUID.randomUUID().toString();
                long now = System.currentTimeMillis();
                for (int i = 0; i < 17; i++)
                    index.publish(
                            "expiry",
                            "tenant",
                            new GraphAuthoringReadIndex.Generation(
                                    i == 0 ? first : UUID.randomUUID().toString(),
                                    "",
                                    "",
                                    "",
                                    now + i),
                            List.of());
                assertFailure(
                        GraphAuthoringException.Failure.INVALID_DOCUMENT,
                        () -> index.page("expiry", "tenant", first, 0, 50));
            }
        }
    }

    @Test
    void githubAppCredentialsGatePersistedReopenForBothDatabases() throws Exception {
        for (String database : List.of("sqlite", "postgresql")) {
            var stores = stores(database);
            var api = new GithubAuthoringRepositoryTest.ScriptedGithub();
            api.seed(100);
            var environment = GithubAuthoringRepositoryTest.environment();
            environment.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "APP");
            environment.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_APP_ID", "1");
            environment.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_INSTALLATION_ID", "1");
            var secret =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            AuthoringReadIdentityTest.key());
            var configuration = GraphAuthoringConfiguration.fromEnvironment(environment);
            for (int reopen = 0; reopen < 2; reopen++) {
                try (var repository =
                        new GithubAuthoringRepository(
                                configuration,
                                ignored ->
                                        Optional.ofNullable(secret.get())
                                                .map(
                                                        value ->
                                                                new ai.ravenroot.api.security
                                                                        .SecretValue(
                                                                        value.toCharArray())),
                                PublicationEvidence.absent(),
                                ignored -> {},
                                api,
                                java.time.Clock.systemUTC())) {
                    repository.useReadIndex(stores.get());
                    int bodies = api.benchmarkBlobs.get();
                    repository.list(ACTOR, "").toCompletableFuture().join();
                    if (reopen == 1) assertEquals(bodies, api.benchmarkBlobs.get());
                    secret.set(AuthoringReadIdentityTest.key());
                    repository.list(ACTOR, "").toCompletableFuture().join();
                    secret.set(null);
                    assertFailure(
                            GraphAuthoringException.Failure.UNAVAILABLE,
                            () ->
                                    repository
                                            .open(ACTOR, "g0000.graphml", false)
                                            .toCompletableFuture()
                                            .join());
                    secret.set(AuthoringReadIdentityTest.key());
                }
            }
        }
    }

    @Test
    void nonzeroPublicationWorkloadRemainsBoundedAcrossReopenAndCatalogOnlyChanges()
            throws Exception {
        for (String database : List.of("sqlite", "postgresql"))
            for (boolean github : List.of(true, false)) {
                var stores = stores(database);
                var fixture = new Fixture(github, 20);
                var artifacts = new PublishedGraphArtifactCatalogTest.ArtifactClient();
                for (int i = 0; i < 20; i++) {
                    PublishedGraphArtifactCatalogTest.addRelease(
                            artifacts, "g" + i, 1, "publication-fixture-" + i, "a".repeat(40));
                    fixture.change(
                            "g%04d.graphml".formatted(i),
                            artifacts.objects.get("graphs/g" + i + "/1.graphml"));
                }
                if (github) fixture.gh.mergeDraftToMain();
                else fixture.gl.merge();
                String release;
                try (var repository = fixture.repository(new VolatileGraphAuthoringReadIndex())) {
                    release =
                            repository
                                    .open(ACTOR, "g0000.graphml", true)
                                    .toCompletableFuture()
                                    .join()
                                    .summary()
                                    .revision()
                                    .release()
                                    .split(":")[0];
                }
                artifacts.entries.clear();
                artifacts.objects.clear();
                for (int i = 0; i < 20; i++)
                    PublishedGraphArtifactCatalogTest.addRelease(
                            artifacts, "g" + i, 1, "publication-fixture-" + i, release);
                artifacts.catalog();
                for (String phase : List.of("warm", "restart")) {
                    var catalog =
                            new PublishedGraphArtifactCatalog(
                                    PublishedGraphArtifactCatalogTest.configuration(), artifacts);
                    try (var repository = fixture.repository(stores.get(), catalog)) {
                        int firstCalls = fixture.calls(), firstBodies = fixture.blobs();
                        var first = repository.list(ACTOR, "").toCompletableFuture().join();
                        if (phase.equals("restart")) {
                            assertEquals(3, fixture.calls() - firstCalls);
                            assertEquals(0, fixture.blobs() - firstBodies);
                            assertEquals(1, catalog.providerCalls());
                        }
                        assertTrue(first.items().stream().allMatch(DocumentSummary::published));
                        int calls = fixture.calls(), bodies = fixture.blobs();
                        long catalogCalls = catalog.providerCalls();
                        long[] times = new long[10];
                        for (int i = 0; i < 10; i++) {
                            long start = System.nanoTime();
                            assertTrue(
                                    repository
                                            .list(ACTOR, "")
                                            .toCompletableFuture()
                                            .join()
                                            .items()
                                            .stream()
                                            .allMatch(DocumentSummary::published));
                            times[i] = System.nanoTime() - start;
                        }
                        Arrays.sort(times);
                        assertEquals(30, fixture.calls() - calls);
                        assertEquals(10, catalog.providerCalls() - catalogCalls);
                        assertEquals(bodies, fixture.blobs());
                        System.out.printf(
                                "PUBLICATION_FIXTURE db=%s provider=%s graphs=20 released=20"
                                    + " catalog_entries=20 phase=%s operation=list samples=10"
                                    + " p50_ms=%.3f p95_ms=%.3f provider_calls=%d catalog_calls=%d"
                                    + " bodies=%d index_storage_bytes=%d%n",
                                database,
                                fixture.provider(),
                                phase,
                                times[4] / 1e6,
                                times[9] / 1e6,
                                fixture.calls() - calls,
                                catalog.providerCalls() - catalogCalls,
                                fixture.blobs() - bodies,
                                stores.physicalBytes().getAsLong());
                        if (phase.equals("restart")) {
                            artifacts.entries.clear();
                            artifacts.catalog();
                            calls = fixture.calls();
                            bodies = fixture.blobs();
                            catalogCalls = catalog.providerCalls();
                            long start = System.nanoTime();
                            assertTrue(
                                    repository
                                            .list(ACTOR, "")
                                            .toCompletableFuture()
                                            .join()
                                            .items()
                                            .stream()
                                            .noneMatch(DocumentSummary::published));
                            assertEquals(0, fixture.blobs() - bodies);
                            assertEquals(1, catalog.providerCalls() - catalogCalls);
                            System.out.printf(
                                    "PUBLICATION_FIXTURE db=%s provider=%s graphs=20 released=20"
                                            + " catalog_entries=0 phase=publication-only samples=1"
                                            + " list_ms=%.3f provider_calls=%d catalog_calls=%d"
                                            + " bodies=%d%n",
                                    database,
                                    fixture.provider(),
                                    (System.nanoTime() - start) / 1e6,
                                    fixture.calls() - calls,
                                    catalog.providerCalls() - catalogCalls,
                                    fixture.blobs() - bodies);
                        }
                    }
                }
            }
    }

    @Test
    void additiveMigrationReopenFutureSchemaRefusalAndRecovery() throws Exception {
        for (boolean pg : List.of(false, true)) {
            javax.sql.DataSource source;
            if (pg) {
                var candidate = new PGSimpleDataSource();
                candidate.setURL(postgres.getJdbcUrl());
                candidate.setUser(postgres.getUsername());
                candidate.setPassword(postgres.getPassword());
                String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
                try (var connection = candidate.getConnection();
                        var statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema);
                }
                candidate.setCurrentSchema(schema);
                source = candidate;
            } else {
                var candidate = new org.sqlite.SQLiteDataSource();
                candidate.setUrl("jdbc:sqlite:" + temporary.resolve(UUID.randomUUID() + ".db"));
                source = candidate;
            }
            try (var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE unrelated_owner_data(value TEXT)");
                statement.execute("INSERT INTO unrelated_owner_data VALUES('preserved')");
                statement.execute(
                        "CREATE TABLE authoring_read_schema(version INTEGER PRIMARY KEY)");
                statement.execute("INSERT INTO authoring_read_schema VALUES(1)");
            }
            try (var index = new JdbcGraphAuthoringReadIndex(source, pg, null)) {
                assertTrue(index.latest("fixture", "tenant").isEmpty());
            }
            try (var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("INSERT INTO authoring_read_schema VALUES(2)");
            }
            assertThrows(
                    GraphAuthoringException.class,
                    () -> new JdbcGraphAuthoringReadIndex(source, pg, null));
            try (var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DELETE FROM authoring_read_schema WHERE version=2");
                try (var rows = statement.executeQuery("SELECT value FROM unrelated_owner_data")) {
                    assertTrue(rows.next());
                    assertEquals("preserved", rows.getString(1));
                }
            }
            try (var reopened = new JdbcGraphAuthoringReadIndex(source, pg, null)) {
                assertTrue(reopened.latest("fixture", "tenant").isEmpty());
            }
        }
    }

    record Stores(Supplier<GraphAuthoringReadIndex> factory, LongSupplier physicalBytes)
            implements Supplier<GraphAuthoringReadIndex> {
        public GraphAuthoringReadIndex get() {
            return factory.get();
        }
    }

    Stores stores(String database) throws Exception {
        if (database.equals("sqlite")) {
            Path file = temporary.resolve(UUID.randomUUID() + ".db");
            return new Stores(
                    () -> new SqliteGraphAuthoringReadIndex(file),
                    () -> {
                        try {
                            long total = 0;
                            for (String suffix : List.of("", "-wal", "-shm")) {
                                Path part = Path.of(file.toString() + suffix);
                                if (java.nio.file.Files.exists(part))
                                    total += java.nio.file.Files.size(part);
                            }
                            return total;
                        } catch (java.io.IOException failure) {
                            throw new AssertionError(failure);
                        }
                    });
        }
        var source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        String schema = "authoring_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        source.setCurrentSchema(schema);
        return new Stores(
                () -> {
                    var pool =
                            ai.ravenroot.server.persistence.SharedExecutionStoreDataSource.open(
                                    new ai.ravenroot.server.persistence.SharedStoreConnection(
                                            source.getURL(),
                                            Optional.of(postgres.getUsername()),
                                            Optional.of(postgres.getPassword()),
                                            4,
                                            java.time.Duration.ofSeconds(5)));
                    return new PostgresGraphAuthoringReadIndex(pool.dataSource(), pool);
                },
                () -> {
                    try (var connection = source.getConnection();
                            var statement =
                                    connection.prepareStatement(
                                            "SELECT COALESCE(SUM(pg_total_relation_size(c.oid)),0)"
                                                    + " FROM pg_class c JOIN pg_namespace n ON"
                                                    + " n.oid=c.relnamespace WHERE n.nspname=? AND"
                                                    + " c.relkind='r' AND c.relname LIKE"
                                                    + " 'authoring_read_%'")) {
                        statement.setString(1, schema);
                        try (var rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                            return rows.getLong(1);
                        }
                    } catch (java.sql.SQLException failure) {
                        throw new AssertionError(failure);
                    }
                });
    }

    static final class Fixture {
        final boolean github;
        final GithubAuthoringRepositoryTest.ScriptedGithub gh;
        final GitlabAuthoringRepositoryTest.Gitlab gl;
        GraphAuthoringReadIndex index;

        Fixture(boolean github, int count) {
            this.github = github;
            gh = github ? new GithubAuthoringRepositoryTest.ScriptedGithub() : null;
            gl = github ? null : new GitlabAuthoringRepositoryTest.Gitlab();
            if (github) gh.seed(count);
            else gl.seed(count);
        }

        GraphAuthoringRepository repository(GraphAuthoringReadIndex index) {
            return repository(index, PublicationEvidence.absent());
        }

        GraphAuthoringRepository repository(
                GraphAuthoringReadIndex index, PublicationEvidence publications) {
            this.index = index;
            if (github) {
                var repository = GithubAuthoringRepositoryTest.repository(gh, publications);
                repository.useReadIndex(index);
                return repository;
            }
            var repository = GitlabAuthoringRepositoryTest.repository(gl, publications);
            repository.useReadIndex(index);
            return repository;
        }

        int calls() {
            return github ? gh.benchmarkRequests.get() : gl.benchmarkRequests.get();
        }

        int blobs() {
            return github ? gh.benchmarkBlobs.get() : gl.benchmarkBlobs.get();
        }

        long responseBytes() {
            return github ? gh.benchmarkResponseBytes.get() : gl.benchmarkResponseBytes.get();
        }

        long documentBytes() {
            return github ? gh.benchmarkDocumentBytes.get() : gl.benchmarkDocumentBytes.get();
        }

        String provider() {
            return github ? "github" : "gitlab";
        }

        void change(String name, byte[] bytes) {
            if (github) gh.external("graphs/customers/a/" + name, bytes);
            else gl.external("graphs/customers/a/" + name, bytes);
        }

        void unauthorized(boolean value) {
            if (github) gh.unauthorized = value;
            else gl.unauthorized = value;
        }
    }

    static List<String> all(GraphAuthoringRepository repository) {
        var result = new ArrayList<String>();
        String cursor = "";
        do {
            var page = repository.list(ACTOR, cursor).toCompletableFuture().join();
            page.items().forEach(value -> result.add(value.documentId()));
            cursor = page.nextCursor();
        } while (!cursor.isEmpty());
        return result;
    }

    static byte[] graph(String id, String label) {
        return GraphReleaseMetadata.assign(
                        ("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"><graph id=\""
                                        + id
                                        + "\" edgedefault=\"directed\"><desc>"
                                        + label
                                        + "</desc></graph></graphml>")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        id,
                        1)
                .graphMl();
    }

    static long usedMemory() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    static long processRss() {
        try {
            var process =
                    new ProcessBuilder(
                                    "ps",
                                    "-o",
                                    "rss=",
                                    "-p",
                                    Long.toString(ProcessHandle.current().pid()))
                            .start();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            return Long.parseLong(new String(process.getInputStream().readAllBytes()).trim())
                    * 1024;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    static long processCpu() {
        return ((com.sun.management.OperatingSystemMXBean)
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    static void concurrentBenchmark(
            GraphAuthoringRepository repository,
            Fixture fixture,
            String database,
            int graphs,
            int readers)
            throws Exception {
        for (String operation : List.of("list", "open", "history")) {
            try (var readersExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new ArrayList<CompletableFuture<Long>>();
                int calls = fixture.calls(), bodies = fixture.blobs();
                long responseBytes = fixture.responseBytes(),
                        documentBytes = fixture.documentBytes();
                long cpu = processCpu();
                for (int i = 0; i < readers; i++)
                    tasks.add(
                            CompletableFuture.supplyAsync(
                                    () -> {
                                        long start = System.nanoTime();
                                        switch (operation) {
                                            case "list" ->
                                                    repository
                                                            .list(ACTOR, "")
                                                            .toCompletableFuture()
                                                            .join();
                                            case "open" ->
                                                    repository
                                                            .open(ACTOR, "g0000.graphml", false)
                                                            .toCompletableFuture()
                                                            .join();
                                            default ->
                                                    repository
                                                            .history(ACTOR, "g0000.graphml", "")
                                                            .toCompletableFuture()
                                                            .join();
                                        }
                                        return System.nanoTime() - start;
                                    },
                                    readersExecutor));
                CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                        .get(30, TimeUnit.SECONDS);
                long[] times = tasks.stream().mapToLong(CompletableFuture::join).sorted().toArray();
                System.out.printf(
                        "INDEX_FIXTURE db=%s provider=%s graphs=%d phase=concurrent operation=%s"
                            + " readers=%d samples=%d p50_ms=%.3f p95_ms=%.3f provider_calls=%d"
                            + " bodies=%d process_cpu_ms=%.3f response_body_bytes=%d"
                            + " document_bytes=%d%n",
                        database,
                        fixture.provider(),
                        graphs,
                        operation,
                        readers,
                        readers,
                        times[(times.length - 1) / 2] / 1e6,
                        times[(int) Math.ceil(times.length * .95) - 1] / 1e6,
                        fixture.calls() - calls,
                        fixture.blobs() - bodies,
                        (processCpu() - cpu) / 1e6,
                        fixture.responseBytes() - responseBytes,
                        fixture.documentBytes() - documentBytes);
            }
        }
    }

    static void benchmark(
            GraphAuthoringRepository repository,
            Fixture fixture,
            String database,
            int graphs,
            String phase) {
        for (String operation : List.of("list", "open", "history")) {
            long[] times = new long[10];
            int calls = fixture.calls(), blobs = fixture.blobs();
            long responseBytes = fixture.responseBytes(), documentBytes = fixture.documentBytes();
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                switch (operation) {
                    case "list" -> repository.list(ACTOR, "").toCompletableFuture().join();
                    case "open" ->
                            repository
                                    .open(ACTOR, "g0000.graphml", false)
                                    .toCompletableFuture()
                                    .join();
                    default ->
                            repository
                                    .history(ACTOR, "g0000.graphml", "")
                                    .toCompletableFuture()
                                    .join();
                }
                times[i] = System.nanoTime() - start;
            }
            Arrays.sort(times);
            System.out.printf(
                    "INDEX_FIXTURE db=%s provider=%s graphs=%d phase=%s operation=%s samples=10"
                            + " p50_ms=%.3f p95_ms=%.3f provider_calls=%d bodies=%d"
                            + " catalog_entries=0 response_body_bytes=%d document_bytes=%d%n",
                    database,
                    fixture.provider(),
                    graphs,
                    phase,
                    operation,
                    times[4] / 1e6,
                    times[9] / 1e6,
                    fixture.calls() - calls,
                    fixture.blobs() - blobs,
                    fixture.responseBytes() - responseBytes,
                    fixture.documentBytes() - documentBytes);
        }
    }

    static void assertFailure(GraphAuthoringException.Failure expected, Runnable action) {
        Throwable failure = assertThrows(Throwable.class, action::run);
        while (!(failure instanceof GraphAuthoringException) && failure.getCause() != null)
            failure = failure.getCause();
        assertInstanceOf(GraphAuthoringException.class, failure);
        assertEquals(expected, ((GraphAuthoringException) failure).failure());
    }
}
