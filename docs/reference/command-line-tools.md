# Command-line tools

Run the repository scripts from the checkout root. Run the `ravenroot` application CLI from an
unpacked distribution or through its installed launcher. Every command writes diagnostics to
standard error and returns a nonzero status when it refuses or cannot complete an operation.

The script help and CLI dispatch tables are maintained machine sources. The documentation check
compares this page with `./plugin.sh help`, `./service.sh help`, `./dev.sh help`,
`./ravenroot/scripts/server.sh help`, and the application CLI sources so a new command, option, or
environment name cannot be added without updating this manual. It also requires the two fixed
distribution entry points below to remain inventoried.

## `plugin.sh`

Purpose: build, validate, install, inspect, and remove deployable plugin bundles. It requires a JDK
and Maven when the plugin bundle support classes or an extension must be compiled. Its convention
directory is `./ravenroot-plugins`, overridden by `RAVENROOT_PLUGINS_DIR` or the command-specific
`--dir` value. Installation changes files only; it does not activate a package, change
`RAVENROOT_ENABLED_PLUGINS`, or rebuild an image.

| Command | Options and effect |
|---|---|
| `list [plugins-dir]` | Validate and list every bundle under the selected directory. The positional directory overrides `RAVENROOT_PLUGINS_DIR`. |
| `build EXTENSION` | Build one module and write `target/plugin-bundle`. `EXTENSION` accepts a discovered short ID such as `kafka`, the module directory name such as `ravenroot-kafka`, or a path to a Maven module containing one production `NodePackage`. `-st` and `--skip-tests` pass `-DskipTests`. |
| `build --all` | Build every discovered first-party node-package module except `ai`, which is always an explicit choice, and `jdbc` unless complete `--driver-jar FILE --driver-sha256 HEX` pairs are supplied. `--all` and an extension name are mutually exclusive. |
| `validate BUNDLE` | Validate one bundle or a directory of bundles with the runtime validator. No files are installed. |
| `check-published PLUGINS-DIR` | Apply the publication boundary to a staged directory and refuse any manifest declaring `ai` or `agentic`. A locally installable bundle can still be ineligible for official publication. |
| `install BUNDLE [--dir DIR] [--name NAME]` | Validate then copy one bundle. By default the destination component is the validated manifest ID. `--name` replaces that component; the current parser does not validate it, so use only the exact validated manifest ID as a simple path component. An existing destination, even byte-identical, is refused. |
| `install --all` | Build and prevalidate the batch before changing the destination. `--replace-existing` and its alias `--force` permit replacement of a differing destination; `-r` and `--remove-existing` reinstall selected existing bundles after the entire batch stages successfully. `--dir`, `--skip-tests`, `-st`, and JDBC driver/digest pairs have the build meanings above. |
| `remove ID [--dir DIR]` | Remove the installed directory for the manifest ID. The command is idempotent. It does not edit the enabled allowlist; startup later refuses an enabled ID that is absent. |
| `bundle-dir EXTENSION` | Print the exact output directory that `build` uses, without building or parsing build output. It accepts the same short ID, module directory name, or module path as `build`. |

`install --all` leaves byte-identical destinations `UNCHANGED` by default and rejects the whole
batch before installation when a differing destination exists. Use replacement flags only after
reviewing the staged lot. An interrupted or refused batch can be retried after fixing the named
prerequisite; the prevalidation step prevents a partially installed batch for validation or
collision errors. See the [bundle lifecycle](../operator-guide/plugin-bundles.md) for activation,
image assembly, verification, update, and removal.

## `service.sh`

Purpose: operate the repository Compose service or the Helm release. The default command is
`restart`. Docker is required only for Compose commands; Helm and cluster access are required only
for Kubernetes commands.

| Command | Alias | Effect |
|---|---|---|
| `start` | `up`, `restart` | Build as selected, recreate the Compose service on `127.0.0.1`, and wait for health. |
| `stop` | `down` | Stop and remove the Compose service. |
| `status` | `ps` | Show Compose state. |
| `logs` | — | Follow Compose logs until interrupted. |
| `deploy` | `upgrade` | Run atomic `helm upgrade --install` and wait for readiness. |
| `undeploy` | — | Uninstall the selected Helm release and wait for deletion. |
| `k8s-status` | — | Show the selected Helm release status. |

Build-selection options apply to `start`, `up`, and `restart`:

- `-si` or `--skipimage` reuses the existing image. It cannot see bundle changes made after that
  image was built.
- `-sb` or `--skipbuild` skips host source/UI/tests and rebuilds the image from available artifacts;
  isolated Docker build stages can still compile. It implies `--skiptest`.
- `-st` or `--skiptest` builds source, UI, bundles, and the image without tests.
- `-h` or `--help` prints help. `--skipimage` wins if build-selection flags are combined.

