# Human Task operations

Human Task authorization is tenant-local in every mode. A fresh deployment leaves responder
requirements disabled, so any authenticated tenant principal may resolve or deny; only the original
requester may cancel. Enable `humanTask.responderEnforcementEnabled` in Helm or
`RAVENROOT_HUMAN_TASK_RESPONDER_ENFORCEMENT_ENABLED=true` elsewhere to require every graph-authored
role and scope. Verify the effective value and override scope in `GET /v1/configuration` before
admitting graphs. Ravenroot consumes identity claims from the configured authenticator and does not
provision users or roles.

Use `ravenroot.human-task.override` only with `TENANT_ADMIN` or `PLATFORM_ADMIN`. Every override must
carry an operator reason and produces separate audit evidence. Do not use override to make a routine
responder workflow; correct the upstream roles/scopes or task authoring instead.

## Register presentation hosts

Keep the closed interaction registry in a secret-managed, read-only file. Compose requires an
override mount. A Kubernetes Kustomize overlay must add its own Secret mount and set the environment
path. Helm accepts `humanTask.interactionConfigSecret`; put the complete document under the Secret's
`config.json` key. Rotate capability/provider signing secrets by replacing the file and restarting
all replicas together; existing capabilities then fail safely and tasks remain recoverable.

```json
{
  "schemaVersion": 1,
  "capabilityTtlSeconds": 300,
  "maxCompletionBytes": 65536,
  "capabilitySecretBase64": "<at-least-32-random-bytes>",
  "profiles": [
    {"id":"expense","version":1,"kind":"CUSTOM","launchUri":"https://forms.example/expense","origin":"https://forms.example"},
    {"id":"approval","version":2,"kind":"EXTERNAL","launchUri":"https://provider.example/task","origin":"https://provider.example","completionSecretBase64":"<independent-at-least-32-byte-secret>"}
  ]
}
```

Never put the file or its secrets in GraphML, a public ConfigMap, served configuration, logs, or an
external host's launch data. Register only origins you operate or contractually trust. External
providers must sign the exact callback body and preserve the capability unchanged.

## Inventory and reconciliation

Begin with `GET /v1/admin/human-tasks` and an exact or bounded selector. Rows are payload-free and
classify actionable, terminal, orphaned, and non-resumable work. Dry-run purge first. `CANCEL`
performs normal graph re-entry; `FORCE_ABANDON` atomically closes only the selected task's handler,
timers, traversal/process work, pending continuation, and audit event without re-entry. It does not
delete history. Supply an `Idempotency-Key` and repeat safely after a lost response.

SQLite and PostgreSQL upgrades add presentation columns and capability revocations automatically;
old rows remain classic tasks. Back up before upgrade, check readiness, list a known pre-upgrade task,
exercise one dry-run reconciliation, then test capability revocation across a replica/restart before
restoring traffic.

