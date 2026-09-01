#!/usr/bin/env sh
# Run the installed ravenroot-mail bundle, GreenMail IMAPS server, durable deployment,
# and real Pekko actor traversal inside one JDK 21 container. This is intentionally separate from
# the palette and image-catalog checks: catalog presence cannot prove source lifecycle or receipt.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-mail-consumer-container.XXXXXX")
PLUGINS_DIR="$WORKDIR/plugins"
MAVEN_REPOSITORY=${RAVENROOT_VERIFY_MAVEN_REPOSITORY:-"${HOME}/.m2"}
IMAGE=${RAVENROOT_VERIFY_MAVEN_IMAGE:-maven:3.9.11-eclipse-temurin-21}

cleanup() {
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

if ! command -v docker >/dev/null 2>&1; then
  echo "Required command not found: docker" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is unavailable" >&2
  exit 1
fi

echo "Building and installing the mail bundle without a reactor install..." >&2
"$PROJECT_DIR/plugin.sh" build mail --skip-tests
MAIL_BUNDLE_DIR=$("$PROJECT_DIR/plugin.sh" bundle-dir mail)
"$PROJECT_DIR/plugin.sh" install "$MAIL_BUNDLE_DIR" --dir "$PLUGINS_DIR"

echo "Running installed-bundle IMAPS -> DefaultGraphDeployment -> Pekko actor lifecycle in $IMAGE..." >&2
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --workdir /workspace \
  --volume "$PROJECT_DIR:/workspace" \
  --volume "$PLUGINS_DIR:/installed-plugins:ro" \
  --volume "$MAVEN_REPOSITORY:/var/maven/.m2" \
  --env MAVEN_CONFIG=/var/maven/.m2 \
  --env RAVENROOT_IMAP_PROFILE_74656E616E74_726561646572='localhost;31443;IMAPS;reader;credential;INBOX;10000;10000;1;20;256' \
  --env RAVENROOT_IMAP_CONSUMER_74656E616E74_726561646572='INBOX;100;4;32;100;1000;3;65536;metadata;0' \
  --env RAVENROOT_MAIL_CREDENTIAL_63726564656E7469616C=secret \
  "$IMAGE" mvn -B -f ravenroot/pom.xml \
    -Dmaven.repo.local=/var/maven/.m2/repository \
    -pl ravenroot-extensions/ravenroot-mail -am \
    -Dtest=InstalledMailImapConsumeContainerTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dravenroot.mail.installedBundleRoot=/installed-plugins \
    -Dravenroot.mail.imapFixturePort=31443 \
    test

echo "PASSED: installed mail bundle reached READY, traversed through Pekko, stopped cleanly, reopened its durable checkpoint without duplication, and traversed the next UID."
