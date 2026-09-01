# Ravenroot profiled JDBC extension

`ai.ravenroot.extensions.jdbc` is an optional node package contributing `jdbc.query` and
`jdbc.insert`. It is not part of the standard distribution. An operator must bundle the extension
with one or more JDBC drivers and explicitly enable the resulting plugin. Ravenroot ships no database driver;
the operator is responsible for its license and provenance.

Build the closed bundle by supplying at least one regular driver jar, immediately followed by its
independently obtained lowercase SHA-256. Repeat the pair for every driver. This example pins the
PostgreSQL JDBC 42.7.7 and MySQL Connector/J 9.5.0 artifacts published by their vendors:

```sh
./plugin.sh build jdbc \
  --driver-jar /operator/artifacts/postgresql-42.7.7.jar \
  --driver-sha256 157963d60ae66d607e09466e8c0cdf8087e9cb20d0159899ffca96bca2528460 \
  --driver-jar /operator/artifacts/mysql-connector-j-9.5.0.jar \
  --driver-sha256 f2ca3dfaf00d4aa311470db7ea3051962944ba0cb60005a2f75467549c39f425
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-jdbc/target/plugin-bundle
./plugin.sh install ravenroot/ravenroot-extensions/ravenroot-jdbc/target/plugin-bundle
```

The tooling performs an early checksum check before the Maven build, then copies and hashes the exact
driver bytes through a separate stream and writes the manifest only when every digest still matches.
It declares each driver as a private dependency in the normal closed plugin manifest, preserving the
operator's order. Missing, extra, incompletely paired, duplicate-filename/driverId, symbolic-link and
checksum-mismatched artifacts are refused. The jars remain private to this plugin and never enter the
default Ravenroot jar or binary distribution.

Graphs can select only the `profile` and `statement` identifiers. They cannot supply JDBC URLs,
driver classes or properties, database/schema selection, credentials, SQL, transaction settings,
generated-key columns, or resource ceilings. Input has the exact shape
`{"contract":"jdbc.parameters.v1","parameters":{"name":<canonical scalar>}}`. A parameter may be
null, Boolean, integer, decimal, text, or strict Base64 in `{"binary":"..."}`. Names must exactly
match the approved statement, including repeated placeholders. Values are bound using typed
`PreparedStatement` setters and are never interpolated into SQL.

## Operator profile

Set `RAVENROOT_JDBC_PROFILE_<TENANT_UTF8_HEX>_<PROFILE_UTF8_HEX>` to strict Base64 of a JSON object
with exactly these fields (`schema` is the only optional field):

```json
{
  "driverId": "postgresql-42.7.7",
  "driverClass": "org.postgresql.Driver",
  "driverSha256": "157963d60ae66d607e09466e8c0cdf8087e9cb20d0159899ffca96bca2528460",
  "url": "jdbc:postgresql://database.internal:5432/application",
  "username": "application",
  "credentialRef": "application-db-password",
  "schema": "accounting",
  "isolation": "READ_COMMITTED",
  "deadlineMs": 2000,
  "maxConcurrency": 4,
  "maxParameters": 32,
  "maxParameterBytes": 65536,
  "maxRows": 1000,
  "maxColumns": 64,
  "maxCellBytes": 65536,
  "maxTotalBytes": 1048576,
  "maxGeneratedKeyRows": 16,
  "statements": {
    "find-user": {
      "kind": "QUERY",
      "sql": "SELECT id,name FROM users WHERE id=:id",
      "generatedKeys": []
    },
    "add-user": {
      "kind": "INSERT",
      "sql": "INSERT INTO users(name) VALUES (:name)",
      "generatedKeys": ["id"]
    }
  }
}
```

