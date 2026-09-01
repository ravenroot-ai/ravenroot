# Ravenroot Telegram extension

`ravenroot-telegram` is a source-installable Node SDK package that contributes four ordinary
request/response behaviors: `telegram.send`, `telegram.answer.callback`, `telegram.edit.message`,
and `telegram.delete.message`. They use the official Telegram Bot API for bounded sends, callback
acknowledgements, explicit text/caption/markup edits, and explicit deletes.

This module is deliberately not included in the standard Ravenroot image. Build or install it from
source:

```shell
./mvnw -pl ravenroot-extensions/ravenroot-telegram -am verify
```

These source-only Node SDK behaviors are not added to the standard image, palette, or Inspector.

## Operator-owned configuration

Graphs contain only an opaque `botProfile` name and optional limits that can tighten the profile.
They cannot select an endpoint, bot token, credential reference, chat allowlist, API method, URL
allowlist, or business authority. The production origin is always `https://api.telegram.org`, and
redirect following is disabled.

The built-in environment resolvers encode identifiers as uppercase hexadecimal UTF-8 bytes. For
tenant `acme` and profile `alerts`, set:

```text
RAVENROOT_TELEGRAM_PROFILE_61636D65_616C65727473=
  telegram-alert-bot;-100123,@channel_name;sendMessage,sendPhoto,answerCallbackQuery,editMessageText,editMessageCaption,editMessageReplyMarkup,deleteMessage;example.test;false;4;20;1000;5000;4096;10000000;20;1
RAVENROOT_TELEGRAM_CREDENTIAL_74656C656772616D2D616C6572742D626F74=
  <BotFather token>
```

Remove whitespace around the values. The 13 semicolon-separated profile fields are, in order:

1. credential reference;
2. allowed chat IDs/usernames (`*` permits all);
3. allowed methods (`sendMessage`, `sendPhoto`, `answerCallbackQuery`, `editMessageText`,
   `editMessageCaption`, `editMessageReplyMarkup`, `deleteMessage`);
4. allowed HTTPS button hosts (`*` permits all);
5. business-connection authority;
6. maximum concurrency (1–16);
7. requests per second (1–30);
8. connect timeout in milliseconds (100–10000);
9. request timeout in milliseconds (100–30000);
10. maximum text characters (1–4096);
11. maximum decoded photo bytes (1–10000000);
12. maximum buttons (0–100);
13. pre-accept/429 retries (0–3).

