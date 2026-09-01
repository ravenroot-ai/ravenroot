# Ravenroot mail extension

`ai.ravenroot.extensions.mail.MailNodePackage` contributes `mail.send`, `mail.imap.query`,
`mail.imap.consume`, `mail.imap.move`, and `mail.imap.delete`. Prefer building and explicitly enabling the validated
plugin bundle (`./plugin.sh build mail`); a direct classpath deployment can instead set
`RAVENROOT_NODE_PACKAGES=ai.ravenroot.extensions.mail.MailNodePackage`.

The graph stores only `mailProfile`, never an endpoint, authentication choice, or password. The operator
binds that opaque name (and the deployment tenant) atomically to SMTP endpoint, TLS mode, authentication
username and credential reference, sender/recipient/header allowlists, and hard limits. The default
environment resolver reads `RAVENROOT_MAIL_PROFILE_<TENANT_HEX>_<NAME_HEX>` as an operator-only value
of ten mandatory fields plus an optional eleventh, `allowedReplyTo`; omitting the eleventh keeps
the ten-field compatibility behaviour, where the Reply-To allow-list is the sender allow-list. See
`docs/deployment.md`, "Operator mail profile", for the field-by-field
format, a conformant example, and the rejection contract -- kept there as the single description so
this README cannot repeat it out of step with the parser. Each
`*_HEX` token is the uppercase two-digit hexadecimal encoding of every UTF-8 byte, preserving case and
separating values injectively: tenant `tenant-a` and profile `Primary` use
`RAVENROOT_MAIL_PROFILE_74656E616E742D61_5072696D617279`. `allowPlaintext` is an operator-only boolean:
SMTP is accepted only when it is true, authentication is absent, and the authoritative host is exactly
`localhost`, `127.0.0.1`, or `::1`. The credential resolver maps the exact UTF-8 bytes of the
case-sensitive reference to uppercase hexadecimal: `credentialRef=primary` resolves from
`RAVENROOT_MAIL_CREDENTIAL_7072696D617279`. Malformed UTF-16 references, including isolated surrogate
code units, are rejected instead of being replaced and aliased. Legacy normalized names such as
`RAVENROOT_MAIL_CREDENTIAL_PRIMARY` stop resolving and must be migrated before deployment. This is an
operator-configuration compatibility break: the environment is trusted deployment input and the mapping
prevents two distinct operator references from selecting the same secret. It is not the graph-reachable
credential exposure addressed by the separate core credential-boundary work. Legacy graph fields are accepted only
when they exactly match the selected profile (numeric limits may only tighten it); they cannot redirect
delivery or relax a policy. `mail.send.v1` payloads are objects
with `version`, non-empty `to`, and `text` and/or `html`; optional fields are `cc`, `bcc`, `from`,
`replyTo`, `subject`, safe string `headers`, structured `attachments`, and `correlationId`. Attachment
`content` is an RFC 4648 Base64 `String` in JSON/public payloads, or an in-JVM `byte[]`; paths and URLs
are rejected. Results are `mail.send.v1` objects with `SENT`, `PARTIAL`, `REJECTED`, or `AMBIGUOUS`
status and safe recipient data. `AMBIGUOUS` means delivery state is unknown after SMTP DATA may have been
accepted: it deliberately has no accepted/rejected recipient assertion and must not be retried automatically.

`securityMode` is operator-controlled: STARTTLS is required rather than opportunistic, TLS verification is
always enabled, and authentication is rejected for plain SMTP. Limits cover recipients, combined body/header
size, attachment count, per-attachment bytes, aggregate decoded bytes, and aggregate Base64 bytes. Empty
sender, recipient, reply-to, or header policies deny all values; `*` is the explicit wildcard that allows any
value. Connection retries occur only before `sendMessage`; a DATA operation is never retried because a server
might have accepted it. Blocking SMTP work runs on virtual threads, never the common pool. JavaMail requires a short-lived immutable password `String` at `Transport.connect`; the
source `char[]` and `SecretValue` are cleared immediately after that String is formed and before the first
connection attempt; only the unavoidable String remains for the bounded retry sequence and it is never stored or logged.
All transport failures are converted to safe typed errors with no protocol/server cause chain exposed to execution monitoring. Tenant and profile identifiers are bounded safe tokens. Inspector legacy endpoint, TLS, authentication, sender and limit fields are migration-only: authority fields exact-match the profile and numeric fields can only tighten it. The optional Inspector `maxConcurrency` integer accepts 1–16; when blank it uses the operator profile ceiling, and when set it can only tighten that ceiling. Admission is fail-fast and acquired before virtual-thread submission: the extension has a global cap of 32, an exact tenant cap of 16, an exact tenant/profile cap from the operator profile (1–16), and a per-NodeAction, per-tenant graph-tightened cap. These quotas reserve headroom so one tenant cannot occupy every global slot; they are capacity limits, not a fairness or scheduling guarantee.

