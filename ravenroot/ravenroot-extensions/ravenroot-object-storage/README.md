# Ravenroot S3-compatible object storage extension

`ai.ravenroot.extensions.storage` is an opt-in Node SDK `/2` plugin. It contributes
`object.get`, `object.put`, `object.list` and `object.delete`; it is not part of the default
distribution and does not provide presigned URLs, ACLs, bucket administration, multipart operations,
owner projection or user-metadata projection.

## Operator profile and managed signing

The graph contains only `storageProfile`, a relative key or list prefix, and optional lower ceilings. The operator
sets `RAVENROOT_OBJECT_STORAGE_PROFILE_<HEX_PROFILE>` to canonical Base64 of strict JSON:

```json
{
  "origin": "https://s3.eu-west-1.example.test",
  "region": "eu-west-1",
  "bucket": "bucket-a",
  "keyPrefix": "tenant-data",
  "addressingStyle": "path",
  "signingBindingId": "assets-s3",
  "operations": ["get", "put", "list", "delete", "delete_version"],
  "contentTypes": ["text/plain", "application/octet-stream"],
  "allowIfMatch": true,
  "allowIfNoneMatch": true,
  "maxObjectBytes": 1048576,
  "timeoutMs": 10000,
  "maxConcurrency": 8,
  "maxRequestsPerSecond": 32
}
```

The managed HTTP-signing composition must bind `assets-s3` to this exact HTTPS origin, region, service
`s3`, and an operator-owned tenant credential reference. The extension sees neither access key,
secret key nor session token: it submits final method, percent-encoded request target, headers and
body with `OutboundHttpSigning("assets-s3")`; core resolves the credential once per invocation,
signs the exact outgoing bytes and clears it. Path style adds the fixed bucket segment. For virtual
hosted style, `origin` must already be the bucket-scoped authority.

Operations are an explicit allowlist. Existing `get`/`put` profiles remain valid; `list`, `delete`,
and the separate `delete_version` capability are opt-in. `delete_version` is invalid unless `delete`
is also present. Graph properties can select a profile, append a narrower list prefix, and lower the
profile's object-byte, time, or concurrency ceilings where the operation exposes them. LIST page size
and retry count are bounded by shipped limits. Graph data cannot replace the profile's origin, bucket,
root prefix, signing binding or operation allowlist.

Every storage request carries the effective byte ceiling and remaining total deadline into the
managed HTTP boundary. Object reads accept only the profile's media allowlist, list responses accept
XML, and mutation responses remain opaque; all storage operations require identity encoding so an
endpoint cannot introduce an unbudgeted decompression layer. Cancellation is registered before
handoff and stops any later list retry.

Keys and prefixes are strict UTF-8 relative paths. Empty/dot segments, controls, backslashes,
literal `%`, query/fragment syntax and paths over 1024 UTF-8 bytes are refused before managed HTTP,
which prevents traversal and single/double-decoding ambiguity. The core independently enforces the
operator origin, DNS and reserved-network policy, TLS, no redirects, signed header authority, byte
ceilings, deadline and admission.

## Payloads and results

`object.get.v1` has only `version`. A successful result is `object.get.result.v1`, with `text` or
canonical `base64`, `encoding`, ETag, optional version id, bytes and SHA-256. Strict text mode refuses
malformed UTF-8.

`object.put.v1` has exactly one of `text` or canonical `base64`, plus optional `contentType`,
`ifMatch` and `ifNoneMatch`. Content types and conditionals must be authorized by the profile. A
successful result is `object.put.result.v1` with ETag, optional version id and bytes.

`object.list.v1` has `version` and an optional opaque `cursor`. Node configuration supplies an
optional relative `prefix`, `maxResults` (1-1000), a comma-separated safe projection chosen from
`size`, `etag`, `lastModified`, and `storageClass`, and `retries` (0-3). Every page reapplies the
operator bucket/root prefix and graph prefix. The cursor is a versioned envelope containing a
tenant- and scope-mismatch digest plus the encoded opaque provider continuation token. This detects
accidental reuse under another tenant, profile rotation, prefix, page size or projection and remains
stable across process restart; it is not a cryptographic integrity or authenticity token. Hostile
cursor contents still cannot replace the fixed bucket or effective prefix. Results contain only
relative keys and the requested safe fields. XML parsing is
streaming, rejects DTDs/entities and foreign namespaces, and is bounded by profile response bytes,
page count, nesting depth and field length. The raw-object limit remains distinct from the derived
canonical-result limit: GET checks exact base64 plus fixed metadata before allocating the base64
string, and all four behaviors validate the completed result payload against that finite ceiling.

`object.delete.v1` has `version` and an optional bounded `versionId`. A version identifier is accepted
only when the profile also grants `delete_version`. The extension issues exactly one DELETE (never a
HEAD probe); 200/204 returns `DELETED`, while 404 is the idempotent successful `NOT_FOUND` outcome.
The result never echoes the key or version identifier.

Stable failures contain no endpoint, key, signed header, remote body or credential reference.
GET and DELETE perform no automatic retry. LIST may retry only configured transient transport or
5xx failures, charges profile rate admission for every attempt, and keeps one concurrency lease and
one absolute deadline across all attempts. Cancellation stops the active call and any future retry.
PUT and DELETE timeout, cancellation, transport uncertainty, or HTTP 500/502/503/504 after handoff
is `AMBIGUOUS`; neither mutation is automatically retried. Recovery is fail-closed by default: LIST
and DELETE expose the standard `recovery.repeatable` declaration with no default, and the engine
repeats an abandoned attempt only after an author explicitly chooses `repeatable`. Exact-version
DELETE is the safest case for such a declaration; unversioned deletion still depends on the
workflow's domain semantics.

## Verification

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -f ravenroot/pom.xml -pl ravenroot-extensions/ravenroot-object-storage -am verify
./plugin.sh build object-storage
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-object-storage/target/plugin-bundle
```

The deterministic S3-protocol test double exercises request targets, GET/PUT/LIST/DELETE wire
projections, limits, tenant identity, admission, retry, cancellation, redirect refusal, XML
hardening, redaction, discovery and ambiguous delivery. The module also starts digest-pinned MinIO
and `mc` containers when Docker and OpenSSL are available. That provider-backed test verifies TLS
and SigV4 targets, pagination across SQLite/runtime recreation, multiple object versions,
exact-version deletion, denial, and a recovery sweep coupled to live provider state. The core suite
contains the published AWS S3 SigV4 vector and raw-socket actual-wire mutation proof used by this
public signing seam.
