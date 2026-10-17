# Install only the Ravenroot UI on Kubernetes

Use this procedure when a Ravenroot backend already runs, including inside your microservice. The
UI pod serves compiled assets and proxies HTTP APIs/SSE to that backend. It starts only a Node.js
static web server; there is no Java backend, database migration, artifact store or security audit
store in this image. The [complete installation](kubernetes-installation.md) provides those backend
concerns separately. This guide targets current `dev` and the planned `0.7.0-alpha.1`; resolve new
artifacts only after protected publication has completed.

## 1. Confirm the existing backend contract

Identify the backend API base URL as reachable **from the UI pod**, for example
`http://existing-backend.application.svc.cluster.local:8080/automation`. It must expose the Ravenroot
`/v1` API below that base, not just an unrelated microservice API. An embedded application owns the
Ravenroot application lifecycle, HTTP mounting, authentication/authorization, origins, data stores,
audit and sandbox policy. Installing this UI does not reconfigure any of them.

Verify backend version/API compatibility and a valid user token before installing. Preserve the
backend's issuer/audience and tenant/roles/scopes. Configure its browser origin to permit
`https://application.example.test`; Origin is forwarded unchanged by the UI proxy. Where the backend
has a trusted TLS terminator/public-origin policy, align it with that public origin. The proxy
replaces the upstream Host with the configured backend authority and strips client-supplied
Forwarded/X-Forwarded headers. Do not rely on those stripped headers for backend authorization or
turn this pod into an implicitly trusted identity proxy. Configure TLS policy explicitly on the
backend. A token accepted by another part of your microservice is not automatically Ravenroot
access authority.

The backend must be reachable through HTTP or verified HTTPS and have enough streaming capacity.
For HTTPS with a private CA, create a CA ConfigMap/Secret, mount its PEM file read-only and set
`NODE_EXTRA_CA_CERTS=/etc/backend-ca/ca.pem`. Hostname and trust validation remain enabled; neither
`NODE_TLS_REJECT_UNAUTHORIZED=0` nor an insecure fallback is supported. Client-certificate upstream
routing is not implemented here; use an approved gateway if the backend requires mTLS.

## 2. Choose artifact and image

The release workflow adds these immutable deliverables while preserving the full distribution:

| Deliverable | Name/reference |
|---|---|
| Compiled static archive plus optional proxy server | `ravenroot-ui-0.7.0-alpha.1.zip` in GitHub Releases |
| UI-only image | `ghcr.io/ravenroot-ai/ravenroot-ui:0.7.0-alpha.1` |
| Full server image | `ghcr.io/ravenroot-ai/ravenroot:0.7.0-alpha.1` |

The UI OCI runtime is Linux amd64, non-root UID/GID 10001. Tags have the product version without `v`;
the GitHub release/tag uses `v0.7.0-alpha.1`. There is no moving `latest` tag. Use the verified OCI
index digest in Kubernetes. Both images retain SBOM and SLSA provenance predicates in their OCI
indexes and receive digest-bound GitHub build-provenance attestations. The ZIP is covered by
`SHA256SUMS` and GitHub release-file provenance; it is not a Maven/JAR artifact or a GPG-signed Maven
payload. See [release verification](../governance/releasing.md).

```sh
# Run after publication, not while the release is still awaiting approval.
docker buildx imagetools inspect ghcr.io/ravenroot-ai/ravenroot-ui:0.7.0-alpha.1
gh attestation verify oci://ghcr.io/ravenroot-ai/ravenroot-ui@sha256:INDEX_DIGEST \
  --repo ravenroot-ai/ravenroot
```

For an archive installation, download the ZIP and SHA256SUMS from the same release, verify its
checksum entry and file attestation, extract, then run:

```sh
unzip ravenroot-ui-0.7.0-alpha.1.zip
cd ravenroot-ui
RAVENROOT_UI_ROOT="$PWD/ui" RAVENROOT_UI_BACKEND_URL=https://backend.example.test/automation \
  RAVENROOT_UI_PREFIX=/ravenroot node server.mjs
```

Node.js 24 is required for the optional server. A different static web server may serve `ui/`, but
then you must implement API/SSE routing and set Service URL to its API base in the connection panel.
The compiled assets are relative and require a trailing-slash public UI URL. Source builds use
`npm ci && npm run build` in `ravenroot/ravenroot-ui`; then, at repository root:

