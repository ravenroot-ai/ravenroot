# Optional personal OpenAI connections

Ravenroot can connect a verified ChatGPT identity to a local Ravenroot account and use its authorized ChatGPT plan in the assistant, `llm-prompt`, and `agent`. This feature is optional. Existing startup authentication, API-key/OpenAI-compatible profiles, system assistant, ConfigMap graphs, and unattended system executions keep their existing defaults.

## Select the intended account

Every AI node has a **Credential mode** in the 0.8 inspector:

- `system` (the default for existing documents) uses the operator's existing profile and credential binding, even while a personal browser session is signed in.
- `personal` uses the initiating user's selected OpenAI connection. The graph contains this logical requirement only. It contains no user ID, connection ID, token, or deployer's personal identity.

Conversational named Agents also support this selector. Their approved source-tenant definition still fixes
the model profile, instructions, commands, tools and budgets; personal mode changes only credential
selection. Graph fields cannot override that definition. The source definition is checked for current
approval at dispatch, while execution data remains in the personal owner's partition.

Workspace-backed Agents run through a separately authorized external runtime. That path has no trusted
personal Responses channel in this release. A graph combining any personal AI requirement with a
Workspace-backed Agent is refused before effects with `personal-workspace-agent-unavailable`.
It cannot silently use a system account or delegate a browser token to a runner.

A graph may mix the two. Personal graph submission preflights its connection before any node effect, including earlier non-AI nodes. Anonymous, missing-account, locked, revoked, or expired authority is refused; it never changes billing accounts or falls back to a system key.

Open **Personal connections** in the assistant panel. **Continue with ChatGPT** starts browser OAuth. Select a linked account, review its health and plan-use permission, and select system or personal assistant mode. **Authorize this browser's personal assistant** grants at most 15 minutes. Each personal assistant turn is bounded to 4,096 output tokens, a 90-second total request deadline,
and a 4 MiB stream. Each browser needs its own sign-in and authorization, including another browser signed in to the same account. Identity-only sign-in does not grant model use. Reconnect explicitly to request missing ChatGPT plan permission.

The account catalog is fetched using the selected account's credential. The assistant shows its available model names; its requests use the corresponding model slug. Node profiles retain the operator's model default and the inspector's model override. Select a model available to the connected account. An unavailable model is a provider refusal, never a reason to use another account.

Use **Load shared deployments** and **Run selected graph with my connection** for an ordinary START graph already deployed by an operator or ConfigMap. Ravenroot authorizes lookup in the original deployment tenant, pins the exact deployment incarnation and graph version, and starts those same bytes in the executing owner's private partition. The original deployment and system grants remain in their original namespace. This route requires at least one personal AI node; use the ordinary system run path for system-only graphs. Source-only graphs and unattended schedules/webhooks have no implicit personal delegation. They cannot borrow the deployer's connection.

Personal execution outputs, downstream Human Task/approval data, events, artifacts, and retained history use an owner partition. The personal issuer and owner must match that partition; an inherited perimeter platform-admin role cannot select another owner's personal partition. The operator controlling the runtime and its storage can still access runtime data. Personal runtime diagnostics are session-only in the browser and are not attached to the shared editor's assistant context or saved workspace snapshots.

## Operator configuration

Keep the existing perimeter authentication and exposure protections. Optional OAuth does not replace OIDC/local-token authentication where configured. The UI and API must use the same origin for the personal browser flow. Cross-origin system clients keep their existing bearer-token behavior.

The AI bundle remains optional and is not added to the published product. Install and enable it through the existing [plugin bundle procedure](../operator-guide/plugin-bundles.md). Its managed service grant still needs `outbound-http`, `tool-authorization`, and `agent-resources`, finite budgets, an HTTPS origin grant for `api.openai.com:443`, and a credential placement binding for the `Authorization` header with prefix `Bearer `. If the grant restricts credential references, include the logical `personal:openai` reference alongside any authorized system references. Linking an account does not create any of these outbound grants.

An operator model profile still selects the endpoint and binding. For personal mode its HTTPS host must be `api.openai.com` and it must declare a credential binding. Ravenroot uses the public `/v1/responses` route for personal requests; system mode continues using the profile's existing endpoint. Set the profile's model or node override from the connected account's catalog. Profiles are still canonical Base64 JSON in `RAVENROOT_LLM_PROFILE_<UPPERCASE_UTF8_HEX_NAME>`.

