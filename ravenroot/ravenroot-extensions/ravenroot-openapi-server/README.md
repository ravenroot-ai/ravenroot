# Ravenroot OpenAPI server extension

`ai.ravenroot.extensions.openapi.server` is an optional Node SDK `/2` plugin contributing the
deployment-scoped source `openapi.receive`. It exposes no listener and imports no server or core
implementation. After the graph has reached source readiness, Ravenroot gives the source a
generation-fenced managed route capability; the source leases one prefix below its package authority.
Stop, rollback and shutdown release that lease before returning. Loading the package or compiling a
graph does not create a live route.

## Asynchronous durable contract

The endpoint is ingress, not request/reply. A valid request is normalized as
`openapi.request.v1` and offered through `TrustedIngress.offerDurably`. Only
`DurablyCommitted` and `Duplicate` return `202`; `VolatileCustody`, `Ambiguous`, non-capacity
refusals and deadline expiry return `503`, while a full deployment or package admission bound returns
`429`. The response contains only `openapi.receive.receipt.v1` and the caller's bounded idempotency
key. There is deliberately no synchronous graph result, status endpoint, callback, webhook, replay or
acknowledgement API.

The payload contains the compiled operation id, method, typed path/query/approved-header projection,
validated JSON body, idempotency key and server-derived principal. Tenant and principal data are
structural and are never accepted from the graph, path, query, header or body. Requests for another
tenant or a principal type outside the profile are refused. The deployment's own trusted
`SecurityContext`, not request content, is passed to `offerDurably`.

## Operator configuration

Set `RAVENROOT_OPENAPI_SERVER_CONFIG` to canonical Base64 of strict JSON. The package-level
`authority` is checked before the server binds. It must use listener `main` and a namespace below
`/managed`; it fixes scopes and hard route/concurrency/body/response/deadline ceilings. `projection`
fixes the only path/query/header data core may disclose. Every profile fixes its exact OpenAPI JSON
document and SHA-256, relative route base, allowed operations, principal types, idempotency header,
and lower request/idempotency/deadline/concurrency ceilings. `targetNode` must be absent in v1: named
durable targets are refused at configuration time until the core contract supports them.

Because each package grants one authority and one projection, all profiles intentionally share
one package namespace, required-scope envelope and idempotency-header name. Profile route bases remain
distinct and are leased independently. The environment compiler refuses disagreement instead of
silently broadening one profile to satisfy another. Graph properties are only `apiProfile`, an
optional comma-separated operation subset, and optional lower ceilings; no graph value can supply a
specification, route, scope, principal type or target.

The OpenAPI document is strict JSON 3.0.3. YAML, 3.1, server overrides, external references, vendor
extensions, callbacks, links, generated classes and document-defined security are outside v1 and fail
source start atomically. Supported request validation covers literal and templated paths, scalar
path/query/header parameters, required values, JSON bodies and the bounded object/array/scalar schema
subset (`properties`, `required`, `additionalProperties`, `items`, `enum`, numeric and size bounds,
and bounded internal component references). Omitted `additionalProperties` follows OAS 3.0.3 and
defaults to `true`; explicit `false` closes the object. JSON bodies require a projected
`Content-Type: application/json`; syntactically valid media-type parameters are accepted. Ambiguous
same-method template shapes are refused.

An executable configuration can be generated from an OpenAPI file without placing its contents in
the graph:

```sh
SPEC=orders-openapi.json
SPEC_B64=$(base64 < "$SPEC" | tr -d '\n')
SPEC_SHA=$(shasum -a 256 "$SPEC" | awk '{print $1}')
# Substitute SPEC_B64 and SPEC_SHA into this strict JSON, then Base64 the completed file.
RAVENROOT_OPENAPI_SERVER_CONFIG=$(base64 < openapi-server-config.json | tr -d '\n')
export RAVENROOT_OPENAPI_SERVER_CONFIG
```

The JSON shape is:

```json
{
  "authority": {"listenerId":"main","pathPrefix":"/managed/openapi","requiredScopes":["graph:execute"],"maxRoutes":8,"maxConcurrentRequests":16,"maxRequestBytes":1048576,"maxResponseBytes":1024,"requestTimeoutMs":5000},
  "projection": {"allowedHeaders":["content-type","idempotency-key","x-trace"],"idempotencyHeader":"idempotency-key","maxRelativePathBytes":2048,"maxQueryParameters":64,"maxQueryBytes":4096,"maxHeaderCount":8,"maxHeaderBytes":2048,"maxHeaderValueBytes":512},
  "profiles": {"orders":{"specBase64":"<SPEC_B64>","specSha256":"<SPEC_SHA>","routeBase":"/orders-api","operations":["createOrder"],"principalTypes":["USER"],"idempotencyHeader":"idempotency-key","maxRequestBytes":262144,"maxIdempotencyBytes":128,"deadlineMs":3000,"maxConcurrency":8}}
}
```

The resulting public prefix is `/managed/openapi/orders-api`; OpenAPI paths are descendants of that
base. Ravenroot owns TLS, authentication, tenant derivation, path decoding, request bounds, route
inventory and readiness. The extension records no credential or request content in its stable errors.

### Telemetry policy

Version 1 deliberately emits no package-specific metrics or logs. The published source capability
does not expose a telemetry sink, and a private meter would create an unverifiable second telemetry
plane. Operators use Ravenroot's managed-route inventory and readiness, whose bounded fields are only
package, route, generation and state. Tenant, deployment, traversal, request, header, body and
idempotency values are never metric dimensions or log fields in this package.

## Verification and packaging

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -f ravenroot/pom.xml -pl ravenroot-extensions/ravenroot-openapi-server -am verify
./plugin.sh build openapi-server
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-openapi-server/target/plugin-bundle
```

With a local or containerized Ravenroot server running an active configured graph, the same bounded
production probe used by the integration suite is executable as follows. Before storing the bearer in
a shell variable, the probe validates the raw input as exactly one non-empty newline-terminated line
containing only ASCII bytes `0x21` through `0x7e`. It passes the bearer to curl through standard input
after clearing curl's environment, so it appears in neither curl arguments nor inherited environment:

```sh
sh ravenroot/ravenroot-extensions/ravenroot-openapi-server/container/probe.sh \
  http://127.0.0.1:8080 < /run/secrets/ravenroot-openapi-probe-token
```

The plugin is opt-in and is not added to the default distribution. Managed-route inventory and
readiness expose only package/route/generation/state, never OpenAPI request
content. The single-replica durability qualification of `TrustedIngress` continues to apply.
