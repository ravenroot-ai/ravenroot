#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
STAGE=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-extensions-all.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT

REPOSITORY="$STAGE/repository"
CONSUMER="$STAGE/consumer"
VERSION=$(python3 "$ROOT/scripts/check_product_version.py" --print-version)
cp -R "$ROOT/scripts/fixtures/extensions-all-consumer" "$CONSUMER"

mvn -B --no-transfer-progress -f "$ROOT/ravenroot/pom.xml" \
  -Dmaven.repo.local="$REPOSITORY" -DskipTests \
  -pl :ravenroot-extensions-all -am install

mvn -B --no-transfer-progress -f "$CONSUMER/pom.xml" \
  -Dmaven.repo.local="$REPOSITORY" -Dravenroot.version="$VERSION" package
mvn -B --no-transfer-progress -f "$CONSUMER/pom.xml" \
  -Dmaven.repo.local="$REPOSITORY" -Dravenroot.version="$VERSION" \
  org.apache.maven.plugins:maven-dependency-plugin:3.9.0:tree \
  -DoutputFile="$STAGE/dependency-tree.txt"
python3 "$ROOT/scripts/check_extension_pack.py" \
  --dependency-tree "$STAGE/dependency-tree.txt"

mvn -B --no-transfer-progress -f "$CONSUMER/pom.xml" \
  -Dmaven.repo.local="$REPOSITORY" -Dravenroot.version="$VERSION" \
  org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath \
  -Dmdep.outputFile="$STAGE/classpath.txt"
java -cp "$CONSUMER/target/classes:$(<"$STAGE/classpath.txt")" \
  dev.ravenroot.fixture.ExtensionPackProbe

echo "The clean staged consumer resolved all first-party extensions without ambient activation."
