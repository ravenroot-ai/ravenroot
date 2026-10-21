# Establish business context from a text email

An email's transport identity does not identify a business operation. The IMAP consumer exposes a
bounded `mail.imap.message.v1` event. Its root `correlation` and mailbox UID identify transport
occurrences. Text is under **`content.textPreview`**, with explicit `truncated` and
`truncatedFields` markers; there is no implicit `body.text` field or scan for an ingestion ID.

This recipe uses a declared JSON **text email format**. It is a format contract with the sender,
not a heuristic extractor for arbitrary prose. Send the entire text body as:

```json
{"ingestionId":"email-ingestion-42","partition":"part-2","stage":"silver","round":"round-1","filename":"sales.csv"}
```

Open [mail-text-context.graphml](../examples/correlation/mail-text-context.graphml). Install the mail
bundle and configure an authorized IMAPS consumer profile. Replace `replace-with-operator-profile`
with that opaque profile ID; endpoints, mailbox credentials, destination exceptions and TLS trust
stay in operator configuration. The operator profile and consumer policy must permit `preview` and
a preview ceiling of at least 1,024 characters. The graph tightens its preview to that finite limit.

Deploy activates the listener without running START. The graph's START branch simply reaches END;
email arrivals enter at `mail`, including when START has never run. The consumer durably transfers
custody before advancing its mailbox checkpoint.

The graph performs these explicit steps:

1. The mail SOURCE generates its own original application context. It does not interpret text as an
   existing application ID.
2. `cel-decision` requires `payload.kind == 'message' && !payload.truncated`. A poison projection or
   truncated preview goes to ERROR, rather than establishing an ID from incomplete text.
3. `json-parse` reads exactly `{{payload.content.textPreview}}`. Its normal finite UTF-8 byte, depth,
   value-count and text limits apply while parsing. Malformed JSON fails the node.
4. `correlation-derive` maps `/ingestionId`, `/partition`, `/stage`, `/round` and `/filename` from that
   parsed dictionary. It uses `ACCEPT_OR_CREATE` and a literal audit reason. Missing required selected
   fields, non-text values, invalid IDs and configured length violations refuse the derived admission.
5. The new traversal starts at `store-result` under the computed context. The original mail traversal
   keeps its generated identity. Retained derivation metadata links the original process, traversal,
   visit and attempt to the child.

The final log node demonstrates the handoff. Replace it with an authorized application effect suited
to the business operation. A payload transform by itself never changes established context; this
recipe uses a distinct, audited derivation boundary deliberately. Use `REQUIRE_EXISTING` instead when
the computed ID must already name an open history in this exact deployment/version/tenant scope.

Before running, select the derive node in Edit mode and preview its selectors against the **parsed
JSON dictionary**, not the entire IMAP envelope. A valid sample checks extraction; it does not prove
that `REQUIRE_EXISTING` admission is authorized. In Monitoring, inspect both actual histories and the
child traversal's derivation Details. Their transport, process, traversal and visit identities remain
separate. Optional activity capture is not required to retain this causal relationship.

`MailTextCorrelationRecipeTest` delivers real text messages through IMAPS and the shipped Angus source,
executes this GraphML through the runtime and SQLite store, and checks valid, malformed, oversized-ID
and truncated cases. Invalid input does not create a guessed `email-ingestion-42` history.
