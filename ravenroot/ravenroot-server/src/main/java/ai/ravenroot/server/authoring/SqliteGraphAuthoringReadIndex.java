package ai.ravenroot.server.authoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import javax.sql.DataSource;

/** SQLite/WAL authoring index in a separately rebuildable file owned by the composition root. */
public final class SqliteGraphAuthoringReadIndex extends JdbcGraphAuthoringReadIndex {
    public SqliteGraphAuthoringReadIndex(Path file) {
        super(source(file), false, null);
    }

    private static DataSource source(Path file) {
        try {
            Path absolute = file.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            var source = new org.sqlite.SQLiteDataSource();
            source.setUrl("jdbc:sqlite:" + absolute);
            try (var connection = source.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=30000");
                statement.execute("PRAGMA journal_mode=WAL");
            }
            return source;
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("Authoring read index could not be opened");
        }
    }
}
