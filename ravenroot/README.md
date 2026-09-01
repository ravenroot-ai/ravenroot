# Ravenroot

Ravenroot is a graph-governed orchestration and low-code backend platform. The project separates
graph semantics, application contracts, programmable node types and execution-engine adapters so
that framework or vendor types never enter the core model. See the workspace-level
[`README.md`](../README.md) for the product overview and current security posture.

## Modules

- `ravenroot-core`: property-graph access, validation and workflow orchestration;
- `ravenroot-application-api`: framework-neutral application and execution-engine contracts;
- `ravenroot-programming-graalvm`: resource-bounded JavaScript worker adapter;
- `ravenroot-engine-testkit`: shared compatibility tests for every supported engine;
- `ravenroot-pekko`: official Apache Pekko execution-engine adapter;
- `ravenroot-akka`: optional Akka Typed adapter for licensed adopters;
- `ravenroot-server`: standalone HTTP bootstrap using the JDK HTTP server;
- `ravenroot-cli`: command-line adapter over the same application API;
- `ravenroot-ui`: static GraphML and Graphify viewer/editor foundation;
- `ravenroot-distribution`: assembly of the runnable Java artifacts.

## Build

```sh
mvn --batch-mode --no-transfer-progress clean verify
```

The reactor targets Java 21 and is built with a JDK from 21 up to the 25 LTS. JDK 26 is not
supported: it fails to compile the reactor, and `maven.compiler.release` does not shield the build
from that, since it pins the bytecode target rather than the rules of the compiler producing it. The
resulting bytecode still runs on newer JVMs — the bound is on building, not on running. The UI is
built independently with `npm test && npm run build` from `ravenroot-ui`.

## Run and deployment

The recommended no-container local path builds the UI and a single executable JAR:

```sh
./scripts/server.sh start
./scripts/server.sh status
```

Use `./scripts/server.sh help` for stop, restart, foreground mode, and abbreviations.

From the workspace root, the equivalent genuine local OCI run is started with the loopback-only
Compose helper; it needs no dummy OIDC issuer, audience, or JWKS URI:

```sh
../service.sh start
../service.sh restart
```

Both commands expose the UI and API at `http://127.0.0.1:8080`. The standalone OCI image and every
Kubernetes deployment require real OIDC configuration. Docker is optional. Compose, Kubernetes,
image publication and production constraints are documented in
[`deployment-startup.md`](../docs/operator-guide/deployment-startup.md) and ADR 0004.

The default build and binary distribution contain Pekko and require only public repositories. Akka
is deliberately outside the default reactor. Licensed adopters configure the official secure Akka
repository in their Maven settings and enable the adapter with:

```sh
mvn --batch-mode --no-transfer-progress -Pakka clean verify
```

Server, CLI and sample select the installed adapter without compile-time framework imports:

```sh
RAVENROOT_ENGINE=pekko bin/ravenroot status
RAVENROOT_ENGINE=akka bin/ravenroot status
```

The standalone server exposes its installed node catalog through `GET /v1/node-types`, accepts
executable GraphML snapshots with `POST /v1/executions`, returns an
execution and graph-version correlation, and publishes runtime transitions through
`GET /v1/events` as Server-Sent Events, resumable by `Last-Event-ID` from an in-memory ring buffer
bounded to the last 2,048 events with drop-oldest eviction; that buffer is lost on restart. The same
events are also written as correlated JSON server log lines. Neither projection is a durable or
tamper-evident audit trail: the log line is a plain stdout write with no retention or integrity
guarantee of its own (see
[`embed-privacy.md`](../docs/security/embed-privacy.md)). Ravenroot UI
consumes these transport adapters while the application API and core remain independent from HTTP
and SSE.

All `/v1` routes, including SSE, authenticate OAuth2/OIDC bearer JWTs before reading a request body
or creating an event subscription. The server validates an RS256 signature against an explicitly
configured JWKS endpoint and requires exact issuer, audience, expiry, subject, scope, tenant
(`tenant_id`), roles and a configurable identity-type claim (`token_kind=user|workload` by default).
Authorization then passes through the tenant-aware, deny-by-default reference monitor documented in
[`trust-identity.md`](../docs/security/trust-identity.md).
With no declared `RAVENROOT_AUTH_MODE` the standalone executable defaults by exposure: `disabled`
on a loopback bind, and a refusal to start on any other. The published image declares `oidc`, so it
keeps its fail-fast OIDC configuration. `scripts/server.sh` is deliberately a loopback-only local
helper and explicitly selects disabled authentication unless `RAVENROOT_AUTH_MODE` is provided;
`local-token` supplies an authenticated loopback alternative. Never use either local mode on a
non-loopback listener.

Browser access additionally uses an exact fail-fast origin allowlist and strict preflight handling.
Configure `RAVENROOT_BROWSER_ALLOWED_ORIGINS` with comma-separated canonical origins. Long-lived SSE
credentials are revalidated at expiry and at the bounded
`RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS` interval. The bearer-only CSRF decision, centralized
response headers, and explicit trusted TLS-terminator contract for HSTS are documented in
[`identity-browser.md`](../docs/operator-guide/identity-browser.md).
The independent `RAVENROOT_UI_CONNECT_ORIGINS` list may extend CSP `connect-src` for explicitly
confirmed external UI service targets; it is never derived from the browser-caller allowlist.

Standard factories currently cover log, template, CEL transformation/decision, guarded HTTP,
delay, JSON parse/path, and programmable-artifact nodes. In the current distribution, `llm-prompt` and `agent`
are not core node types: `StandardBehaviorFactories` constructs neither, and the reactor holds the
`ModelProvider`/`AgentRuntime` SPI (`ai.ravenroot.api.ai`) without any core reader of it. The SPI is
redesignated as the extension surface of an application that embeds Ravenroot and composes its own
`BehaviorEnvironment` and node factory — not something the shipped reactor calls into — or of a
plugin bundle reaching a model through the managed HTTP channel instead. The distribution includes the process-separated
GraalVM JavaScript adapter, but it is non-executable by default: GraalVM execution now requires an
integrator-provided, capability-verified external sandbox supervisor (SEC-11) that Ravenroot
declares a contract for but does not write or ship — the same integrator-supplies-the-missing-piece
shape as the core-unread `ModelProvider`/`AgentRuntime` SPI above. Without one configured, execution
fails closed regardless of tool policy.
Artifact lifecycle endpoints are always registered; mutations require
authentication, tenant-aware authorization, maker-checker separation, and fail-closed lifecycle
audit — an intent/control log of attempted and denied mutations, not a durable or tamper-evident
record (see the lifecycle-audit paragraph under Server API in the workspace-level
[`README.md`](../README.md#server-api)). Program execution also remains
independently deny-by-default through tool policy, a separate gate from the sandbox-supervisor
requirement above.

After the reactor has been installed locally, the separate embedded example can be verified with:

```sh
cd ../ravenroot-sample
mvn --batch-mode --no-transfer-progress clean verify
```

The physical presence of a module does not mean every capability described by the ADRs is already
complete; support claims should be checked against that module's public documentation and tests.

The common engine TCK loads
`ravenroot-engine-testkit/src/main/resources/fixtures/engine-conformance.graphml` through the same
`GraphManager` used by applications. Consequently both Pekko and Akka must execute identical
fan-out/fan-in routing, unknown-behavior fallback and custom GraphML attributes before an adapter is
considered compatible.
