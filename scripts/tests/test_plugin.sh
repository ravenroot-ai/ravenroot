#!/usr/bin/env sh
# Tests for plugin.sh, following test_dev.sh and test_service.sh: PATH stubs, no real build, no network.
#
# Covers a target-resolution defect: `./plugin.sh build --all` resolves targets with
# `known_extensions()`, which
# discovers modules from the tree; `ravenroot-jdbc` always has a `NodePackage` and therefore always
# appears in the list; and the JDBC guard ran BEFORE the build loop, ending the entire command with
# exit 2 before a single extension was built -- zero bundles produced, no announcement.
#
# The fix skips JDBC ONLY when --all discovered the target (never when the user explicitly named it),
# and declares that: it announces the skip on stderr, naming the extension and reason instead of
# silently omitting it. It preserves every other branch of the same guard: an explicit JDBC build with
# no driver must still fail with exit 2, and --all with --driver-jar/--driver-sha256 must still build JDBC.
#
# A second failure mode was that "never when explicitly named" did not hold: `./plugin.sh
# build jdbc --all` silently replaced the explicit target with the --all list, so JDBC reached the guard
# as if --all had discovered it and a build that previously failed with exit 2 stopped failing. The fix
# rejects the combination itself before examining JDBC: --all with an explicit id exits 2 with a dedicated
# message, in both argument orders and even with driver options supplied.
#
# The project under test is a synthetic fixture, not the real ravenroot/ravenroot-extensions tree:
# plugin.sh derives PROJECT_DIR from $0, so a symbolic link to the real script inside a fake directory
# with three fake extensions (alpha, beta, JDBC) exercises real code without writing in the real reactor
# tree or requiring a real Maven build. A second fixture with only JDBC covers the edge case in which
# --all finds nothing else to build without a driver.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

MOCK_BIN="$TEMP_DIR/bin"
MOCK_LOG="$TEMP_DIR/calls.log"
FIXTURE="$TEMP_DIR/proj"
mkdir -p "$MOCK_BIN" "$FIXTURE"

failures=0
check() {
  description=$1
  shift
  if "$@"; then
    printf 'ok   %s\n' "$description"
  else
    printf 'FAIL %s\n' "$description" >&2
    failures=$((failures + 1))
  fi
}
contains() { printf '%s' "$1" | grep -q -- "$2"; }
not_contains() { ! printf '%s' "$1" | grep -q -- "$2"; }

# ---- fixture: fake reactors, not the real ravenroot/ravenroot-extensions tree --------------------
#
# make_extension takes the fixture directory as its first argument so the same function serves both
# FIXTURE (three extensions: alpha, beta, JDBC) and ONLY_JDBC_FIXTURE (only JDBC, for section 7's edge case).

make_extension() {
  fixture=$1
  id=$2
  dir="$fixture/ravenroot/ravenroot-extensions/ravenroot-$id"
  pkg_dir="$dir/src/main/java/ai/ravenroot/extensions/$id"
  mkdir -p "$pkg_dir"
  : >"$dir/pom.xml"
  cap=$(printf '%s' "$id" | sed 's/^\(.\)/\U\1/')
  cat >"$pkg_dir/${cap}NodePackage.java" <<EOF
package ai.ravenroot.extensions.$id;

public final class ${cap}NodePackage {
}
EOF
}

setup_fixture_skeleton() {
  fixture=$1
  ln -s "$PROJECT_DIR/plugin.sh" "$fixture/plugin.sh"
  mkdir -p \
    "$fixture/ravenroot/ravenroot-plugin-bundle/target/classes/ai/ravenroot/plugin/bundle" \
    "$fixture/ravenroot/ravenroot-application-api/target/classes/ai/ravenroot/api/node"
  : >"$fixture/ravenroot/ravenroot-plugin-bundle/target/classes/ai/ravenroot/plugin/bundle/PluginCli.class"
  : >"$fixture/ravenroot/ravenroot-application-api/target/classes/ai/ravenroot/api/node/NodePackage.class"
}

setup_fixture_skeleton "$FIXTURE"
make_extension "$FIXTURE" alpha
make_extension "$FIXTURE" beta
make_extension "$FIXTURE" jdbc
# AI is a second extension that --all must not build, for a reason distinct from JDBC: the AI
# node bundle is never shipped with the product and must not be included in an image through a batch
# command. The fixture includes it in the same way as JDBC because the rule lives in plugin.sh, not
# in the real tree.
make_extension "$FIXTURE" ai

ONLY_JDBC_FIXTURE="$TEMP_DIR/proj-only-jdbc"
mkdir -p "$ONLY_JDBC_FIXTURE"
setup_fixture_skeleton "$ONLY_JDBC_FIXTURE"
make_extension "$ONLY_JDBC_FIXTURE" jdbc

