package ai.ravenroot.server.palette;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** JDBC implementation over the execution store's already-migrated SQLite or PostgreSQL schema. */
public final class JdbcNodePaletteStore implements NodePaletteStore {
    @FunctionalInterface
    private interface Connections { Connection open() throws SQLException; }

    @FunctionalInterface
    private interface Work<T> { T run(Connection connection) throws SQLException; }

    private final Connections connections;
    private final Clock clock;
    private final boolean sqlite;

    private JdbcNodePaletteStore(Connections connections, Clock clock, boolean sqlite) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sqlite = sqlite;
    }

    public static JdbcNodePaletteStore sqlite(Path databaseFile, Clock clock) {
        Path file = Objects.requireNonNull(databaseFile, "databaseFile").toAbsolutePath().normalize();
        return new JdbcNodePaletteStore(() -> DriverManager.getConnection("jdbc:sqlite:" + file), clock, true);
    }

    public static JdbcNodePaletteStore postgresql(DataSource dataSource, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new JdbcNodePaletteStore(dataSource::getConnection, clock, false);
    }

    @Override
    public List<Palette> listPalettes(Owner owner) {
        return transaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT palette_id, name, version,
                           created_at_epoch_second, created_at_nano,
                           updated_at_epoch_second, updated_at_nano
                      FROM node_palette
                     WHERE tenant_id = ? AND issuer = ? AND subject = ?
                     ORDER BY created_at_epoch_second, created_at_nano, palette_id
                    """)) {
                bindOwner(query, owner, 1);
                try (ResultSet rows = query.executeQuery()) {
                    var result = new ArrayList<Palette>();
                    while (rows.next()) result.add(palette(rows));
                    return List.copyOf(result);
                }
            }
        });
    }

    @Override
    public List<Template> listTemplates(Owner owner) {
        return transaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT template_id, palette_id, name, kind, payload, version,
                           created_at_epoch_second, created_at_nano,
                           updated_at_epoch_second, updated_at_nano
                      FROM node_template
                     WHERE tenant_id = ? AND issuer = ? AND subject = ?
                     ORDER BY created_at_epoch_second, created_at_nano, template_id
                    """)) {
                bindOwner(query, owner, 1);
                try (ResultSet rows = query.executeQuery()) {
                    var result = new ArrayList<Template>();
                    while (rows.next()) result.add(template(rows));
                    return List.copyOf(result);
                }
            }
        });
    }

    @Override
    public Palette createPalette(Owner owner, String name) {
        return transaction(connection -> {
            lockOwner(connection, owner);
            if (count(connection, "node_palette", owner, null) >= MAX_PALETTES_PER_OWNER) {
                throw new StoreException(Failure.LIMIT_REACHED);
            }
            String id = UUID.randomUUID().toString();
            Instant now = clock.instant();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO node_palette
                      (tenant_id, issuer, subject, palette_id, name, version,
                       created_at_epoch_second, created_at_nano,
                       updated_at_epoch_second, updated_at_nano)
                    VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                    """)) {
                bindOwner(insert, owner, 1);
                insert.setString(4, id);
                insert.setString(5, name);
                bindInstant(insert, 6, now);
                bindInstant(insert, 8, now);
                insert.executeUpdate();
            }
            return new Palette(id, name, 1, now, now);
        });
    }

    @Override
    public Palette renamePalette(Owner owner, String id, long expectedVersion, String name) {
        return transaction(connection -> {
            Palette before = findPalette(connection, owner, id);
            if (before.version() != expectedVersion) throw new StoreException(Failure.CONFLICT);
            Instant now = clock.instant();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE node_palette SET name = ?, version = version + 1,
                           updated_at_epoch_second = ?, updated_at_nano = ?
                     WHERE tenant_id = ? AND issuer = ? AND subject = ?
                       AND palette_id = ? AND version = ?
                    """)) {
                update.setString(1, name);
                bindInstant(update, 2, now);
                bindOwner(update, owner, 4);
                update.setString(7, id);
                update.setLong(8, expectedVersion);
                if (update.executeUpdate() != 1) throw new StoreException(Failure.CONFLICT);
            }
            return new Palette(id, name, expectedVersion + 1, before.createdAt(), now);
        });
    }

    @Override
    public void deletePalette(Owner owner, String id, long expectedVersion) {
        transaction(connection -> {
            findPalette(connection, owner, id);
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM node_palette
                     WHERE tenant_id = ? AND issuer = ? AND subject = ?
                       AND palette_id = ? AND version = ?
                    """)) {
                bindOwner(delete, owner, 1);
                delete.setString(4, id);
                delete.setLong(5, expectedVersion);
                if (delete.executeUpdate() != 1) throw new StoreException(Failure.CONFLICT);
            }
            return null;
        });
    }

    @Override
    public Template createTemplate(Owner owner, String paletteId, String name, String kind, String payload) {
        return transaction(connection -> {
            lockOwner(connection, owner);
            findPalette(connection, owner, paletteId);
            if (count(connection, "node_template", owner, paletteId) >= MAX_TEMPLATES_PER_PALETTE) {
                throw new StoreException(Failure.LIMIT_REACHED);
            }
            String id = UUID.randomUUID().toString();
            Instant now = clock.instant();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO node_template
                      (tenant_id, issuer, subject, template_id, palette_id, name, kind, payload, version,
                       created_at_epoch_second, created_at_nano,
                       updated_at_epoch_second, updated_at_nano)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                    """)) {
                bindOwner(insert, owner, 1);
                insert.setString(4, id);
                insert.setString(5, paletteId);
                insert.setString(6, name);
                insert.setString(7, kind);
                insert.setString(8, payload);
                bindInstant(insert, 9, now);
                bindInstant(insert, 11, now);
                insert.executeUpdate();
            }
            return new Template(id, paletteId, name, kind, payload, 1, now, now);
        });
    }

    @Override
    public Template updateTemplate(Owner owner, String id, long expectedVersion,
                                   String paletteId, String name) {
        return transaction(connection -> {
            lockOwner(connection, owner);
            Template before = findTemplate(connection, owner, id);
            if (before.version() != expectedVersion) throw new StoreException(Failure.CONFLICT);
            findPalette(connection, owner, paletteId);
            if (!before.paletteId().equals(paletteId)
                    && count(connection, "node_template", owner, paletteId) >= MAX_TEMPLATES_PER_PALETTE) {
                throw new StoreException(Failure.LIMIT_REACHED);
            }
            Instant now = clock.instant();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE node_template SET palette_id = ?, name = ?, version = version + 1,
                           updated_at_epoch_second = ?, updated_at_nano = ?
                     WHERE tenant_id = ? AND issuer = ? AND subject = ?
                       AND template_id = ? AND version = ?
                    """)) {
                update.setString(1, paletteId);
                update.setString(2, name);
                bindInstant(update, 3, now);
                bindOwner(update, owner, 5);
                update.setString(8, id);
                update.setLong(9, expectedVersion);
                if (update.executeUpdate() != 1) throw new StoreException(Failure.CONFLICT);
            }
            return new Template(id, paletteId, name, before.kind(), before.payload(), expectedVersion + 1,
                    before.createdAt(), now);
        });
    }

    @Override
    public void deleteTemplate(Owner owner, String id, long expectedVersion) {
        transaction(connection -> {
            findTemplate(connection, owner, id);
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM node_template
                     WHERE tenant_id = ? AND issuer = ? AND subject = ?
                       AND template_id = ? AND version = ?
                    """)) {
                bindOwner(delete, owner, 1);
                delete.setString(4, id);
                delete.setLong(5, expectedVersion);
                if (delete.executeUpdate() != 1) throw new StoreException(Failure.CONFLICT);
            }
            return null;
        });
    }

    @Override
    public Template findTemplate(Owner owner, String id) {
        return transaction(connection -> findTemplate(connection, owner, id));
    }

    private Palette findPalette(Connection connection, Owner owner, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT palette_id, name, version,
                       created_at_epoch_second, created_at_nano,
                       updated_at_epoch_second, updated_at_nano
                  FROM node_palette
                 WHERE tenant_id = ? AND issuer = ? AND subject = ? AND palette_id = ?
                """)) {
            bindOwner(query, owner, 1);
            query.setString(4, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new StoreException(Failure.NOT_FOUND);
                return palette(rows);
            }
        }
    }

    private Template findTemplate(Connection connection, Owner owner, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT template_id, palette_id, name, kind, payload, version,
                       created_at_epoch_second, created_at_nano,
                       updated_at_epoch_second, updated_at_nano
                  FROM node_template
                 WHERE tenant_id = ? AND issuer = ? AND subject = ? AND template_id = ?
                """)) {
            bindOwner(query, owner, 1);
            query.setString(4, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new StoreException(Failure.NOT_FOUND);
                return template(rows);
            }
        }
    }

    private long count(Connection connection, String table, Owner owner, String paletteId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table
                + " WHERE tenant_id = ? AND issuer = ? AND subject = ?"
                + (paletteId == null ? "" : " AND palette_id = ?");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            bindOwner(query, owner, 1);
            if (paletteId != null) query.setString(4, paletteId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    /** Serializes PostgreSQL capacity decisions for one personal namespace. SQLite serializes writers. */
    private void lockOwner(Connection connection, Owner owner) throws SQLException {
        if (sqlite) return;
        try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
            lock.setInt(1, owner.tenantId().hashCode());
            lock.setInt(2, (owner.issuer() + '\0' + owner.subject()).hashCode());
            lock.executeQuery().close();
        }
    }

    private <T> T transaction(Work<T> work) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            if (sqlite) {
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                    statement.execute("PRAGMA busy_timeout = 5000");
                }
            }
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (StoreException known) {
                rollback(connection);
                throw known;
            } catch (SQLException failed) {
                rollback(connection);
                if (duplicate(failed)) throw new StoreException(Failure.DUPLICATE_NAME);
                throw new StoreException(Failure.UNAVAILABLE);
            } catch (RuntimeException failed) {
                rollback(connection);
                throw failed;
            }
        } catch (StoreException known) {
            throw known;
        } catch (SQLException failed) {
            throw new StoreException(Failure.UNAVAILABLE);
        }
    }

    private static boolean duplicate(SQLException failure) {
        String state = failure.getSQLState();
        String message = failure.getMessage();
        return "23505".equals(state) || (message != null && message.toLowerCase().contains("unique"));
    }

    private static void rollback(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }

    private static void bindOwner(PreparedStatement statement, Owner owner, int first) throws SQLException {
        statement.setString(first, owner.tenantId());
        statement.setString(first + 1, owner.issuer());
        statement.setString(first + 2, owner.subject());
    }

    private static void bindInstant(PreparedStatement statement, int first, Instant instant) throws SQLException {
        statement.setLong(first, instant.getEpochSecond());
        statement.setInt(first + 1, instant.getNano());
    }

    private static Instant instant(ResultSet rows, int first) throws SQLException {
        return Instant.ofEpochSecond(rows.getLong(first), rows.getInt(first + 1));
    }

    private static Palette palette(ResultSet rows) throws SQLException {
        return new Palette(rows.getString(1), rows.getString(2), rows.getLong(3),
                instant(rows, 4), instant(rows, 6));
    }

    private static Template template(ResultSet rows) throws SQLException {
        return new Template(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                rows.getString(5), rows.getLong(6), instant(rows, 7), instant(rows, 9));
    }
}
