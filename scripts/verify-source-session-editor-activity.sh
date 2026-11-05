#!/usr/bin/env sh
# Prove that a graph with a LISTENING inbound source actually lights up in the editor.
#
# The defect this guards against was invisible to every layer's own tests. Each layer was defensible
# on its own -- the event carried a deployment id, the session had a lifecycle, the router matched an
# execution id, the projection tracked a traversal -- and the whole was blind, because the identity
# that joins a long-lived session to its unbounded series of traversals was never carried end to end.
# Only a check that spans all of them at once can fail when that join breaks again.
#
# What it does:
#   1. Builds the editor (`npm run build`) and the Java reactor it is served by.
#   2. Runs SourceSessionEditorActivityBrowserTest, which starts a REAL Ravenroot server that serves
#      that built editor and hosts a REAL inbound source admitting one message every 700 ms.
#   3. That test drives a REAL Chromium through `playwright.source-session-activity.config.js`: open
#      the graph, press Run, and watch for over twenty seconds of admitted traffic. It asserts the
#      original pinned topology shows one selected actual traversal, that twenty distinct SOURCE
#      traversals remain attributed through focus/draft changes, and that log output reaches the
#      activity panel. The mutable draft is never painted with deployment traffic.
#
# The window is deliberately long. A seven-second sample can fall entirely between admissions and
# show nothing at all, which reads as a broken stream and sends the reader after a transport fault
# that does not exist; that is how the original investigation went wrong, so the check does not
# repeat it.
#
# Requires: JDK 21 through 25, Maven, Node.js 24, and an installed Playwright Chromium
# (`npx playwright install chromium` once, the same requirement as `npm run test:e2e`). No Docker and
# no broker: the source is a first-party test source, so what is proved is the editor's attribution
# of real runtime events rather than any one adapter's transport.
#
# Usage: ./scripts/verify-source-session-editor-activity.sh
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
REACTOR_DIR="$PROJECT_DIR/ravenroot"
UI_DIR="$REACTOR_DIR/ravenroot-ui"

echo "Building the editor..." >&2
(cd "$UI_DIR" && npm run build >/dev/null)

echo "Running the real server, source and browser..." >&2
mvn -B -f "$REACTOR_DIR/pom.xml" \
  -pl ravenroot-server -am \
  -Dravenroot.sourceSessionEditor.browserTest=true \
  -Dtest=SourceSessionEditorActivityBrowserTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

echo "PASSED: real SOURCE traffic retained origin ownership and exact pinned traversal history,"
echo "preserved draft isolation, showed log output, and stopped the captured deployment."