## One-shot IMAP queries

The package also contributes `mail.imap.query`. It opens one read-only IMAP session per invocation and always closes the folder and store; it never listens, polls, uses IDLE, or changes flags. GraphML stores only an opaque tenant-scoped `profile` and tightening defaults. The resolver reads `RAVENROOT_IMAP_PROFILE_<TENANT_HEX>_<PROFILE_HEX>` as `host;port;IMAPS|STARTTLS;username;credentialRef;folders;connectMs;readMs;concurrency;results;previewChars`. All eleven fields are mandatory; unlike the SMTP format nothing has been appended to it, so ten fields, or twelve, are simply wrong rather than a compatibility shape. `folders` is a comma-separated set; the other ten are single values. `securityMode` is matched **case-sensitively** against `IMAPS` and `STARTTLS` — `imaps` is rejected, which is *not* how the SMTP format behaves. `username` and `credentialRef` must **both** be non-blank; the SMTP format's "both blank means unauthenticated" shape has no IMAP equivalent, because a read-only query always authenticates.

Rejection contract. An **absent** profile and a **malformed** one are distinguishable. An absent profile — the variable unset, or an identifier that never reaches a lookup — is silent. A profile that is *present and rejected* emits one record at level `WARNING` on logger `ai.ravenroot.mail.imap.profile.rejected`, reading `ravenroot_imap_profile_rejected tenant=<t> profile=<p> constraint=<NAME>`. **Search for the token `ravenroot_imap_profile_rejected`, or for the logger name — not for the word `WARNING`.** With no logging configuration the JDK's default handler may localise the level label, so the message body is the only locale-independent part of the line. `NAME` is one of `FIELD_COUNT`, `HOST_BLANK`, `PORT_FORMAT`, `PORT_RANGE`, `UNKNOWN_SECURITY_MODE`, `USERNAME_BLANK`, `CREDENTIAL_REF_BLANK`, `DUPLICATE_FOLDER`, `FOLDERS_EMPTY`, `CONNECT_TIMEOUT_FORMAT`, `CONNECT_TIMEOUT_RANGE`, `READ_TIMEOUT_FORMAT`, `READ_TIMEOUT_RANGE`, `CONCURRENCY_FORMAT`, `CONCURRENCY_RANGE`, `MAX_RESULTS_FORMAT`, `MAX_RESULTS_RANGE`, `PREVIEW_CHARS_FORMAT`, `PREVIEW_CHARS_RANGE`. Every one of these names a **constraint**, not the act of rejecting, and every one is reachable. `RECORD_POLICY` also exists but is measured unreachable under today's `ImapProfile`; it is a residual net so that a constraint added to the record later still fails closed with a label rather than silently. The line carries **only** the two identifiers and the name: no host, port, username, credential reference, folder name or any other part of the value ever appears on it — the exception messages that would have carried them (`Integer.parseInt`'s `For input string: "…"`, `Set.of`'s `duplicate element: …`) are pre-empted by name rather than caught and relabelled. The return type is unchanged: the diagnostic is for the operator reading logs, and a graph author still sees only `PROFILE_UNAVAILABLE`.

