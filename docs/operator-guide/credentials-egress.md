# Credentials, connectors, and egress

Provision privileged dependencies so references are usable but secret material and outbound authority remain contained.

## Operator procedure

1. Configure the credential backend and create secrets through the write-only API; inventory only metadata and minted references.
2. Install connector or model adapters from trusted packages and review their declared capabilities.
3. Set exact allowed hosts and agent-tool allowlists for the deployment; deny everything outside them.
4. Run provider verification and a non-destructive connector check before allowing an effectful graph.

## Authority

The operator grants deployment-level availability. Callers may use only resources they own, and GraphML may carry references but never secret values or new network rights.

## Verification

Verify that credential reads omit secret material, disallowed destinations fail closed, and adapter removal makes dependent nodes unavailable.

- [Contract](../security/input-secrets-egress.md)
- [Runbook](../troubleshooting/ai-extensions.md)
