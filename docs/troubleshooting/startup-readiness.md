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

## The selected engine is unavailable

**Diagnosis:** `RAVENROOT_ENGINE` names an adapter that is not installed or failed discovery.

**Action:** Set `RAVENROOT_ENGINE=pekko` or install a compatible Akka adapter. Remove incompatible duplicate packages.

**Verify:** Verify the runtime reports the selected engine and that a minimal graph reaches a terminal result.

## Related contracts

- [Primary contract](../operator-guide/deployment-startup.md)
- [Control procedure](../reference/configuration.md)
