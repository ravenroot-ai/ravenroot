package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.programming.ArtifactRegistry;
import ai.ravenroot.api.programming.ArtifactReservation;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramArtifactIdentity;
import ai.ravenroot.api.programming.ProgramBuildNodePlan;
import ai.ravenroot.api.programming.ProgramBuildNodeSnapshot;
import ai.ravenroot.api.programming.ProgramBuildPhase;
import ai.ravenroot.api.programming.ProgramBuildSnapshot;
import ai.ravenroot.api.programming.ProgramTestPayload;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable SQLite authority for tenant-scoped, content-addressed program qualification. */
public final class SqliteArtifactRegistry implements ArtifactRegistry, AutoCloseable {
    public static final String FILE_NAME = "ravenroot-program-artifacts.db";
    private static final int SCHEMA_VERSION = 3;
    private static final String OWNER = AuthorizedRavenrootApplication.OWNER_TENANT_METADATA;
    private static final String CREATOR = AuthorizedRavenrootApplication.CREATOR_METADATA;
    private static final Map<ArtifactState, EnumSet<ArtifactState>> TRANSITIONS = transitions();
    private static final String CREATE_TABLE = """
            CREATE TABLE program_artifact (
                tenant TEXT NOT NULL,
                id TEXT NOT NULL PRIMARY KEY,
                language TEXT NOT NULL,
                source_utf8 BLOB NOT NULL,
                source_digest BLOB NOT NULL CHECK(length(source_digest)=32),
                digest_format INTEGER NOT NULL,
                state TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK(revision > 0),
                compatibility_fingerprint TEXT NOT NULL,
                payload_digest BLOB CHECK(payload_digest IS NULL OR length(payload_digest)=32),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                creator_identity TEXT NOT NULL,
                approver_identity TEXT NOT NULL,
                validation_evidence TEXT NOT NULL,
                smoke_evidence TEXT NOT NULL,
                approval_evidence TEXT NOT NULL,
                activation_evidence TEXT NOT NULL,
                retirement_evidence TEXT NOT NULL,
                metadata TEXT NOT NULL,
                UNIQUE(tenant, source_digest)
            ) WITHOUT ROWID
            """;
    private static final String CREATE_BUILD_TABLE = """
            CREATE TABLE program_build (
                id TEXT NOT NULL PRIMARY KEY,
                tenant TEXT NOT NULL,
                request_digest BLOB NOT NULL CHECK(length(request_digest)=32),
                dual_control INTEGER NOT NULL CHECK(dual_control IN (0,1)),
                revision INTEGER NOT NULL CHECK(revision > 0),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                terminal INTEGER NOT NULL CHECK(terminal IN (0,1)),
                metadata TEXT NOT NULL
            ) WITHOUT ROWID
            """;
    private static final String CREATE_BUILD_NODE_TABLE = """
            CREATE TABLE program_build_node (
                build_id TEXT NOT NULL,
                tenant TEXT NOT NULL,
                node_id TEXT NOT NULL,
                position INTEGER NOT NULL CHECK(position >= 0),
                language TEXT NOT NULL,
                source_utf8 BLOB NOT NULL,
                source_digest BLOB NOT NULL CHECK(length(source_digest)=32),
                payload_json TEXT NOT NULL,
                payload_digest BLOB NOT NULL CHECK(length(payload_digest)=32),
                artifact_id TEXT NOT NULL,
                phase TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK(revision > 0),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                terminal INTEGER NOT NULL CHECK(terminal IN (0,1)),
                ready INTEGER NOT NULL CHECK(ready IN (0,1)),
                reused INTEGER NOT NULL CHECK(reused IN (0,1)),
                diagnostic TEXT NOT NULL CHECK(length(diagnostic) <= 4096),
                smoke_output_json TEXT NOT NULL CHECK(length(smoke_output_json) <= 262144),
                PRIMARY KEY(build_id, node_id),
                FOREIGN KEY(build_id) REFERENCES program_build(id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """;

    private final Connection connection;
    private final Path databaseFile;
    private final ArtifactProvenanceVerifier verifier;
    private final ConcurrentHashMap<String, ArtifactReservation> reservations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Collection<Runnable>> revocations = new ConcurrentHashMap<>();

