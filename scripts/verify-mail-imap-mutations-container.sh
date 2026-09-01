#!/usr/bin/env sh
# Exercise the real installed mail bundle inside a fresh JDK 21/Maven container.
# The container loads the validated bundle through PluginBundleLoader (not the reactor's package),
# talks IMAPS to its deterministic fixture, and reopens INBOX/Archive/Trash to observe both effects.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-mail-mutation-container.XXXXXX")
PLUGINS_DIR="$WORKDIR/plugins"
M2_DIR="$WORKDIR/m2"

cleanup() {
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

if ! command -v docker >/dev/null 2>&1; then
  echo "Required command not found: docker" >&2
  exit 1
fi

mkdir -p "$PLUGINS_DIR" "$M2_DIR"
"$PROJECT_DIR/plugin.sh" build mail --skip-tests
BUNDLE_DIR=$("$PROJECT_DIR/plugin.sh" bundle-dir mail)
"$PROJECT_DIR/plugin.sh" install "$BUNDLE_DIR" --dir "$PLUGINS_DIR"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --volume "$PROJECT_DIR:/workspace" \
  --volume "$PLUGINS_DIR:/plugins:ro" \
  --volume "$M2_DIR:/tmp/.m2" \
  --workdir /workspace/ravenroot \
  --env HOME=/tmp \
  --env RAVENROOT_IMAP_PROFILE_74656E616E74_726561646572='localhost;3993;IMAPS;reader;credential;INBOX,Archive,Trash;5000;5000;2;10;128' \
  --env RAVENROOT_IMAP_MUTATION_POLICY_74656E616E74_726561646572='MOVE,TRASH,HARD_DELETE;Archive,Trash;Trash' \
  --env RAVENROOT_MAIL_CREDENTIAL_63726564656E7469616C='secret' \
  --entrypoint mvn \
  maven:3.9.11-eclipse-temurin-21 \
  --batch-mode --no-transfer-progress \
    -pl ravenroot-extensions/ravenroot-mail -am \
    -Dtest=InstalledMailBundleMutationContainerTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dravenroot.mail.installedBundleRoot=/plugins test

echo "PASSED: installed mail bundle queried, moved, trashed, and observed both effects after reopen."