```sh
python3 scripts/package_ui.py --output ci-artifacts/ravenroot-ui.zip
mkdir -p ci-artifacts/ui
cp -R ravenroot/ravenroot-ui/dist/. ci-artifacts/ui/
docker build -f Dockerfile.ui -t ravenroot-ui:local .
```

The runtime image assembly uses those compiled bytes without rebuilding the frontend. The full
server distribution is built through its existing commands.

## 3. Select root or prefix and configure the proxy

Copy [ui-only.yaml](../examples/kubernetes/ui-only.yaml). Set the image to the verified digest,
replace the backend URL and public hostname, and use your existing HTTPS Gateway/listener.
The manifest preserves `/ravenroot` in the request rather than rewriting it away. The baseline manifest has no PVC, backend secrets, service-account token or writable root filesystem.
The optional shared backend token below adds only a deployment-injected UI server secret.

| Runtime variable | Default/contract |
|---|---|
| `RAVENROOT_UI_BACKEND_URL` | Unset selects static-only mode (API/backend probes return `503`); operator-owned HTTP(S) URL with optional backend prefix; no credentials/query/fragment |
| `RAVENROOT_UI_BACKEND_TOKEN` | Optional literal bearer value, nonempty RFC 6750 characters, at most 16,384 characters; requires backend URL; server memory only |
| `RAVENROOT_UI_PREFIX` | Empty for root; otherwise slash-prefixed segments of letters/digits/underscore/hyphen, no trailing slash |
| `RAVENROOT_UI_PORT` | `8080`, integer TCP port |
| `RAVENROOT_UI_ROOT` | `/opt/ravenroot/ui`; absolute/relative operator-owned asset directory |
| `NODE_EXTRA_CA_CERTS` | Optional operator-mounted additional HTTPS CA bundle |

If no backend URL is set, the UI serves its assets and local health/readiness without an upstream.
API and backend-probe requests return `503` with `UI_BACKEND_NOT_CONFIGURED`, so offline authoring
can remain available. Explicit loopback/localhost upstreams targeting the UI listening port refuse
startup. Other DNS aliases can still identify the same pod: verify your configured backend endpoint
belongs to the existing backend, not this UI Service. Never configure the UI Service as its upstream.

No backend URL or bearer token is written into frontend assets. The index response pre-fills only
the public same-origin Service URL prefix. Configure the backend URL on the **server**, not in the
UI panel. Never place a credential in that URL, an image layer or browser asset.

| Browser request | Backend request with the example configuration |
|---|---|
| `/ravenroot/` | Local compiled index |
| `/ravenroot/assets/...` | Local compiled assets |
| `/ravenroot/v1/node-types` | `/automation/v1/node-types` at existing backend |
| `/ravenroot/v1/events` | `/automation/v1/events`, streamed incrementally |
| `/ravenroot/backend-health` | `/automation/health` |
| `/health`, `/ready` | Local UI web-server probes, independent of backend readiness |

HTTP methods, bodies, query strings, authorization and browser Origin are forwarded; hop-by-hop
headers and untrusted forwarded-authority headers are removed. The optional interaction WebSocket
is not supported by this proxy. UI REST and fetch-based SSE require no WebSocket route.
`RAVENROOT_UI_BACKEND_URL` is the administrator-owned outbound authority for this standalone
Node.js proxy: its scheme, hostname, finite port and optional path prefix are fixed at process
startup. A browser request can add only the documented API path and query below that prefix; it
cannot replace the upstream scheme, authority or port. Backend redirects (301, 302, 303, 307 and
308) are neither followed nor passed through: the proxy returns a generic 502 without Location,
preventing a second destination or credential disclosure. A 304 retains normal response behavior.
`http://` therefore explicitly authorizes a plaintext hop that may
forward the browser's authorization header; use it only inside the operator-controlled network or
authenticated mesh. `https://` uses Node's normal certificate-chain and hostname verification,
with private roots supplied through `NODE_EXTRA_CA_CERTS`; disabling verification is refused.
DNS resolution and connection reuse remain Node transport behavior, so enforce the configured
backend name/address boundary with namespace egress policy or a controlled mesh endpoint.
For root deployment, remove `RAVENROOT_UI_PREFIX`, set HTTPRoute PathPrefix to `/`, and use a
**dedicated hostname** so you do not replace another application's routes. The backend prefix can
remain `/automation`. Requests to `/ravenroot` redirect to `/ravenroot/`; unknown paths return 404.

## 4. Deploy with a controlled network and HTTPS edge

