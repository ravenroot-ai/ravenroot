# Ravenroot configuration packager

This standalone Node 24 component turns guided operator configuration into a portable `.rrcfg` package and applies it to an existing Ravenroot deployment. It does not build Ravenroot or extension source. A selected optional package requires its already-built closed bundle directory, including `ravenroot-plugin.json` and every declared JAR.

## Build and run

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm build
node bin/ravenroot-config.mjs ui
```

The local UI listens on `127.0.0.1:4178`. The CLI exposes the same shared registry:

```bash
node bin/ravenroot-config.mjs contracts
node bin/ravenroot-config.mjs pack package-spec.json deployment.rrcfg
node bin/ravenroot-config.mjs inspect deployment.rrcfg
node bin/ravenroot-config.mjs plan deployment.rrcfg --target-root /srv/ravenroot
node bin/ravenroot-config.mjs apply deployment.rrcfg --target-root /srv/ravenroot --execute
node bin/ravenroot-config.mjs verify deployment.rrcfg --base-url http://127.0.0.1:8080/
node bin/ravenroot-config.mjs rollback --target-root /srv/ravenroot
```

Use `--password-stdin` when a package contains encrypted embedded secrets. The pack specification names the source environment with `valueEnvironment`; it never contains a secret value. Verification reads an optional bearer from `RAVENROOT_CONFIG_VERIFY_BEARER` and never accepts it on the command line.

## Package and apply contract

The version-one package is a deterministic ZIP container with a canonical manifest, target and tenant binding, selected contracts, required capabilities, bundle manifests, a redacted plan, and SHA-256 size bindings for every entry. Embedded secret entries use Argon2id and AES-256-GCM with the binding identity as authenticated data. Target references remain references for Compose variables or Kubernetes Secret keys.

Optional profiles that request managed services require a `bundle.service-grant` selection keyed by the exact plugin manifest ID. The packager refuses a missing or unrelated grant, so the installed `RAVENROOT_NODE_PACKAGE_SERVICES_<PACKAGE_UTF8_HEX>` carrier cannot be silently omitted.

Apply writes only `.ravenroot-config/` beneath the selected target. It refuses target identity changes, unmanaged file drift, incompatible bundles, digest failures, missing bundle payloads, and an interrupted transaction. A transaction backs up every managed file and its prior state, writes a pending recovery marker, recreates the target when requested, and restores the previous bytes if restart fails. `--replace` is the explicit operation for replacing a managed value.

Planning performs structural collision checks before writing. Compose reads the existing `services.ravenroot.environment` map or list. Kubernetes reads the live Deployment, referenced `envFrom` objects, and generated Secret name with `kubectl`; if that live state cannot be queried, Kubernetes planning and apply fail closed. Target-side references must name a concrete Compose variable or Kubernetes Secret and key.

- **Compose:** writes an additive override, mounts generated operator documents read only, stages prebuilt bundles into the existing image build, and recreates only `ravenroot`.
- **Kubernetes:** writes target-owned Secrets, Helm image values, and an additive strategic Deployment patch; mounts generated documents, builds an optional derived image from prebuilt bundle bytes, performs the Helm upgrade, applies the patch, and waits for rollout.
- **Pre-start:** writes a mode `0600` environment script, generated documents, and bundle directory. Set `restartCommandJson` to a JSON string array to run an embedding-specific restart without a shell.

JSON documents may contain an exact `{ "$secret": "binding-id" }` value. The referenced secret must be an encrypted embedded binding; substitution happens only during apply, after decryption. The package retains only the placeholder.

Run the sanitized AMQP exercise with `pnpm example:amqp`. It creates a closed fixture bundle in a temporary directory, packages publisher and consumer profiles, preserves a target-side password reference, applies twice to prove idempotency, and rolls back.
