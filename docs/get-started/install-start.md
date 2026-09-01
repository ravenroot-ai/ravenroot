# Install and start Ravenroot

Use this procedure to start a local standalone service on loopback and prove that the process, runtime, and node catalog are available.

## Prerequisites

A source build requires JDK 21, Maven, Node.js 20.19 or later, npm, and `unzip`. Docker and Docker Compose provide the container path. Keep the first launch on `127.0.0.1`; a non-loopback bind requires explicit authentication.

## Build and extract the distribution

Run this block from the root of the Ravenroot source checkout. It builds the release archive and
extracts it into one installation directory, `ravenroot-quickstart/ravenroot`:

```bash
./ravenroot/scripts/build-release.sh
mkdir -p ravenroot-quickstart
unzip -q -o ravenroot/ravenroot-distribution/target/ravenroot-bin.zip -d ravenroot-quickstart
cd ravenroot-quickstart/ravenroot
```

All remaining standalone commands in Get Started run from this extracted
`ravenroot-quickstart/ravenroot` directory. Start the packaged server in this terminal:

```bash
bin/ravenroot-server
```

The server hosts the UI and API at `http://127.0.0.1:8080`. Leave it running while completing the
next pages. The sibling `bin/ravenroot` executable is the client CLI.

## Verify the service contract

Open a second terminal at the source-checkout root, enter the same installation directory, and run
all four probes before opening the workspace:

```bash
cd ravenroot-quickstart/ravenroot
curl --fail http://127.0.0.1:8080/health
curl --fail http://127.0.0.1:8080/ready
curl --fail http://127.0.0.1:8080/v1/runtime
curl --fail http://127.0.0.1:8080/v1/node-types
```

Liveness returns `{"status":"UP"}`. Readiness admits traffic only after required runtime dependencies are usable. Runtime identifies the selected engine; node discovery returns the palette contract used by the UI.

## Authentication boundary

Authentication may be disabled only on loopback, where Ravenroot emits a warning. A non-loopback listener refuses startup unless authentication is configured. Use a local token of at least 32 characters for controlled local access, or OIDC for an externally reachable deployment. Never copy a token into GraphML.

If a probe fails, follow [Startup and readiness](../troubleshooting/startup-readiness.md). Exact defaults are listed in [Configuration](../reference/configuration.md).