Tokens are resolved on every invocation, so rotation and revocation take effect without rebuilding a
graph. Resolution is tenant/profile scoped; returned profile identity must match the request. Secret
buffers are closed and cleared immediately after use. Do not put tokens in GraphML, payloads, node
properties, URLs, logs, exceptions, or documentation. Create and revoke tokens with Telegram's
[official BotFather workflow](https://core.telegram.org/bots/features#botfather).

## Graph configuration

```xml
<node id="telegram">
  <data key="behavior">telegram.send</data>
  <data key="botProfile">alerts</data>
  <data key="requestTimeoutMs">3000</data>
  <data key="maxTextChars">1024</data>
  <data key="maxConcurrency">2</data>
  <data key="retries">0</data>
</node>
```

The optional properties are tightening-only: a value above the operator profile ceiling is rejected.

## Input contract

### Send

The payload version is `telegram.send.v1`:

```json
{
  "version": "telegram.send.v1",
  "chatId": "-100123",
  "messageThreadId": 42,
  "text": "Deployment complete",
  "parseMode": "HTML",
  "disableNotification": false,
  "protectContent": true,
  "replyToMessageId": 41,
  "inlineKeyboard": [[
    {"text": "Details", "url": "https://example.test/deploy/42"},
    {"text": "Acknowledge", "callbackData": "ack:42"}
  ]],
  "correlationId": "deploy-42"
}
```

`chatId` is a non-zero signed 64-bit decimal ID or an `@username`, and must be allowed by the profile.
Text is well-formed Unicode and bounded by both Telegram and the profile. `parseMode` is `HTML` or
`MarkdownV2`; alternatively, supply safe non-link `entities` with Telegram UTF-16 offsets. Inline
keyboards support only bounded callback data (1–64 UTF-8 bytes) and HTTPS URLs whose host is allowed by
the profile.

For a photo, add an object containing `contentBase64`, a safe `filename`, and one of `image/jpeg`,
`image/png`, or `image/webp`. Filesystem paths, file URLs, remote media URLs, Telegram `file_id` values,
and arbitrary multipart fields are not accepted.

### Answer a callback

`telegram.answer.callback.v1` acknowledges one normalized callback-query identifier. A callback
should be acknowledged before slow graph work because Telegram clients keep showing progress until
the bot answers it.

```json
{
  "version": "telegram.answer.callback.v1",
  "callbackId": "4382...",
  "text": "Accepted",
  "showAlert": false,
  "cacheTime": 0,
  "url": "https://example.test/game",
  "correlationId": "callback-42"
}
```

Text is limited to 200 characters, `cacheTime` to 0–86400 seconds, and URLs to HTTPS hosts allowed by
the operator profile. Telegram gives `url` special game or `t.me` semantics; the node validates
transport and host authority but does not reinterpret those semantics. A bounded tenant/profile/
callback registry reserves the first acknowledgement attempt. Reuse is rejected locally as
`DUPLICATE_CALLBACK`; Telegram's response for an already-old query becomes `EXPIRED`/
`CALLBACK_EXPIRED`. The extension does not contribute an inbound behavior: the example assumes a
separately normalized callback fixture.

### Edit a message

`telegram.edit.message.v1` requires an explicit `editType` of `text`, `caption`, or `markup`. Address
the target using exactly one form: `chatId` plus `messageId`, or `inlineMessageId`.

```json
{
  "version": "telegram.edit.message.v1",
  "editType": "text",
  "chatId": "-100123",
  "messageId": 42,
  "text": "Updated deployment status",
  "parseMode": "HTML",
  "inlineKeyboard": [[{"text": "Details", "url": "https://example.test/deploy/42"}]],
  "correlationId": "edit-42"
}
```

Caption edits use the same `text` input and call `editMessageCaption`. Markup-only edits require
`inlineKeyboard`; an empty array removes the keyboard. Text, entity, button, URL-host, chat, and
business-connection authority remain bounded by the profile. The node never substitutes a send when
an edit fails, and it does not support media replacement, uploads, or arbitrary Bot API fields.

### Delete a message

`telegram.delete.message.v1` requires an allowed `chatId` and a positive `messageId`:

```json
{
  "version": "telegram.delete.message.v1",
  "chatId": "-100123",
  "messageId": 42,
  "correlationId": "delete-42"
}
```

Enabling `deleteMessage` in a profile is an explicit operator decision. A local authorization does
not guarantee deletion: Telegram applies message-age, dice, service-message, chat-role, and bot
permission rules, including the usual 48-hour limit.

### Receive → acknowledge → act

A typical callback flow normalizes an inbound callback to a bounded fixture, immediately invokes
`telegram.answer.callback`, then runs slower work and optionally invokes `telegram.edit.message` or
`telegram.send`. A delete is always a separate explicit action. This keeps acknowledgement latency
independent of downstream work and makes every remote side effect visible in the graph.

## Results and delivery semantics

Results contain the request version, a safe status, chat/correlation identifiers, attempt number,
numeric API code, stable message, and safe metadata. Successful responses may include `messageId` and
`timestamp`; error responses may include Telegram's documented `retry_after` and
`migrate_to_chat_id`. Telegram descriptions and response bodies are never propagated.

Send statuses are `SENT`, `REJECTED`, `RATE_LIMITED`, `TEMPORARY_FAILURE`, `PERMANENT_FAILURE`, or
`AMBIGUOUS`. Action successes are `ANSWERED`, `EDITED`, or `DELETED`; callback expiry is `EXPIRED`.
Stable messages distinguish duplicates, unchanged edits, missing messages, permission failures, and
local or remote rate limits without exposing Telegram descriptions. The nodes retry only failures
known to occur before connection acceptance and bounded HTTP 429 responses. They do not retry 5xx
responses, read/request timeouts, malformed or oversized
responses, or other post-transmission I/O failures; those cases avoid unsafe duplicate sends and are
reported as temporary or ambiguous as appropriate. Local bounded admission covers global, tenant,
profile, and node-action concurrency plus a tenant/profile rate window.

The rate limit is an exact per-process rolling one-second window driven by `System.nanoTime`, not a
wall-clock or calendar-second bucket. A profile may burst up to `maxPerSecond` calls at once; each
accepted call occupies one slot until exactly one elapsed second after that call. The limiter keeps at
most 4096 tenant/profile keys and retires a key after five monotonic minutes without an observation.
A negative, overflowing, or greater-than-one-hour single ticker step is treated as a discontinuity:
the current call is rejected, no key is evicted, and the affected key receives no capacity until one
full stable second has elapsed. This fail-closed quarantine prevents clock anomalies from silently
refilling or discarding rate state. Concurrent observations are generation-checked at each per-key
linearization point, so a delayed ticker sample cannot replace a newer accepted event or retire its
live rate window.

Protocol limits and response fields follow Telegram's
[Bot API documentation](https://core.telegram.org/bots/api), including
[`answerCallbackQuery`](https://core.telegram.org/bots/api#answercallbackquery),
[`editMessageText`](https://core.telegram.org/bots/api#editmessagetext), and
[`deleteMessage`](https://core.telegram.org/bots/api#deletemessage). Telegram's
[button documentation](https://core.telegram.org/api/bots/buttons) explains callback acknowledgement
latency and game-button behavior.