Three field shapes are worth writing down because they are not what an operator would guess, and all were measured rather than assumed. `folders` is checked in two separate places, in this order: first the resolver splits the raw field on commas and rejects a **repeated raw entry** as `DUPLICATE_FOLDER` — this runs before any name is stripped or discarded, and it compares entries byte-for-byte, unstripped. Only after that check passes does `ImapProfile` strip every name and discard the ones that go blank, then test what is left: a field that is **blank or whitespace-only**, or one consisting only of commas, reduces to nothing and is refused as `FOLDERS_EMPTY`. The tolerance this gives a stray comma is **not** a count of blank entries — it is that no two raw entries are byte-identical. Any number of blank entries survive and are silently dropped, provided no two of them are written the same way: `INBOX, ,Archive,,Notes` has two blank entries, `" "` and `""`, which differ, so the raw-entry check does not fire and the profile resolves with folders `{INBOX, Archive, Notes}`; `,INBOX, ` is the same shape (leading comma, trailing comma-space) and resolves with `{INBOX}`. `INBOX,,Archive,,Notes` and `,INBOX,,Archive`, by contrast, each repeat the identical empty string `""` twice — exactly like `INBOX,INBOX` repeats `INBOX` — so `DUPLICATE_FOLDER` catches them and the whole profile is refused, not just the extra blank name. And `previewChars` is the one numeric field whose floor is `0` rather than `1`; every other numeric field rejects `0`.

Inputs use `mail.imap.query.v1` with typed address/subject/unseen/date/UID criteria, a limit, an optional `{uidValidity,lastUid}` cursor, and an optional `contentMode`. Results are ascending by UID and include bounded safe metadata, the body in the selected mode, attachment metadata without content, a folder-scoped cursor, and `hasMore`. Secrets and raw server diagnostics never appear in graph or result data.

### Body modes: `preview` and `full`

`contentMode` exists in **two** places, and they do not mean the same thing. The **payload field**
`"contentMode": "preview" | "full"` is authoritative for that invocation. The **node property**
`contentMode`, with the same two values and default `preview`, supplies only the value used when the
payload omits the field. Both are refused as `INVALID_INPUT` when they carry anything else — the node
property at build time, like an unusable `limit`; the payload field before any credential lookup. The
reason the node property exists at all, rather than the payload field alone, is that a payload field
is neither visible in the Inspector nor carried by GraphML, and a body-mode selection had to be both.

In `preview` the result retains its compatibility shape: `textPreview` truncated to
the profile's `maxPreviewChars`, `previewTruncated`, and `attachments`. No field was added to it. A
payload written before `contentMode` existed therefore gets back precisely what it got back before.

In `full` the row carries **no** `textPreview` and **no** `previewTruncated`, and carries instead:

```text
content { mode: "full", text, html, complete: true, attachmentBodiesIncluded: false }
```

Dropping the two preview fields is the point, not a side effect: a successful `full` result that also
shipped a shortened copy of the same body would be the silent downgrade this mode exists to forbid,
and no caller could tell the two apart from the payload alone. `complete` is the unambiguous marker
that replaces them. `text` is the concatenation, in document order, of every non-attachment
`text/plain` part; `html` the same for `text/html`, or `""` when there is none. The separator is a
single `\n` **between** parts and never around them, so the single-part case — which is the norm — is
byte-exact with respect to the part it came from. Multiple textual parts are concatenated rather than
reduced to the first because discarding the rest would be an abbreviation. `attachments` keeps the
same metadata it has in `preview`, in both modes.

**`maxPreviewChars` is inert in `full`.** It is not merely ignored: no full code path receives it, so
changing the operator's preview budget cannot move a byte of a full result. The ceiling that does
apply in full is the infrastructure one, 1 MiB, which is roughly sixteen times the largest
`maxPreviewChars` an `ImapProfile` will accept (65,536).

**Infrastructure limits fail typed; they never downgrade.** A message over 1 MiB, an aggregate read
over 1 MiB, a part that would exceed the per-part ceiling, a MIME tree deeper than eight levels or
wider than 64 parts, the deadline, and a transport fault all produce the same stable typed errors as
in preview — `RESOURCE_LIMIT`, `TIMEOUT`, `TRANSPORT_FAILURE` — with no body at all. A full query
never returns a partially-read body under any of them.

**Attachment bodies are out of scope.** `attachmentBodiesIncluded: false` states that boundary in the
payload rather than leaving it to be inferred: `full` means the complete body *text*, not the complete
*message*. Attachment bytes are still not fetched, and are never mixed into `text` or `html`.

One vocabulary asymmetry is deliberate and is **not** a bug: the sibling `mail.imap.consume` node also
has a `contentMode`, whose values are `metadata` and `preview`. It answers a different question —
whether a body is read at all. Harmonising the two belongs to the long-lived consumer design, not
the one-shot query. `mail.imap.query` refuses `metadata` and
`mail.imap.consume` refuses `full`; neither silently accepts the other's word.

