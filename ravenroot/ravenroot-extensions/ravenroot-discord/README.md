# Ravenroot Discord extension

`ai.ravenroot.extensions.discord` is an optional Node SDK `/2` package for bounded Discord channel
messages and signed guild slash-command ingress. It contributes `discord.send` and
`discord.interactions`. Classpath presence grants nothing: an operator must install the artifact,
activate `ai.ravenroot.extensions.discord.DiscordNodePackage`, and separately grant its ingress and
managed outbound HTTP authorities. It is not in Ravenroot's default distribution or OCI image.

## Discord application setup

Create a Discord application and install it only in approved guilds. Register the slash commands
named in the operator profile. An installation that sends messages normally needs the `bot` and
`applications.commands` OAuth2 scopes, `Send Messages`, and `Attach Files` only when attachments are
enabled. This extension uses HTTP interactions, not the Gateway, so it requests no Gateway intents.
Use a separate application/profile where a narrower permission boundary is required.

Store the bot token in Ravenroot's operator credential store. The Discord configuration contains
only an opaque credential reference and binding ID. Configure the managed HTTP binding to inject
that credential as the `Authorization` header with the `Bot ` prefix, and constrain the grant to
`https://discord.com`, `/api/v10/channels/<allowed-channel>/messages`, `POST`, request headers
`accept`, `content-type`, and `user-agent`, and response headers `content-type`, `content-encoding`,
`retry-after`, `x-ratelimit-bucket`, `x-ratelimit-remaining`, and `x-ratelimit-reset-after`.

## Authenticated interaction relay

Ravenroot managed ingress authenticates and authorizes a Ravenroot principal before an extension
handler runs. Discord does not carry that credential, so Discord must not call the managed route
directly. An operator-managed relay authenticates to the Ravenroot listener as the target tenant and
forwards the unchanged body and exactly `X-Signature-Ed25519` and `X-Signature-Timestamp`. It must
not parse, reserialize, decompress, or otherwise transform the body.

The extension verifies Ed25519 over the timestamp followed by those exact body bytes before JSON
parsing or durable work. It also enforces finite signature age and future-clock-skew bounds, then
checks the configured application, guild, channel, and command. Invalid signatures return `401`;
malformed requests return `400`; disallowed authority returns `403`.

A valid Discord PING returns protocol PONG immediately. PING validates the endpoint and never enters
the graph. A permitted application command is first bound by interaction ID and body digest in the
extension's bounded SQLite store, then offered through Ravenroot's durable ingress. Only
`DurablyCommitted` and `Duplicate` receive the fixed ephemeral `Accepted for processing.` response.
Full admission returns `429`; volatile, ambiguous, unavailable, or expired custody returns `503`, so
the provider may retry rather than advance past an unprotected event. Run one Ravenroot replica per
deployment unless the configured execution store and route ownership provide stronger shared
fencing.

The delivered `discord.interaction.v1` payload contains the structural interaction, application,
guild and channel IDs, the allowed command name, and its bounded `data` object. The interaction token
is removed. Components, modals, autocomplete, direct messages, deferred follow-ups, and retained
interaction-token operations are outside v1.

## Operator configuration

`RAVENROOT_DISCORD_CONFIG` is canonical Base64 of strict JSON. Unknown fields, duplicate values,
authority widening, non-production API origins, unsafe routes, and unsupported limits fail package
activation. The example shows the decoded shape; replace every placeholder and encode the complete
document as standard padded Base64:

