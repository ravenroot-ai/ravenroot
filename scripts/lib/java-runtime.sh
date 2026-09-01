# JDK selection for builds — single source of truth.
#
# This is not an executable: it is included with `.` (dot). It lives here rather than in dev.sh for
# the same reason as node-runtime.sh, plus one more: discovery has a branch that no run on the
# author's machine can exercise — Homebrew JDKs, which /usr/libexec/java_home cannot see — and an
# includable file lets scripts/tests/test_dev.sh exercise it nevertheless.
#
# The macOS helper path is a simple assignment, NOT a "${VAR:-default}" form: a host variable must
# not be able to redirect where this script looks for a JDK. The test reassigns it after inclusion,
# which only an includer can do.
JAVA_HOME_HELPER=/usr/libexec/java_home

# Every variable name below carries the `probe_` prefix, and this is not pedantry: sh has NO local
# variables, so every assignment inside a function is global. The first version of these two
# functions used `major` and `candidate`, which are also loop variables in select_java_runtime: on
# each failed probe, `major` became empty and the two following searches in the SAME iteration asked
# for `brew --prefix openjdk@` with no version. The only branch that broke was the one for Homebrew
# JDKs, precisely the case /usr/libexec/java_home cannot see; on a machine whose `java` on PATH is
# already supported, the defect never manifests. The explicit case keeps it observable.

# The major version reported by a JDK, or nothing if that directory contains none.
java_home_major() {
  probe_home=$1
  [ -n "$probe_home" ] && [ -x "$probe_home/bin/java" ] || return 1
  "$probe_home/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p'
}

java_home_is_supported() {
  probe_major=$(java_home_major "$1" 2>/dev/null) || return 1
  [ -n "$probe_major" ] || return 1
  # The same range that maven-enforcer-plugin enforces at validate for all modules.
  [ "$probe_major" -ge 21 ] && [ "$probe_major" -lt 26 ]
}

# The JDK is DISCOVERED, never hard-coded. An absolute path from the machine that wrote this script
# — the way this instruction previously existed in POM comments and the bench README — works on one
# machine only and on no other, and its failure mode never identifies itself.
# Returns 0 and populates JAVA_HOME_SELECTED/JAVA_HOME_ORIGIN, or returns 1 silently: `check` must
# be able to report the same outcome that `setup` would obtain, without exiting.
select_java_runtime() {
  JAVA_HOME_SELECTED=""
  JAVA_HOME_ORIGIN=""
  if java_home_is_supported "${JAVA_HOME:-}"; then
    JAVA_HOME_SELECTED=$JAVA_HOME
    JAVA_HOME_ORIGIN="environment JAVA_HOME"
    return 0
  fi

  if command -v java >/dev/null 2>&1; then
    on_path=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p' | head -1)
    if java_home_is_supported "$on_path"; then
      JAVA_HOME_SELECTED=$on_path
      JAVA_HOME_ORIGIN="java on PATH"
      return 0
    fi
  fi

  for wanted_major in 21 22 23 24 25; do
    if [ -x "$JAVA_HOME_HELPER" ]; then
      found=$("$JAVA_HOME_HELPER" -v "$wanted_major" 2>/dev/null || true)
      # Revalidation is NOT a duplicate check; removing it causes a silent defect. Measurements show
      # that `/usr/libexec/java_home -v 21` on a machine without JDK 21 does NOT fail — it returns
      # the closest installed version, such as JDK 18, with exit zero. Trusting that response would
      # stop discovery on an out-of-range JDK while JAVA_HOME_ORIGIN states a different one was
      # requested, and the build would fail later with an enforcer message that does not name this
      # line.
      if java_home_is_supported "$found"; then
        JAVA_HOME_SELECTED=$found
        JAVA_HOME_ORIGIN="$JAVA_HOME_HELPER -v $wanted_major"
        return 0
      fi
    fi
    # /usr/libexec/java_home does not see Homebrew-installed JDKs, so ask brew
    # for the prefix rather than writing it by hand.
    if command -v brew >/dev/null 2>&1; then
      prefix=$(brew --prefix "openjdk@$wanted_major" 2>/dev/null || true)
      for found in "$prefix/libexec/openjdk.jdk/Contents/Home" "$prefix"; do
        if java_home_is_supported "$found"; then
          JAVA_HOME_SELECTED=$found
          JAVA_HOME_ORIGIN="brew --prefix openjdk@$wanted_major"
          return 0
        fi
      done
    fi
    for found in /usr/lib/jvm/java-"$wanted_major"-openjdk* /usr/lib/jvm/temurin-"$wanted_major"* \
        /usr/lib/jvm/jdk-"$wanted_major"*; do
      if java_home_is_supported "$found"; then
        JAVA_HOME_SELECTED=$found
        JAVA_HOME_ORIGIN="$found"
        return 0
      fi
    done
  done
  return 1
}

# Like select_java_runtime, but for callers that need to build: if nothing is found, explain what to
# do and exit.
require_java_runtime() {
  if select_java_runtime; then
    JAVA_HOME=$JAVA_HOME_SELECTED
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
    return 0
  fi
  echo "No JDK between 21 and 25 was found." >&2
  echo "Searched, in order: JAVA_HOME, java on PATH, /usr/libexec/java_home, Homebrew prefixes" >&2
  echo "for openjdk@21..25, and conventional Linux paths." >&2
  echo "Ravenroot builds on JDK 21 (the baseline and bytecode target) through LTS 25; 26 is not a" >&2
  echo "supported build platform, and maven-enforcer-plugin stops out-of-range builds." >&2
  echo "Install one (for example, 'brew install openjdk@21') or export JAVA_HOME and retry." >&2
  exit 2
}
