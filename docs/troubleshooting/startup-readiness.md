# Startup and readiness

Begin with process and readiness probes, then isolate bind, identity, engine, or dependency admission without opening traffic prematurely.

## Server refuses a non-loopback bind

**Diagnosis:** Authentication is omitted or disabled while the listener is externally reachable.

**Action:** Configure local-token or OIDC authentication before changing the bind address. Use a token of at least 32 characters; use issuer, audience, and JWKS URI for OIDC.

**Verify:** Restart and require `/health` and `/ready` to succeed from the intended network while an unauthenticated request is denied.

## `/health` is up but `/ready` is unavailable

**Diagnosis:** The process lives, but an engine, persistence, plugin, or recovery dependency has not reached traffic-ready state, or drain is active.

**Action:** Read `/v1/runtime` and startup diagnostics. Correct the named dependency; do not route traffic merely because liveness is up.

**Verify:** Confirm `/ready`, `/v1/runtime`, and `/v1/node-types`; then submit a bounded Test execution.

## `/ready` reports `RECOVERING`

**Diagnosis:** The process has started but has not finished classifying the durable work it inherited, so it cannot yet say whether that work is runnable here. A pass that cannot read the execution store does not finish and retries.

**Action:** Expect this briefly on every restart. If it persists, check the execution store is reachable — the same condition also shows as `STORE_DEGRADED` once the pass gets far enough to probe. Do not route traffic while it holds.

**Verify:** Confirm `/ready` reaches `READY`, then read the startup log for the recovery classification line reporting how many inherited instances were found and how many were refused.

## An inherited execution is refused after a restart

**Diagnosis:** The retained graph document or the execution manifest for that instance does not resolve in this deployment: it is missing, does not verify, or the deployment now resolves something the manifest does not describe.

**Action:** Read the refusal logged for that instance; it names the instance and the reason. Restore the deployment the execution was accepted under, or abandon that work deliberately. Refused work is left untouched and still claimable — it is not parked, acknowledged or discarded — so correcting the deployment lets it proceed.

**Verify:** Restart and confirm the instance no longer appears among the refusals in the recovery classification log line.

## The selected engine is unavailable

**Diagnosis:** `RAVENROOT_ENGINE` names an adapter that is not installed or failed discovery.

**Action:** Set `RAVENROOT_ENGINE=pekko` or install a compatible Akka adapter. Remove incompatible duplicate packages.

**Verify:** Verify the runtime reports the selected engine and that a minimal graph reaches a terminal result.

## Related contracts

- [Primary contract](../operator-guide/deployment-startup.md)
- [Control procedure](../reference/configuration.md)
