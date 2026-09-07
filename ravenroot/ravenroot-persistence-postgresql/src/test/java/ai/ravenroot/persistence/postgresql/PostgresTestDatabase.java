package ai.ravenroot.persistence.postgresql;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * The one PostgreSQL server this module's tests share, and the schema isolation between them.
 *
 * <h2>A real server, not a substitute</h2>
 * <p>Everything this adapter exists to prove is invisible against anything but a real PostgreSQL:
 * {@code SELECT ... FOR UPDATE} excluding a second transaction, {@code SKIP LOCKED} handing two workers
 * disjoint sets, a conditional {@code UPDATE} losing a race it should lose, a partial unique index
 * refusing a second live correlation key. An embedded or in-memory stand-in would run the same test
 * methods and assert none of those things.</p>
 *
 * <h2>One container for the whole JVM</h2>
 * <p>The conformance suite creates a store per test method, and starting a container per method would
 * dominate the run to the point where nobody runs it. One container is started on first use and left
 * running; Testcontainers' own reaper removes it when the JVM exits, so there is no shutdown hook here
 * that a hard kill could skip.</p>
 *
 * <h2>One schema per {@code storeId}, and why that is the right unit</h2>
 * <p>Isolation has to be at least as strong as "these two stores cannot see each other's rows", because
 * the suite's tenant-scoped assertions would otherwise be answered from a neighbouring test's data and
 * would pass or fail depending on execution order. A schema gives exactly that while leaving the two
 * stores in one database, which is deliberate: it keeps a cross-process test able to reach the same
 * rows from a second JVM by naming the same schema, and it exercises the migration advisory lock, which
 * is database-wide and therefore shared by every schema here.</p>
 *
 * <p>A separate <em>database</em> per store would isolate more and prove less: the advisory lock would
 * never be contended, and the schema-qualified search path the adapter actually runs under in a shared
 * deployment would never be exercised.</p>
 *
 * <h2>Credentials</h2>
 * <p>The container mints its own for the life of the JVM and they are read from it at runtime. Nothing
 * here is a literal, and nothing here belongs anywhere but this process and the child JVMs it starts.</p>
 */
final class PostgresTestDatabase {

    /**
     * Pinned rather than floating. {@code SKIP LOCKED}, {@code RETURNING} on a conflict arm and
     * conditional {@code ON CONFLICT DO UPDATE ... WHERE} are all long-standing, but a test suite whose
     * subject is concurrency semantics should not silently change the server it is asserting against
     * when a tag moves.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:17-alpine");

    private static PostgreSQLContainer<?> shared;

    private PostgresTestDatabase() {
    }

    private static synchronized PostgreSQLContainer<?> container() {
        if (shared == null) {
            var started = new PostgreSQLContainer<>(IMAGE);
            started.start();
            shared = started;
        }
        return shared;
    }

    /** The JDBC URL of the shared server, for a child JVM that must reach the same rows. */
    static String jdbcUrl() {
        return container().getJdbcUrl();
    }

    /** Runtime-minted, container-scoped, and never written down anywhere. */
    static String username() {
        return container().getUsername();
    }

    /** Runtime-minted, container-scoped, and never written down anywhere. */
    static String password() {
        return container().getPassword();
    }

    /** The database every {@link #dataSourceFor} schema lives in, which is the one a dump names. */
    static String databaseName() {
        return container().getDatabaseName();
    }

    /**
     * The same server, addressed as a different database on it.
     *
     * <p>Derived from the container's own URL rather than rebuilt from a host and a port, so a change
     * in how Testcontainers exposes the server — a different host, a mapped port, a query parameter it
     * appends — reaches this method without anybody having to notice. Only the database segment of the
     * path is replaced; everything else is carried over exactly.</p>
     */
    static String jdbcUrlForDatabase(String database) {
        String url = container().getJdbcUrl();
        // The JDBC form is "jdbc:" followed by a URI, so the URI parser is what should decide where the
        // path ends and a query begins. String surgery on the last '/' would be wrong for any URL whose
        // query happened to contain one.
        URI uri = URI.create(url.substring("jdbc:".length()));
        String rebuilt = uri.getScheme() + "://" + uri.getAuthority() + "/" + database;
        return "jdbc:" + (uri.getRawQuery() == null ? rebuilt : rebuilt + "?" + uri.getRawQuery());
    }