# Fake mvn: records every call and, only for the "package" goal, creates the JAR build_one seeks below
# target/ of every module passed by -pl. Maven's reactor selector may contain a comma-separated batch,
# which is the contract exercised by build --all.
cat >"$MOCK_BIN/mvn" <<'EOF'
#!/usr/bin/env sh
printf 'mvn %s\n' "$*" >>"$MOCK_LOG"
module=""
goal=""
prev=""
for arg in "$@"; do
  if [ "$prev" = "-pl" ]; then
    module=$arg
  fi
  prev=$arg
  case "$arg" in
    package) goal=package ;;
  esac
done
if [ -n "${MOCK_BUILD_FAIL:-}" ] && printf '%s' "$module" | grep -q "ravenroot-$MOCK_BUILD_FAIL"; then
  echo "synthetic build failure for $MOCK_BUILD_FAIL" >&2
  exit 1
fi
if [ "$goal" = "package" ] && [ -n "$module" ]; then
  old_ifs=$IFS
  IFS=,
  for selected_module in $module; do
    mkdir -p "$selected_module/target"
    : >"$selected_module/target/built.jar"
  done
  IFS=$old_ifs
fi
exit 0
EOF
chmod +x "$MOCK_BIN/mvn"

cat >"$MOCK_BIN/java" <<'EOF'
#!/usr/bin/env sh
printf 'java %s\n' "$*" >>"$MOCK_LOG"

operation=""
while [ $# -gt 0 ]; do
  if [ "$1" = "ai.ravenroot.plugin.bundle.PluginCli" ]; then
    shift
    operation=${1:-}
    [ $# -eq 0 ] || shift
    break
  fi
  shift
done

case "$operation" in
  generate-manifest)
    output_dir=$1
    module_dir=$(dirname "$(dirname "$output_dir")")
    short_id=$(basename "$module_dir" | sed 's/^ravenroot-//')
    manifest_id="ai.ravenroot.extensions.$short_id"
    if [ "$short_id" = "alpha" ] && [ -n "${MOCK_ALPHA_MANIFEST_ID:-}" ]; then
      manifest_id=$MOCK_ALPHA_MANIFEST_ID
    fi
    if [ "$short_id" = "beta" ] && [ -n "${MOCK_BETA_MANIFEST_ID:-}" ]; then
      manifest_id=$MOCK_BETA_MANIFEST_ID
    fi
    if [ "${MOCK_COLLIDE_IDS:-}" = "1" ] && [ "$short_id" = "beta" ]; then
      manifest_id="ai.ravenroot.extensions.alpha"
    fi
    mkdir -p "$output_dir"
    printf 'id=%s\n' "$manifest_id" >"$output_dir/ravenroot-plugin.json"
    printf 'stable bundle bytes for %s\n' "$short_id" >"$output_dir/$short_id.jar"
    if [ "${MOCK_INVALID_EXTENSION:-}" = "$short_id" ]; then
      : >"$output_dir/.invalid-candidate"
    fi
    ;;
  validate)
    source_dir=$1
    if [ ! -f "$source_dir/ravenroot-plugin.json" ] || [ -e "$source_dir/.invalid-candidate" ]; then
      echo "synthetic invalid bundle: $source_dir" >&2
      exit 1
    fi
    echo "VALID: $source_dir"
    ;;
  manifest-id)
    case "$1" in
      *ravenroot-alpha*) manifest_id=${MOCK_ALPHA_MANIFEST_ID:-ai.ravenroot.extensions.alpha} ;;
      *ravenroot-beta*) manifest_id=${MOCK_BETA_MANIFEST_ID:-ai.ravenroot.extensions.beta} ;;
      *) sed -n 's/^id=//p' "$1/ravenroot-plugin.json"; exit $? ;;
    esac
    if [ "${MOCK_COLLIDE_IDS:-}" = "1" ] && [ "$manifest_id" = "ai.ravenroot.extensions.beta" ]; then
      manifest_id="ai.ravenroot.extensions.alpha"
    fi
    printf '%s\n' "$manifest_id"
    ;;
  verify-sha256)
    driver_file=$1
    checksum=$2
    if [ ! -f "$driver_file" ] || [ -L "$driver_file" ] \
        || [ "${MOCK_VERIFY_FAIL:-}" = "1" ] \
        || [ "${#checksum}" -ne 64 ] \
        || printf '%s' "$checksum" | grep -q '[^0-9a-f]'; then
      echo "synthetic invalid JDBC checksum" >&2
      exit 1
    fi
    ;;
esac
EOF
chmod +x "$MOCK_BIN/java"

require_call() {
  if ! grep -F -- "$1" "$MOCK_LOG" >/dev/null; then
    echo "Expected call not found: $1" >&2
    cat "$MOCK_LOG" >&2
    return 1
  fi
}

