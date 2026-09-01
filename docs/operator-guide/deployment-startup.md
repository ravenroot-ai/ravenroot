# Deployment and startup

Establish a safe service boundary before admitting workflow traffic.

## Operator procedure

1. Select JAR, distribution archive, or container topology and allocate a durable data path.
2. Bind to loopback for a local deployment; configure authentication before any non-loopback bind.
3. Start the service, then require `/health`, `/ready`, `/v1/runtime`, and `/v1/node-types` to succeed.
4. Publish the UI and API through one origin and expose no internal adapter or store ports.

## Authority

Only the operator chooses listeners, process identity, engine, storage, and admission. A graph or API caller cannot change these controls.

## Verification

Save probe output and the effective configuration fingerprint. Readiness must become unavailable during drain and recover only after a clean restart.

- [Contract](../reference/configuration.md)
- [Runbook](../troubleshooting/startup-readiness.md)
