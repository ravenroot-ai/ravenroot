Ravenroot now documents and demonstrates what a backup of the shared PostgreSQL store restores. A dump
and restore is shown to preserve every durable family the store holds — graph definitions and their
bindings, pinned manifests and their node packages, executions with their traversals and attempts,
leases and claimed work, idempotency records, the event journal with its outbox and inbox positions,
the process inventory, execution results, every durable continuation, and deployments with their
versions, command ledger and lease — together with the retention floors that say how far back each
answer is still complete. The reference explains what a full and a data-only restore each require, and
why a restore is verified by reading the store back through its own interfaces: a manifest separated
from its package rows violates no database constraint and is still refused as damaged when it is read.
