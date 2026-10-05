# Ravenroot independent UI

This archive contains compiled frontend assets in `ui/` and an optional Node.js 24 static web
server/streaming proxy in `server.mjs`. It contains no Java backend. Start against an existing backend:

```sh
RAVENROOT_UI_ROOT="$PWD/ui" RAVENROOT_UI_BACKEND_URL=http://127.0.0.1:8080 \
  RAVENROOT_UI_PORT=8081 node server.mjs
```

Open http://localhost:8081/. Supply a backend-issued access token in the UI connection panel.
The backend URL is server configuration; it is never emitted into assets. The browser uses the
same-origin proxy. Never put credentials in the backend URL or image configuration.

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
