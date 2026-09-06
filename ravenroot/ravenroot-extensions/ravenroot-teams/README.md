# Ravenroot Microsoft Teams extension

`ravenroot-teams` is an optional, independently installable node package. It contributes:

- `teams.send`, a bounded outbound action for an operator-owned Teams Workflow HTTP trigger; and
- `teams.outgoing-webhook`, a durable source for Teams Outgoing Webhooks relayed through the managed ingress boundary.

The optional `ravenroot-extensions-all` pack includes the artifact. Classpath presence enables nothing:
the embedding application must register `ai.ravenroot.extensions.teams.TeamsNodePackage`, allow its
manifest identity, and grant managed credential, outbound HTTP, ingress authority, and durable ingress
services. Unregister the package, remove its grants and configuration, and restart the embedding service
to remove it.

## Supported Teams surfaces

For outbound messages, create a Power Automate Workflow using **When a Teams webhook request is
received**, restrict the trigger to the intended tenant and service principal, and make its action post
the supplied `text` to the supplied profile-authorized team/channel. Store the renewable OAuth access
token in the operator credential provider. The configured `workflowEndpoint` has no query string or
embedded credential. Managed egress injects `Authorization: Bearer` from the declared credential
binding; extension code cannot read or submit that header. Legacy Office 365 incoming connectors and
anonymous/SAS URLs are intentionally unsupported because those connectors are retired.

Inbound supports Teams **Outgoing Webhooks** only. Teams invokes these webhooks by `@mention` in public
channels and expects a synchronous response within five seconds. Teams sends its HMAC proof in the
provider `Authorization` header, while Ravenroot reserves that header for tenant authentication. Put an
operator-controlled relay in front of managed ingress. The relay must authenticate and select the
Ravenroot tenant, preserve the exact UTF-8 request body, extract only the provider's Base64 HMAC digest
into `X-Ravenroot-Teams-Signature`, and forward `Content-Type`. It must never forward the Teams signing
key, provider Authorization value, or Ravenroot bearer token to the node package. Ravenroot resolves the
signing key independently and recomputes HMAC-SHA256 over the exact body.

## Operator profile

Set `RAVENROOT_TEAMS_CONFIG` to canonical Base64 for a strict JSON document. Unknown fields, duplicate
routes, query-bearing workflow URLs, or profiles wider than ingress authority are rejected. Example:

```json
{
  "authority": {
    "listenerId": "main",
    "pathPrefix": "/managed/teams",
    "requiredScopes": ["teams:callbacks"],
    "maxRoutes": 8,
    "maxConcurrentRequests": 32,
    "maxRequestBytes": 1048576,
    "maxResponseBytes": 65536,
    "requestTimeoutMs": 4500
  },
  "projection": {
    "maxRelativePathBytes": 256,
    "maxQueryParameters": 1,
    "maxQueryBytes": 256,
    "maxHeaderCount": 2,
    "maxHeaderBytes": 1024,
    "maxHeaderValueBytes": 512
  },
  "store": {
    "path": "/var/lib/ravenroot/teams-deliveries.db",
    "maxDeliveries": 100000,
    "retentionHours": 168
  },
  "profiles": {
    "operations": {
      "tenantId": "tenant-a",
      "workflowEndpoint": "https://example.logic.azure.com/workflows/operations/triggers/manual/paths/invoke",
      "microsoftTenantId": "4c7b3e37-289e-4cc3-98ea-e865f40d45bb",
      "teamId": "19:team-id@thread.tacv2",
      "channels": ["19:channel-id@thread.tacv2"],
      "credentialBindingId": "teams-workflow",
      "credentialReference": "teams-workflow-token",
      "signingSecretReference": "teams-outgoing-webhook-key",
      "webhookRoute": "/outgoing",
      "limits": {
        "requestTimeoutMs": 2500,
        "maxRequestBytes": 1048576,
        "maxResponseBytes": 65536,
        "maxTextChars": 4000,
        "maxConcurrency": 4,
        "maxPerSecond": 20,
        "ackTimeoutMs": 4000,
        "signatureMaxAgeSeconds": 300
      }
    }
  }
}
```

Use a deployment-specific store path with restricted permissions. The SQLite store contains only tenant,
profile, callback kind, bounded structural activity IDs, one-way body digests, and timestamps. It contains no message body,
HMAC key, OAuth token, endpoint, or provider response.

## Node contracts

Both nodes require `teamsProfile=operations`. `teams.send` may further narrow `channelId`,
`requestTimeoutMs`, `maxTextChars`, and `maxConcurrency`.

`teams.send` accepts:

```json
{
  "version": "teams.message.v1",
  "channelId": "19:channel-id@thread.tacv2",
  "text": "Deployment completed.",
  "correlationId": "deployment-42"
}
```

The Workflow receives that shape plus the profile's `microsoftTenantId` and `teamId`. The result is
`teams.message.result.v1`, containing only status, channel ID, correlation ID, attempt, HTTP status and
structural evidence. Any post-dispatch transport failure is indeterminate and is not retried. Provider
response bodies and message text never enter the result or logs.

`teams.outgoing-webhook` emits `teams.outgoing-message.v1` with the signed activity ID/timestamp,
Microsoft tenant/team/channel, sender/recipient/conversation IDs and bounded text. Timestamp skew beyond
the profile window, tenant/team/channel mismatch, malformed JSON and HMAC mismatch fail before graph
admission. Activity ID is durably bound to a body digest across restart; reuse with different content is
rejected. Ravenroot returns its fixed bounded `Accepted.` Teams message only after a durable committed or
duplicate receipt. Capacity maps to 429 and unavailable, volatile or ambiguous custody maps to 503.
Teams does not document a delivery retry guarantee, so operators must monitor rejected callbacks.

Protocol references: [Teams Outgoing Webhooks](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-outgoing-webhook),
[Teams webhooks and Workflows](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/what-are-webhooks-and-connectors),
[OAuth authentication for Workflow HTTP triggers](https://learn.microsoft.com/en-us/power-automate/oauth-authentication),
and [Office 365 connector retirement](https://devblogs.microsoft.com/microsoft365dev/retirement-of-office-365-connectors-within-microsoft-teams/).
