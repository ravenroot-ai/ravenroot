# Ravenroot Slack extension

`ravenroot-slack` is an optional, independently installable node package for Slack. It provides:

- `slack.post-message` for bounded `chat.postMessage` calls;
- `slack.events` for signed Events API callbacks and URL verification; and
- `slack.commands` for signed slash-command callbacks.

The extension is included by the optional `ravenroot-extensions-all` dependency pack, but not by the
default Ravenroot distribution or OCI image. Classpath presence activates nothing. An embedding
application must register `ai.ravenroot.extensions.slack.SlackNodePackage` and grant its managed
credential, outbound HTTP, ingress authority, and durable-ingress services.

## Slack application setup

Create a Slack app, install it into the intended workspace, and give its bot only the scopes needed by
the configured features. Posting requires `chat:write`; posting to public channels without joining them
also requires `chat:write.public`. Slash commands require `commands`. Events require the event-specific
bot or user scopes documented by Slack. Subscribe only to event types named in the operator profile.

Configure these request URLs on the Slack app, using the public HTTPS endpoints exposed by the trusted
relay and the profile routes below:

- Events API request URL: the managed `eventsRoute`;
- each slash command request URL: the managed `commandsRoute`.

Slack cannot authenticate as a Ravenroot tenant. Put an operator-controlled relay in front of managed
ingress. The relay must authenticate to Ravenroot, select the correct tenant, preserve the exact raw
request body, and forward `Content-Type`, `X-Slack-Signature`, and `X-Slack-Request-Timestamp` without
normalization. It may also forward `X-Slack-Retry-Num` and `X-Slack-Retry-Reason`. Re-encoding JSON or
form data breaks signature verification.

Store the bot token and signing secret in the operator credential provider. GraphML contains only the
opaque `slackProfile` selector and optional limit tightenings; it never contains either secret.

## Operator profile

Set `RAVENROOT_SLACK_CONFIG` to the canonical Base64 encoding of a strict JSON document. Unknown fields,
non-production API origins, duplicate routes, profiles wider than ingress authority, and missing
`chat:write` or `commands` authority are rejected. A representative decoded document is:

```json
{
  "authority": {
    "listenerId": "main",
    "pathPrefix": "/managed/slack",
    "requiredScopes": ["slack:callbacks"],
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
    "maxHeaderCount": 5,
    "maxHeaderBytes": 1024,
    "maxHeaderValueBytes": 512
  },
  "store": {
    "path": "/var/lib/ravenroot/slack-deliveries.db",
    "maxDeliveries": 100000,
    "retentionHours": 168
  },
  "profiles": {
    "operations": {
      "tenantId": "tenant-a",
      "apiOrigin": "https://slack.com",
      "teamId": "T01234567",
      "applicationId": "A01234567",
      "credentialBindingId": "slack-bot",
      "credentialReference": "slack-bot-token",
      "signingSecretReference": "slack-signing-secret",
      "eventsRoute": "/events",
      "commandsRoute": "/commands",
      "channels": ["C01234567"],
      "eventTypes": ["message", "app_mention"],
      "commands": ["/deploy"],
      "scopes": ["chat:write", "commands"],
      "limits": {
        "requestTimeoutMs": 2500,
        "maxRequestBytes": 1048576,
        "maxResponseBytes": 65536,
        "maxTextChars": 4000,
        "maxConcurrency": 4,
        "maxPerSecond": 20,
        "retries": 2,
        "signatureMaxAgeSeconds": 300
      }
    }
  }
}
```

Use a deployment-specific absolute store path with restricted filesystem permissions. The database
contains only tenant/profile/kind identifiers, one-way delivery and body digests, and timestamps. It
does not contain Slack request bodies, signing secrets, bot tokens, message text, or response URLs.

## Graph and payload examples

All three descriptors require `slackProfile=operations`. A sender may additionally set `channelId` to
narrow the node to one profile-authorized channel and may tighten `requestTimeoutMs`, `maxTextChars`,
`maxConcurrency`, or `retries`.

Input to `slack.post-message`:

```json
{
  "version": "slack.message.v1",
  "channelId": "C01234567",
  "text": "Deployment completed.",
  "threadTs": "1725451199.000001",
  "correlationId": "deployment-42"
}
```

The result is `slack.message.result.v1` with a content-free status, channel, attempt count, HTTP code,
correlation ID, and structural evidence. It never repeats the message or a Slack error body.

`slack.events` emits `slack.event.v1` with the verified `eventId`, `teamId`, `applicationId`, `eventType`,
and Slack `event` object. `slack.commands` emits `slack.command.v1` with structural team, application,
channel, user and command IDs plus command text. It deliberately drops Slack's deprecated verification
token, `response_url`, `trigger_id`, and human-readable workspace/channel/user names.

## Verification, acknowledgement, retries, and limits

The callback sources verify Slack's `v0` HMAC-SHA256 over the exact timestamp and raw body using a
constant-time comparison. Both old and future timestamps outside the configured window are rejected.
Signature and profile authority checks happen before durable ingress. Events use Slack's `event_id` as
their durable delivery identity; commands use a one-way digest of timestamp plus raw body. Bindings
survive restart and reject the same delivery identity with different content.

Ravenroot returns HTTP 200 only after durable commit or a durable duplicate receipt. Capacity returns
429; unavailable, volatile, ambiguous, expired, or cancelled custody returns a retryable 503. The
handler always narrows even a wider runtime request window to at most 2800 ms from request arrival, so
Slack's three-second acknowledgement contract remains fail-closed. URL-verification challenges are
signed, authority-checked control requests and do not enter the graph.

Outbound requests use the fixed `https://slack.com/api/chat.postMessage` destination and an
operator-owned credential binding. The fully encoded JSON body is checked before dispatch. Managed
HTTP limits bound encoded/decoded/projected responses and operation duration. Profile and per-node
concurrency are bounded. Local per-channel admission complements Slack's authoritative limits; HTTP
429 is retried only when a bounded `Retry-After` fits the remaining deadline. Cancellation aborts an
active managed call or backoff. An indeterminate post-dispatch transport failure is never retried.

The extension does not log request bodies, message content, provider error bodies, tokens, signing
secrets, response URLs, or credentials. Keep the relay and the Ravenroot credential provider under the
same operator trust boundary and monitor only sanitized status categories.

Protocol references: [verifying Slack requests](https://docs.slack.dev/authentication/verifying-requests-from-slack/),
[Events API](https://docs.slack.dev/apis/events-api/),
[slash-command acknowledgement](https://docs.slack.dev/interactivity/implementing-slash-commands/), and
[Web API rate limits](https://docs.slack.dev/apis/web-api/rate-limits/).