reject_call() {
  if grep -F -- "$1" "$MOCK_LOG" >/dev/null; then
    echo "Unexpected call found: $1" >&2
    cat "$MOCK_LOG" >&2
    return 1
  fi
}

package_call_count() {
  grep -c ' package' "$MOCK_LOG" || true
}

DRIVER_JAR="$TEMP_DIR/fake-driver.jar"
: >"$DRIVER_JAR"
DRIVER_SHA256="0000000000000000000000000000000000000000000000000000000000000000"
SECOND_DRIVER_JAR="$TEMP_DIR/second-driver.jar"
: >"$SECOND_DRIVER_JAR"
SECOND_DRIVER_SHA256="1111111111111111111111111111111111111111111111111111111111111111"

MOCK_BUILD_FAIL=""
MOCK_INVALID_EXTENSION=""
MOCK_COLLIDE_IDS=""
MOCK_VERIFY_FAIL=""
MOCK_ALPHA_MANIFEST_ID=""
MOCK_BETA_MANIFEST_ID=""

run_plugin_in() {
  fixture=$1
  shift
  : >"$MOCK_LOG"
  set +e
  ( cd "$fixture" && PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
      MOCK_BUILD_FAIL="$MOCK_BUILD_FAIL" MOCK_INVALID_EXTENSION="$MOCK_INVALID_EXTENSION" \
      MOCK_COLLIDE_IDS="$MOCK_COLLIDE_IDS" MOCK_VERIFY_FAIL="$MOCK_VERIFY_FAIL" \
      MOCK_ALPHA_MANIFEST_ID="$MOCK_ALPHA_MANIFEST_ID" MOCK_BETA_MANIFEST_ID="$MOCK_BETA_MANIFEST_ID" \
      ./plugin.sh "$@" >"$TEMP_DIR/out" 2>"$TEMP_DIR/err" )
  LAST_STATUS=$?
  set -e
  LAST_OUT=$(cat "$TEMP_DIR/out")
  LAST_ERR=$(cat "$TEMP_DIR/err")
}

run_plugin() {
  run_plugin_in "$FIXTURE" "$@"
}

# ---- 1. --all without a driver: skip and ANNOUNCE JDBC; build the rest; exit 0 --------------------

run_plugin build --all
check "'build --all' without a driver exits 0 (not 2: leaving other extensions unbuilt is a defect)" \
  test "$LAST_STATUS" -eq 0
check "announces the skipped JDBC target on stderr" \
  contains "$LAST_ERR" "[Ss]kip.*jdbc"
check "the announcement names the reason (missing driver)" \
  contains "$LAST_ERR" "driver"
check "alpha is still built" \
  require_call 'ravenroot-extensions/ravenroot-alpha'
check "beta is still built" \
  require_call 'ravenroot-extensions/ravenroot-beta'
check "build --all packages the discovered extensions in one Maven reactor invocation" \
  test "$(package_call_count)" -eq 1
check "JDBC is NOT built (no mvn/java call names it)" \
  reject_call 'ravenroot-extensions/ravenroot-jdbc'
check "build_one does not print 'Building jdbc' for a skipped target" \
  not_contains "$LAST_ERR" "Building jdbc"

# ---- 1b. --all always skips and announces AI -------------------------------------------------------
#
# Unlike JDBC, this skip does not depend on missing input: it is an unconditional release rule and
# also applies when --all receives driver options. The test verifies both runs because it is exactly
# the branch where a poorly written condition ("skip only when there are no drivers") would go unnoticed.

 check "announces that AI was skipped on stderr" \
  contains "$LAST_ERR" "[Ss]kip.*ai (discovered via --all)"
 check "the announcement names the reason (it is not included in an image through a batch)" \
  contains "$LAST_ERR" "never supplied with the product"
 check "AI is NOT built by --all" \
  reject_call 'ravenroot-extensions/ravenroot-ai'

# ---- 2. explicitly named JDBC without a driver: it must still fail, never be silenced ------------

run_plugin build jdbc
check "explicit 'build jdbc' without a driver still exits 2" \
  test "$LAST_STATUS" -eq 2
check "the existing message remains unchanged" \
  contains "$LAST_ERR" "Building jdbc requires --driver-jar and --driver-sha256"
check "no build is attempted for rejected explicit JDBC" \
  reject_call 'ravenroot-extensions/ravenroot-jdbc'

# ---- 3. --all WITH a driver: JDBC is built; no skip is announced ---------------------------------

run_plugin build --all --driver-jar "$DRIVER_JAR" --driver-sha256 "$DRIVER_SHA256"
check "'build --all' with a driver exits 0" \
  test "$LAST_STATUS" -eq 0
check "JDBC is built when the driver is supplied" \
  require_call 'ravenroot-extensions/ravenroot-jdbc'
