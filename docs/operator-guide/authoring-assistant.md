# Configure the authoring assistant

The authoring assistant is the workspace service behind `/v1/assistant`. It helps an authenticated
author work with graph content. Its provider profile is deployment configuration and is separate
from `llm-prompt` and `agent` node profiles: changing assistant settings never installs an AI node
bundle, enables a graph behavior, or grants a graph outbound authority.

All assistant settings are read from the process environment at startup. Change them by updating
the deployment configuration and restarting or recreating the server. They are not persisted through
the API. Per-author consent is durable in its own SQLite file; OAuth access tokens remain process-local
and require reconnection after restart.

## Setting reference

| Variable | Type and default | Effect |
|---|---|---|
| `RAVENROOT_ASSISTANT_ENABLED` | Boolean-like; unset or any value except `false` enables the route | `false` removes the service surface and `GET /v1/assistant` returns 404. Unset leaves an inert, discoverable panel until a profile is configured. |
| `RAVENROOT_ASSISTANT_PROVIDER` | String; no default | `anthropic`, `openai-compatible`, or the test/development-only `scripted` adapter. Other IDs have no registered adapter. |
| `RAVENROOT_ASSISTANT_ENDPOINT` | HTTPS URI; no default except Anthropic | Exact messages endpoint. `anthropic` defaults to `https://api.anthropic.com/v1/messages`; `openai-compatible` requires an endpoint. User info, query, and fragment are refused. |
| `RAVENROOT_ASSISTANT_MODEL` | String; Anthropic defaults to the pinned `claude-opus-5`; otherwise required | Provider model identifier. |
| `RAVENROOT_ASSISTANT_CREDENTIAL_SOURCE` | `api-key` or `oauth`; default `api-key` | Selects one credential model. There is no fallback between an author token and the operator key. Unknown values retain the `api-key` default. |
| `RAVENROOT_ASSISTANT_API_KEY` | Secret string; no default | Operator key read only in `api-key` mode. Do not put it in GraphML, a command-line flag, or a browser setting. `openai-compatible` may be deliberately credential-free for a local endpoint. |
| `RAVENROOT_ASSISTANT_ALLOWED_HOSTS` | Comma-separated exact hosts; no default | Operator-owned outbound allowlist. A provider and credential without this setting remain inert. |
| `RAVENROOT_ASSISTANT_ALLOWED_PORTS` | Comma-separated ports from 1 to 65535; default `80,443` | Ports allowed for the provider and device-flow endpoints. Name a non-default local-model port explicitly. Malformed nonblank values refuse startup. |
| `RAVENROOT_ASSISTANT_ALLOW_LOCAL_HTTP` | `true` or `false`; default `false` | Allows plaintext only for a credential-free `openai-compatible` endpoint on localhost, a loopback literal, a `.localhost` name, or `host.docker.internal`. It does not bypass host/port allowlists. OAuth and credential-bearing HTTP remain refused. Malformed nonblank values refuse startup. |
| `RAVENROOT_ASSISTANT_TIMEOUT_SECONDS` | Positive whole seconds; default 120 | Wall-clock bound for a provider operation. Malformed and non-positive nonblank values refuse startup. |
| `RAVENROOT_ASSISTANT_CONSENT_DIR` | Directory; default `./data/assistant-consent` | Holds `ravenroot-assistant-consent.db`. In the published container working directory, the default is inside the mounted data volume. Back up this directory with the deployment's durable data. |
| `RAVENROOT_ASSISTANT_DEVICE_AUTHORIZATION_ENDPOINT` | Absolute HTTPS URI with a host and no user info or fragment; no default | OAuth RFC 8628 device-authorization endpoint. Provider-specific paths, ports, and queries are preserved. It is used only with the complete OAuth configuration. |
| `RAVENROOT_ASSISTANT_TOKEN_ENDPOINT` | Absolute HTTPS URI with a host and no user info or fragment; no default | OAuth token endpoint. Provider-specific paths, ports, and queries are preserved. |
| `RAVENROOT_ASSISTANT_OAUTH_CLIENT_ID` | Public client identifier; no default | Provider's device-flow client ID; it is not a client secret. |
| `RAVENROOT_ASSISTANT_SESSION_MINUTES` | Positive whole minutes representable as a duration; default 480 | Maximum lifetime of a redeemed token inside one process. Unrepresentable values refuse startup; if adding a valid duration to the actual redemption time cannot be represented, the token and consumed grant are not retained. Restart ends the session sooner. |

