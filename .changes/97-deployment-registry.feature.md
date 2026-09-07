The shared PostgreSQL store now holds the deployment registry, so several Ravenroot hosts can agree on
what a deployment is meant to be doing and which of them is entitled to advance it. Deployment intent,
its graph versions, the command ledger and the ownership lease live beside the executions they govern
in the same database, and a host whose lease has been taken over is refused with its stale fence
rather than allowed to write. The registry's tables arrive as a second schema step, so a database
created by an earlier build is upgraded in place rather than rebuilt.
