package ai.ravenroot.server.authoring;

import javax.sql.DataSource;

/** PostgreSQL projection sharing the deployment's bounded pool; never owns a supplied pool. */
public final class PostgresGraphAuthoringReadIndex extends JdbcGraphAuthoringReadIndex {
    public PostgresGraphAuthoringReadIndex(DataSource source) {
        super(source, true, null);
    }

    PostgresGraphAuthoringReadIndex(DataSource source, AutoCloseable owner) {
        super(source, true, owner);
    }
}
