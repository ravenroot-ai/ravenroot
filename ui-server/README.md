# Ravenroot independent UI

This archive contains compiled frontend assets in `ui/` and an optional Node.js 24 static web
server/streaming proxy in `server.mjs`. It contains no Java backend. Start against an existing backend:

```sh
RAVENROOT_UI_ROOT="$PWD/ui" RAVENROOT_UI_BACKEND_URL=http://127.0.0.1:8080 \
  RAVENROOT_UI_PORT=5001 node server.mjs
```

Without `RAVENROOT_UI_BACKEND_URL`, the server runs in static-only mode: UI/local probes work,
while `/v1` and backend probes return `503` with `UI_BACKEND_NOT_CONFIGURED`. Explicit localhost or
loopback URLs using the UI listening port are rejected; do not use this UI's Service/DNS name as its
own upstream. Configure an existing backend to enable connected features.

Open http://localhost:5001/. Supply a backend-issued access token in the UI connection panel.
The backend URL is server configuration; it is never emitted into assets. The browser uses the
same-origin proxy. Never put credentials in the backend URL or image layers.

Optional `RAVENROOT_UI_PREFIX=/ravenroot` serves `/ravenroot/` and proxies `/ravenroot/v1/*`.
An upstream `RAVENROOT_UI_BACKEND_URL=https://backend.example.test/automation` maps that API to
`https://backend.example.test/automation/v1/*`. Prefixes are independent. The gateway must preserve
this UI prefix. `/health` and `/ready` check the web server only; `<prefix>/backend-health` and
`<prefix>/backend-ready` proxy backend probes. SSE is streamed with backpressure; disable buffering
and allow long response timeouts in any ingress. HTTPS upstream verification is always enabled;
use `NODE_EXTRA_CA_CERTS=/mounted/ca.pem` for a private CA. Client certificates are not configured by
this server. Connect to an HTTPS-enabled gateway if your backend needs another transport policy.

The server binds 0.0.0.0. Terminate browser TLS at a trusted gateway. Run as non-root on a read-only
filesystem, remove Linux capabilities and restrict network egress to the selected backend/DNS.
Only the `/v1` API and explicit backend probes are proxied; the optional interaction WebSocket is
not proxied. Static UI uses a same-origin CSP and denies framing; terminate TLS and set HSTS at the gateway.
Tokens and upstream responses are not logged. Unknown static paths return 404.

You may serve `ui/` with another static server. Configure that server's API/SSE routing and set the
UI connection panel to its same-origin API base URL, including the public prefix. Assets are relative,
so serve the UI at a trailing-slash URL. This server pre-fills that prefix automatically.

See the public Kubernetes guides at https://docs.ravenroot.ai/operator-guide/kubernetes-ui-only.html
and https://docs.ravenroot.ai/operator-guide/kubernetes-installation.html for full deployment steps.


## Browser connections and optional shared server authentication

After a successful explicit **Authenticate**, a dialog offers to remember that public Service and
its token in this browser's Local Storage. Decline (or Escape) keeps the token only in page memory.
Consent is not repeated on transport retries or automatic restoration. A remembered token has no
additional expiry timer: the backend remains the authority on validity. The blank password field
shows **saved in this browser** while the saved credential is active. **Forget token** removes its
browser opt-in and both saved and memory token, retaining Service. A definitive 401 does the same;
403, network and CORS failures do not prove expiry and do not erase it. Clearing site data removes
the browser connection; clearing only cached assets need not. Browser storage is readable by page
JavaScript, so use memory-only authentication when that exposure is inappropriate.

Service precedence is current manual selection, a valid saved connection, the startup public
prefix, then same origin. The browser binding is the normalized public scheme/host/port **and API
path prefix**; it does not identify the private backend behind a proxy. Changing Service never
transfers its token to another binding. Direct external service restoration waits for a fresh origin
confirmation through **Authenticate**, even with an empty password field. No trust bypass is saved.
This standalone server's CSP permits same-origin connections only; direct external browser services
require a separately reviewed serving policy.

Optionally set `RAVENROOT_UI_BACKEND_TOKEN` at process startup alongside the backend URL. It is a
literal bearer value (nonempty RFC 6750 bearer characters, at most 16,384 characters), held only in
server memory. A variable containing a secret name or path is **not resolved**; deployers must inject
the effective value. For a local, access-controlled test deployment:

```sh
RAVENROOT_UI_ROOT="$PWD/ui" RAVENROOT_UI_PORT=5001 \
RAVENROOT_UI_BACKEND_URL=http://127.0.0.1:8081 \
RAVENROOT_UI_BACKEND_TOKEN='replace-with-backend-issued-token' node server.mjs
```

Every authorized browser opening this UI connects with that shared identity when it sends no
Authorization header. Explicit browser Authorization (including a saved personal token) takes
precedence. Empty, malformed, non-Bearer and duplicate headers are rejected; a rejected client
credential is never retried with the default. No remember dialog is shown for server-held access.
Protect access to the UI deployment: these callers share the backend identity and scopes of the
operator token. Browser Origin and Fetch Metadata are checked before ambient injection, and the
backend still receives the browser Origin and enforces its own policy. CLI clients without browser
headers rely on the deployment's access control. A TLS edge must preserve the public Host; untrusted
Forwarded headers are never used to authorize a browser.

The index exposes only the public prefix and a `data-server-auth` boolean for that proxy binding.
The boolean lets Forget reconnect to configured shared access; backend authorization still decides
which features and scopes are available. The token is used only for the fixed `/v1` upstream, never static assets, local health or backend
probes. Backend redirects are refused rather than followed. Secret reflection in upstream headers
or bodies is redacted across byte-chunk boundaries, while ordinary SSE bytes continue immediately.
The upstream is requested with identity encoding so that redaction can inspect its response; an
upstream ignoring that and returning a compressed response is refused. Neither credentials nor
upstream responses are logged, and no secret enters public configuration, assets or browser storage.

**Forget token** and clearing site data remove only browser-held access. The operator secret remains;
after forgetting a personal token the UI reconnects and the shared server identity may apply again.
This is not global logout. Expired server-held access requires operator rotation and UI server
restart; no cookie session, login or refresh exchange is added. Vite's development proxy still uses
`RAVENROOT_SERVICE_URL` and browser-token authentication, not this standalone secret setting.
