The shared PostgreSQL store now carries the durable continuations a long-running execution depends on:
named handlers and their wait and re-entry, scoped tool approvals, first-class human tasks with their
embedded confirmations and authorized attention queries, operator holds on a traversal, and
process-rooted agent authority budgets. Each of them commits in the same transaction as the execution
transition beside it, so a process recorded as waiting is always waiting on something that exists, and
a resolution and the work it authorizes arrive together or not at all. Correlation and deduplication
keys stay unique across hosts because the database decides the winner rather than a check performed
before the write.
