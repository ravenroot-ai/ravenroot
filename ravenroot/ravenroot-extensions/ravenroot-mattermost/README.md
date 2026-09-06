# Mattermost extension

The Mattermost bundle is optional and independent. It provides `mattermost.send` for API v4 posts and
`mattermost.outgoing-webhook` for Mattermost's token-authenticated outgoing-webhook callback. Install
and enable this bundle by its package id, `ai.ravenroot.extensions.mattermost`; removing that bundle id
and its configuration disables both behaviors without affecting other providers.

`mattermost.send` accepts only this closed payload:

```json
{"version":"mattermost.message.v1","channelId":"<26-character id>","text":"hello","correlationId":"optional"}
```

It posts exactly `{"channel_id":"...","message":"..."}` to the configured origin's
`/api/v4/posts` endpoint. The graph supplies no header or credential. The profile names an opaque
credential binding and secret reference; managed egress resolves that reference and injects the
operator-approved `Authorization: Bearer ...` placement. Results use
`mattermost.message.result.v1` and contain bounded status, identifiers, counters, and evidence only;
message text and provider error bodies are never returned.

Set `RAVENROOT_MATTERMOST_CONFIG` to canonical base64 of a JSON document shaped like this:

```json
{
  "authority": {
    "listenerId": "main",
    "pathPrefix": "/managed/mattermost",
    "requiredScopes": ["mattermost:callbacks"],
    "maxRoutes": 8,
    "maxConcurrentRequests": 32,
    "maxRequestBytes": 1048576,
    "maxResponseBytes": 65536,
    "requestTimeoutMs": 2800
  },
  "projection": {
    "maxRelativePathBytes": 256,
    "maxQueryParameters": 1,
    "maxQueryBytes": 256,
    "maxHeaderCount": 1,
    "maxHeaderBytes": 1024,
    "maxHeaderValueBytes": 512
  },
  "store": {
    "path": "/var/lib/ravenroot/mattermost-deliveries.db",
    "maxDeliveries": 100000,
    "retentionHours": 168
  },
  "profiles": {
    "operations": {
      "tenantId": "tenant-a",
      "origin": "https://mattermost.example.com",
      "teamId": "aaaaaaaaaaaaaaaaaaaaaaaaaa",
      "publicChannels": ["bbbbbbbbbbbbbbbbbbbbbbbbbb"],
      "credentialBindingId": "mattermost-bearer",
      "credentialReference": "mattermost-bot-token",
      "webhookTokenReference": "mattermost-outgoing-token",
      "outgoingWebhookRoute": "/outgoing/operations",
      "limits": {
        "maxTextChars": 4000,
        "maxRequestBytes": 1048576,
        "maxResponseBytes": 65536,
        "maxConcurrency": 4,
        "maxPerSecond": 20,
        "requestTimeoutMs": 2500,
        "retries": 1
      }
    }
  }
}
```

The operator must separately grant managed egress only to the profile origin, allow `POST`, allow the
three non-sensitive request headers used by the bundle, and bind `mattermost-bearer` to that origin's
`Authorization` header with the `Bearer ` prefix. The bot or personal access token must be limited to
posting in the selected team and channels. Keep outbound and webhook tokens in distinct managed-secret
references.

Create a Mattermost outgoing webhook for the same team and one of the profile's listed public channels,
choose either `application/json` or `application/x-www-form-urlencoded`, set its callback to the managed
route, and store the generated token at `webhookTokenReference`. Mattermost outgoing webhooks are only
available for public channels. Ravenroot compares the body token in constant time, drops it before graph
delivery, and narrows every callback to the configured team and channel. The callback's timestamp is
metadata, not a signed nonce. Replay protection binds the stable `post_id` to the exact body digest in
SQLite under tenant, profile, and source identity. A changed body for the same identity returns `409`.

The route acknowledges with `200` only after durable graph custody or a durable duplicate. Local or
managed capacity returns `429`; unavailable storage, cancellation, ambiguous or volatile custody, a
stopped generation, and other unavailable conditions return `503`. Malformed callbacks return `400`,
bad tokens return `401`, and out-of-profile teams or channels return `403`. SQLite retention and row
bounds are per tenant/profile/source, and busy waits honor the callback deadline and cancellation.

For removal, undeploy graphs using these behaviors, remove the package id from enabled plugins, remove
the environment document and both managed-secret bindings, then delete the SQLite file after the chosen
retry and retention window. Removing the outgoing webhook in Mattermost stops new callbacks.