check "alpha is built" \
  require_call 'ravenroot-extensions/ravenroot-alpha'
check "beta is built" \
  require_call 'ravenroot-extensions/ravenroot-beta'
check "no skip is announced when JDBC is actually built" \
  not_contains "$LAST_ERR" "Skipping jdbc"
 check "AI remains skipped when --all receives a driver" \
  reject_call 'ravenroot-extensions/ravenroot-ai'
 check "and the AI skip is still announced" \
  contains "$LAST_ERR" "[Ss]kip.*ai (discovered via --all)"

# ---- 3c. explicitly named AI is built: the skip belongs to --all, not the command -----------------

run_plugin build ai
 check "explicit 'build ai' exits 0" test "$LAST_STATUS" -eq 0
 check "explicit 'build ai' actually builds the module" \
  require_call 'ravenroot-extensions/ravenroot-ai'
 check "no skip is announced when AI was named" \
  not_contains "$LAST_ERR" "Skipping ai"

# ---- 3b. repeated JDBC pairs: stable order; duplicates and incomplete forms fail closed ----------

run_plugin build jdbc \
  --driver-jar "$DRIVER_JAR" --driver-sha256 "$DRIVER_SHA256" \
  --driver-jar "$SECOND_DRIVER_JAR" --driver-sha256 "$SECOND_DRIVER_SHA256"
check "two complete JDBC pairs are accepted" test "$LAST_STATUS" -eq 0
check "pinned dependencies reach the manifest in declared order" \
  require_call "--pinned-dependency $DRIVER_JAR $DRIVER_SHA256 --pinned-dependency $SECOND_DRIVER_JAR $SECOND_DRIVER_SHA256"

run_plugin build jdbc --driver-sha256 "$DRIVER_SHA256" --driver-jar "$DRIVER_JAR"
check "a digest before its JAR is rejected" test "$LAST_STATUS" -eq 2
check "incomplete order is rejected before Maven" reject_call 'mvn '

mkdir -p "$TEMP_DIR/duplicate-one" "$TEMP_DIR/duplicate-two"
cp "$DRIVER_JAR" "$TEMP_DIR/duplicate-one/duplicate-driver.jar"
cp "$SECOND_DRIVER_JAR" "$TEMP_DIR/duplicate-two/duplicate-driver.jar"
run_plugin build jdbc \
  --driver-jar "$TEMP_DIR/duplicate-one/duplicate-driver.jar" --driver-sha256 "$DRIVER_SHA256" \
  --driver-jar "$TEMP_DIR/duplicate-two/duplicate-driver.jar" --driver-sha256 "$SECOND_DRIVER_SHA256"
check "two drivers with the same filename/driverId are rejected" test "$LAST_STATUS" -eq 2
check "the duplicate is rejected before Maven" reject_call 'mvn '

SYMLINK_DRIVER="$TEMP_DIR/symlink-driver.jar"
ln -s "$DRIVER_JAR" "$SYMLINK_DRIVER"
run_plugin build jdbc --driver-jar "$SYMLINK_DRIVER" --driver-sha256 "$DRIVER_SHA256"
check "a symlink driver is rejected" test "$LAST_STATUS" -ne 0
check "the symlink is rejected before Maven" reject_call 'mvn '

# ---- 4. non-regression: the "exactly one jdbc build target" guard remains coherent --------------

run_plugin build jdbc jdbc --driver-jar "$DRIVER_JAR" --driver-sha256 "$DRIVER_SHA256"
check "two explicit JDBC targets with a driver remain rejected" \
  test "$LAST_STATUS" -eq 2
check "the guard message remains the existing one" \
  contains "$LAST_ERR" "JDBC driver options require exactly one jdbc build target"
check "no build is attempted when the guard rejects" \
  reject_call 'ravenroot-extensions/ravenroot-jdbc'

# ---- 5. non-regression: build one named extension, unchanged -------------------------------------

run_plugin build alpha
check "'build alpha' exits 0" \
  test "$LAST_STATUS" -eq 0
check "alpha is built" \
  require_call 'ravenroot-extensions/ravenroot-alpha'
check "beta is NOT built when unnamed" \
  reject_call 'ravenroot-extensions/ravenroot-beta'
check "a named extension still uses one Maven package invocation" \
  test "$(package_call_count)" -eq 1

# ---- 5b. -st is the short skip-tests form for both build and install --all ----------------------

run_plugin build --all -st
check "'build --all -st' exits 0" test "$LAST_STATUS" -eq 0
check "-st still uses one reactor package invocation" test "$(package_call_count)" -eq 1
check "-st passes Maven's skip-tests property" require_call '-DskipTests'