| Setting | Purpose |
| --- | --- |
| `RAVENROOT_PERSONAL_OPENAI_MODE` | `disabled` by default; `loopback` or `registered` explicitly enables the feature. |
| `RAVENROOT_PERSONAL_CALLBACK` | Exact callback for this installation; path must be `/auth/callback`. |
| `RAVENROOT_PERSONAL_ALLOWED_HOSTS` | Explicit comma-separated OAuth/inference grant; requires `auth.openai.com,api.openai.com`. |
| `RAVENROOT_PERSONAL_STORE_DIR` | Protected connection state directory; default `/opt/ravenroot/data/personal`. |
| `RAVENROOT_PERSONAL_KEY_FILE` | Operator-owned 32-byte AES key file; defaults to `storage.key` under that directory. |
| `RAVENROOT_PERSONAL_OPENAI_CLIENT_ID` | Required provisioned registration for `registered`; never ship another user's dynamically issued client ID. |
| `RAVENROOT_PERSONAL_OPENAI_CLIENT_SECRET` | Server-only secret when that provisioned registration uses `client_secret_basic`; omit for public clients. |
| `RAVENROOT_PERSONAL_ASSISTANT_MODEL` | Optional initial account model slug; otherwise the first visible account model is selected. |

### Local installations

Use `loopback` and `http://127.0.0.1:<service-port>/auth/callback`. The browser and callback listener must run on the same computer. Do not substitute `localhost`, a public hostname, another callback path, or HTTPS for this direct registration contract. Ravenroot starts from `dynamic_agent_client`, requests fresh state, nonce, and S256 PKCE, and stores the newly issued account/workspace-bound client ID after verification. Subsequent reconnects reuse that registration and the installation's stable host ID.
An opaque HttpOnly returning-registration cookie lasts at most 30 days; it selects the saved client
for fresh sign-in after logout or restart and supplies no identity or model authority.

For another browser, sign in on the current browser and choose **Create returning sign-in code**.
Copy the ten-minute opaque code to **Sign in with returning code** in the second browser. The code
reveals no account metadata and grants nothing by itself: fresh state, PKCE, nonce and ID-token
verification must prove the stored client and subject before the original local owner is restored.
The second browser then needs its own assistant authorization and, when enabled, owner unlock.
A protected connection remains encrypted during identity-only reauthentication. Plan reconnect
requires the verified owner to unlock it first.

A browser without a returning cookie or code uses dynamic registration for a new account. Do not
assume bootstrap reuses an existing registration. **Connect another account** explicitly selects that
new-account path. Distinct registrations are not merged by email.

The first-party cookie is HttpOnly and SameSite=Lax; HTTP is permitted only for this loopback deployment mode. A local container must already satisfy the existing explicit loopback-publication/exposure guards. Configure the callback's externally published loopback port.

### Self-hosted remote and hosted multi-user installations

A loopback callback reaches the computer running the browser, not a remote VM. The official OSS VM procedure transfers an existing protected session after completing OAuth locally; importing such a file alone is not Ravenroot's integrated browser sign-in path.

For an integrated remote browser flow use `registered`, an eligible operator-provisioned registration, and its exact registered HTTPS callback ending in `/auth/callback`. OpenAI website clients and ChatGPT plan usage have separate eligibility and consent requirements. Setting a client ID does not establish either eligibility or plan-use permission. Without a suitable registration, leave the feature disabled and use system mode; the UI reports configuration required. Do not attempt dynamic registration against an arbitrary remote callback.

Replicas and execution workers that share these connections must share the protected state and key through operator-controlled storage supporting POSIX owner-only permissions, advisory file locks, atomic rename, and file/directory fsync. Browser sessions and pending OAuth transactions are process-local: use sticky routing to the OAuth-serving instance. A restart requires a fresh browser sign-in. Durable execution grants can be checked by another worker against the shared state within their original lifetime. Do not use independent per-pod connection volumes and assume they form a shared store.

## Lifecycle and protection

The backend exchanges codes and verifies RS256 ID-token signatures against OpenAI JWKS, exact issuer, issued-client audience, subject, nonce, and expiry. Transactions expire after 10 minutes and are consumed once. A local account is keyed by verified issuer/client/subject, never by matching email. Linking another provider registration requires an existing signed-in browser session and fresh proof from the account being linked. Different registrations remain separate even when their email labels match.

Connection state is AES-256-GCM encrypted and atomically replaced under a cross-process lock. Unix key/state/lock files are created with mode `0600`; the state directory is `0700`. The storage key is separate from ciphertext, but its default sidecar is accessible to the same service account. This protects a copied ciphertext file without its key; it does not protect against compromise of the key, the service account, an authorized operator, or the executing runtime. Move and manage the key separately when the deployment requires stronger storage separation. Existing user-credential storage is not assumed to be encrypted by this feature.

Refresh is serialized with connection mutation across processes. The latest access token, expiry, granted scopes, and rotating refresh token are replaced together. Temporary provider failures retain credentials. Terminal refresh invalidation removes unusable tokens and requires reconnect. Identity permission alone cannot authorize Responses requests.