    /**
     * Runs a command inside the server's own container, the way an operator runs one on the host that
     * holds the database.
     *
     * <p>This exists for {@code pg_dump}, {@code pg_restore}, {@code createdb} and {@code psql}: the
     * tools the operator documentation names, run as the versions that shipped with the server being
     * dumped. Reaching them any other way — a client binary from the test host, or a row copier written
     * in Java — would exercise a procedure nobody performs.</p>
     *
     * <p>{@code PGPASSWORD} is exported inside the shell rather than passed as an argument, and the
     * value is the credential this container minted for itself at startup. Nothing here is a literal
     * and nothing outlives the container.</p>
     */
    static Container.ExecResult psqlTool(String command) throws IOException, InterruptedException {
        return container().execInContainer("sh", "-c",
                "export PGPASSWORD='" + password() + "'; " + command);
    }

    /**
     * A {@link DataSource} over this {@code storeId}'s own schema, creating the schema if it is new.
     *
     * <p>Reconnecting with the same {@code storeId} reaches the same rows, which is what makes the
     * conformance suite's reopen a genuine process restart against stored state rather than a fresh
     * empty store wearing the same name.</p>
     */
    static DataSource dataSourceFor(String storeId) {
        return dataSourceFor(storeId, null);
    }

    /**
     * The same, with an {@code application_name} the server reports in {@code pg_stat_activity}.
     *
     * <p>That is how a test can name one connection precisely enough to terminate it at a chosen
     * instant, which is the only way to reach a commit whose outcome is genuinely unknown.</p>
     */
    static DataSource dataSourceFor(String storeId, String applicationName) {
        String schema = schemaNameFor(storeId);
        PostgreSQLContainer<?> server = container();
        try (Connection connection = dataSource(server.getJdbcUrl(), server.getUsername(),
                server.getPassword(), null).getConnection();
             Statement statement = connection.createStatement()) {
            // Quoted, because the name is derived from a caller-supplied identifier and a schema name
            // is one of the few places SQL takes an identifier rather than a value. The derivation
            // below already excludes everything but lowercase alphanumerics and underscore, so the
            // quoting is the second of two guards rather than the only one.
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        } catch (SQLException failed) {
            throw new IllegalStateException("could not prepare the schema for store " + storeId, failed);
        }
        var dataSource = (PGSimpleDataSource) dataSource(server.getJdbcUrl(), server.getUsername(),
                server.getPassword(), schema);
        if (applicationName != null) {
            dataSource.setApplicationName(applicationName);
        }
        return dataSource;
    }

    /**
     * Builds a driver {@link DataSource}, optionally pinned to one schema.
     *
     * <p>A plain {@link PGSimpleDataSource} rather than a pool, deliberately. A pool would add its own
     * connection lifecycle, its own timeouts and its own opinions about a broken connection to a suite
     * whose subject is what the server does under contention, and a failure would then have two
     * candidate causes. The adapter is handed a {@code DataSource} and cannot tell the difference,
     * which is the point of the port taking one.</p>
     */
    static DataSource dataSource(String jdbcUrl, String username, String password, String schema) {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        if (schema != null) {
            dataSource.setCurrentSchema(schema);
        }
        return dataSource;
    }

    /**
     * Maps a {@code storeId} to a legal, stable schema name.
     *
     * <p>The mapping has to be a function of the id alone: the conformance suite reopens a store by
     * handing back the same {@code storeId}, so a name derived from anything else — a counter, an
     * instant, a hash of the instance — would make every reopen land on an empty schema and every
     * durability assertion pass vacuously.</p>
     */
    static String schemaNameFor(String storeId) {
        var name = new StringBuilder("s_");
        for (char character : storeId.toLowerCase(Locale.ROOT).toCharArray()) {
            name.append(Character.isLetterOrDigit(character) ? character : '_');
        }
        // PostgreSQL truncates an identifier past 63 bytes rather than refusing it, which would silently
        // collide two long ids onto one schema. Truncating here at least makes the rule visible.
        return name.length() > 63 ? name.substring(0, 63) : name.toString();
    }
}