The provider call also has fixed in-code runtime ceilings of 16,000 output tokens and eight tool
iterations per author message. There are no environment overrides for those two values in this
baseline.

## Local OpenAI-compatible provider

This procedure is suitable for a local model server that implements the chat-completions contract.
The example uses loopback port 11434; replace the model and endpoint path with values your provider
actually exposes.

1. Start the provider and verify its health locally.
2. Configure the assistant process:

   ```sh
   export RAVENROOT_ASSISTANT_PROVIDER=openai-compatible
   export RAVENROOT_ASSISTANT_ENDPOINT=http://localhost:11434/v1/chat/completions
   export RAVENROOT_ASSISTANT_MODEL=local-model
   export RAVENROOT_ASSISTANT_ALLOWED_HOSTS=localhost
   export RAVENROOT_ASSISTANT_ALLOWED_PORTS=11434
   export RAVENROOT_ASSISTANT_ALLOW_LOCAL_HTTP=true
   ```

3. Leave `RAVENROOT_ASSISTANT_API_KEY` unset. Plain local HTTP is refused when any credential can be
   sent, and the exception does not apply to OAuth mode.
4. Restart or recreate Ravenroot so the immutable startup configuration is rebuilt.
5. As an authenticated author, request `GET /v1/assistant`. Require HTTP 200 with `configured:true`,
   `allowlisted:true`, `insecureRefused:false`, and a non-null provider before sending a message.
6. Submit one bounded message in the workspace and verify the response. A successful status check
   proves configuration and policy admission; the message proves provider reachability and protocol
   compatibility.

Use the `localhost` name exactly. Numeric loopback literals such as `127.0.0.1` pass the assistant's
plaintext-local check but are refused by the shared egress policy; the shipped reserved-address
exception is name-scoped as `localhost:LOOPBACK`.

When Ravenroot runs in a container, `localhost` names that container. On a platform that provides
`host.docker.internal`, use the maintained [Compose override](../examples/assistant/compose.override.yaml):

```sh
export RAVENROOT_COMPOSE_OVERRIDE_FILE=$PWD/docs/examples/assistant/compose.override.yaml
export RAVENROOT_ASSISTANT_PROVIDER=openai-compatible
export RAVENROOT_ASSISTANT_ENDPOINT=http://host.docker.internal:11434/v1/chat/completions
export RAVENROOT_ASSISTANT_MODEL=local-model
export RAVENROOT_ASSISTANT_ALLOWED_HOSTS=host.docker.internal
export RAVENROOT_ASSISTANT_ALLOWED_PORTS=11434
export RAVENROOT_ASSISTANT_ALLOW_LOCAL_HTTP=true
export RAVENROOT_EGRESS_RESERVED_EXCEPTIONS=host.docker.internal:PRIVATE
docker compose -f compose.yaml -f "$RAVENROOT_COMPOSE_OVERRIDE_FILE" config --quiet
./service.sh restart -si
```

The explicit `PRIVATE` exception is required when that name resolves to a private container-network
address; it grants only that name and network class. Use `LOOPBACK` for a container-local provider,
and the matching reviewed class for another private service name. The explicit override variable keeps
this example separate from any existing `.ravenroot-local/compose.override.yaml`; `restart -si`
force-recreates the container with the validated environment while reusing its existing image. Keep
`RAVENROOT_ASSISTANT_API_KEY` unset for this plaintext path.