    private SqliteArtifactRegistry(Connection connection, Path databaseFile, ArtifactProvenanceVerifier verifier) {
        this.connection = connection;
        this.databaseFile = databaseFile;
        this.verifier = verifier;
    }

    public static SqliteArtifactRegistry openUnder(Path directory, ArtifactProvenanceVerifier verifier) {
        if (directory == null || verifier == null) throw new IllegalArgumentException("directory and verifier are required");
        Path file = directory.toAbsolutePath().normalize().resolve(FILE_NAME);
        Connection opened = null;
        try {
            Files.createDirectories(file.getParent());
            opened = DriverManager.getConnection("jdbc:sqlite:" + file);
            prepare(opened);
            return new SqliteArtifactRegistry(opened, file, verifier);
        } catch (SQLException | java.io.IOException | RuntimeException failure) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new IllegalStateException("cannot open durable program artifact registry at " + file, failure);
        }
    }

    private static void prepare(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) {
                    throw new IllegalStateException("program artifact registry refused WAL mode");
                }
            }
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            integrityCheck(statement);
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : 0;
            }
            if (version > SCHEMA_VERSION) {
                throw new IllegalStateException("program artifact registry schema is newer than this Ravenroot");
            }
            if (version == 0) {
                transaction(connection, () -> {
                    try (Statement migration = connection.createStatement()) {
                        migration.execute(CREATE_TABLE);
                        createBuildTables(migration);
                        migration.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                    }
                });
            } else if (version == 1) {
                migrateV1(connection);
            } else if (version == 2) {
                transaction(connection, () -> {
                    try (Statement migration = connection.createStatement()) {
                        createBuildTables(migration);
                        migration.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                    }
                });
            }
            integrityCheck(statement);
            verifyAllRows(connection);
            verifyAllBuildRows(connection);
        }
    }

    private static void createBuildTables(Statement migration) throws SQLException {
        migration.execute(CREATE_BUILD_TABLE);
        migration.execute(CREATE_BUILD_NODE_TABLE);
        migration.execute("CREATE UNIQUE INDEX active_program_build_request "
                + "ON program_build(tenant, request_digest) WHERE terminal=0");
        migration.execute("CREATE INDEX program_build_node_artifact ON program_build_node(tenant, artifact_id)");
    }

    /**
     * SQLite's integrity check proves that pages and indexes are structurally sound, not that a
     * source blob still matches the authority digest stored beside it. Scan every authority row on
     * open so byte-level source corruption makes startup/readiness fail closed instead of waiting
     * until that particular artifact is selected for execution.
     */
    private static void verifyAllRows(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM program_artifact");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) row(rows);
        }
    }

    private static void verifyAllBuildRows(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM program_build_node");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) buildNodeRow(rows);
        }
    }

    private static void migrateV1(Connection connection) throws SQLException {
        var rows = new java.util.ArrayList<GeneratedArtifact>();
        try (Statement read = connection.createStatement();
             ResultSet result = read.executeQuery("SELECT * FROM program_artifact")) {
            while (result.next()) {
                rows.add(new GeneratedArtifact(result.getString("id"), result.getString("language"),
                        result.getString("sha256"), result.getString("source"),
                        ArtifactState.valueOf(result.getString("state")), result.getLong("revision"),
                        Instant.parse(result.getString("created_at")), Instant.parse(result.getString("updated_at")),
                        decode(result.getString("metadata"))));
            }
        }
        transaction(connection, () -> {
            try (Statement migration = connection.createStatement()) {
                migration.execute("ALTER TABLE program_artifact RENAME TO program_artifact_v1");
                migration.execute(CREATE_TABLE);
            }
            for (GeneratedArtifact artifact : rows) {
                verifyDigest(artifact);
                if (artifact.metadata().getOrDefault(OWNER, "").isBlank()) {
                    throw new IllegalStateException("legacy artifact has no tenant: " + artifact.id());
                }
                insert(connection, artifact);
            }
            try (Statement migration = connection.createStatement()) {
                migration.execute("DROP TABLE program_artifact_v1");
                createBuildTables(migration);
                migration.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
        });
    }

    private static void transaction(Connection connection, SqlWork work) throws SQLException {
        boolean automatic = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            connection.setAutoCommit(automatic);
        }
    }

    private static void integrityCheck(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1)) || result.next()) {
                throw new IllegalStateException("program artifact registry failed SQLite integrity_check");
            }
        }
    }

    public Path databaseFile() { return databaseFile; }

    public synchronized void checkHealth() {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT 1")) {
            if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("artifact store health read failed");
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public synchronized GeneratedArtifact create(String language, String source, Map<String, String> metadata) {
        String tenant = metadata == null ? null : metadata.get(OWNER);
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("artifact tenant is required");
        String normalized = source == null ? "" : source;
        Instant now = Instant.now();
        var artifact = new GeneratedArtifact(UUID.randomUUID().toString(), language,
                ProgramArtifactIdentity.sha256(language, normalized), normalized, ArtifactState.GENERATED, 1,
                now, now, metadata);
        try {
            insert(connection, artifact);
            return artifact;
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override public synchronized Optional<GeneratedArtifact> find(String id) { return Optional.ofNullable(read(id)); }

    @Override
    public synchronized Optional<GeneratedArtifact> findByTenantAndDigest(String tenantId, String sha256) {
        if (tenantId == null || tenantId.isBlank() || sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("tenant and source digest are required");
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM program_artifact WHERE tenant=? AND source_digest=?")) {
            query.setString(1, tenantId);
            query.setBytes(2, parseDigest(sha256));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(row(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public synchronized List<GeneratedArtifact> list() {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM program_artifact");
             ResultSet rows = query.executeQuery()) {
            var result = new java.util.ArrayList<GeneratedArtifact>();
            while (rows.next()) result.add(row(rows));
            return result.stream().sorted(Comparator.comparing(GeneratedArtifact::createdAt)).toList();
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override public GeneratedArtifact transition(String id, ArtifactState expected, ArtifactState target) {
        return transition(id, expected, target, Map.of());
    }

    @Override
    public GeneratedArtifact transition(String id, ArtifactState expected, ArtifactState target,
                                        Map<String, String> evidence) {
        GeneratedArtifact changed;
        synchronized (this) {
            if (reservations.containsKey(id)) throw new IllegalStateException("Artifact lifecycle operation is already in progress");
            changed = update(required(id, expected), target, evidence == null ? Map.of() : evidence, false);
        }
        if (changed.state() == ArtifactState.RETIRED) revoke(id);
        return changed;
    }

    @Override
    public synchronized GeneratedArtifact recordEvidence(String id, long expectedRevision,
                                                         Map<String, String> evidence) {
        if (reservations.containsKey(id)) throw new IllegalStateException("Artifact lifecycle operation is already in progress");
        GeneratedArtifact current = read(id);
        if (current == null) throw new IllegalArgumentException("Unknown artifact: " + id);
        if (current.revision() != expectedRevision) {
            throw new IllegalStateException("Artifact changed while qualification evidence was recorded");
        }
        if (current.state() == ArtifactState.RETIRED) throw new IllegalStateException("Retired artifact qualification is immutable");
        return update(current, current.state(), evidence == null ? Map.of() : evidence, true);
    }

    @Override
    public synchronized ProgramBuildSnapshot startOrFindBuild(
            String tenantId, String requestDigest, boolean dualControl,
            Map<String, String> trustedMetadata, List<ProgramBuildNodePlan> nodes) {
        if (tenantId == null || tenantId.isBlank() || requestDigest == null || requestDigest.isBlank()) {
            throw new IllegalArgumentException("tenant and request digest are required");
        }
        if (nodes == null || nodes.isEmpty() || nodes.size() > 256
                || nodes.stream().map(ProgramBuildNodePlan::nodeId).distinct().count() != nodes.size()) {
            throw new IllegalArgumentException("one to 256 uniquely identified nodes are required");
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id FROM program_build WHERE tenant=? AND request_digest=? AND terminal=0")) {
            query.setString(1, tenantId);
            query.setBytes(2, parseDigest(requestDigest));
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) return requiredBuild(tenantId, rows.getString(1));
            }
        } catch (SQLException failure) {
            throw unavailable(failure);
        }

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try {
            transaction(connection, () -> {
                try (PreparedStatement write = connection.prepareStatement("""
                        INSERT INTO program_build
                        (id,tenant,request_digest,dual_control,revision,created_at,updated_at,terminal,metadata)
                        VALUES (?,?,?,?,1,?,?,0,?)
                        """)) {
                    write.setString(1, id);
                    write.setString(2, tenantId);
                    write.setBytes(3, parseDigest(requestDigest));
                    write.setInt(4, dualControl ? 1 : 0);
                    write.setString(5, now.toString());
                    write.setString(6, now.toString());
                    write.setString(7, encode(trustedMetadata == null ? Map.of() : trustedMetadata));
                    write.executeUpdate();
                }
                try (PreparedStatement write = connection.prepareStatement("""
                        INSERT INTO program_build_node
                        (build_id,tenant,node_id,position,language,source_utf8,source_digest,payload_json,
                         payload_digest,artifact_id,phase,revision,created_at,updated_at,terminal,ready,reused,
                         diagnostic,smoke_output_json)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,1,?,?,0,0,0,'','')
                        """)) {
                    int position = 0;
                    for (ProgramBuildNodePlan node : nodes) {
                        write.setString(1, id);
                        write.setString(2, tenantId);
                        write.setString(3, node.nodeId());
                        write.setInt(4, position++);
                        write.setString(5, node.language());
                        write.setBytes(6, node.source().getBytes(StandardCharsets.UTF_8));
                        write.setBytes(7, parseDigest(node.sourceDigest()));
                        write.setString(8, node.payloadJson());
                        write.setBytes(9, parseDigest(node.payloadDigest()));
                        write.setString(10, "");
                        write.setString(11, ProgramBuildPhase.REGISTER.name());
                        write.setString(12, now.toString());
                        write.setString(13, now.toString());
                        write.addBatch();
                    }
                    write.executeBatch();
                }
            });
            return requiredBuild(tenantId, id);
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public synchronized Optional<ProgramBuildSnapshot> findBuild(String tenantId, String buildId) {
        if (tenantId == null || tenantId.isBlank() || buildId == null || buildId.isBlank()) {
            throw new IllegalArgumentException("tenant and build id are required");
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM program_build WHERE tenant=? AND id=?")) {
            query.setString(1, tenantId);
            query.setString(2, buildId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(buildRow(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public synchronized List<ProgramBuildSnapshot> listIncompleteBuilds() {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT tenant,id FROM program_build WHERE terminal=0 ORDER BY created_at");
             ResultSet rows = query.executeQuery()) {
            var builds = new java.util.ArrayList<ProgramBuildSnapshot>();
            while (rows.next()) builds.add(requiredBuild(rows.getString(1), rows.getString(2)));
            return List.copyOf(builds);
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public synchronized ProgramBuildNodeSnapshot recordBuildNode(
            String tenantId, String buildId, String nodeId, long expectedRevision,
            String artifactId, ProgramBuildPhase phase, boolean terminal, boolean ready,
            boolean reused, String diagnostic, String smokeOutputJson) {
        ProgramBuildSnapshot build = requiredBuild(tenantId, buildId);
        ProgramBuildNodeSnapshot current = build.nodes().stream()
                .filter(node -> node.plan().nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown program build node"));
        if (current.revision() != expectedRevision) {
            throw new IllegalStateException("Program build node changed during transactional compare-and-set");
        }
        String boundedDiagnostic = bounded(diagnostic, 4096);
        String boundedOutput = bounded(smokeOutputJson, 256 * 1024);
        Instant now = Instant.now();
        try {
            transaction(connection, () -> {
                try (PreparedStatement write = connection.prepareStatement("""
                        UPDATE program_build_node SET artifact_id=?,phase=?,revision=revision+1,updated_at=?,
                        terminal=?,ready=?,reused=?,diagnostic=?,smoke_output_json=?
                        WHERE tenant=? AND build_id=? AND node_id=? AND revision=?
                        """)) {
                    write.setString(1, artifactId == null ? "" : artifactId);
                    write.setString(2, phase.name());
                    write.setString(3, now.toString());
                    write.setInt(4, terminal ? 1 : 0);
                    write.setInt(5, ready ? 1 : 0);
                    write.setInt(6, reused ? 1 : 0);
                    write.setString(7, boundedDiagnostic);
                    write.setString(8, boundedOutput);
                    write.setString(9, tenantId);
                    write.setString(10, buildId);
                    write.setString(11, nodeId);
                    write.setLong(12, expectedRevision);
                    if (write.executeUpdate() != 1) {
                        throw new IllegalStateException("Program build node changed during transactional compare-and-set");
                    }
                }
                boolean buildTerminal;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT NOT EXISTS(SELECT 1 FROM program_build_node WHERE build_id=? AND terminal=0)")) {
                    query.setString(1, buildId);
                    try (ResultSet row = query.executeQuery()) { buildTerminal = row.next() && row.getInt(1) == 1; }
                }
                try (PreparedStatement write = connection.prepareStatement("""
                        UPDATE program_build SET revision=revision+1,updated_at=?,terminal=?
                        WHERE tenant=? AND id=?
                        """)) {
                    write.setString(1, now.toString());
                    write.setInt(2, buildTerminal ? 1 : 0);
                    write.setString(3, tenantId);
                    write.setString(4, buildId);
                    if (write.executeUpdate() != 1) throw new IllegalArgumentException("Unknown program build");
                }
            });
            return requiredBuild(tenantId, buildId).nodes().stream()
                    .filter(node -> node.plan().nodeId().equals(nodeId)).findFirst().orElseThrow();
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public synchronized ArtifactReservation reserve(String id, ArtifactState expected, ArtifactState target) {
        requireTransition(expected, target);
        if (reservations.containsKey(id)) throw new IllegalStateException("Artifact lifecycle operation is already in progress");
        var reservation = new ArtifactReservation(UUID.randomUUID(), required(id, expected), target);
        reservations.put(id, reservation);
        return reservation;
    }

    @Override
    public synchronized GeneratedArtifact complete(ArtifactReservation reservation, Map<String, String> evidence) {
        ArtifactReservation owned = reservations.get(reservation.artifact().id());
        if (!reservation.equals(owned)) throw new IllegalStateException("Artifact lifecycle reservation is not current");
        try {
            GeneratedArtifact current = required(reservation.artifact().id(), reservation.artifact().state());
            if (current.revision() != reservation.artifact().revision()) {
                throw new IllegalStateException("Artifact changed while lifecycle operation was reserved");
            }
            return update(current, reservation.target(), evidence == null ? Map.of() : evidence, false);
        } finally {
            reservations.remove(reservation.artifact().id(), reservation);
        }
    }

    @Override public synchronized void cancel(ArtifactReservation reservation) {
        if (reservation != null) reservations.remove(reservation.artifact().id(), reservation);
    }

    @Override
    public ProgramAdmission admitForExecution(String tenantId, String artifactId) {
        if (tenantId == null || tenantId.isBlank() || artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("tenant and artifact are required");
        }
        GeneratedArtifact observed = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown program artifact: " + artifactId));
        return new DurableAdmission(tenantId, artifactId, observed);
    }

    private final class DurableAdmission implements ProgramAdmission {
        private final String tenantId;
        private final String artifactId;
        private final GeneratedArtifact observed;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile Runnable cancellation;

        private DurableAdmission(String tenantId, String artifactId, GeneratedArtifact observed) {
            this.tenantId = tenantId;
            this.artifactId = artifactId;
            this.observed = observed;
        }

        @Override public String artifactId() { return artifactId; }
        @Override public GeneratedArtifact unverifiedSnapshot() { return observed; }

        @Override
        public GeneratedArtifact redeem() {
            if (closed.get()) throw new SecurityException("Program artifact admission is already released: " + artifactId);
            synchronized (SqliteArtifactRegistry.this) {
                GeneratedArtifact current = read(artifactId);
                if (current == null) throw new SecurityException("Unknown program artifact: " + artifactId);
                if (!tenantId.equals(current.metadata().get(OWNER))) {
                    throw new SecurityException("Program artifact is not owned by tenant " + tenantId);
                }
                if (current.state() != ArtifactState.ACTIVE || current.revision() != observed.revision()) {
                    throw new SecurityException("Program artifact is no longer executable: " + artifactId);
                }
                verifier.verify(current);
                return current;
            }
        }

        @Override
        public void onRevoked(Runnable toCancel) {
            if (toCancel == null || closed.get()) return;
            cancellation = toCancel;
            revocations.computeIfAbsent(artifactId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(toCancel);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            Collection<Runnable> live = revocations.get(artifactId);
            if (cancellation != null && live != null) live.remove(cancellation);
        }
    }

    private void revoke(String artifactId) {
        Collection<Runnable> cancellations = revocations.remove(artifactId);
        if (cancellations == null) return;
        for (Runnable cancellation : cancellations) {
            try { cancellation.run(); } catch (RuntimeException ignored) { }
        }
    }

    private GeneratedArtifact update(GeneratedArtifact current, ArtifactState target,
                                     Map<String, String> evidence, boolean stateUnchanged) {
        if (!stateUnchanged) requireTransition(current.state(), target);
        var metadata = new java.util.LinkedHashMap<>(current.metadata());
        if (stateUnchanged) metadata.putAll(evidence);
        else evidence.forEach((key, value) -> metadata.put("evidence." + target.name().toLowerCase() + "." + key, value));
        var changed = new GeneratedArtifact(current.id(), current.language(), current.sha256(), current.source(),
                target, current.revision() + 1, current.createdAt(), Instant.now(), metadata);
        try {
            transaction(connection, () -> {
                try (PreparedStatement write = connection.prepareStatement("""
                        UPDATE program_artifact SET state=?, revision=?, updated_at=?,
                        compatibility_fingerprint=?, payload_digest=?, creator_identity=?, approver_identity=?,
                        validation_evidence=?, smoke_evidence=?, approval_evidence=?, activation_evidence=?,
                        retirement_evidence=?, metadata=? WHERE id=? AND revision=?
                        """)) {
                    write.setString(1, changed.state().name());
                    write.setLong(2, changed.revision());
                    write.setString(3, changed.updatedAt().toString());
                    bindQualificationUpdate(write, changed, 4);
                    write.setString(14, changed.id());
                    write.setLong(15, current.revision());
                    if (write.executeUpdate() != 1) throw new IllegalStateException("Artifact changed during transactional compare-and-set");
                }
            });
            return changed;
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    private GeneratedArtifact required(String id, ArtifactState expected) {
        GeneratedArtifact artifact = read(id);
        if (artifact == null) throw new IllegalArgumentException("Unknown artifact: " + id);
        if (artifact.state() != expected) throw new IllegalStateException("Artifact " + id + " is " + artifact.state() + ", not " + expected);
        return artifact;
    }

    private GeneratedArtifact read(String id) {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM program_artifact WHERE id=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? row(rows) : null; }
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    private static GeneratedArtifact row(ResultSet row) throws SQLException {
        String digest = HexFormat.of().formatHex(row.getBytes("source_digest"));
        String source = new String(row.getBytes("source_utf8"), StandardCharsets.UTF_8);
        var metadata = new java.util.LinkedHashMap<>(decode(row.getString("metadata")));
        metadata.put(OWNER, row.getString("tenant"));
        var artifact = new GeneratedArtifact(row.getString("id"), row.getString("language"), digest, source,
                ArtifactState.valueOf(row.getString("state")), row.getLong("revision"),
                Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")), metadata);
        if (row.getInt("digest_format") != ProgramArtifactIdentity.FORMAT_VERSION) {
            throw new IllegalStateException("unsupported program source digest format for " + artifact.id());
        }
        verifyDigest(artifact);
        return artifact;
    }

    private ProgramBuildSnapshot requiredBuild(String tenantId, String buildId) {
        return findBuild(tenantId, buildId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown program build"));
    }

    private ProgramBuildSnapshot buildRow(ResultSet row) throws SQLException {
        String id = row.getString("id");
        String tenant = row.getString("tenant");
        var nodes = new java.util.ArrayList<ProgramBuildNodeSnapshot>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM program_build_node WHERE build_id=? ORDER BY position")) {
            query.setString(1, id);
            try (ResultSet nodeRows = query.executeQuery()) {
                while (nodeRows.next()) nodes.add(buildNodeRow(nodeRows));
            }
        }
        return new ProgramBuildSnapshot(id, tenant,
                HexFormat.of().formatHex(row.getBytes("request_digest")), row.getInt("dual_control") == 1,
                row.getLong("revision"), Instant.parse(row.getString("created_at")),
                Instant.parse(row.getString("updated_at")), row.getInt("terminal") == 1,
                decode(row.getString("metadata")), nodes);
    }

    private static ProgramBuildNodeSnapshot buildNodeRow(ResultSet row) throws SQLException {
        String language = row.getString("language");
        String source = new String(row.getBytes("source_utf8"), StandardCharsets.UTF_8);
        String sourceDigest = HexFormat.of().formatHex(row.getBytes("source_digest"));
        String payloadJson = row.getString("payload_json");
        String payloadDigest = HexFormat.of().formatHex(row.getBytes("payload_digest"));
        var payload = PayloadJson.read(payloadJson.getBytes(StandardCharsets.UTF_8), PayloadLimits.DEFAULTS);
        String canonicalPayload = PayloadJson.write(payload);
        if (!MessageDigest.isEqual(canonicalPayload.getBytes(StandardCharsets.UTF_8),
                payloadJson.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("program build contains noncanonical smoke payload");
        }
        String calculatedPayload = ProgramTestPayload.sha256(payload.toJava());
        if (!MessageDigest.isEqual(parseDigest(calculatedPayload), parseDigest(payloadDigest))) {
            throw new IllegalStateException("program build contains a corrupt smoke payload digest");
        }
        var plan = new ProgramBuildNodePlan(row.getString("node_id"), language, source, sourceDigest,
                payloadJson, payloadDigest);
        return new ProgramBuildNodeSnapshot(row.getString("build_id"), row.getString("tenant"), plan,
                row.getString("artifact_id"), ProgramBuildPhase.valueOf(row.getString("phase")),
                row.getLong("revision"), Instant.parse(row.getString("created_at")),
                Instant.parse(row.getString("updated_at")), row.getInt("terminal") == 1,
                row.getInt("ready") == 1, row.getInt("reused") == 1,
                row.getString("diagnostic"), row.getString("smoke_output_json"));
    }

    private static void insert(Connection connection, GeneratedArtifact artifact) throws SQLException {
        String tenant = artifact.metadata().get(OWNER);
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("artifact tenant is required");
        try (PreparedStatement write = connection.prepareStatement("""
                INSERT INTO program_artifact (
                    tenant,id,language,source_utf8,source_digest,digest_format,state,revision,
                    compatibility_fingerprint,payload_digest,created_at,updated_at,creator_identity,
                    approver_identity,validation_evidence,smoke_evidence,approval_evidence,
                    activation_evidence,retirement_evidence,metadata)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            write.setString(1, tenant);
            write.setString(2, artifact.id());
            write.setString(3, artifact.language());
            write.setBytes(4, artifact.source().getBytes(StandardCharsets.UTF_8));
            write.setBytes(5, parseDigest(artifact.sha256()));
            write.setInt(6, ProgramArtifactIdentity.FORMAT_VERSION);
            write.setString(7, artifact.state().name());
            write.setLong(8, artifact.revision());
            bindQualification(write, artifact, 9);
            write.executeUpdate();
        }
    }

    /** Binds compatibility through metadata (12 columns) starting at {@code start}. */
    private static void bindQualification(PreparedStatement write, GeneratedArtifact artifact, int start)
            throws SQLException {
        Map<String, String> metadata = artifact.metadata();
        write.setString(start, metadata.getOrDefault("ravenroot.program.compatibilityFingerprint", ""));
        String payload = metadata.get("ravenroot.program.payloadDigest");
        if (payload == null || payload.isBlank()) write.setNull(start + 1, java.sql.Types.BLOB);
        else write.setBytes(start + 1, parseDigest(payload));
        write.setString(start + 2, artifact.createdAt().toString());
        write.setString(start + 3, artifact.updatedAt().toString());
        write.setString(start + 4, metadata.getOrDefault(CREATOR, ""));
        write.setString(start + 5, metadata.getOrDefault("evidence.approved.approver", ""));
        write.setString(start + 6, evidence(metadata, "evidence.validated."));
        write.setString(start + 7, evidence(metadata, "evidence.tested."));
        write.setString(start + 8, evidence(metadata, "evidence.approved."));
        write.setString(start + 9, evidence(metadata, "evidence.active."));
        write.setString(start + 10, evidence(metadata, "evidence.retired."));
        write.setString(start + 11, encode(metadata));
    }

    private static void bindQualificationUpdate(PreparedStatement write, GeneratedArtifact artifact, int start)
            throws SQLException {
        Map<String, String> metadata = artifact.metadata();
        write.setString(start, metadata.getOrDefault("ravenroot.program.compatibilityFingerprint", ""));
        String payload = metadata.get("ravenroot.program.payloadDigest");
        if (payload == null || payload.isBlank()) write.setNull(start + 1, java.sql.Types.BLOB);
        else write.setBytes(start + 1, parseDigest(payload));
        write.setString(start + 2, metadata.getOrDefault(CREATOR, ""));
        write.setString(start + 3, metadata.getOrDefault("evidence.approved.approver", ""));
        write.setString(start + 4, evidence(metadata, "evidence.validated."));
        write.setString(start + 5, evidence(metadata, "evidence.tested."));
        write.setString(start + 6, evidence(metadata, "evidence.approved."));
        write.setString(start + 7, evidence(metadata, "evidence.active."));
        write.setString(start + 8, evidence(metadata, "evidence.retired."));
        write.setString(start + 9, encode(metadata));
    }

    private static String evidence(Map<String, String> metadata, String prefix) {
        var selected = new java.util.LinkedHashMap<String, String>();
        metadata.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> selected.put(entry.getKey(), entry.getValue()));
        return encode(selected);
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static void verifyDigest(GeneratedArtifact artifact) {
        byte[] calculated = parseDigest(ProgramArtifactIdentity.sha256(artifact.language(), artifact.source()));
        byte[] stored = parseDigest(artifact.sha256());
        if (!MessageDigest.isEqual(calculated, stored)) {
            throw new IllegalStateException("program artifact registry contains a corrupt source digest for " + artifact.id());
        }
    }

    private static byte[] parseDigest(String digest) {
        try {
            byte[] parsed = HexFormat.of().parseHex(digest);
            if (parsed.length != 32) throw new IllegalArgumentException("digest is not SHA-256");
            return parsed;
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("invalid SHA-256 digest", malformed);
        }
    }

    private static String encode(Map<String, String> metadata) {
        return metadata.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> b64(entry.getKey()) + ":" + b64(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Map<String, String> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return Map.of();
        var metadata = new java.util.LinkedHashMap<String, String>();
        for (String entry : encoded.split(";", -1)) {
            String[] parts = entry.split(":", -1);
            if (parts.length != 2) throw new IllegalStateException("corrupt artifact metadata");
            metadata.put(unb64(parts[0]), unb64(parts[1]));
        }
        return Map.copyOf(metadata);
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static IllegalStateException unavailable(SQLException failure) {
        return new IllegalStateException("durable program artifact registry is unavailable", failure);
    }

    private static void requireTransition(ArtifactState expected, ArtifactState target) {
        if (!TRANSITIONS.getOrDefault(expected, EnumSet.noneOf(ArtifactState.class)).contains(target)) {
            throw new IllegalStateException("Illegal artifact transition " + expected + " -> " + target);
        }
    }

    private static Map<ArtifactState, EnumSet<ArtifactState>> transitions() {
        var transitions = new EnumMap<ArtifactState, EnumSet<ArtifactState>>(ArtifactState.class);
        transitions.put(ArtifactState.GENERATED, EnumSet.of(ArtifactState.VALIDATED, ArtifactState.RETIRED));
        transitions.put(ArtifactState.VALIDATED, EnumSet.of(ArtifactState.TESTED, ArtifactState.RETIRED));
        transitions.put(ArtifactState.TESTED, EnumSet.of(ArtifactState.APPROVED, ArtifactState.RETIRED));
        transitions.put(ArtifactState.APPROVED, EnumSet.of(ArtifactState.ACTIVE, ArtifactState.RETIRED));
        transitions.put(ArtifactState.ACTIVE, EnumSet.of(ArtifactState.RETIRED));
        transitions.put(ArtifactState.RETIRED, EnumSet.noneOf(ArtifactState.class));
        return Map.copyOf(transitions);
    }

    @Override public synchronized void close() {
        try { connection.close(); } catch (SQLException failure) { throw unavailable(failure); }
    }

    @FunctionalInterface
    private interface SqlWork { void run() throws SQLException; }
}