A personal execution receives a 15-minute, owner-bound connection grant keyed by a server-minted request identity. The trusted source tenant, issuer, subject and exact deployment tuple are retained separately from the verified executor in this grant. The immutable request identity is carried by immutable runtime security context through routing and durable re-entry. Caller headers, graph properties, payload owner fields, copied connection IDs, workers, and service identities cannot replace it. Retry and recovery keep the original account; expired or unavailable authority fails closed. Start a new authorized execution when the original grant expires. There is no automatic personal delegation for unattended execution.

**Owner presence** is an explicit optional passphrase protection. A passphrase of at least 12 characters derives a key with PBKDF2-HMAC-SHA256 (600,000 iterations) and encrypts that connection's token record with AES-GCM in addition to storage encryption. A short PIN is weaker and is not accepted as this protection. The raw passphrase is not persisted or placed in a graph, queue, or checkpoint.

Unlock either the **next execution** or **this assistant session**, for at most 15 minutes. An execution unlock is consumed at admission and bound to that execution's trusted request identity; it cannot unlock another run. Assistant unlock stays bound to that browser. Unlock keys are process-local and are lost on restart: recovery refuses protected use after a restart, and the owner must unlock and start a new execution. Possession of a reference or knowledge of the passphrase without the verified owner session grants no authority. An authorized runtime necessarily accesses usable credentials; no memory-erasure or compromised-runtime protection is promised.

Sign-out removes that browser's personal authority, including its outstanding grants. Disconnect stops use of the selected connection and attempts provider session revocation using discovery's revocation endpoint. If remote revocation cannot be confirmed, Ravenroot still disconnects locally and reports this explicitly; disconnect the app in ChatGPT Settings as well. A verified owner can disconnect a locked connection without supplying its passphrase. Ravenroot then
discards its sealed token record and protection salt, invalidates its grants, and reports remote
revocation unconfirmed because it cannot decrypt a token for the provider. Only safe registration
metadata remains. Fresh verified reconnect and plan consent are required before reuse; owner presence
can then be enabled again. Other users and system credentials are unaffected. Active personal graph HTTP operations recheck local revocation/unlock authority every second and before accepting the response. Personal assistant streams recheck authority while reading. OpenAI currently sends no push notification when access is revoked in ChatGPT Settings; a confirmed provider/refresh refusal requires reconnect.

## API and diagnosis

`GET /v1/personal` returns only this browser's connection metadata, identity, health, and session authorization. `POST /v1/personal/sign-in` begins OAuth; `/auth/callback` consumes the matching transaction. POST `/v1/personal/registration` creates a ten-minute non-authorizing returning-sign-in code for the selected connection. `POST /v1/personal/sign-in` accepts that code as `registration`, or `newConnection: true` for explicit fresh registration. POST operations `/select`, `/authorize`, `/protect`, `/unlock`, `/lock`, `/disconnect`, and `/sign-out` affect only the verified owner/browser. `/unlock` requires `purpose: "execution"` or `"assistant"`. `GET /v1/personal/models` returns visible account model slugs and display names. POST `/v1/personal/executions` accepts `deploymentId`, `incarnationId`, `graphVersion`, and an optional standard payload envelope.

The assistant uses the existing messages body with `X-Ravenroot-Assistant-Mode: personal` and optional `X-Ravenroot-Assistant-Model`. Ordinary submissions inspect graph modes server-side; no caller-provided owner field is accepted. Private execution/history reads use `X-Ravenroot-Execution-Context: personal` plus the HttpOnly browser cookie and existing perimeter authentication. The header selects a context, not a tenant or account. System-only submissions always use their original system context. Non-browser clients without a verified personal session can continue system execution; they cannot import a token into this API or impersonate a personal executor.

Resolve named states at the correct boundary: login, connect, reconnect, missing plan permission, unlock, model unavailable, operator egress not granted, unsupported deployment, provider permission denied, quota exceeded, temporary provider unavailable, incomplete/interrupted stream, and unconfirmed revocation. Responses requests set `store:false` and `stream:true`. Only `response.completed` is success; late quota failures, incomplete responses, and interrupted streams fail without returning a successful node result. Agent tools use the existing finite loop, tool authorization, egress policy, and resource accounting; OAuth adds no tool authority.

Never put provider credentials in browser storage, graphs/exports, ConfigMaps, logs, error text, or support evidence. Graphs rebind their logical personal requirement to the verified executor and that environment's selected connection. There is no portable personal secret reference in an exported document.

Official contracts: [quickstart](https://developers.openai.com/siwc/quickstart), [direct registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [models and completed inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [accounts and refresh](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [remote VMs](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms), and [provisioned website registrations](https://developers.openai.com/siwc/website).