IMAP is encrypted-only: `IMAPS` verifies the server certificate and hostname; `STARTTLS` is required and never falls back to plaintext. The result page limit can only tighten the graph and operator limits. Before a virtual thread is started the node reserves bounded global (32), tenant (16), tenant/profile, and per-NodeAction tenant capacity; rejection is fail-fast and every reservation is released on validation, credential, submission, and transport failure paths. The node has no persistent executor, polling loop, listener, IDLE connection, or persistent session to shut down.

Each query uses ascending UID windows of at most 128 messages and stops after 4,096 inspected messages or UID windows. Its absolute per-invocation deadline is the smaller of the operator read timeout and 30 seconds; a watchdog closes the active sockets, folder, and store and interrupts the worker at that deadline, even when a server sends an endless slow response beneath the per-read timeout. Credential resolution runs in a separately admitted virtual task with global (32), tenant (16), tenant/profile, and per-NodeAction tenant ceilings. The query waits only until its absolute deadline: a resolver that ignores interruption retains only its resolver capacity, and any secret it returns after abandonment is immediately erased. Query admission remains owned by the query worker and is released only after that worker and its watchdog have exited; resolver admission remains owned by the resolver task until that task actually exits. Message processing is capped at 1 MiB, eight MIME nesting levels, 64 parts, 20 attachment metadata entries, 50 addresses per field, and — in `preview` mode only — the profile preview limit. Exceeding a work budget fails the invocation with a stable typed error, closes the read-only folder/store, and advances no cursor.

## Long-lived IMAP consumer

`mail.imap.consume` is the polling-only, long-lived inbound source. It supports the same encrypted
`IMAPS` and required-`STARTTLS` profile modes as the one-shot query. It does not support POP3, IDLE,
flag changes, move, delete, expunge, or any mutation; mutation actions are a separate capability. The source
opens the operator-authorized folder read-only with peek enabled, waits for a durable-ingress probe,
then for authenticated folder open and generation-specific checkpoint recovery before reporting
READY. Each interval performs an IMAP `NOOP` round trip on the selected folder, processes refreshed
EXISTS/EXPUNGE state, and scans a bounded ascending UID window. It never uses `STATUS` as a selected-
mailbox new-mail poll.

The existing eleven-field `RAVENROOT_IMAP_PROFILE_<TENANT_HEX>_<PROFILE_HEX>` remains the sole owner
of host, port, TLS mode, username, credential reference, allowed folders, I/O timeouts, and general
query bounds. Enabling consumption requires a second operator-only variable,
`RAVENROOT_IMAP_CONSUMER_<TENANT_HEX>_<PROFILE_HEX>`. The preferred record has eleven fields:

```text
folder;pollIntervalMs;batchSize;scanWindow;retryBackoffMs;maxRetryBackoffMs;poisonAttempts;maxMessageBytes;contentMode;maxPreviewChars;allowedHeaders
```

`allowedHeaders` is a comma-separated operator allowlist, normalized to lowercase. A legacy ten-field
record remains valid but grants no header output at all. At most 32 names of at most 64 UTF-8 bytes are
accepted; malformed names and security-sensitive transport/authentication headers make the entire
policy fail closed. The optional graph property `allowedHeaders` may select a subset of the operator
allowlist, including the empty set, but can never add authority. If the graph omits it, the operator
allowlist applies. This authority is separate from SMTP's outbound header policy.

The folder is UTF-8-byte bounded to 256 bytes, control-free, and must also be in the selected IMAP
profile. Poll and retry intervals are 100–60,000 ms; batch is 1–100; scanWindow is at least batch and
at most 512; poisonAttempts is 1–100; maxMessageBytes is 1–1,048,576; contentMode is `metadata` or
`preview`; maxPreviewChars is 0–65,536 and must be zero in metadata mode. A graph stores only the
opaque `profile` plus optional restrictions. Batch, poison-attempt, and preview limits can only lower
operator ceilings. Poll/retry values can only raise operator floors, up to 60 seconds, so a graph
cannot amplify server traffic. `previewChars` is ignored unless `contentMode=preview`; a hidden value
in hand-authored GraphML carries no authority. Unknown properties, unauthorized folders, and invalid
inactive/active combinations are rejected before credential lookup or network access.

Every accepted message begins one trusted durable traversal with version `mail.imap.message.v1`.
Its public envelope is:

