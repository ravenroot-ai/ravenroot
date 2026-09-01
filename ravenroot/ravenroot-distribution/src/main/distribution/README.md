# Ravenroot binary distribution

The archive contains the framework-neutral core, the official Apache Pekko execution engine, the
GraalVM JavaScript worker adapter, the HTTP server, the CLI and their runtime dependencies.

Java 21 or newer is required. If more than one JDK is installed, ensure that `java` on `PATH` points
to a compatible runtime before invoking the scripts.

The primary binary release is now the executable `ravenroot.jar`, which also embeds the Ravenroot
UI. For a genuine local container run from a repository checkout, use the loopback-only Compose
helper rather than placeholder OIDC values:

```sh
./service.sh start
./service.sh restart
```

This explicit local mode uses disabled authentication only behind `127.0.0.1`. The standalone JAR
and every Kubernetes deployment retain the OIDC default: configure issuer, audience, and JWKS URI
from a real reachable Identity Provider; never substitute placeholder values.

The ZIP remains a secondary compatibility format for users who prefer separate libraries and
scripts.

Run the CLI with:

```sh
bin/ravenroot status
bin/ravenroot node-types
bin/ravenroot inspect path/to/graph.graphml
```

Start the standalone server and UI on port 8080 with:

```sh
bin/ravenroot-server
```

Set `RAVENROOT_PORT` to select another port. `RAVENROOT_UI_DIR` may point at external static assets;
otherwise the server uses the UI embedded in the distribution JAR.

When `RAVENROOT_AUTH_MODE` is not declared, the executable decides by exposure: on the default
loopback bind it starts with authentication `disabled` and warns, and on any other bind it refuses
to start and names the variable to set. The published container image declares
`RAVENROOT_AUTH_MODE=oidc` in its own environment, so it still fails fast unless issuer, audience
and JWKS URI are configured. In `oidc` mode every `/v1` API and SSE request requires an
`Authorization: Bearer` JWT.
The token must carry exact `scope`, `tenant_id`, `roles`, and `token_kind` claims; role and scope are
both required by the deny-by-default authorization policy. Global runtime observation is restricted
to `PLATFORM_ADMIN`; execution events are filtered using ownership recorded at submission.
For local-only development, `RAVENROOT_AUTH_MODE=local-token` requires a token of at least 32
characters and a loopback bind. `RAVENROOT_AUTH_MODE=disabled` is an explicit development escape
hatch and is rejected unless the resolved bind address is loopback. The only container exception is
the repository's Compose contract: it detects Docker, attests a `127.0.0.1` host publication, and
then permits the container's `0.0.0.0` listener. Do not set that internal contract manually.

Browser origins are exact and fail-fast. Set `RAVENROOT_BROWSER_ALLOWED_ORIGINS` to a comma-separated
list of canonical HTTP(S) origins; wildcards are rejected. SSE authentication is revalidated every
`RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS` (default 30) and at token expiry. OIDC revalidation forces
a current JWKS refresh; key removal, key replacement, refresh failure, identity/claim drift, or
current authorization denial closes the stream. HSTS is emitted only under the explicit
`RAVENROOT_TRUSTED_TLS_TERMINATOR=true` and HTTPS `RAVENROOT_PUBLIC_ORIGIN` operator contract.
`RAVENROOT_UI_CONNECT_ORIGINS` is a separate comma-separated CSP destination allowlist for
explicitly confirmed external UI targets. It defaults to empty (`connect-src 'self'`), requires
HTTPS except for loopback development origins, and is never inferred from allowed browser callers.

The standard archive contains Pekko and uses `RAVENROOT_ENGINE=pekko` by default. An archive built
with the Maven `akka` profile also contains the optional Akka adapter and can be started with
`RAVENROOT_ENGINE=akka`. Building and running that variant requires adopter-provided access to the
official Akka repository and the applicable Akka license; Ravenroot stores neither credentials nor
license material.