```json
{
  "authority": {
    "listenerId": "managed-main",
    "pathPrefix": "/managed/discord",
    "requiredScopes": ["discord:interactions"],
    "maxRoutes": 8,
    "maxConcurrentRequests": 32,
    "maxRequestBytes": 1048576,
    "maxResponseBytes": 65536,
    "requestTimeoutMs": 2500
  },
  "projection": {
    "maxRelativePathBytes": 256,
    "maxQueryParameters": 1,
    "maxQueryBytes": 256,
    "maxHeaderCount": 2,
    "maxHeaderBytes": 512,
    "maxHeaderValueBytes": 256
  },
  "store": {
    "path": "/var/lib/ravenroot/discord-deliveries.db",
    "maxDeliveries": 100000,
    "retentionHours": 168
  },
  "profiles": {
    "operations": {
      "tenantId": "tenant-a",
      "apiOrigin": "https://discord.com/api/v10",
      "applicationId": "123456789012345678",
      "publicKeyHex": "eb6fa3a04b766ee3ef693301641cc4f20870b87b0c9d077665ca1339106585b3",
      "guilds": {
        "223456789012345678": ["323456789012345678"]
      },
      "commands": ["deploy"],
      "credentialBindingId": "discord-bot",
      "credentialReference": "discord-bot-token",
      "route": "/interactions",
      "limits": {
        "requestTimeoutMs": 2000,
        "maxRequestBytes": 1048576,
        "maxResponseBytes": 65536,
        "maxContentChars": 2000,
        "maxAttachmentBytes": 1048576,
        "maxAttachments": 4,
        "maxConcurrency": 4,
        "maxPerSecond": 20,
        "retries": 2,
        "signatureMaxAgeSeconds": 300,
        "futureSkewSeconds": 30
      }
    }
  }
}
```

The store records only tenant/profile/application/interaction structural identity, a SHA-256 body
digest, and update time. It never stores the request, signature, token, command options, message
content, attachment bytes, or provider error body. An exact replay may reach Ravenroot's durable
deduplication; reuse of an interaction ID with different signed content returns `409`. Expired rows
are pruned within the configured retention period and `maxDeliveries` bounds each tenant/profile.

## Graph configuration and payloads

Both nodes require only the opaque `discordProfile`. `discord.send` additionally permits
`channelId`, `requestTimeoutMs`, `maxContentChars`, `maxAttachmentBytes`, `maxAttachments`,
`maxConcurrency`, and `retries`; each value may only narrow its operator profile. No graph property
can supply an API origin, key, token, route, guild, command, credential binding, or rate ceiling.

The send input is exact and versioned:

```json
{
  "version": "discord.message.v1",
  "channelId": "323456789012345678",
  "content": "Deployment accepted",
  "attachments": [
    {
      "contentBase64": "cHJvb2YK",
      "filename": "proof.txt",
      "mediaType": "text/plain"
    }
  ],
  "correlationId": "deployment-42"
}
```

Content is limited to 2,000 Unicode code points and an operator may lower it. Attachments are inline
standard Base64 and are bounded by count and aggregate decoded bytes. V1 allows PNG, JPEG, GIF,
plain text, and PDF with conservative filenames. Remote URLs, filesystem paths, embeds, components,
polls, TTS, and mention expansion are rejected. Every request sets `allowed_mentions.parse` to an
empty list.

`discord.message.result.v1` contains only a stable status, channel ID, correlation ID, attempt,
HTTP status, and structural evidence. It never includes message content, attachment metadata,
credentials, or remote error text. Statuses include `sent`, `rate-limited`, `capacity`,
`authentication-failed`, `forbidden`, `rejected`, and `indeterminate`.

## Limits, retries, and cancellation

Every outbound request supplies explicit finite request, encoded-response, decoded-response,
projected-output, decompression-ratio, deadline, and cancellation limits. The managed service
intersects them with operator policy. Successful responses must be JSON; error bodies remain opaque
and are never parsed or reported. The final result is checked against the effective operator output
ceiling returned by the managed service.

Local concurrency and per-second admission are partitioned by tenant/profile. A provider bucket
reported exhausted by bounded `X-RateLimit-Remaining` and `X-RateLimit-Reset-After` headers blocks
later local dispatch until its capped reset. Provider `429` responses are retried only when a valid
`Retry-After` fits the remaining deadline and the configured attempt ceiling. Managed pre-dispatch
admission refusal may also be retried within that ceiling.
Timeout, cancellation, transport loss after possible dispatch, and post-effect accounting failure
are never blindly retried because Discord message creation has no extension-enforced exactly-once
boundary. `discord.send` therefore does not declare recovery repeatability. Cancellation cancels the
active managed call and releases both node and profile permits.

The module emits no application logs or telemetry. Operators should monitor only Ravenroot's
content-free managed-service and ingress status dimensions; never add bot tokens, interaction
payloads, signatures, message content, command options, attachments, or raw provider errors to
diagnostics.

Provider protocol references: [Receiving and responding to
interactions](https://docs.discord.com/developers/interactions/receiving-and-responding),
[Create Message](https://docs.discord.com/developers/resources/message#create-message), and
[rate limits](https://docs.discord.com/developers/topics/rate-limits).
