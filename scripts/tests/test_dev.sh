#!/usr/bin/env sh
# Tests for dev.sh, following test_service.sh: PATH stubs, no real build, no network.
#
# They cover the two branches that an execution on the script author's machine does NOT exercise, and
# this is why they exist:
#
#   1. JDK discovery when /usr/libexec/java_home finds nothing and the JDK is a Homebrew keg.
#      On a machine whose PATH `java` is already supported, discovery exits at the second step and
#      never runs the rest. The defect this test fixes was exactly there: `major`, assigned inside
#      java_home_is_supported, is the SAME select_java_runtime loop variable -- sh has no local
#      variables -- so after the first failed probe it became "" and brew was queried for versionless
#      `openjdk@`.
#
#   2. egress-policy derivation for a literal IPv6 endpoint, which cannot be expressed as a reserved-
#      network exception and must not be declared applied.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM
MOCK_BIN="$TEMP_DIR/bin"
BREW_LOG="$TEMP_DIR/brew.log"
mkdir -p "$MOCK_BIN"

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

# A fake JDK: the only thing the functions under test read is the `-version` line.
make_fake_jdk() {
  home=$1
  version=$2
  mkdir -p "$home/bin"
  cat >"$home/bin/java" <<EOF
#!/usr/bin/env sh
echo 'openjdk version "$version" 2026-01-01' >&2
EOF
  chmod +x "$home/bin/java"
}

FAKE_21="$TEMP_DIR/brew-openjdk-21/libexec/openjdk.jdk/Contents/Home"
make_fake_jdk "$FAKE_21" 21.0.11
FAKE_18="$TEMP_DIR/path-jdk-18"
make_fake_jdk "$FAKE_18" 18.0.2

# `java` on PATH: an OUT-OF-RANGE version, so discovery must continue to brew.
cat >"$MOCK_BIN/java" <<EOF
#!/usr/bin/env sh
case "\$*" in
  *-XshowSettings:properties*) echo "    java.home = $FAKE_18" >&2 ;;
esac
echo 'openjdk version "18.0.2" 2026-01-01' >&2
EOF
chmod +x "$MOCK_BIN/java"

# brew: records HOW it was invoked and only knows openjdk@21.
cat >"$MOCK_BIN/brew" <<EOF
#!/usr/bin/env sh
printf '%s\n' "\$*" >>"$BREW_LOG"
case "\$*" in
  '--prefix openjdk@21') printf '%s\n' "$TEMP_DIR/brew-openjdk-21" ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$MOCK_BIN/brew"

# A macOS helper that never finds anything: this is the condition in which the Homebrew branch is the
# only one that can answer, and the condition that the defect made unusable.
cat >"$TEMP_DIR/java_home_stub" <<'EOF'
#!/usr/bin/env sh
exit 1
EOF
chmod +x "$TEMP_DIR/java_home_stub"

# ---- 1. JDK selection -----------------------------------------------------------------------------

: >"$BREW_LOG"
selection=$(
  PATH="$MOCK_BIN:$PATH"
  export PATH
  unset JAVA_HOME
  PROJECT_DIR="$PROJECT_DIR"
  . "$PROJECT_DIR/scripts/lib/java-runtime.sh"
  JAVA_HOME_HELPER="$TEMP_DIR/java_home_stub"
  if select_java_runtime; then printf '%s\n' "$JAVA_HOME_SELECTED"; else printf 'NONE\n'; fi
)
brew_calls=$(cat "$BREW_LOG")

check "the Homebrew keg is found when java_home does not answer" \
  test "$selection" = "$FAKE_21"
check "brew receives the requested version, not an empty 'openjdk@'" \
  contains "$brew_calls" "^--prefix openjdk@21$"
check "no versionless brew invocation" \
  not_contains "$brew_calls" "^--prefix openjdk@$"

# ---- 2. egress policy derived by bench -------------------------------------------------------------

cat >"$MOCK_BIN/mvn" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
chmod +x "$MOCK_BIN/mvn"
FAKE_UI="$TEMP_DIR/ui-dist"
mkdir -p "$FAKE_UI"
: >"$FAKE_UI/index.html"

run_bench() {
  endpoint_variable=$1
  extra_variable=$2
  (
    PATH="$MOCK_BIN:$PATH"
    export PATH
    JAVA_HOME="$FAKE_21"
    export JAVA_HOME
    RAVENROOT_UI_DIR="$FAKE_UI"
    export RAVENROOT_UI_DIR
    unset RAVENROOT_HTTP_ALLOWED_HOSTS RAVENROOT_HTTP_ALLOWED_PORTS RAVENROOT_EGRESS_RESERVED_EXCEPTIONS
    [ -z "$endpoint_variable" ] || export "$endpoint_variable"
    [ -z "$extra_variable" ] || export "$extra_variable"
    "$PROJECT_DIR/dev.sh" bench 2>&1
  )
}

ipv4_output=$(run_bench "" "")
check "the IPv4 loopback endpoint opens host, port, and exception" \
  contains "$ipv4_output" "RAVENROOT_EGRESS_RESERVED_EXCEPTIONS=127.0.0.1:LOOPBACK"
check "and names the declared endpoint host and port" \
  contains "$ipv4_output" "RAVENROOT_HTTP_ALLOWED_PORTS=11434"