`driverId` is the exact bundled driver filename without the `.jar` suffix. It is an ASCII safe
artifact basename of 1 through 64 characters: the first and last characters are alphanumeric;
internal alphanumerics, `.`, `-`, and `_` are allowed; `..` is always refused. Consequently `.`,
`..`, leading/trailing punctuation, separators, controls, whitespace, non-ASCII text, and a 65th
character are refused, while ordinary dotted versions such as `postgresql-42.7.7` are accepted.
Ravenroot selects that exact regular `<driverId>.jar` from the bundle URL list before consulting
`driverClass`; it never uses a class-resource lookup whose result could depend on classpath order.
It copies at most 64 MiB while verifying the complete SHA-256, and parses the accepted bytes into a
bounded immutable in-memory image. A private platform-parent classloader dedicated to that driver
defines and initializes it only from that image, so drivers in the same bundle cannot see one
another and replacement of the installed path after verification cannot alter any class byte.
Expanded entries are also
bounded before retention. The class must implement `java.sql.Driver`. The extension
rejects multi-release jars (a `Multi-Release` manifest attribute or any versioned-entry namespace)
before class initialization; operators must supply one flat Java-21-compatible driver image. This
narrow contract avoids silently selecting different class bytes from the pinned image. Driver
initialization, its private dependencies/resources/services, every JDBC operation, and asynchronous
cancel/abort/close callbacks run with that private loader as TCCL, with the caller context restored
on every exit. The extension calls that exact driver directly; it does not use `DriverManager`, a
pool, or a data-source reflection seam. URLs reject user info, query/semicolon properties and credential-like
fields. Only the profile's fixed URL, username and a per-invocation tenant credential are passed.
The optional `schema` is operator-owned and is applied to the fresh connection with
`Connection.setSchema()`; the database/catalog remains part of `url`. A driver that reports this
operation unsupported fails as the stable redacted `JDBC_SCHEMA_UNSUPPORTED`.

Named SQL recognizes parameters only outside quoted strings and line/block comments, preserves
PostgreSQL `::` casts, and rejects semicolons, unclosed syntax and more than one statement. Query
profiles accept only `SELECT`; insert profiles accept only `INSERT`. DDL, delete/update, procedures,
arbitrary properties and raw SQL from GraphML or payloads are deliberately absent.

## Transactions, limits, and failures

Each invocation resolves one fresh credential lease and opens one connection. Query uses
`autoCommit=false`, requests read-only mode, executes once, materializes ordered bounded columns and
rows as `jdbc.query.result.v1`, then rolls back and closes. Insert executes one approved statement,
materializes only allowlisted generated keys, and calls commit exactly once. It never retries after
execution starts. A failure or deadline after commit begins is `JDBC_AMBIGUOUS_COMMIT` because the
caller must reconcile database state.

A monotonic outer deadline covers credential resolution, connection, execution, result reading,
commit and cleanup. Cancellation and commit entry share one atomic handshake: a cancellation winner
prevents commit and publishes `JDBC_CANCELLED`/`JDBC_DEADLINE_EXCEEDED`; a commit winner makes the
same request `JDBC_AMBIGUOUS_COMMIT`, with one terminal publication. Text, binary and large JDBC
values are read through bounded streams; each binary read is capped by the smaller remaining raw-cell
and Base64/canonical-result allowance plus one detection byte. Before a streamed cell is read, that
allowance reserves every already-determined enclosing row/object/array/result suffix. Row/column counts, per-cell bytes and
the exact canonical JSON result bytes are charged incrementally before a corresponding result
allocation or copy. Cancellation requests credential cancellation, statement cancellation,
connection abort and connection close through four independent one-shot lanes, so a blocking driver
callback cannot suppress the other cleanup attempts. Every admitted invocation reserves those four
lanes from a fixed global budget of 128; unused lanes return when its worker settles and launched lanes
remain accounted until their callback actually exits. When the callback budget is exhausted, new work
is refused before credentials or connection creation rather than accumulating virtual threads. Global
and tenant/profile semaphores refuse excess work before credentials or a connection, and their permits
remain held until the worker has actually unwound. JDBC cancellation is cooperative: a hostile driver
can retain its isolated worker, a blocked cleanup virtual thread and the admission permit until the
worker returns, but it cannot publish a late result over the already terminal Ravenroot stage.

Failures expose only stable `JDBC_*` codes. SQL, parameters, rows, URL, credential, SQLState, vendor
codes and raw driver exceptions are neither returned nor logged. JDBC's API requires a password
`String` in `Properties` at the final driver boundary; Ravenroot creates that one unavoidable immutable
copy as late as possible, clears all mutable arrays/properties, never stores it in a URL or diagnostic,
and does not pool connections.

The deterministic fake-driver suite covers parser/injection behavior, exact typed binding,
read-only rollback, one-shot commit and ambiguous commit, generated-key projection, exact and +1
incremental result limits, deadline/cancellation cleanup with a blocking `Statement.cancel`, credential
rotation, tenant/profile isolation, mixed PostgreSQL/MySQL concurrency, admission and package
contract. A generated hostile-driver fixture proves that mismatch and tampering are rejected before
its static initializer. Test-scoped PostgreSQL 42.7.7 and MySQL Connector/J 9.5.0 artifacts exercise
deterministic driver selection and same-name dependency isolation end to end without opening a
database connection. Both are absent from runtime and default-distribution dependency graphs. No live
database is required by the build.