```text
version, kind, sourceFolder, uidValidity, uid, deliveryAttempt,
checkpoint { version, sourceFolder, uidValidity, candidateDeliveredThroughUid },
messageId, sentAt, receivedAt, from[], to[], cc[], replyTo[], subject,
headers { lower-case-name: [value, ...] }, flags[], size,
content { mode, textPreview, htmlPreview, attachments[] },
truncated, truncatedFields[], sanitizedFields[], correlation
```

The checkpoint version is `mail.imap.checkpoint.v1`. `candidateDeliveredThroughUid` describes the
position proposed by this event; it is deliberately not named `deliveredThrough` and is not proof
that durable source state advanced. The source advances its durable cursor to that UID only after
`offerDurably` returns an acknowledgeable `DurablyCommitted` or `Duplicate` receipt. Refused,
volatile, and ambiguous admission leaves it unchanged. A restart reads that durable cursor, while a
UIDVALIDITY rollover changes both the checkpoint's UIDVALIDITY and its durable source namespace.

The event's stable identity fields are top-level `sourceFolder`, unsigned-32 `uidValidity`, and
unsigned-32 positive `uid`, so the output can feed mutation actions without LLM transcription. The event
does not contain the profile, host, username, credential reference, or secret. The durable source
namespace injectively encodes node, profile, canonical folder, and UIDVALIDITY; the cursor position is
the UID alone. UIDVALIDITY and UID are never bit-packed into a signed long. A UIDVALIDITY rollover
selects a new checkpoint namespace, fences the old session generation, and reconnects through capped,
stop-interruptible jittered backoff.

Delivery is at least once. `DurablyCommitted` and `Duplicate` receipts advance the checkpoint;
`VolatileCustody`, `Refused`, and `Ambiguous` never do. Refused or ambiguous admission reoffers the
same idempotency key. A crash after durable admission but before checkpoint advancement can therefore
reoffer a message, and the durable ingress key suppresses duplicate ownership. Poison is emitted only
for deterministic validation or MIME-budget failures and advances only after the poison event itself
has durable custody. Provider disconnects, lazy header/body I/O failures, and unknown top-level message
size are transient: they reconnect and retry the same UID without poison or checkpoint. Bounded retry
exhaustion halts/degrades rather than silently skipping the message.

Projection is structurally bounded: at most 1 MiB declared/inspected content and conservative event
wire size, MIME depth 8, 64 parts, 20 attachment metadata entries, 50 addresses per field (including
Reply-To), 8 KiB aggregate address and attachment metadata, and UTF-8-bounded subject, Message-ID,
filenames, types, and previews. Allowed headers are separately capped at 32 values, 2,048 UTF-8 bytes
per value, and 8,192 aggregate name/value bytes. Control characters in their values are replaced by
spaces and named in `sanitizedFields`; byte truncation is named in `truncatedFields`. Disallowed and
sensitive headers are never read into the event. Attachment content is never emitted. Unknown attachment size is represented as `-1`
without materializing its content. Unknown top-level size is treated as transient unavailable because
parsing an unknown multipart could materialize unbounded provider data.

Stop revokes the graph generation before closing every tracked opening/session socket and waking the
poller. Cleanup lives in `stop()` because the host does not promise a distinct final shutdown hook.
One process-local lease permits only one active tenant/profile/folder consumer; credential resolver
tasks are additionally capped at 32 globally and one per tenant/profile, and a resolver that ignores
interruption retains that bounded slot until it exits. Late returned secrets are erased. Connect,
read, write, checkpoint, and credential waits are capped at 30 seconds and stop observes them at a
100 ms cadence. Reconnect delay is capped and stop-interruptible.

Jakarta Mail's `Store.connect` requires an immutable password `String` and retains it in the live
Store's `URLName`; that provider boundary gives the String a session-long heap-dump lifetime. The
resolver `SecretValue` and mutable caller copy are erased immediately when synchronous open returns
or fails, and the retained String is never placed in GraphML, events, diagnostics, or logs, but this
provider API prevents claiming end-to-end in-memory erasure before session close.

Stable author-visible health reasons are fixed tokens such as `imap-consumer-reconnecting`,
`message-projection-unavailable`, `ambiguous-ingress`, `ingress-refused`, and terminal checkpoint,
policy, or poison-halt classifications. Raw server messages and credential material are never used as
health text. Deployment is single-process/single-active-consumer; durable checkpointing survives
source restart, while the process-local lease itself does not coordinate multiple Ravenroot pods.