| Environment | Default or rule |
|---|---|
| `RAVENROOT_PLUGINS_DIR` | `ravenroot-plugins`; must remain a relative, non-`..` Docker build-context path |
| `RAVENROOT_COMPOSE_FILE` | `./compose.yaml` |
| `RAVENROOT_COMPOSE_OVERRIDE_FILE` | auto-detect `./.ravenroot-local/compose.override.yaml`; set empty to disable auto-detection |
| `RAVENROOT_COMPOSE_WAIT_TIMEOUT` | `60` seconds |
| `RAVENROOT_HOST_PORT` | `8080` |
| `RAVENROOT_HELM_RELEASE` | `ravenroot` |
| `RAVENROOT_HELM_NAMESPACE` | `default` |
| `RAVENROOT_HELM_TIMEOUT` | `5m` |
| `RAVENROOT_IMAGE_REPOSITORY` | `ravenroot` |
| `RAVENROOT_IMAGE_TAG` | `local` |
| `RAVENROOT_IMAGE_DIGEST` | no default; when set, takes precedence over the tag |

Helm `deploy` and `upgrade` also require `RAVENROOT_AUTH_ISSUER`, `RAVENROOT_AUTH_AUDIENCE`, and
`RAVENROOT_AUTH_JWKS_URI`. Compose start verifies the rendered service has exactly one TCP
publication bound to loopback; `jq` is required for that preflight. If health waiting fails, inspect
`./service.sh status` and `./service.sh logs`, repair the reported configuration or image, and rerun
`./service.sh restart` without `--skipimage` when image inputs changed.

## `dev.sh`

Purpose: prepare and verify a source development checkout. It is not a release installation tool.

| Command | Options and effect |
|---|---|
| `setup` | Default. Build the editor, install required reactor modules to the local Maven repository, build every checkout adapter, verify the development harness, and prepare the sandbox supervisor. `--with-tests` runs reactor and adapter suites too. `--skip-ui` keeps an already-built editor. |
| `check` | Report prepared and missing components without building. It may repair only the supervisor executable bit. |
| `bench` | Start the loopback development bench after `setup`, deriving the exact model-provider egress values without replacing operator-provided environment values. The bench and its model node are never release artifacts. |
| `verify-supervisor` | Verify only the sandbox supervisor: its executable bit, its capability probe, and that `compose.yaml` names the same container path in both the variable and the mount. Exit non-zero when any fails. It never repairs the executable bit, so continuous integration reports that fault rather than absorbing it. |
| `help` | Print the maintained help text. |

`setup` exits 0 when every component is ready, 1 after a build or final supervisor readiness failure,
and 2 when JDK 21–25, the `.nvmrc` Node runtime, or Maven is missing. Use `check` to distinguish a
missing prerequisite from an incomplete prior setup, then repeat `setup` after repair.

## Distribution build and local runner

`./ravenroot/scripts/build-release.sh` is the fixed release build entry point. It takes no options.
It requires Java, Maven, Node, and npm; derives the Maven project version; runs `npm ci`, the UI
tests and production UI build, then `mvn -B clean verify`. Success writes the runnable artifact to
`ravenroot/ravenroot-distribution/target/ravenroot.jar`. A missing tool or any failed build step
stops the script with a nonzero status. Repair the first reported failure and rerun the whole build;
the script does not certify a partial result.

`./ravenroot/scripts/server.sh COMMAND` manages that jar in a checkout. `start` builds a missing jar,
starts it in the background, writes a PID and log under `RAVENROOT_RUN_DIR` (default
`ravenroot/target/server`), and waits up to ten seconds for `/health`. `foreground` builds when
needed and replaces the shell with Java. `stop`, `status`, and `restart` manage only a live process
whose command line identifies `ravenroot.jar`; aliases are listed by `help`. `status` returns 3 when
stopped, 1 when the process is running but unhealthy, and 0 when healthy or when `curl` is absent
and only process state can be checked. Configure the Java command, port, and log with
`RAVENROOT_JAVA`, `RAVENROOT_PORT`, and `RAVENROOT_LOG_FILE`. The helper defaults
`RAVENROOT_AUTH_MODE` to `disabled` and `RAVENROOT_BIND_ADDRESS` to `127.0.0.1`; authenticated use
requires `RAVENROOT_AUTH_LOCAL_TOKEN` or the OIDC identity settings described in
[Configuration](configuration.md). Browser and proxy deployments also use
`RAVENROOT_BROWSER_ALLOWED_ORIGINS`, `RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS`,
`RAVENROOT_UI_CONNECT_ORIGINS`, `RAVENROOT_TRUSTED_TLS_TERMINATOR`, and
`RAVENROOT_PUBLIC_ORIGIN`. Program execution requires `RAVENROOT_ALLOWED_TOOLS=program.execute`;
maker-checker artifact approval is separately enabled with `RAVENROOT_ARTIFACT_DUAL_CONTROL=true`
and defaults to false.

