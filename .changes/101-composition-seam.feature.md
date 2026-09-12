A deployment can now choose which execution store Ravenroot runs on. `RAVENROOT_EXECUTION_STORE`
selects the single-host store, which stays the default and behaves exactly as before, or the shared
PostgreSQL store, which until now could be built and published but never reached from a running
server. Selecting it needs a connection URL and nothing else; the server builds and pools the
connections itself, and no credential reaches the store adapter or any diagnostic.

Every claim a replica takes on an execution now carries a readable identity — the replica's name, a
token minted at each start, and whether the claim belongs to the runtime advancing the execution or to
the sweep that reclaims abandoned work — so an operator reading the process inventory can name the pod
that holds an execution. How long a claim outlives its last renewal is now configurable within the
bounds the store publishes.

Configurations whose guarantees are not supported are refused at startup with the specific combination
named, rather than started quietly: more than one replica against the single-host store, more than one
replica while state that lives on a single pod is enabled, or a shared store selected together with a
single-host directory. Backup and restore of the single-host bundle refuse under the shared store and
point at the database's own procedure.
