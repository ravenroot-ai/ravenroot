# Ravenroot OpenAPI client extension

`ai.ravenroot.extensions.openapi.client` contributes `openapi.call`. It is an opt-in Node SDK `/2`
plugin and is not part of the default distribution. It uses only the package-scoped `outbound-http`
service; it never constructs an HTTP client or changes the built-in `http-request` behavior.

## Operator profile

The graph stores only `apiProfile`, an allowlisted `operationId`, and optional lower ceilings. The
operator sets `RAVENROOT_OPENAPI_CLIENT_PROFILE_<HEX_PROFILE>` to canonical Base64 of strict JSON.
The JSON fixes an HTTPS origin, Base64 OpenAPI document and SHA-256 digest, allowed operation ids,
fixed/request/response header allowlists, at most one managed credential binding/reference, and
request, response, deadline and concurrency ceilings. The OpenAPI document is strict JSON 3.0.3;
YAML, 3.1, external references, server overrides, callbacks, links, vendor extensions, generated
classes, query/cookie authentication, OAuth acquisition and multiple credentials are refused.

The managed service independently enforces the operator egress policy, DNS/reserved-address checks,
TLS, no redirects, credential placement, byte limits and its own admission bound. Secret values never
enter this module. The one binding is resolved and injected by the managed service for the call.
Compilation retains the OpenAPI credential placement identity (normalized header and prefix) and
refuses an allowed-operation set that would require incompatible placements, such as bearer
`Authorization` and an `apiKey` header. An authenticated header may not also be graph-supplied or a
fixed profile header.

## Payload and outcome

`openapi.call.v1` is an object with optional `path`, `query`, `headers` maps and `body`. Every member
is checked against the compiled operation. Path values are typed and percent-encoded exactly once;
empty, dot, slash and percent-bearing path segments are refused. Query order is deterministic.
Bodies and responses use bounded canonical JSON and the supported schema subset: object properties,
required/additional-properties, arrays, JSON scalar types, enum, numeric bounds and size bounds.
The operation supplies explicit per-call request, encoded, decoded, and canonical-output ceilings to
the managed transport. JSON is the only accepted non-empty response media type; identity and one
bounded gzip member are accepted, while ambiguous or stacked encodings fail closed.

Success returns `openapi.call.result.v1` with operation id, status, approved headers and a validated
body. Stable exceptions never contain the remote body, URL, header values or credential reference.
Redirects are `REDIRECT_REFUSED`. A timeout, cancellation or transport failure after a non-idempotent
request was handed to the managed executor is `AMBIGUOUS`. Version 1 deliberately performs no
automatic retry: the managed transport does not expose a trustworthy pre-send marker, so retrying based only on a
transport exception could duplicate a POST/PATCH or resolve its credential more than once.
Direct cancellation requests are propagated to the managed call through a protected outcome future:
they complete with `AMBIGUOUS` after non-idempotent handoff and `DEADLINE_EXCEEDED` for idempotent
calls, never a raw `CancellationException`. Admission remains held until the managed completion and
response validation settle. The profile `maxConcurrency` is shared by all operations for the same
tenant/profile; a graph's lower `maxConcurrency` is an independent per-action ceiling and therefore
does not depend on node initialization order. Both registries retain only active references: the
last settlement removes its tenant/profile and tenant/action keys through the same linearized update
used by reacquisition, so idle tenant churn is bounded without ever splitting a concurrency gate.

## Verification

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -f ravenroot/pom.xml -pl ravenroot-extensions/ravenroot-openapi-client -am verify
./plugin.sh build openapi-client
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-openapi-client/target/plugin-bundle
```