`./ravenroot/scripts/run-local.sh` is a fixed foreground alias. It takes no options of its own and
executes `server.sh foreground`, so it builds a missing jar, inherits the same environment, logs to
the terminal, and ends when the Java process ends. Use the managed `server.sh start` path when PID,
background-log, status, or stop operations are required.

## Application CLI

Global synopsis:

```text
ravenroot [--server URL --token-file PATH] COMMAND
```

Without `--server`, commands use the embedded composition. With `--server`, the CLI uses the remote
HTTP backend and requires a bearer token from `RAVENROOT_TOKEN` or `--token-file`. The rejected
`--token` option does not exist because command-line values are visible in shell history and process
listings. `--help` and `help` print usage.

| Command | Arguments and result |
|---|---|
| `status` | Service state, selected execution engine, and capabilities. |
| `runtime` | Active executions and active arrivals by node. |
| `node-types` | Effective running catalog; use it to verify enabled bundles. |
| `inspect FILE` | Read and inspect local GraphML without executing it. |
| `validate FILE` | Validate the local GraphML profile; runs locally even with `--server`. |
| `events decode` | Decode a captured execution SSE body from standard input into JSON lines; runs locally without a backend or credentials. |
| `run FILE [PAYLOAD]` | Submit Run and print process, traversal, execution, graph-version, and execution-policy identifiers. |
| `result EXECUTION-ID` | Read live or durable terminal evidence and qualified failure/cancellation fields. |
| `live` | List process-local non-terminal executions. |
| `inventory` | Read the entire durable tenant inventory, including terminal rows, following every page internally. |
| `traversals PROCESS-INSTANCE-ID` | List durable traversals for one process instance. This is not an execution/traversal ID. |
| `cancel TRAVERSAL-ID` | Request cancellation and print the recorded outcome and note. |
| `drain` | Stop new admission and begin controlled drain. |
| `deployments list` | List process-local deployment registrations. |
| `deployments register ID FILE` | Reserve an ID and validate its graph; does not start it. |
| `deployments inspect ID` | Read one registration. |
| `deployments start ID` | Start a registered deployment and wait for `READY` or truthful `FAILED`. |
| `deployments stop ID` | Stop it but keep it registered. |
| `deployments restart ID` | Stop and start the registration. |
| `deployments undeploy ID` | Stop and remove the process-local registration. |
| `credentials list` | Remote-only metadata listing; secret values are never returned. |
| `credentials add` | Remote-only creation with `--label TEXT --scheme api-key|basic|oauth-token [--username TEXT] --value-file PATH`. `basic` requires a username; other schemes reject one. `--value-file -` reads standard input. The rejected `--value` option never carries a secret. |
| `backup DIRECTORY` | Create an offline recovery bundle from configured durable stores. |
| `verify DIRECTORY` | Verify a bundle using only its contents. |
| `restore DIRECTORY` | Restore configured durable stores from a verified bundle. |

`ravenroot events decode < capture.sse` reads local standard input, starts no engine, contacts no
server and resolves no credentials; global `--server` and `--token-file` options are ignored by this
command. Each output line is a JSON object with `event`, `id` and `data`; exact decimal event cursor IDs
remain strings. Complete input returns 0, malformed or incomplete input and I/O failures return 1,
argument misuse returns 2, and `stream-truncated` or `stream-overrun` is emitted before returning 3.
Diagnostics go to standard error without raw input. See [Decoding execution streams](../integrator-guide/application-http.md)
for framing limits, legacy compatibility and control-frame semantics.

`embed-registration show`, `embed-registration provision`, and `embed-registration revoke` operate a
local registration store and never use `--server`. All take `--store-dir`, `--tenant`, and
`--registration-id`. Provision and revoke also require `--audit-dir` and the compare-and-set
`--expected-revision`; there is no force option. Provision additionally takes `--graphml`,
`--graph-id`, `--graph-version-id`, `--snapshot-state`, `--issuer`, `--subject`, `--parent-origin`,
`--resource-id`, `--deployment-id`, `--deployment-version`, `--policy-revision`, and every explicit
attestation: `--gate-deployment`, `--gate-provenance`, `--gate-classification`, `--gate-retention`,
`--gate-dsr-suppression`, `--gate-takedown`, and `--gate-eea`. Optional `--theme` is `dark` or
`light`; optional `--operator` supplies the audit subject. Read the current revision with `show`
before a mutation. See [Embed and extension contracts](embed-extension-contracts.md) for the exact
registration contract.

Application CLI help, an unknown command, and command parsers that classify argument misuse return 2.
Two current parser paths instead return 1: a missing value for a global option is thrown before the
CLI dispatch catch, and malformed credential-add list syntax is handled as a runtime failure. An
accepted command returns 0, and a refused or failed operation returns 1 unless a command's reference
specifies a classified HTTP result. Exact output fields,
payload limits, remote-query constraints, retention behavior, and transport mappings are in
[HTTP API and CLI](api-cli.md); backup files and recovery steps are in
[Backup and recovery bundle](backup-recovery.md).
