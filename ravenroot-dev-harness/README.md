# Ravenroot development harness

This module is a source-only local development bench. It is not a release artifact, must remain
bound to loopback, and must not be used for production workloads.

The harness starts the ordinary Ravenroot server while supplying both an `llm-prompt` node and a
registered `ModelProvider`. The default distribution supplies neither. Keeping this composition in a
separate module preserves the release boundary while providing an executable example of the
embedding API.

## Safety boundary

The module is deliberately separate from the release reactor and has its own Maven coordinates.
Its build enforces the following properties:

- Maven install and deploy are disabled.
- The listener is forced to the loopback address; `RAVENROOT_BIND_ADDRESS` is ignored.
- `NotAReleaseArtifactTest` rejects references to this module from release, container and deployment
  surfaces.
- The ordinary authentication, egress and credential-resolution implementations are retained.

These controls do not make the harness suitable for real workloads. They only keep a development
tool out of release artifacts and off reachable network interfaces.

## Build

From the repository root:

```bash
./dev.sh
```

The equivalent manual sequence, with a supported `JAVA_HOME`, is:

```bash
mvn -f ravenroot/pom.xml install -pl ravenroot-server,ravenroot-pekko -am -DskipTests
mvn -f ravenroot-adapter-openai-compatible/pom.xml install
mvn -f ravenroot-dev-harness/pom.xml verify
```

Installing the harness itself is intentionally a no-op.

## Run

Build the editor and start the bench with:

```bash
./dev.sh bench
```

The default provider expects an OpenAI-compatible Ollama endpoint at
`http://127.0.0.1:11434/v1/chat/completions` and uses model `qwen3`. The editor is served from the
directory named by `RAVENROOT_UI_DIR`; the development script configures it automatically.

To run the module directly:

```bash
npm --prefix ravenroot/ravenroot-ui install
npm --prefix ravenroot/ravenroot-ui run build
RAVENROOT_UI_DIR=ravenroot/ravenroot-ui/dist \
  mvn -f ravenroot-dev-harness/pom.xml exec:java
```

The server listens on `http://127.0.0.1:8080` by default.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `RAVENROOT_DEV_MODEL_PROVIDERS` | `ollama-local` | Comma-separated provider ids using lowercase letters, digits and hyphens |
| `RAVENROOT_DEV_MODEL_<ID>_ENDPOINT` | `http://127.0.0.1:11434/v1/chat/completions` | Complete chat-completions URI |
| `RAVENROOT_DEV_MODEL_<ID>_MODEL` | `qwen3` | Model used when the node leaves `model` blank |
| `RAVENROOT_DEV_MODEL_<ID>_CREDENTIAL_REF` | unset | Credential reference; its presence enables authenticated mode |
| `RAVENROOT_HTTP_ALLOWED_HOSTS` / `_PORTS` | unset | Outbound policy used by the adapter |
| `RAVENROOT_UI_DIR` | unset | Built editor directory |
| `RAVENROOT_PORT` | `8080` | Loopback port |
| `RAVENROOT_ALLOWED_TOOLS` | `model.generate` | Tool allowlist |

`<ID>` is uppercased and has `-` replaced by `_`; for example, `ollama-local` becomes
`OLLAMA_LOCAL`. Credential values are resolved by `EnvironmentCredentialResolver` and do not appear
in the provider declaration.

## Composition differences

Compared with `RavenrootServerMain`, this program:

1. supplies an `llm-prompt` behavior and registers a model provider;
2. forces the listener to loopback;
3. defaults the tool allowlist to `model.generate` so the development node can run.

All other tools remain denied unless explicitly allowed. Setting `RAVENROOT_ALLOWED_TOOLS` to an
empty string restores deny-all behavior.

The provider registry is armed directly from this program's environment because this harness has a
single configuration surface. Bundle nodes use the separate managed-HTTP composition route.

## Related modules

- `ravenroot-adapter-openai-compatible/` provides the adapter registered by this harness.
- `ravenroot-sample/` demonstrates ordinary embedding without the model-provider bench.
- `ravenroot/ravenroot-distribution/` is the release artifact.