ipv6_output=$(run_bench \
  'RAVENROOT_DEV_MODEL_OLLAMA_LOCAL_ENDPOINT=http://[::1]:11434/v1/chat/completions' "")
check "an IPv6 endpoint produces no exception the runtime would reject" \
  not_contains "$ipv6_output" "::1:LOOPBACK"
check "and says so rather than leaving a rejected profile to reveal it" \
  contains "$ipv6_output" "IPv6 literal address"

preset_output=$(run_bench "" 'RAVENROOT_HTTP_ALLOWED_PORTS=443')
check "a variable already set is never overwritten" \
  not_contains "$preset_output" "RAVENROOT_HTTP_ALLOWED_PORTS="

# ---- 3. guardie ------------------------------------------------------------------------------------

no_ui_status=0
(
  PATH="$MOCK_BIN:$PATH"; export PATH
  JAVA_HOME="$FAKE_21"; export JAVA_HOME
  RAVENROOT_UI_DIR="$TEMP_DIR/does-not-exist"; export RAVENROOT_UI_DIR
  "$PROJECT_DIR/dev.sh" bench
) >/dev/null 2>&1 || no_ui_status=$?
check "bench refuses to start without a built editor" test "$no_ui_status" -eq 1

unknown_status=0
"$PROJECT_DIR/dev.sh" --does-not-exist >/dev/null 2>&1 || unknown_status=$?
check "an unknown option is a usage error" test "$unknown_status" -eq 2

# ---- 4. the three setup exit codes are distinct -----------------------------------------------------
#
# The help table declares them; these two tests prevent declaration and execution from diverging. They
# build nothing: `mvn` is a stub and with --skip-ui even Node is not sought.

# A PATH where `mvn` is absent BY CONSTRUCTION, not because this machine keeps it elsewhere.
#
# The first version of this block used `PATH="$NO_MVN_BIN:/usr/bin:/bin"` and was green here only
# because Homebrew puts mvn in /opt/homebrew/bin. On an image where Maven is in /usr/bin -- the normal
# distribution case and plausibly that of the CI runner this suite targets -- the same construction
# would run REAL Maven against the reactor POM, the opposite of what the job declares. A test whose
# truth depends on where the host installed a binary is not a test.
#
# A barn of links to individual commands, not an entire system directory: this makes it impossible for
# `mvn` to get in by accident.
CORE_BIN="$TEMP_DIR/bin-core"
mkdir -p "$CORE_BIN"
# `sh` is present because the shebang is `#!/usr/bin/env sh`, which resolves the interpreter on PATH:
# without it, the test would fail with `env: sh: No such file or directory` rather than its assertion.
for core_command in sh sed grep find basename dirname head sort tr chmod cat mkdir rm uname; do
  core_path=$(command -v "$core_command" 2>/dev/null) || continue
  ln -s "$core_path" "$CORE_BIN/$core_command" 2>/dev/null || true
done
NO_MVN_BIN="$TEMP_DIR/bin-without-mvn"
mkdir -p "$NO_MVN_BIN"
cp "$MOCK_BIN/java" "$NO_MVN_BIN/java"
NO_MVN_PATH="$NO_MVN_BIN:$CORE_BIN"

# ANTI-FALSE-GREEN, in the form already used by NotAReleaseArtifactTest: an invalid fixture must fail
# BY NAMING ITSELF, rather than disguising itself as incorrect script behaviour.
if ( PATH="$NO_MVN_PATH"; export PATH; command -v mvn >/dev/null 2>&1 ); then
  printf 'FAIL invalid fixture: mvn is reachable from the curated PATH, so the test proves nothing\n' >&2
  failures=$((failures + 1))
else
  missing_mvn_status=0
  (
    PATH="$NO_MVN_PATH"; export PATH
    JAVA_HOME="$FAKE_21"; export JAVA_HOME
    "$PROJECT_DIR/dev.sh" setup --skip-ui
  ) >/dev/null 2>&1 || missing_mvn_status=$?
  check "a missing prerequisite exits 2, as help declares" test "$missing_mvn_status" -eq 2
fi

# An mvn that succeeds for the reactor and fails for the first adapter: the build had started.
cat >"$MOCK_BIN/mvn" <<'EOF'
#!/usr/bin/env sh
case "$*" in
  *ravenroot-adapter*) echo 'synthetic adapter build failure' >&2; exit 1 ;;
esac
exit 0
EOF
chmod +x "$MOCK_BIN/mvn"
build_failure_output=$TEMP_DIR/build-failure.txt
build_failure_status=0
(
  PATH="$MOCK_BIN:$PATH"; export PATH
  JAVA_HOME="$FAKE_21"; export JAVA_HOME
  "$PROJECT_DIR/dev.sh" setup --skip-ui
) >"$build_failure_output" 2>&1 || build_failure_status=$?
check "a failed build exits 1" test "$build_failure_status" -eq 1
check "and prints no final report, which distinguishes the two exit-code-1 cases" \
  not_contains "$(cat "$build_failure_output")" "^Prepared$"

# Restore the neutral stub for whatever follows in this file.
cat >"$MOCK_BIN/mvn" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
chmod +x "$MOCK_BIN/mvn"

if [ "$failures" -ne 0 ]; then
  printf '\n%s test(s) failed.\n' "$failures" >&2
  exit 1
fi
printf '\ndev.sh smoke tests passed.\n'