## Anthropic with an operator key

```sh
export RAVENROOT_ASSISTANT_PROVIDER=anthropic
export RAVENROOT_ASSISTANT_MODEL=claude-opus-5
export RAVENROOT_ASSISTANT_ALLOWED_HOSTS=api.anthropic.com
export RAVENROOT_ASSISTANT_ALLOWED_PORTS=443
export RAVENROOT_ASSISTANT_API_KEY='replace-through-your-secret-manager'
```

The endpoint and model defaults shown above are pinned by this baseline, but naming them explicitly
makes an operator change reviewable. Inject the key through the deployment's secret mechanism; the
literal shown is a placeholder. Recreate the service and verify the status route, then send a bounded
message. A reachable endpoint can still reject authentication or provider-specific model access;
those are provider failures, not egress-policy failures.

## OAuth device flow

Set `RAVENROOT_ASSISTANT_CREDENTIAL_SOURCE=oauth`, the provider, model, host/port allowlists, and the
three provider-specific `RAVENROOT_ASSISTANT_DEVICE_AUTHORIZATION_ENDPOINT`,
`RAVENROOT_ASSISTANT_TOKEN_ENDPOINT`, and `RAVENROOT_ASSISTANT_OAUTH_CLIENT_ID` values. Ravenroot has
no default for either OAuth endpoint because this baseline does not verify a universal provider URL.
Do not set `RAVENROOT_ASSISTANT_API_KEY`; OAuth mode does not read it and never falls back to it.

After restart, `GET /v1/assistant` can report `linkRequired:true`. The authenticated author uses the
workspace connection action, completes the provider's device flow, and retries the status request.
The token is kept only in memory; a service restart or session expiry requires reconnection. Consent
records remain in the configured consent database and fail closed if that database cannot be read.

## Availability diagnosis

Use the status response and server diagnostics in this order:

| Symptom | Diagnosis | Action | Verification |
|---|---|---|---|
| HTTP 404 | `service-unavailable`: assistant explicitly disabled or route absent | Set `RAVENROOT_ASSISTANT_ENABLED` to a value other than `false` on a build that includes the service, then restart | Status returns an authenticated 200 response |
| `configured:false` | `no-profile`: provider, endpoint/model, or required credential is missing; an unknown provider also cannot construct an adapter | Complete the selected provider profile. In OAuth mode, configure device endpoints and client ID; in API-key mode, inject the key unless using the allowed credential-free local path | `configured:true` |
| `insecureRefused:true` | `insecure-refused`: HTTP endpoint is outside the narrow credential-free local exception | Use HTTPS, or for an eligible local OpenAI-compatible endpoint set the local-HTTP opt-in and remove credentials | `insecureRefused:false` |
| `allowlisted:false` | `host-not-allowlisted`: exact host or port is outside operator egress policy | Add only the configured destination and port, then restart | `allowlisted:true` |
| HTTP 401 or 403 | Ravenroot session is not authenticated or lacks author authority | Repair the Ravenroot identity/session; do not change provider credentials | Authenticated status request reaches the assistant response |
| `linkRequired:true` | `not-linked`: OAuth operator configuration is ready but this author has no active provider connection | Complete the device flow for that author | `linkRequired:false` and a message succeeds |
| Status ready, message fails authentication | Provider rejected the selected key/token or account/model access | Rotate or reconnect the selected credential model; never fall back to the other billing identity | A new bounded message succeeds |
| Status ready, message times out or is unreachable | DNS, provider health, endpoint path, TLS, or network path failed after policy admission | Check the exact endpoint from the Ravenroot host and provider health; retain the allowlist boundary | Status remains ready and a message completes inside the timeout |

See [Credentials, connectors, and egress](credentials-egress.md) for shared outbound policy and
[AI, artifacts, plugins, and connectors](../troubleshooting/ai-extensions.md) for bundle and model-node
diagnosis.