SHORT_SKIP_INSTALL_DIR="$TEMP_DIR/install-all-short-skip"
run_plugin install --all -st --dir "$SHORT_SKIP_INSTALL_DIR"
check "'install --all -st' exits 0" test "$LAST_STATUS" -eq 0
check "install --all -st performs one reactor package invocation" test "$(package_call_count)" -eq 1
check "install --all -st passes Maven's skip-tests property" require_call '-DskipTests'
check "install --all -st installs alpha" \
  test -f "$SHORT_SKIP_INSTALL_DIR/ai.ravenroot.extensions.alpha/ravenroot-plugin.json"

# ---- 6. non-regression: bundle-dir invokes neither mvn nor java ----------------------------------

: >"$MOCK_LOG"
bundle_dir_out=$(cd "$FIXTURE" && PATH="$MOCK_BIN:$PATH" ./plugin.sh bundle-dir alpha)
check "'bundle-dir alpha' prints the expected path" \
  test "$bundle_dir_out" = "$FIXTURE/ravenroot/ravenroot-extensions/ravenroot-alpha/target/plugin-bundle"
check "'bundle-dir' touches neither mvn nor java" \
  test ! -s "$MOCK_LOG"

# ---- 7. edge case: --all discovers ONLY JDBC, without a driver -----------------------------------
#
# In a reactor where the only discoverable extension is JDBC, --all has nothing it can build without a
# driver. It filters JDBC from the targets; the remaining set is empty; and the build loop iterates over
# no extension. It still exits 0: --all did not LIE about what it built -- it said so on stderr, naming
# JDBC and the reason -- and attempted no build that would fail. The undesired result would be the
# opposite: --all finds nothing buildable and says nothing, or worse exits nonzero for an already declared
# absence. This is not the target-resolution defect in disguise: that failure produced zero bundles
# AND zero announcement; here zero bundles are the only possible result (nothing else is in the
# reactor), but the
# announcement exists.

run_plugin_in "$ONLY_JDBC_FIXTURE" build --all
check "'--all' that discovers only JDBC without a driver still exits 0" \
  test "$LAST_STATUS" -eq 0
check "still announces skipped JDBC on stderr" \
  contains "$LAST_ERR" "[Ss]kip.*jdbc"
check "no build is attempted (there is nothing else to build)" \
  test ! -s "$MOCK_LOG"

# ---- 8. --all and an explicit id are mutually exclusive ------------------------------------------
#
# `./plugin.sh build jdbc --all` made "jdbc" disappear without saying so
# (`targets=$(known_extensions)` silently overwrote collected explicit targets); the JDBC guard saw JDBC
# arrive ONLY from --all and skipped it with "discovered via --all" -- false, JDBC had been named by hand.
# An explicit `build jdbc` that previously failed with exit 2 (missing driver) thus stopped failing as
# soon as --all appeared on the same command line: the same target-overwrite failure through another
# door. The fix rejects the combination itself before looking at JDBC: --all with
# an explicit id exits 2 with a dedicated message, in either argument order and even when the caller
# supplies driver options -- no build must ever start.

run_plugin build jdbc --all
check "'build jdbc --all' is rejected, not silently reduced to --all" \
  test "$LAST_STATUS" -eq 2
check "the dedicated message names mutual exclusion" \
  contains "$LAST_ERR" "mutually exclusive"
check "no build is attempted (neither JDBC nor anything else)" \
  test ! -s "$MOCK_LOG"

run_plugin build --all jdbc
check "'build --all jdbc' is rejected in the same way, reversed order" \
  test "$LAST_STATUS" -eq 2
check "same dedicated message with reversed arguments" \
  contains "$LAST_ERR" "mutually exclusive"
check "no build is attempted with reversed arguments" \
  test ! -s "$MOCK_LOG"

run_plugin build --all jdbc --driver-jar "$DRIVER_JAR" --driver-sha256 "$DRIVER_SHA256"
check "mutual exclusion wins even when the driver is supplied" \
  test "$LAST_STATUS" -eq 2
check "no build is attempted even with the driver supplied" \
  test ! -s "$MOCK_LOG"

# ---- 9. install --all: build, prevalidate the whole batch, install, and summarize ----------------

INSTALL_DIR="$TEMP_DIR/install-all"
run_plugin install --all --skip-tests --dir "$INSTALL_DIR"
check "'install --all' on an empty directory exits 0" test "$LAST_STATUS" -eq 0
check "install --all uses the build --all path for alpha" \
  require_call 'ravenroot-extensions/ravenroot-alpha'
check "install --all uses the build --all path for beta" \
  require_call 'ravenroot-extensions/ravenroot-beta'
check "alpha is installed below the manifest id" \
  test -f "$INSTALL_DIR/ai.ravenroot.extensions.alpha/ravenroot-plugin.json"
check "beta is installed below the manifest id" \
  test -f "$INSTALL_DIR/ai.ravenroot.extensions.beta/ravenroot-plugin.json"
