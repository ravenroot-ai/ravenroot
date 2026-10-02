# Personal node palettes

The Workbench can save configured nodes in named personal palettes and insert them into another editable GraphML workflow. Palettes belong to the exact authenticated tenant, identity issuer, and user subject. They follow the user across browsers and devices that connect to the same durable Ravenroot store.

Palette reads require the `ravenroot.palette.read` scope. Creating, renaming, moving, or deleting palette content requires `ravenroot.palette.manage`. Workload identities cannot use either operation. A platform administrator still reads only their own palette namespace; changing a tenant in a request does not select another user's palettes.

## Saved content

Ravenroot derives saved behavior properties from the current trusted node descriptor. It stores ordinary authored properties and opaque workspace or broker profile references. It omits secret references, adapter bindings, undeclared fields, node IDs, coordinates, edges, runtime state, parser metadata, credentials, endpoints, and other server authority. Structural nodes use a small explicit allowlist for join settings.

Before saving and again before insertion, package behaviors may validate operator-owned references for the authenticated tenant. The AMQP package resolves `brokerProfile` only as a tenant-scoped operator profile; its endpoint and credentials never enter the template. The Workbench also requires every workspace reference to name an existing `workspace` behavior node in the destination workflow.

A missing plugin or a destination reference that the authenticated tenant cannot use returns `NODE_TEMPLATE_REFERENCE_UNAVAILABLE` with a fixed actionable message and no resolver detail. Malformed authored properties remain `INVALID_REQUEST`, so clients can distinguish editing the node from choosing an available plugin, profile, policy, or destination reference.

Insertion creates a fresh node ID at the canvas center or drop point, adds no edges, and produces one undo entry. A saved START, END, or ERROR node is refused when the destination already contains that terminal kind.

## Limits and concurrency

Each user may keep up to 32 palettes and 64 templates per palette. Names are at most 120 characters and template request bodies are at most 96 KiB. Updates and deletes use the returned `version` with `If-Match`; a stale version returns a conflict instead of overwriting another session.

Palettes are unavailable when durable execution persistence is disabled. SQLite and PostgreSQL schema migrations create the palette tables automatically. Take the normal verified pre-upgrade backup before applying the forward-only migration.

The SQLite offline recovery bundle includes palettes in `execution-store.db`. For PostgreSQL, palettes are part of the same database as execution state and are covered by the documented whole-database snapshot or dump; do not back up the palette tables separately. See [Backup and recovery](backup-recovery.md) and [Shared PostgreSQL persistence](postgresql-persistence.md#backup-and-restore).
