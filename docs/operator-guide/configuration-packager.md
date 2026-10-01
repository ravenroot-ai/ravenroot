# Configuration packager

Use the standalone configurator when an operator needs one reviewable artifact for Ravenroot profiles, backend policy, credentials, and optional prebuilt node packages. It supports the shipped Compose file, Helm chart, and embedding pre-start layouts. It does not build Ravenroot, invoke `plugin.sh`, or compile extension source on the target.

## Build the tool

The component requires Node 24 and pnpm 11. Its build is independent from the Maven reactor.

```bash
cd ravenroot-configurator
corepack enable
pnpm install --frozen-lockfile
pnpm build
node bin/ravenroot-config.mjs ui
```

The guided UI binds to `127.0.0.1`. It selects one or more profile families, target and tenant identities, secret handling, and the closed bundle directories required by the selected optional nodes. `pnpm check:coverage` compares its registry with the current shipped and governed descriptor inventories and the first-party bundle index. A newly shipped node or bundle therefore fails the configurator build until its operator configuration is mapped or it is explicitly classified as author-only.

## Package review

`ravenroot-config pack` creates a version-one `.rrcfg` ZIP. Review it with `inspect`; the manifest reports target binding, tenant identities, selected contracts, required capabilities, bundle identities and digests, entry digests, and a redacted change plan. Secret values never appear in that plan.

Target-owned credentials remain references. Compose references name an environment variable; Kubernetes references name an existing Secret and key. Embedded values are accepted only through a process environment named by the pack specification, with the package password read from standard input. They are encrypted with Argon2id and AES-256-GCM. Generated JSON files can use `{ "$secret": "binding-id" }`; substitution occurs during apply and the portable configuration retains only that placeholder.

Every selected optional profile requires its complete prebuilt bundle directory. A profile that declares managed-service capabilities also requires a `bundle.service-grant` selection whose profile identity is the exact plugin manifest ID; the generated `RAVENROOT_NODE_PACKAGE_SERVICES_<PACKAGE_UTF8_HEX>` value is the package's closed runtime authority. Packaging verifies the version-one plugin manifest, `ravenroot.node-sdk/2` contract, declared filenames, sizes, SHA-256 digests, and absence of undeclared files. Applying a package never calls a source build or `plugin.sh`.

## Plan, apply, and recover

Always run `plan` against the intended target directory first. Plans show only managed paths, digests, secret binding identities, restart commands, and verification steps. Compose planning parses `compose.yaml` and refuses a non-identical `services.ravenroot.environment` key in either map or list form. Kubernetes planning queries the live Deployment, its direct and `envFrom` sources, and the generated configuration Secret; unavailable or malformed cluster state fails closed, so offline Kubernetes operation is limited to `inspect` and cannot produce an applicable plan. Apply is confined to `.ravenroot-config/` and refuses a different bound target, managed file drift, package integrity failure, or a pending interrupted transaction. Use `--replace` only after reviewing a planned managed value replacement.

With `--execute`, Compose rebuilds and recreates the `ravenroot` service, Kubernetes applies generated Secrets, upgrades the Helm release, adds a strategic Deployment patch, and waits for rollout, and pre-start runs the configured argument-vector restart command. A failed command rolls back the written files. A pending journal left by a process crash blocks another apply until `rollback` restores the backed-up bytes.

After restart, `verify` requires `/ready` and checks every node ID implied by the selected contracts in `/v1/node-types`. Put an optional bearer in `RAVENROOT_CONFIG_VERIFY_BEARER`; do not place it in shell history.

## Publication boundary policies

The packaged server accepts `RAVENROOT_PUBLICATION_POLICY_CONFIG`, an operator-owned closed version-one JSON file. Unset retains the fail-closed empty resolver. The document contains one or more immutable policy IDs and versions, candidate byte ceilings, and ordered declarative rules for destination, logical path, sensitive content, language, artifact type, companion files, and provenance. The configurator mounts this file and sets the environment carrier. Policy decisions are written to the durable audit trail without candidate content, destinations, logical paths, matched values, or credentials.

## AMQP exercise

`pnpm example:amqp` is a sanitized, network-free acceptance exercise. It creates a prebuilt closed bundle fixture in a temporary directory, packages publisher and consumer authority, preserves the broker password as a target reference, applies twice to prove idempotency, and performs rollback. The checked-in `examples/amqp/package-spec.json` is also a starting point for a real bundle directory and target.