check "JDBC without a driver remains absent" \
  test ! -e "$INSTALL_DIR/ai.ravenroot.extensions.jdbc"
check "the summary declares alpha INSTALLED" \
  contains "$LAST_OUT" "alpha.*INSTALLED"
check "the summary declares beta INSTALLED" \
  contains "$LAST_OUT" "beta.*INSTALLED"
check "the summary declares JDBC SKIPPED" \
  contains "$LAST_OUT" "jdbc.*SKIPPED"

# ---- 10. identical second pass and partially installed checkout ----------------------------------

run_plugin install --all --skip-tests --dir "$INSTALL_DIR"
check "a byte-identical second install --all exits 0" test "$LAST_STATUS" -eq 0
check "byte-identical alpha is UNCHANGED" contains "$LAST_OUT" "alpha.*UNCHANGED"
check "byte-identical beta is UNCHANGED" contains "$LAST_OUT" "beta.*UNCHANGED"

PARTIAL_DIR="$TEMP_DIR/install-partial"
mkdir -p "$PARTIAL_DIR"
cp -R "$FIXTURE/ravenroot/ravenroot-extensions/ravenroot-alpha/target/plugin-bundle" \
  "$PARTIAL_DIR/ai.ravenroot.extensions.alpha"
run_plugin install --all --skip-tests --dir "$PARTIAL_DIR"
check "a partially installed checkout exits 0" test "$LAST_STATUS" -eq 0
check "the existing identical bundle remains UNCHANGED" contains "$LAST_OUT" "alpha.*UNCHANGED"
check "the missing bundle becomes INSTALLED" contains "$LAST_OUT" "beta.*INSTALLED"

# ---- 11. conflict, invalid candidate, and collision: zero destination mutations ------------------

CONFLICT_DIR="$TEMP_DIR/install-conflict"
mkdir -p "$CONFLICT_DIR/ai.ravenroot.extensions.alpha"
printf 'operator-owned different bytes\n' >"$CONFLICT_DIR/ai.ravenroot.extensions.alpha/keep.txt"
run_plugin install --all --skip-tests --dir "$CONFLICT_DIR"
check "a different installed bundle rejects the batch" test "$LAST_STATUS" -ne 0
check "the conflict gives explicit remove-and-rerun guidance" \
  contains "$LAST_ERR" "plugin.sh remove ai.ravenroot.extensions.alpha"
check "the conflicting bundle is not overwritten" \
  contains "$(cat "$CONFLICT_DIR/ai.ravenroot.extensions.alpha/keep.txt")" "operator-owned"
check "a later candidate is not installed before the conflict is found" \
  test ! -e "$CONFLICT_DIR/ai.ravenroot.extensions.beta"

REPLACE_DIR="$TEMP_DIR/install-replace"
mkdir -p "$REPLACE_DIR/ai.ravenroot.extensions.alpha"
printf 'old installed bytes\n' >"$REPLACE_DIR/ai.ravenroot.extensions.alpha/keep.txt"
run_plugin install --all --skip-tests --replace-existing --dir "$REPLACE_DIR"
check "--replace-existing accepts a differing installed bundle" test "$LAST_STATUS" -eq 0
check "--replace-existing removes the previous bundle bytes" \
  test ! -e "$REPLACE_DIR/ai.ravenroot.extensions.alpha/keep.txt"
check "--replace-existing publishes the newly built alpha bundle" \
  test -f "$REPLACE_DIR/ai.ravenroot.extensions.alpha/ravenroot-plugin.json"
check "--replace-existing still installs a missing bundle in the same lot" \
  test -f "$REPLACE_DIR/ai.ravenroot.extensions.beta/ravenroot-plugin.json"
check "the replacement summary declares alpha REPLACED" contains "$LAST_OUT" "alpha.*REPLACED"

FORCE_DIR="$TEMP_DIR/install-force-alias"
mkdir -p "$FORCE_DIR/ai.ravenroot.extensions.alpha"
printf 'old installed bytes\n' >"$FORCE_DIR/ai.ravenroot.extensions.alpha/keep.txt"
run_plugin install --all -st --force --dir "$FORCE_DIR"
check "--force is an alias for --replace-existing" test "$LAST_STATUS" -eq 0
check "--force publishes the newly built alpha bundle" \
  test -f "$FORCE_DIR/ai.ravenroot.extensions.alpha/ravenroot-plugin.json"

REINSTALL_DIR="$TEMP_DIR/install-reinstall-all"
run_plugin install --all --skip-tests --dir "$REINSTALL_DIR"
check "the reinstall fixture is initially installed" test "$LAST_STATUS" -eq 0
run_plugin install --all -st -r --dir "$REINSTALL_DIR"
check "'install --all -st -r' exits 0" test "$LAST_STATUS" -eq 0
check "-r reinstalls byte-identical alpha instead of leaving it unchanged" \
  contains "$LAST_OUT" "alpha.*REPLACED"