```sh
kubectl create namespace ravenroot-ui
kubectl apply -n ravenroot-ui --dry-run=server -f ui-only.yaml
kubectl apply -n ravenroot-ui -f ui-only.yaml
kubectl rollout status -n ravenroot-ui deployment/ravenroot-ui
kubectl get -n ravenroot-ui pods,httproute
```

The Gateway must permit these namespace routes and present the public certificate. Configure HSTS
at that HTTPS edge. The UI server sends a same-origin CSP, denies framing and restricts browser
connections to its own origin; cross-origin direct-service connections and external interaction
frames require a separately reviewed serving policy. Restrict pod
network ingress to the Gateway and egress to DNS plus the configured backend. The manifest drops all
capabilities and disallows privilege escalation, runs as non-root and needs no filesystem writes.
Do not mount the backend's data PVC into the UI pod. Local `/ready` only means that UI serving is
available; monitor the backend readiness separately. Increasing UI replicas is stateless and does
not authorize increasing backend replicas or sharing its SQLite storage.

For long SSE streams configure your Gateway controller's stream timeout and disable response
buffering/compression that delays event delivery. HTTPRoute alone cannot express every controller's
buffering policy. Validate this on the public route, not just through pod port-forwarding.

## 5. Authenticate and verify routing and incremental events

