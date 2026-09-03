package ai.ravenroot.persistence.sqlite;

import java.util.List;
import java.util.Objects;

/**
 * One forward step of the schema, applied atomically.
 *
 * <p>There is deliberately no {@code down} half. A rollback script is a promise that the data written
 * under the newer schema can be expressed under the older one, which is false for every migration
 * that adds information rather than a column — and an untested rollback is a rollback that does not
 * work. Recovery from a bad migration is a restore, not an inverse script that runs for the first
 * time during an incident.</p>
 *
 * @param version    strictly positive, applied in ascending order with no gaps
 * @param description recorded in {@code store_schema_history} so an operator can read what ran.
 *                   It ships inside every database this produces and is read by people with no
 *                   sight of how the change was tracked, so it names the change on its own terms
 *                   and never carries an internal work-item or issue reference
 * @param statements DDL executed in order inside the migration's own transaction
 */
record SchemaMigration(int version, String description, List<String> statements) {

    SchemaMigration {
        if (version < 1) {
            throw new IllegalArgumentException("schema version must be positive");
        }
        Objects.requireNonNull(description, "description");
        statements = List.copyOf(statements);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("a migration must contain at least one statement");
        }
    }
}