check "-r reinstalls byte-identical beta instead of leaving it unchanged" \
  contains "$LAST_OUT" "beta.*REPLACED"
check "-r still uses one Maven reactor package invocation" test "$(package_call_count)" -eq 1

INVALID_DIR="$TEMP_DIR/install-invalid"
mkdir -p "$INVALID_DIR/ai.ravenroot.extensions.alpha"
printf 'operator-owned different bytes\n' >"$INVALID_DIR/ai.ravenroot.extensions.alpha/keep.txt"
MOCK_INVALID_EXTENSION=beta
run_plugin install --all --skip-tests --replace-existing --dir "$INVALID_DIR"
MOCK_INVALID_EXTENSION=""
check "an invalid candidate rejects the batch" test "$LAST_STATUS" -ne 0
check "an invalid candidate does not replace a pre-existing bundle" \
  contains "$(cat "$INVALID_DIR/ai.ravenroot.extensions.alpha/keep.txt")" "operator-owned"
check "an invalid candidate does not install a later bundle" \
  test ! -e "$INVALID_DIR/ai.ravenroot.extensions.beta"
check "the failed summary declares ERROR" contains "$LAST_OUT" "ERROR"

COLLISION_DIR="$TEMP_DIR/install-collision"
MOCK_COLLIDE_IDS=1
run_plugin install --all --skip-tests --dir "$COLLISION_DIR"
MOCK_COLLIDE_IDS=""
check "two candidates with the same manifest id reject the batch" test "$LAST_STATUS" -ne 0
check "a collision leaves the install directory absent" test ! -e "$COLLISION_DIR"
check "diagnostics name the manifest-id collision" contains "$LAST_ERR" "collision"

# ---- 12. build failure and JDBC checksum: always before the installed directory ------------------

BUILD_FAIL_DIR="$TEMP_DIR/install-build-fail"
MOCK_BUILD_FAIL=beta
run_plugin install --all --skip-tests --dir "$BUILD_FAIL_DIR"
MOCK_BUILD_FAIL=""
check "a build failure makes install --all nonzero" test "$LAST_STATUS" -ne 0
check "a build failure creates no install directory" test ! -e "$BUILD_FAIL_DIR"

BAD_SHA_DIR="$TEMP_DIR/install-bad-sha"
MOCK_VERIFY_FAIL=1
run_plugin install --all --skip-tests --dir "$BAD_SHA_DIR" \
  --driver-jar "$DRIVER_JAR" --driver-sha256 "$DRIVER_SHA256"
MOCK_VERIFY_FAIL=""
check "an invalid JDBC checksum makes install --all nonzero" test "$LAST_STATUS" -ne 0
check "an invalid JDBC checksum creates no install directory" test ! -e "$BAD_SHA_DIR"
check "the invalid checksum stops the batch before Maven" reject_call 'mvn '

ONE_ARG_DIR="$TEMP_DIR/install-one-jdbc-arg"
run_plugin install --all --skip-tests --dir "$ONE_ARG_DIR" --driver-jar "$DRIVER_JAR"
check "one JDBC argument exits nonzero" test "$LAST_STATUS" -ne 0
check "one JDBC argument creates no install directory" test ! -e "$ONE_ARG_DIR"

BAD_DRIVER_DIR="$TEMP_DIR/install-bad-driver"
run_plugin install --all --skip-tests --dir "$BAD_DRIVER_DIR" \
  --driver-jar "$TEMP_DIR/missing-driver.jar" --driver-sha256 "$DRIVER_SHA256"
check "a nonexistent JDBC driver exits nonzero" test "$LAST_STATUS" -ne 0
check "a nonexistent JDBC driver creates no install directory" test ! -e "$BAD_DRIVER_DIR"
check "a nonexistent JDBC driver stops the batch before Maven" reject_call 'mvn '

run_plugin install --all --dir --skip-tests
check "--dir without a value is an argument error" test "$LAST_STATUS" -eq 2
check "--dir without a value does not create a destination named after the next option" \
  test ! -e "$FIXTURE/--skip-tests"

# ---- 13. complete JDBC pair: participates in the same batch -------------------------------------

JDBC_DIR="$TEMP_DIR/install-with-jdbc"
run_plugin install --all --skip-tests --dir "$JDBC_DIR" \
  --driver-jar "$DRIVER_JAR" --driver-sha256 "$DRIVER_SHA256"
check "install --all with a valid JDBC pair exits 0" test "$LAST_STATUS" -eq 0
check "the JDBC build receives the driver/checksum pair" require_call 'ravenroot-extensions/ravenroot-jdbc'
check "JDBC is installed below the manifest id" \
  test -f "$JDBC_DIR/ai.ravenroot.extensions.jdbc/ravenroot-plugin.json"