Open `https://application.example.test/ravenroot/`. Service URL is pre-filled `/ravenroot`; keep it
for this same-origin deployment. Paste your backend-issued bearer token into **Service token** and
connect. UI installation does not introduce native Keycloak login, OAuth callback or automatic
refresh. For Keycloak claim mapping and an approved workstation token flow follow
[the full guide's authentication steps](kubernetes-installation.md#4-map-token-claims-and-grant-narrow-authority).
Do not paste the internal backend URL into Service URL, which would bypass the proxy and change the
browser security boundary.

```sh
curl --fail https://application.example.test/ravenroot/
# Backend probes reflect backend, while /health tests the UI server itself.
curl --fail https://application.example.test/ravenroot/backend-ready
# Expected 401 for anonymous protected API access:
curl -i https://application.example.test/ravenroot/v1/node-types
# bearer-header is an operator-created permission-restricted file containing Authorization: Bearer ...
curl --fail --header @./bearer-header https://application.example.test/ravenroot/v1/node-types
curl --no-buffer --header @./bearer-header https://application.example.test/ravenroot/v1/events
```

Verify an execution's events arrive before the SSE response ends, query/body forwarding works,
authorized calls succeed, missing/expired tokens fail and denied scopes remain denied. Repeat with
a backend URL prefix different from the UI prefix. `/v1/runtime` still needs `PLATFORM_ADMIN` and
`ravenroot.observe`; do not make every user administrator to remove that 403.

The CI runtime integration tests exercise root/prefixed static serving, distinct backend prefix,
authorization/body/query forwarding, header stripping, incremental SSE, path confinement and HTTPS
trust rejection/acceptance. CI also builds the real compiled UI image and starts it as non-root on a
read-only filesystem. These tests do not certify a particular ingress, IAM or database deployment.

## 6. Upgrade and roll back independently

Record the prior UI digest and backend version. Check API compatibility, change only the UI image,
observe health and repeat authentication/SSE probes. Roll back to the previous UI digest if needed;
this pod performs no data migration. Backend storage, credential rotation, audit and database
backup/restore remain the backend operator's responsibilities.


## Browser restore and shared operator token

Service is resolved as current explicit user choice, valid saved browser connection, operator public
prefix, then same origin. A saved connection wins over a changed startup prefix. After successful
explicit authentication, the accessible confirmation offers Local Storage persistence of exactly
that public service binding and token. Decline or Escape keeps memory-only access; automatic restore
and retries never ask again. The empty password field says **saved in this browser** while a saved
credential is active. It has no additional retention timeout; backend validity is unchanged.

**Forget token** clears the active browser token and opt-in in both memory and storage, keeping
Service. A definitive 401 invalidates that credential; 403 scope denial, network and CORS failure do
not imply expiry. Unsupported or unreadable storage falls back to explicit memory-only access with
feedback. Clearing this site's browser data removes its saved connection; clearing cached assets
alone need not. Tokens in Local Storage are readable by page JavaScript. Tokens are never read from
public URL parameters, frontend environment variables or compiled assets, nor included in graph or
workspace exports. A remembered external service still requires fresh origin confirmation through
**Authenticate**, with no need to repaste its saved token. The default standalone CSP blocks direct
external services; such routing needs a separately reviewed serving policy.

The browser binding includes normalized public scheme, host, port and API path prefix. It cannot
identify changes to the private upstream behind a same-origin proxy: the operator owns that
upstream and must preserve its intended identity when changing deployment configuration. No token
is transferred to a different browser-selected service or prefix. An existing durable task's validated service-only recovery locator is a legacy remembered browser
service, used only when no new connection record exists; it supplies no token or origin trust.

For local development and standalone testing, keep UI and backend ports separate:

```sh
# Development: public UI is http://127.0.0.1:5173, API proxy targets backend 8081.
# Run in ravenroot/ravenroot-ui. No server default token is supported by Vite.
RAVENROOT_SERVICE_URL=http://127.0.0.1:8081 npm run dev

# Standalone root UI on 5001, existing backend on 8080.
RAVENROOT_UI_ROOT="$PWD/ui" RAVENROOT_UI_PORT=5001 \
RAVENROOT_UI_BACKEND_URL=http://127.0.0.1:8080 node server.mjs

# Standalone prefixed UI: browser opens http://127.0.0.1:5002/ravenroot/.
# Browser Service is /ravenroot; backend API prefix remains /automation.
RAVENROOT_UI_ROOT="$PWD/ui" RAVENROOT_UI_PORT=5002 RAVENROOT_UI_PREFIX=/ravenroot \
RAVENROOT_UI_BACKEND_URL=http://127.0.0.1:8081/automation node server.mjs
```

In a container, `127.0.0.1` names that container, not a backend container or the browser's computer.
Use a reachable container-network service name such as `http://ravenroot-backend:8081/automation`,
or the backend's Kubernetes Service DNS. The browser continues to use the public same-origin prefix;
it never needs to resolve that private hostname. No new public startup endpoint is introduced: the index exposes only the validated prefix and a
`data-server-auth` boolean. The boolean is bound to that prefix and lets Forget reconnect to shared
server access; it is not backend authorization. The private upstream and token stay on the server.

To connect multiple authorized browsers without a local token, inject the effective backend-issued
value into `RAVENROOT_UI_BACKEND_TOKEN` at UI server startup. For Kubernetes, add an environment entry
to the existing UI container, backed by a Secret you manage separately:

```yaml
env:
  - name: RAVENROOT_UI_BACKEND_TOKEN
    valueFrom:
      secretKeyRef:
        name: ui-backend-access
        key: token
```

For a local test, a literal assignment is also accepted:

```sh
RAVENROOT_UI_ROOT="$PWD/ui" RAVENROOT_UI_PORT=5001 \
RAVENROOT_UI_BACKEND_URL=http://127.0.0.1:8081 \
RAVENROOT_UI_BACKEND_TOKEN='replace-with-backend-issued-token' node server.mjs
```

This variable does not read a file, resolve a secret name or fetch a reference. Keep actual values
out of image layers, source, command history and public logs; use deployment secret injection for
real deployments. Rotate the value and restart the UI process when its authority changes or expires.

Credential precedence is explicit browser token, saved browser token for that connection, then
server token **only when Authorization is absent**. Malformed, duplicate, empty or non-Bearer client
headers are refused. There is no retry of a rejected browser token with the shared identity. The
server token never reaches HTML, assets, runtime configuration, logs or browser storage, and never
triggers browser remember consent. Successful normal runtime authority enables connected features
without requiring a locally held token or a public authentication-capability endpoint.

All callers using the default share its backend identity and scopes. Access-control the UI deployment
accordingly; do not substitute broad administrator scopes for the backend's ordinary policies.
Ambient injection rejects cross-site Fetch Metadata and mismatched/null Origin. The public Host must
be preserved by a TLS edge, and client-supplied Forwarded headers do not change this authority.
Non-browser clients lacking these headers rely on deployment access control. Origin is still
forwarded to the backend, whose existing allowlist and auth checks remain authoritative.

The default attaches only to the fixed configured `/v1` upstream, never assets or health/readiness
probes. Redirects are refused with a generic 502. Reflected secret bytes in upstream response headers
and bodies are redacted across chunk boundaries without buffering ordinary SSE until completion;
the proxy requests identity encoding and refuses a compressed response it cannot safely inspect.
An expired default produces authentication-required feedback; only the operator can replace it.
**Forget token** or browser data clearing cannot remove this server secret: forgetting a personal
credential reconnects and may regain shared access. Neither action is global logout. Vite retains
its existing backend routing and browser-token flow, with no standalone server-token behavior.
