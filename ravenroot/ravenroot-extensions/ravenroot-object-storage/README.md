# Ravenroot S3-compatible object storage extension

`ai.ravenroot.extensions.storage` is an opt-in Node SDK `/2` plugin. It contributes only
`object.get` and `object.put`; it is not part of the default distribution and does not provide list,
delete, presigned URL, ACL, bucket administration or multipart operations.

## Operator profile and managed signing

The graph contains only `storageProfile`, a relative `key`, and optional lower ceilings. The operator
sets `RAVENROOT_OBJECT_STORAGE_PROFILE_<HEX_PROFILE>` to canonical Base64 of strict JSON:

```json
{
  "origin": "https://s3.eu-west-1.example.test",
  "region": "eu-west-1",
  "bucket": "bucket-a",
  "keyPrefix": "tenant-data",
  "addressingStyle": "path",
  "signingBindingId": "assets-s3",
  "operations": ["get", "put"],
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

Stable failures contain no endpoint, key, signed header, remote body or credential reference.
Version 1 deliberately performs no automatic retry: the public managed seam does not expose a
trustworthy pre-send marker. A PUT timeout, cancellation or transport failure after handoff is
therefore `AMBIGUOUS`; retrying could duplicate a committed write. GET uncertainty is safe and is
reported as deadline/transport failure for graph-controlled handling.

## Verification

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -f ravenroot/pom.xml -pl ravenroot-extensions/ravenroot-object-storage -am verify
./plugin.sh build object-storage
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-object-storage/target/plugin-bundle
```

The deterministic test double exercises request targets, GET/PUT wire projections, conditionals,
limits, tenant identity, admission, rate limiting, redirect refusal, redaction and ambiguous delivery.
The core suite contains the published AWS S3 SigV4 vector and raw-socket actual-wire mutation
proof used by this public signing seam. An optional live MinIO/S3 smoke is supplemental and is not
required for the protocol contract.