check "the summary declares JDBC INSTALLED" contains "$LAST_OUT" "jdbc.*INSTALLED"

# ---- 14. compatibility: single install remains fail-closed, even if byte-identical ---------------

SINGLE_SOURCE="$TEMP_DIR/single-source"
SINGLE_DEST="$TEMP_DIR/single-dest"
mkdir -p "$SINGLE_SOURCE"
printf 'id=ai.ravenroot.extensions.single\n' >"$SINGLE_SOURCE/ravenroot-plugin.json"
printf 'stable\n' >"$SINGLE_SOURCE/single.jar"
run_plugin install "$SINGLE_SOURCE" --dir "$SINGLE_DEST"
check "initial single install remains compatible" test "$LAST_STATUS" -eq 0
run_plugin install "$SINGLE_SOURCE" --dir "$SINGLE_DEST"
check "byte-identical single install continues to reject overwrite" test "$LAST_STATUS" -ne 0
check "single install retains explicit remove guidance" \
  contains "$LAST_ERR" "plugin.sh remove ai.ravenroot.extensions.single"

# ---- 15. R2: authoritative ids must be direct, lossless, uniquely normalized components ---------
#
# PluginManifest intentionally accepts every nonblank text: the batch installer, not the manifest
# contract, must prove that an id can become ONE direct child directory. These fixtures therefore keep
# `validate` green and change only the authoritative `manifest-id` output, including bytes ordinary
# shell command substitution would lose.

UNSAFE_DIR="$TEMP_DIR/install-unsafe-id"
UNSAFE_SIBLING="$TEMP_DIR/escaped-by-id"
printf 'sibling sentinel\n' >"$UNSAFE_SIBLING"
MOCK_ALPHA_MANIFEST_ID="../escaped-by-id"
run_plugin install --all --skip-tests --dir "$UNSAFE_DIR"
MOCK_ALPHA_MANIFEST_ID=""
check "an id containing traversal is rejected" test "$LAST_STATUS" -ne 0
check "traversal creates no install directory" test ! -e "$UNSAFE_DIR"
check "traversal does not modify the resolved sibling" \
  test "$(cat "$UNSAFE_SIBLING")" = "sibling sentinel"
check "diagnostics name the safe-component contract" \
  contains "$LAST_ERR" "safe install directory component"
check "containment rejection also retains the per-extension ERROR summary" \
  contains "$LAST_OUT" "alpha: ERROR"

ALIAS_DIR="$TEMP_DIR/install-case-alias"
MOCK_ALPHA_MANIFEST_ID="Ai.Ravenroot.Alias"
MOCK_BETA_MANIFEST_ID="ai.ravenroot.alias"
run_plugin install --all --skip-tests --dir "$ALIAS_DIR"
MOCK_ALPHA_MANIFEST_ID=""
MOCK_BETA_MANIFEST_ID=""
check "two distinct ids aliasing after normalization are rejected" test "$LAST_STATUS" -ne 0
check "the normalized alias leaves the install directory absent" test ! -e "$ALIAS_DIR"
check "diagnostics name the normalized collision" contains "$LAST_ERR" "normalized target collision"

PLAN_DIR="$TEMP_DIR/install-plan-id"
MOCK_ALPHA_MANIFEST_ID=$(printf 'ai.ravenroot\tcorrupt')
run_plugin install --all --skip-tests --dir "$PLAN_DIR"
MOCK_ALPHA_MANIFEST_ID=""
check "an id containing the plan delimiter is rejected" test "$LAST_STATUS" -ne 0
check "an id containing a tab creates no install directory" test ! -e "$PLAN_DIR"
check "the tab is rejected before staging" \
  not_contains "$LAST_ERR" "cannot stage"

LOSSLESS_DIR="$TEMP_DIR/install-lossless-id"
MOCK_ALPHA_MANIFEST_ID="$(printf 'ai.ravenroot.lossless\n.')"
MOCK_ALPHA_MANIFEST_ID=${MOCK_ALPHA_MANIFEST_ID%.}
run_plugin install --all --skip-tests --dir "$LOSSLESS_DIR"
MOCK_ALPHA_MANIFEST_ID=""
check "an id with a final newline is not silently shortened" test "$LAST_STATUS" -ne 0
check "a non-lossless id leaves the install directory absent" test ! -e "$LOSSLESS_DIR"
check "diagnostics name the non-lossless representation" contains "$LAST_ERR" "losslessly"

if [ "$failures" -ne 0 ]; then
  printf '\n%s test(s) failed.\n' "$failures" >&2
  exit 1
fi
printf '\nplugin.sh smoke tests passed.\n'
