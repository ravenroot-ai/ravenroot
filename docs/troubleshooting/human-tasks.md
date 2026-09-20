# Human Task troubleshooting

Use exact task ID and generation only after authenticating to the expected tenant. Collection views
are intentionally summary-only; review content appears only on exact authorized detail.

| Symptom | Check | Recovery |
|---|---|---|
| Resolve/deny is unexpectedly allowed | Read `humanTasks.responderEnforcementEnabled` from `/v1/configuration`. | Enable enforcement at startup if task roles/scopes must be mandatory. Do not weaken tenant/auth boundaries. |
| Cancel is refused | Compare the authenticated qualified identity with the original requester. | Re-authenticate as the requester; admin role alone does not replace requester-only cancel. |
| Override is refused | Check `TENANT_ADMIN`/`PLATFORM_ADMIN`, `ravenroot.human-task.override`, tenant, and nonblank reason. | Correct authority or use the normal responder path. Ravenroot does not assign the missing identity claims. |
| Form returns `INVALID_REQUEST` | Compare every field name/type, required value, enum member, date/time format, and response byte limit with the pinned schema. | Submit the exact closed typed map; omit unknown fields and executable/markup content. |
| Registered presentation is unavailable | Check `registeredPresentationsEnabled`, registry file readability, profile id/version/kind, and safe same-origin launch URI. | Repair the operator registry and restart; the task remains open. |
| Callback is forbidden | Check exact `Origin`, external signature over the exact body, task/generation/schema/action binding, and absence of bearer/cookie. | Retry only with the original bounded capability after correcting the provider. Never add ambient credentials. |
| Callback conflicts | Capability is expired/revoked, task generation is stale, or a different settlement already won. | Fetch exact task detail. Issue a fresh capability only while it remains actionable. |
| Success response was lost | Read exact task state, then replay the same capability/body if the provider did not receive a terminal result. | The idempotent callback returns the recorded outcome and cannot create a second continuation. |
| Task survives a close/restart | This is expected: failed/closed/timed-out hosts do not settle it, and revocation is durable. | Reopen exact detail and issue a fresh capability. |
| Admin inventory reports orphaned/non-resumable | Dry-run a narrowly selected admin purge and inspect process/traversal/handler states. | Use `FORCE_ABANDON` with an idempotency key only when normal re-entry is unsafe. |

For migration faults, stop admission, retain the database, inspect the first startup error, and fix
the migration/configuration cause before retrying. Do not delete task, revocation, handler, timer,
continuation, or audit rows independently.

