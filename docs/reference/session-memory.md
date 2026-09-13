# Session memory

Session memory is durable agent context with an independent lifecycle. It is not an execution
payload, workflow state, a Human Task continuation, or a substitute for any of them.

The `SessionMemoryStore` SPI supports three exact scopes:

- `SESSION` is shared by a named session and carries no process or node identity.
- `PROCESS` adds one process-instance UUID and is isolated from the session-wide value.
- `NODE` adds both a process-instance UUID and node identifier.

Every key begins with the authenticated tenant. Reads of the same session identifier in another
tenant return no value. Values are opaque bytes with an explicit content type, a monotonically
increasing revision, creation/update times, and a mandatory expiry. Callers replace values through
an `Absent`, `Exactly(revision)`, or explicit `Any` expectation; deletion uses the same expectations.

Agent integrations should write through `SessionMemoryService`. It applies the configured redactor
before bytes reach the store, rejects retention beyond policy, and enforces the adapter's byte limit.
An idempotency key is tenant-scoped and binds the complete write. Replaying that key and body returns
the original entry; reusing it for different content is rejected. Workflow replay therefore reads
already-committed memory and does not repeat nondeterministic writes unless it deliberately replays
the identical store command.

Retention expiry and explicit deletion remove memory without deleting executions. Conversely,
execution retention does not implicitly delete session-scoped memory. SQLite and PostgreSQL adapters
persist entries and idempotency outcomes across restart; the in-memory adapter is a deterministic
reference implementation only.