The dedicated lifecycle proof is `./scripts/verify-mail-imap-consumer-container.sh`. It builds and
installs the mail bundle, then runs GreenMail IMAPS, SQLite durability, `DefaultGraphDeployment`, and
the real Pekko engine together inside a JDK 21 Maven container. It verifies READY-before-append,
actor traversal identity, stop cleanup, durable reopen without replay, and the next UID. This is a
source-mounted integration container, not a claim that the separately published Ravenroot image was
rebuilt; `verify-plugin-activation-on-image.sh` covers installed-image catalog activation.

## IMAP move and delete actions

`mail.imap.move` and `mail.imap.delete` mutate exactly one message. Their target is always the tuple
`profile + sourceFolder + uidValidity + uid`; a mailbox index, subject, or Message-ID is never an
identity. They accept their own bounded `mail.imap.move.v1` / `mail.imap.delete.v1` input, an exactly
one-message `mail.imap.query.v1` page, or the stable `sourceFolder`, `uidValidity`, and `uid` fields of
the `mail.imap.message.v1` event emitted by `mail.imap.consume`. This lets a query or
consumer feed an action without an LLM copying identifiers. The action envelope is capped at six
nesting levels, 128 entries per collection, 512 visited values, and 65,536 UTF-8 bytes. Direct action
envelopes have a closed key set, so endpoint, username, credential reference, password, and other
connection authority cannot arrive in payload data.

The existing eleven-field IMAP profile continues to own the endpoint, credential reference, source
folders, timeouts, and concurrency. It grants query access only. Mutation authority is a separate,
same-id operator value:

```text
RAVENROOT_IMAP_MUTATION_POLICY_<TENANT_HEX>_<PROFILE_HEX>=operations;destinationFolders;trashFolder
```

`operations` is a comma-separated subset of the case-sensitive tokens `MOVE`, `TRASH`, and
`HARD_DELETE`; `destinationFolders` is a comma-separated allowlist. A `MOVE` policy needs at least one
destination. A `TRASH` policy needs a non-empty trash folder that is also in the destination allowlist.
When `TRASH` is absent the third field must be empty. Missing or malformed policy grants no mutation
authority and exposes only the stable refusal outcome; a rejected present record logs tenant/profile
identifiers plus a finite constraint token on
`ai.ravenroot.mail.imap.mutation-policy.rejected`, never the raw operator value.

GraphML stores only the opaque profile, source-folder fallback, policy-selected destination or delete
mode, concurrency tightening, and repeatability declaration. `mail.imap.delete` defaults to `TRASH`.
`HARD_DELETE` is never implicit: it requires all three independent gates—`HARD_DELETE` in the operator
policy, the graph acknowledgement `I_UNDERSTAND_EXPUNGE_IS_PERMANENT`, and
`authorizeHardDelete: true` in that invocation's `mail.imap.delete.v1` payload. It expunges only the
addressed message; it never performs an unqualified mailbox expunge.

Results are bounded `mail.imap.move.v1` or `mail.imap.delete.v1` objects. Node outcomes are
`success`, `missing`, `stale`, `refused`, and `ambiguous`, with separate stable status/reason tokens.
`stale` means the live folder UIDVALIDITY differs from the supplied checkpoint; `missing` means the
UID is absent under the matching UIDVALIDITY. A successful move/trash receives a tagged server reply,
then closes and reopens the source folder to verify that the immutable source UID is gone; a server
with UIDPLUS may additionally return the destination UID tuple. Move and delete-to-trash are
effect-idempotent: repeating the source identity cannot affect a second message and yields `missing`.
Hard delete is declared not-repeatable. Any disconnect or absolute timeout after a mutation command
returns `ambiguous` with `DO_NOT_RETRY_AUTOMATICALLY`; the implementation never retries a command whose
effect may already have happened.

Mutation sessions use the query node's encrypted-only transport, certificate/hostname verification,
absolute 30-second ceiling, separate credential-resolution admission, and global/tenant/profile/action
backpressure. Password arrays and `SecretValue` instances are erased, all failures crossing the node
boundary are typed and sanitized, the source folder closes without expunge, and the store closes on
every path. Operators can run the installed-bundle container proof with
`./scripts/verify-mail-imap-mutations-container.sh`; it loads the validated bundle in a JDK 21 container,
queries fixture messages, performs move and trash, then reopens all folders to observe both effects.
