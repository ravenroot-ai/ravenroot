# Credentials, providers, and artifacts

Configure references to privileged services while keeping secret values and executable approval outside the graph.

## Procedure

1. Create a credential through the credentials surface and retain the server-minted reference; the secret cannot be read back.
2. Create a model-provider profile with adapter, endpoint, model, credential mode, and credential reference; run verification before use.
3. Create a program artifact, validate and test it, then obtain the approvals required for activation.
4. Select only references visible to the current principal when configuring a node in the Inspector.

## Authority boundary

Users choose among operator-enabled resources they own. They cannot reveal a stored secret, bypass egress, install an adapter, or self-approve code.

## Verification

Require credential metadata without a secret, a usable provider verification, and an active artifact identity before selecting those references in a graph.

- [Reference contract](../reference/api-cli.md)
- [Concept or recovery](../troubleshooting/ai-extensions.md)
