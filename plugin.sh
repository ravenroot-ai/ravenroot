#!/usr/bin/env sh
# The developer-facing plugin bundle toolchain, beside service.sh.
#
# The acceptance bar this script exists for: a developer runs `./plugin.sh build mail`, gets a
# copyable bundle without needing to know Maven, drops it into the convention directory
# (`./plugin.sh install`), and a later `./service.sh restart` (or a plain `docker build`) picks it
# up. Trust-sensitive work -- parsing a manifest, verifying a checksum, deciding whether a bundle is
# valid -- is never reimplemented here: every one of those calls into
# ai.ravenroot.plugin.bundle.PluginCli, the same validator increments 1-3 already have tests for.
# This script's own job is orchestration: run Maven, gather files, call the Java validator, copy
# directories. Nothing here parses JSON or computes a checksum.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REACTOR_DIR="$PROJECT_DIR/ravenroot"
PLUGINS_DIR=${RAVENROOT_PLUGINS_DIR:-"$PROJECT_DIR/ravenroot-plugins"}
APPLICATION_API_CLASSES="$REACTOR_DIR/ravenroot-application-api/target/classes"
PLUGIN_BUNDLE_CLASSES="$REACTOR_DIR/ravenroot-plugin-bundle/target/classes"

usage() {
  cat <<'EOF'
Usage: ./plugin.sh <command> [options]

Commands:
  list [plugins-dir]                    List bundles in the convention directory (default:
                                         ./ravenroot-plugins) and their validation status.

  build <extension-id>|--all [-st|--skip-tests] [--driver-jar file --driver-sha256 hex]...
                                         Compile ravenroot/ravenroot-extensions/<extension-id> and
                                         produce an installable bundle under that module's own
                                         target/plugin-bundle/ -- no Maven knowledge required beyond
                                         having it installed. --all and an explicit extension id are
                                         mutually exclusive: combining them exits 2 rather than
                                         silently picking one. --all builds every known extension
                                         EXCEPT jdbc, which it skips and announces on stderr unless
                                         one or more --driver-jar/--driver-sha256 pairs are also given -- jdbc's bundle
                                         is closed and cannot be produced without a driver, and --all
                                         has none to offer on its own. Naming jdbc explicitly (with no
                                         --all) without a driver still fails outright with a non-zero
                                         exit, exactly like any other missing prerequisite.
                                         --all ALSO always skips the 'ai' extension, and announces
                                         that too: the AI node bundle is never supplied with the
                                         product and is never swept into an image by a batch command.
                                         './plugin.sh build ai' builds it, which is the point --
                                         including it has to be an explicit act, not a default.
                                         -st and --skip-tests pass -DskipTests through, matching
                                         service.sh's -st semantics for the same reason: iterative
                                         local development, never the default.
                                         The JDBC extension additionally requires one or more regular
                                         driver jars, each immediately paired with its lowercase SHA-256.
                                         Every pair is verified before its driver is copied into the
                                         closed bundle manifest; filenames/driverIds must be unique.

  validate <bundle-or-dir>              Validate one bundle directory and print why, if it fails.

  check-published <plugins-dir>         Refuse a directory of bundles for PUBLICATION: fails
                                         if any bundle there declares a generative node capability
                                         ("ai", "agentic"). Separate from validate on purpose --
                                         such a bundle is perfectly VALID and perfectly installable
                                         by an operator; what it may not be is shipped in the
                                         artifact and image this project publishes. Always prints
                                         how many bundles it inspected, including zero.

  install <bundle-or-dir> [--dir plugins-dir]
                                         Validate, then copy one bundle into the convention
                                         directory. Refuses to overwrite an existing installation,
                                         including a byte-identical one, preserving the original
                                         fail-closed single-bundle contract.

  install --all [-st|--skip-tests] [-r|--remove-existing] [--replace-existing|--force]
                [--dir plugins-dir]
                [--driver-jar file --driver-sha256 hex]...
                                         Build every known extension through the same path as
                                         `build --all`, prevalidate the complete lot, then install it.
                                         Existing byte-identical bundles are UNCHANGED; differing
                                         destinations reject the whole lot before installation unless
                                         --replace-existing (alias: --force) is explicitly supplied.
                                         -r/--remove-existing reinstalls every existing bundle in the
                                         selected lot, including byte-identical ones, after the whole
                                         lot has been built, validated and staged successfully.
                                         Without at least one complete JDBC driver/digest pair, jdbc is explicitly
                                         SKIPPED, and 'ai' is always SKIPPED. This command never
                                         activates plugins, changes
                                         RAVENROOT_ENABLED_PLUGINS/plugins.lock.yaml, or builds an
                                         image. -st/--skip-tests have the same meaning as for build.

  remove <id> [--dir plugins-dir]       Remove an installed bundle. Succeeds unconditionally, even
                                         if the id is still named in an allowlist -- the existing
                                         unknown-id startup check catches that on its own, rather
                                         than this command growing a second copy of it.

  bundle-dir <extension-id>             Print the path build would produce for this extension,
                                         without building anything. The PLAT-12 CI artifact staging
                                         step uses this
                                         instead of parsing build's own stdout: build's last line is
                                         the same path, but its stdout also carries
                                         generate-manifest's own multi-line success output, so
                                         extracting "the last line" would need a pipe (`| tail -n1`)
                                         that silently discards build's real exit status. This command
                                         has nothing to pipe: its only output is the path, computed
                                         from the same extension_module() table build itself uses, so
                                         the two can never drift apart.

Configuration:
  RAVENROOT_PLUGINS_DIR   Convention directory (default: ./ravenroot-plugins)

Examples:
  ./plugin.sh build mail
  ./plugin.sh build ai   # the AI node bundle; never built by --all, never shipped
  ./plugin.sh build jdbc --driver-jar postgresql-42.7.7.jar --driver-sha256 <sha256>
  ./plugin.sh build jdbc --driver-jar postgresql-42.7.7.jar --driver-sha256 <sha256> \
    --driver-jar mysql-connector-j-9.5.0.jar --driver-sha256 <sha256>
  ./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-mail/target/plugin-bundle
  ./plugin.sh install ravenroot/ravenroot-extensions/ravenroot-mail/target/plugin-bundle
  ./plugin.sh install --all --skip-tests --dir ./ravenroot-plugins
  ./plugin.sh list
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_compiled_classes() {
  # PluginCli itself has to exist before it can validate anything. Building it here, once, keeps
  # every command below simple; it is fast when already up to date (Maven's own incremental
  # compile) and correct when it is not.
  plugin_cli_class="$PLUGIN_BUNDLE_CLASSES/ai/ravenroot/plugin/bundle/PluginCli.class"
  node_package_class="$APPLICATION_API_CLASSES/ai/ravenroot/api/node/NodePackage.class"
  if [ ! -f "$plugin_cli_class" ] || [ ! -f "$node_package_class" ]; then
    echo "Compiling ravenroot-plugin-bundle (first run, or classes missing)..." >&2
    ( cd "$REACTOR_DIR" && mvn -q -B -pl ravenroot-plugin-bundle -am compile )
  fi
}

plugin_cli() {
  require_compiled_classes
  java -cp "$PLUGIN_BUNDLE_CLASSES:$APPLICATION_API_CLASSES" ai.ravenroot.plugin.bundle.PluginCli "$@"
}

# ---- extension discovery ------------------------------------------------------------------------
# Extensions are DISCOVERED from the tree, not listed in a table here. The table this replaces
# named one module while four existed, so `./plugin.sh build kafka` answered "Unknown extension id"
# for a module sitting in the tree with a working NodePackage -- and, worse, a bundle a developer
# wrote themselves could never be built at all, because no line here described it.
#
# The trust argument the table was defending is preserved, and it is worth being precise about
# where it actually lives. Naming a class must stay a decision rather than a discovery AT RUNTIME:
# that decision is the `nodePackageClasses` entry written into the generated manifest, validated
# on load, plus `RAVENROOT_ENABLED_PLUGINS`, which still has to name the bundle before the server
# will touch it. Neither of those gates moves. What is discovered here is only which modules in a
# developer's own checkout are buildable, at build time, in a tree they already control.

extension_module_dirs() {
  find "$REACTOR_DIR/ravenroot-extensions" -mindepth 1 -maxdepth 1 -type d 2>/dev/null |
    while IFS= read -r dir; do
      [ -f "$dir/pom.xml" ] || continue
      find "$dir/src/main/java" -name '*NodePackage.java' -print -quit 2>/dev/null | grep -q . || continue
      echo "$dir"
    done | sort
}

# The short id is the module directory name with the project prefix stripped: ravenroot-kafka -> kafka.
extension_id_of_dir() {
  basename "$1" | sed 's/^ravenroot-//'
}

known_extensions() {
  extension_module_dirs | while IFS= read -r dir; do extension_id_of_dir "$dir"; done
}

# The extension `--all` never sweeps up, whatever options it is given -- and unlike jdbc's skip,
# which lifts as soon as a driver is supplied, this one has nothing that lifts it.
#
# The AI bundle carries node types that invoke a model. It is never supplied with the product
# jar or default image: it is compiled here, installed, named in RAVENROOT_ENABLED_PLUGINS and
# included in an operator-built image -- four deliberate acts. `install --all` performs the first two
# for every discovered extension at once, into the very directory Dockerfile copies into the image,
# so a plain `--all` would let a batch command supply what only an explicit choice may supply.
#
# So `--all` skips it and SAYS SO on stderr, exactly as it already skips jdbc and for the same class
# of reason: an --all that quietly omits something is a silent truncation. Naming it explicitly --
# `./plugin.sh build ai` -- builds it, and must keep doing so: silencing an explicit request would be
# worse than the omission this rule exists to make deliberate.
#
# This is a narrowing of a batch convenience, not the control. The control is the release gate
# `./plugin.sh check-published`, which refuses any bundle declaring a generative capability in a
# published directory, plus RAVENROOT_ENABLED_PLUGINS, and neither of them moves.
AI_EXTENSION_ID=ai

# Accepts a short id (kafka), a module directory name (ravenroot-kafka), or a path to any module
# directory -- including one outside ravenroot-extensions/, so a bundle written elsewhere builds
# without this file ever having heard of it.
resolve_extension_dir() {
  candidate=$1
  if [ -d "$candidate" ] && [ -f "$candidate/pom.xml" ]; then
    (cd "$candidate" && pwd)
    return 0
  fi
  for dir in $(extension_module_dirs); do
    if [ "$(extension_id_of_dir "$dir")" = "$candidate" ] || [ "$(basename "$dir")" = "$candidate" ]; then
      echo "$dir"
      return 0
    fi
  done
  return 1
}

# Derived from the module's own sources rather than from a mapping, so it cannot fall out of date
# with the class it names. Refuses rather than guesses when a module declares more than one: the
# manifest carries exactly one entry and picking silently would put a name in it nobody chose.
extension_node_package_class_of_dir() {
  dir=$1
  files=$(find "$dir/src/main/java" -name '*NodePackage.java' 2>/dev/null | sort)
  count=$(printf '%s\n' "$files" | grep -c . || true)
  if [ "$count" -eq 0 ]; then
    echo "No NodePackage implementation found under $dir/src/main/java" >&2
    return 1
  fi
  if [ "$count" -gt 1 ]; then
    echo "Module $dir declares more than one NodePackage:" >&2
    printf '  %s\n' $files >&2
    echo "The manifest carries exactly one; name it explicitly rather than letting this script pick." >&2
    return 1
  fi
  file=$(printf '%s\n' "$files" | head -1)
  package=$(sed -n 's/^package \(.*\);.*/\1/p' "$file" | head -1)
  class=$(basename "$file" .java)
  if [ -z "$package" ]; then
    echo "Cannot read the package declaration from $file" >&2
    return 1
  fi
  echo "$package.$class"
}

# ---- commands -----------------------------------------------------------------------------------

cmd_list() {
  dir=${1:-$PLUGINS_DIR}
  plugin_cli list "$dir"
}

cmd_validate() {
  if [ $# -lt 1 ]; then
    echo "Usage: ./plugin.sh validate <bundle-or-dir>" >&2
    exit 2
  fi
  plugin_cli validate "$1"
}

cmd_check_published() {
  if [ $# -lt 1 ]; then
    echo "Usage: ./plugin.sh check-published <plugins-dir>" >&2
    exit 2
  fi
  plugin_cli check-published "$1"
}

directories_byte_identical() {
  [ -d "$1" ] && [ -d "$2" ] && diff -qr "$1" "$2" >/dev/null 2>&1
}

print_install_all_error_summary() {
  include_jdbc=$1
  echo "Install summary:"
  for extension_dir in $(extension_module_dirs); do
    extension_id=$(extension_id_of_dir "$extension_dir")
    if [ "$extension_id" = "jdbc" ] && [ "$include_jdbc" = "0" ]; then
      echo "  jdbc: SKIPPED (requires --driver-jar and --driver-sha256)"
    elif [ "$extension_id" = "$AI_EXTENSION_ID" ]; then
      echo "  $AI_EXTENSION_ID: SKIPPED (never installed by a batch command; build and install it explicitly)"
    else
      echo "  $extension_id: ERROR (lot rejected before installation; see diagnostics above)"
    fi
  done
}

# PluginManifest deliberately accepts any non-blank id because the runtime identity contract is not
# a filesystem-name contract. Batch installation needs the narrower property before that id can be
# used as a plan field and directory component. Capture PluginCli's stdout in a file first: command
# substitution strips trailing newlines, which would silently turn a validator-accepted "id\n" into
# "id" before any shell check could see the difference.
read_safe_install_manifest_id() {
  source_dir=$1
  id_file=$2
  extension_id=$3
  if ! plugin_cli manifest-id "$source_dir" >"$id_file"; then
    echo "install --all: cannot resolve manifest id for $extension_id: $source_dir" >&2
    return 1
  fi

  wire_bytes=$(LC_ALL=C wc -c <"$id_file" | tr -d ' ')
  manifest_id=""
  if ! IFS= read -r manifest_id <"$id_file"; then
    echo "install --all: manifest id for $extension_id cannot be represented losslessly as one plan record" >&2
    return 1
  fi
  manifest_id_bytes=$(LC_ALL=C printf '%s' "$manifest_id" | wc -c | tr -d ' ')
  if [ "$wire_bytes" -ne $((manifest_id_bytes + 1)) ]; then
    echo "install --all: manifest id for $extension_id cannot be represented losslessly as one plan record" >&2
    return 1
  fi

  # ASCII keeps byte length, case folding and filesystem spelling identical on the supported
  # Unix/OCI paths. One leading alphanumeric plus only dot/underscore/hyphen thereafter excludes
  # separators, dot segments, control bytes, plan delimiters and shell/path metacharacters. The
  # 255-byte ceiling is the direct-component ceiling of the supported APFS/ext4 deployment paths.
  if ! LC_ALL=C grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$' "$id_file"; then
    echo "install --all: manifest id for $extension_id is not a safe install directory component (ASCII, 255-byte maximum)" >&2
    return 1
  fi
}

cmd_install_all() {
  dest_root=$PLUGINS_DIR
  skip_tests=0
  replace_existing=0
  remove_existing=0
  driver_plan=$(mktemp "${TMPDIR:-/tmp}/plugin-install-jdbc.XXXXXX")
  : >"$driver_plan"
  pending_driver_jar=""
  driver_jar_set=0
  tab=$(printf '\t')

  while [ $# -gt 0 ]; do
    case "$1" in
      -st|--skip-tests)
        skip_tests=1
        shift
        ;;
      --replace-existing|--force)
        replace_existing=1
        shift
        ;;
      -r|--remove-existing)
        remove_existing=1
        shift
        ;;
      --dir)
        [ $# -ge 2 ] || { echo "install --all: --dir requires a value" >&2; return 2; }
        case "$2" in -*) echo "install --all: --dir requires a value" >&2; return 2 ;; esac
        dest_root=$2
        shift 2
        ;;
      --driver-jar)
        [ -z "$pending_driver_jar" ] && [ $# -ge 2 ] \
          || { echo "install --all: each --driver-jar must be followed by one --driver-sha256" >&2; rm -f "$driver_plan"; return 2; }
        case "$2" in -*) echo "install --all: --driver-jar requires a value" >&2; rm -f "$driver_plan"; return 2 ;; esac
        pending_driver_jar=$2
        shift 2
        ;;
      --driver-sha256)
        [ -n "$pending_driver_jar" ] && [ $# -ge 2 ] \
          || { echo "install --all: --driver-sha256 must complete a preceding --driver-jar" >&2; rm -f "$driver_plan"; return 2; }
        case "$2" in -*) echo "install --all: --driver-sha256 requires a value" >&2; rm -f "$driver_plan"; return 2 ;; esac
        printf '%s\t%s\n' "$pending_driver_jar" "$2" >>"$driver_plan"
        pending_driver_jar=""
        driver_jar_set=1
        shift 2
        ;;
      --*)
        echo "Unknown install --all option: $1" >&2
        return 2
        ;;
      *)
        echo "install --all does not accept an extension id or bundle path: $1" >&2
        return 2
        ;;
    esac
  done

  if [ -n "$pending_driver_jar" ]; then
    echo "JDBC driver bundling requires both --driver-jar and --driver-sha256" >&2
    rm -f "$driver_plan"
    return 2
  fi

  # Use cmd_build itself rather than a parallel batch implementation. This keeps discovery, Maven
  # selection, manifest generation, checksum verification, JDBC narrowing and --skip-tests exactly
  # the same as `build --all`; install --all only adds a transaction boundary after those artifacts
  # exist. Build output under target/ is intentionally outside that boundary.
  build_status=0
  set -- build --all
  [ "$skip_tests" = "0" ] || set -- "$@" --skip-tests
  while IFS="$tab" read -r driver_jar driver_sha256; do
    [ -n "$driver_jar" ] || continue
    set -- "$@" --driver-jar "$driver_jar" --driver-sha256 "$driver_sha256"
  done <"$driver_plan"
  "$PROJECT_DIR/plugin.sh" "$@" || build_status=$?
  rm -f "$driver_plan"
  if [ "$build_status" -ne 0 ]; then
    echo "install --all: build phase failed; installation directory was not mutated" >&2
    print_install_all_error_summary "$driver_jar_set"
    return "$build_status"
  fi

  plan_dir=$(mktemp -d "${TMPDIR:-/tmp}/plugin-install-all.XXXXXX")
  plan_file="$plan_dir/plan"
  : >"$plan_file"
  seen_normalized_targets="|"
  prevalidation_failed=0
  candidate_index=0

  if [ -e "$dest_root" ] && [ ! -d "$dest_root" ]; then
    echo "install --all: destination root exists but is not a directory: $dest_root" >&2
    prevalidation_failed=1
  fi

  # First pass over the complete built lot: validate every candidate, derive its authoritative
  # manifest id, detect intra-lot id collisions, and classify every existing destination. Nothing
  # under dest_root is created, copied or removed until this pass has succeeded for the whole lot.
  for extension_dir in $(extension_module_dirs); do
    extension_id=$(extension_id_of_dir "$extension_dir")
    if [ "$extension_id" = "jdbc" ] && [ "$driver_jar_set" = "0" ]; then
      continue
    fi
    # Same exclusion as build --all's, and it has to be repeated here rather than inherited: this
    # loop walks extension_module_dirs itself, so without it the lot would be prevalidated against a
    # target/plugin-bundle build --all deliberately never produced, and the whole install would be
    # rejected for a bundle nobody asked for.
    if [ "$extension_id" = "$AI_EXTENSION_ID" ]; then
      continue
    fi
    source_dir="$extension_dir/target/plugin-bundle"
    if ! validation_output=$(plugin_cli validate "$source_dir" 2>&1); then
      echo "install --all: invalid candidate for $extension_id: $source_dir" >&2
      echo "$validation_output" >&2
      prevalidation_failed=1
      continue
    fi
    candidate_index=$((candidate_index + 1))
    if ! read_safe_install_manifest_id \
        "$source_dir" "$plan_dir/manifest-id.$candidate_index" "$extension_id"; then
      prevalidation_failed=1
      continue
    fi
    normalized_target=$(LC_ALL=C printf '%s' "$manifest_id" | tr 'A-Z' 'a-z')
    case "$seen_normalized_targets" in
      *"|$normalized_target|"*)
        echo "install --all: normalized target collision in candidate lot: $manifest_id" >&2
        prevalidation_failed=1
        continue
        ;;
    esac
    seen_normalized_targets="$seen_normalized_targets$normalized_target|"

    destination="$dest_root/$manifest_id"
    status=INSTALL
    if [ -e "$destination" ] || [ -L "$destination" ]; then
      if [ "$remove_existing" = "1" ]; then
        status=REPLACE
      elif directories_byte_identical "$source_dir" "$destination"; then
        status=UNCHANGED
      elif [ "$replace_existing" = "1" ]; then
        status=REPLACE
      else
        echo "install --all: refusing conflicting installed bundle: $destination differs from $source_dir" >&2
        echo "Run ./plugin.sh remove $manifest_id --dir '$dest_root', then rerun ./plugin.sh install --all with the same build options to replace it explicitly." >&2
        prevalidation_failed=1
        continue
      fi
    fi
    printf '%s\t%s\t%s\t%s\n' "$extension_id" "$manifest_id" "$source_dir" "$status" >>"$plan_file"
  done

  if [ "$prevalidation_failed" = "1" ]; then
    print_install_all_error_summary "$driver_jar_set"
    rm -rf "$plan_dir"
    return 1
  fi

  # Copy every would-be installation into private staging while the destination is still untouched.
  # A deterministic copy/read failure therefore cannot leave a half-installed lot. The subsequent
  # commit phase only publishes already-staged directories; UNCHANGED entries never move at all.
  # A replacement keeps the old directory in the private transaction area until the new directory
  # has been published, so a failed publish can restore the exact bytes it displaced.
  mkdir -p "$plan_dir/stage"
  mkdir -p "$plan_dir/backup"
  tab=$(printf '\t')
  stage_failed=0
  while IFS="$tab" read -r extension_id manifest_id source_dir status; do
    [ -n "$extension_id" ] || continue
    case "$status" in
      INSTALL|REPLACE)
        if ! cp -R "$source_dir" "$plan_dir/stage/$manifest_id"; then
          echo "install --all: cannot stage $extension_id ($manifest_id)" >&2
          stage_failed=1
        fi
        ;;
    esac
  done <"$plan_file"
  if [ "$stage_failed" = "1" ]; then
    print_install_all_error_summary "$driver_jar_set"
    rm -rf "$plan_dir"
    return 1
  fi

  if ! mkdir -p "$dest_root"; then
    echo "install --all: cannot create installation directory: $dest_root" >&2
    print_install_all_error_summary "$driver_jar_set"
    rm -rf "$plan_dir"
    return 1
  fi
  install_failed=0
  : >"$plan_dir/result"
  while IFS="$tab" read -r extension_id manifest_id source_dir status; do
    [ -n "$extension_id" ] || continue
    if [ "$status" = "INSTALL" ]; then
      if [ -e "$dest_root/$manifest_id" ] || [ -L "$dest_root/$manifest_id" ]; then
        echo "install --all: destination appeared after prevalidation: $dest_root/$manifest_id" >&2
        status=ERROR
        install_failed=1
      elif mv "$plan_dir/stage/$manifest_id" "$dest_root/$manifest_id"; then
        status=INSTALLED
      else
        echo "install --all: publish failed for $extension_id ($manifest_id)" >&2
        status=ERROR
        install_failed=1
      fi
    elif [ "$status" = "REPLACE" ]; then
      destination="$dest_root/$manifest_id"
      backup="$plan_dir/backup/$manifest_id"
      if { [ ! -e "$destination" ] && [ ! -L "$destination" ]; }; then
        echo "install --all: replacement destination disappeared after prevalidation: $destination" >&2
        status=ERROR
        install_failed=1
      elif ! mv "$destination" "$backup"; then
        echo "install --all: cannot preserve existing bundle before replacing $extension_id ($manifest_id)" >&2
        status=ERROR
        install_failed=1
      elif mv "$plan_dir/stage/$manifest_id" "$destination"; then
        status=REPLACED
      else
        echo "install --all: replacement publish failed for $extension_id ($manifest_id); restoring previous bundle" >&2
        if ! mv "$backup" "$destination"; then
          echo "install --all: automatic restore failed; previous bundle is retained at $backup" >&2
        fi
        status=ERROR
        install_failed=1
      fi
    fi
    printf '%s\t%s\t%s\n' "$extension_id" "$manifest_id" "$status" >>"$plan_dir/result"
  done <"$plan_file"

  echo "Install summary:"
  while IFS="$tab" read -r extension_id manifest_id status; do
    [ -n "$extension_id" ] || continue
    echo "  $extension_id ($manifest_id): $status"
  done <"$plan_dir/result"
  if [ "$driver_jar_set" = "0" ]; then
    echo "  jdbc: SKIPPED (requires --driver-jar and --driver-sha256)"
  fi
  if [ "$install_failed" = "1" ]; then
    echo "install --all: transaction artifacts retained for recovery at $plan_dir" >&2
    return 1
  fi
  rm -rf "$plan_dir"
  echo "Not enabled: activate bundles explicitly via RAVENROOT_ENABLED_PLUGINS (or plugins.lock.yaml) when ready."
}

cmd_install() {
  if [ $# -lt 1 ]; then
    echo "Usage: ./plugin.sh install <bundle-or-dir> [--dir plugins-dir] [--name name]" >&2
    echo "   or: ./plugin.sh install --all [-st|--skip-tests] [-r|--remove-existing] [--replace-existing|--force] [--dir plugins-dir] [--driver-jar file --driver-sha256 hex]" >&2
    exit 2
  fi
  if [ "$1" = "--all" ]; then
    shift
    cmd_install_all "$@"
    return
  fi
  source_dir=$1
  shift
  dest_root=$PLUGINS_DIR
  name_override=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --dir)
        dest_root=$2
        shift 2
        ;;
      --name)
        name_override=$2
        shift 2
        ;;
      *)
        echo "Unknown option: $1" >&2
        exit 2
        ;;
    esac
  done

  # Validated BEFORE it is copied anywhere near the convention directory: checksum verification
  # must happen before the copy, not after.
  plugin_cli validate "$source_dir"

  if [ -n "$name_override" ]; then
    name=$name_override
  else
    # The manifest's own id, never the source path's basename: `build`'s own output directory is
    # always literally named plugin-bundle (each extension module's own target/plugin-bundle, never
    # disambiguated by extension), so basename-derived naming installed every extension under the
    # same collided directory name the first time this was actually run end to end.
    name=$(plugin_cli manifest-id "$source_dir")
  fi
  mkdir -p "$dest_root"
  dest="$dest_root/$name"
  if [ -e "$dest" ]; then
    echo "Refusing to install: $dest already exists." >&2
    echo "Run ./plugin.sh remove $name first if you mean to replace it." >&2
    exit 1
  fi
  cp -R "$source_dir" "$dest"
  echo "Installed: $dest"
  echo "Not enabled: activate it explicitly via RAVENROOT_ENABLED_PLUGINS (or plugins.lock.yaml) when you are ready."
}

cmd_remove() {
  if [ $# -lt 1 ]; then
    echo "Usage: ./plugin.sh remove <id> [--dir plugins-dir]" >&2
    exit 2
  fi
  id=$1
  shift
  dest_root=$PLUGINS_DIR
  while [ $# -gt 0 ]; do
    case "$1" in
      --dir)
        dest_root=$2
        shift 2
        ;;
      *)
        echo "Unknown option: $1" >&2
        exit 2
        ;;
    esac
  done
  target="$dest_root/$id"
  if [ ! -e "$target" ]; then
    echo "Nothing to remove: $target does not exist"
    exit 0
  fi
  rm -rf "$target"
  echo "Removed: $target"
  echo "If '$id' is still named in an allowlist, the next startup refuses it as an unknown id -- the existing fail-closed check catching a stale entry, not a gap this command needs to cover itself."
}

cmd_bundle_dir() {
  if [ $# -lt 1 ]; then
    echo "Usage: ./plugin.sh bundle-dir <extension-id>" >&2
    exit 2
  fi
  module_dir=$(resolve_extension_dir "$1") || {
    echo "Unknown extension: $1" >&2
    echo "Known ids: $(known_extensions | tr '\n' ' ')" >&2
    echo "A path to any module directory containing a pom.xml also works." >&2
    exit 1
  }
  echo "$module_dir/target/plugin-bundle"
}

build_one() {
  extension_id=$1
  skip_tests=$2
  pinned_dependency_plan=$3
  package_ready=${4:-0}

  module_dir=$(resolve_extension_dir "$extension_id") || {
    echo "Unknown extension: $extension_id" >&2
    echo "Known ids: $(known_extensions | tr '\n' ' ')" >&2
    echo "A path to any module directory containing a pom.xml also works." >&2
    return 1
  }
  node_package_class=$(extension_node_package_class_of_dir "$module_dir") || return 1
  module=${module_dir#"$REACTOR_DIR/"}
  if [ ! -d "$module_dir" ]; then
    echo "Extension module not found: $module_dir" >&2
    return 1
  fi

  if [ "$package_ready" = "0" ]; then
    echo "Building $extension_id ($module)..." >&2
    skip_flag=""
    if [ "$skip_tests" = "1" ]; then
      skip_flag="-DskipTests"
    fi
    # shellcheck disable=SC2086  # $skip_flag is intentionally either empty or one literal flag
    ( cd "$REACTOR_DIR" && mvn -q -B -pl "$module" -am package $skip_flag )
  else
    echo "Assembling $extension_id bundle from the completed reactor build ($module)..." >&2
  fi

  main_jar=""
  for candidate in "$module_dir"/target/*.jar; do
    case "$(basename "$candidate")" in
      *-sources.jar|*-javadoc.jar|original-*) continue ;;
    esac
    main_jar=$candidate
    break
  done
  if [ -z "$main_jar" ] || [ ! -f "$main_jar" ]; then
    echo "No jar produced for $extension_id under $module_dir/target" >&2
    return 1
  fi

  # Third-party runtime dependencies only: ai.ravenroot.* is excluded deliberately, not gathered
  # from here at all. PluginClassLoader treats those packages as reserved and parent-first at runtime,
  # so bundling them would be both unnecessary and exactly the kind of
  # ambient-dependency inclusion the manifest's closed artifact list exists to prevent. This is
  # also why dependency:copy-dependencies resolving them correctly is not a concern here the way
  # it would be for computing this extension's OWN classpath: excluded groupIds are never
  # resolved to a path at all, stale or otherwise.
  dep_dir=$(mktemp -d "${TMPDIR:-/tmp}/plugin-build-deps.XXXXXX")
  ( cd "$REACTOR_DIR" && mvn -q -B -pl "$module" dependency:copy-dependencies \
      -DincludeScope=runtime -DexcludeGroupIds=ai.ravenroot -DoutputDirectory="$dep_dir" )
  dep_jars=""
  for jar in "$dep_dir"/*.jar; do
    [ -e "$jar" ] && dep_jars="$dep_jars $jar"
  done

  # The classpath PluginCli runs generate-manifest with is built entirely from paths this script
  # already knows, in this order: the extension's own freshly-compiled classes, then
  # ravenroot-application-api's, both direct target/classes references -- NEVER resolved through
  # a Maven dependency plugin goal. dependency:build-classpath can resolve ai.ravenroot modules to
  # stale ~/.m2 jars instead of the reactor's just-built output; putting
  # these two paths first, always, is what avoids that class of defect here rather than
  # rediscovering it.
  output_dir="$module_dir/target/plugin-bundle"
  rm -rf "$output_dir"
  generate_cp="$PLUGIN_BUNDLE_CLASSES:$APPLICATION_API_CLASSES:$module_dir/target/classes"
  for jar in $dep_jars; do
    generate_cp="$generate_cp:$jar"
  done
  if [ -n "$pinned_dependency_plan" ] && [ -s "$pinned_dependency_plan" ]; then
    # The manifest uses a generic safe bare-filename contract; JDBC narrows that to the exact
    # driverId grammar consumed by EnvironmentJdbcProfileResolver and JdbcDriverLoader. Invoke the
    # freshly compiled extension contract here so tooling and runtime cannot drift independently.
    tab=$(printf '\t')
    while IFS="$tab" read -r pinned_dependency pinned_sha256; do
      [ -n "$pinned_dependency" ] || continue
      java -cp "$module_dir/target/classes" ai.ravenroot.extensions.jdbc.JdbcDriverArtifactName \
          "$(basename "$pinned_dependency")"
      generate_cp="$generate_cp:$pinned_dependency"
    done <"$pinned_dependency_plan"
    set -- java -cp "$generate_cp" ai.ravenroot.plugin.bundle.PluginCli generate-manifest \
        "$output_dir" "$main_jar" "$node_package_class"
    for jar in $dep_jars; do
      set -- "$@" "$jar"
    done
    while IFS="$tab" read -r pinned_dependency pinned_sha256; do
      [ -n "$pinned_dependency" ] || continue
      set -- "$@" --pinned-dependency "$pinned_dependency" "$pinned_sha256"
    done <"$pinned_dependency_plan"
    "$@"
  else
    # shellcheck disable=SC2086  # $dep_jars is intentionally word-split, zero or more jar paths
    java -cp "$generate_cp" ai.ravenroot.plugin.bundle.PluginCli generate-manifest \
        "$output_dir" "$main_jar" "$node_package_class" $dep_jars
  fi

  rm -rf "$dep_dir"
  echo "$output_dir"
}

cmd_build() {
  if [ $# -lt 1 ]; then
    echo "Usage: ./plugin.sh build <extension-id>|--all [-st|--skip-tests] [--driver-jar file --driver-sha256 hex]..." >&2
    exit 2
  fi
  require_command mvn
  require_command java
  # build_one invokes PluginCli directly to generate the manifest, so the exact CLI/API classes
  # must exist here too; list/validate/install already reach this guard through plugin_cli().
  require_compiled_classes

  build_all=0
  skip_tests=0
  driver_plan=$(mktemp "${TMPDIR:-/tmp}/plugin-build-jdbc.XXXXXX")
  : >"$driver_plan"
  pending_driver_jar=""
  driver_count=0
  targets=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --all) build_all=1; shift ;;
      -st|--skip-tests) skip_tests=1; shift ;;
      --driver-jar)
        [ -z "$pending_driver_jar" ] && [ $# -ge 2 ] \
          || { echo "Each --driver-jar must be followed by one --driver-sha256" >&2; exit 2; }
        case "$2" in --*) echo "--driver-jar requires a value" >&2; exit 2 ;; esac
        pending_driver_jar=$2; shift 2 ;;
      --driver-sha256)
        [ -n "$pending_driver_jar" ] && [ $# -ge 2 ] \
          || { echo "--driver-sha256 must complete a preceding --driver-jar" >&2; exit 2; }
        case "$2" in --*) echo "--driver-sha256 requires a value" >&2; exit 2 ;; esac
        printf '%s\t%s\n' "$pending_driver_jar" "$2" >>"$driver_plan"
        pending_driver_jar=""
        driver_count=$((driver_count + 1))
        shift 2 ;;
      --*) echo "Unknown build option: $1" >&2; exit 2 ;;
      *) targets="$targets $1"; shift ;;
    esac
  done
  # --all and an explicit extension id are mutually exclusive. Before this guard, --all silently
  # WON: `targets=$(known_extensions)` below overwrote whatever the caller had just typed, with no
  # word said about it. That silent overwrite is what let `./plugin.sh build jdbc --all` sail past
  # the jdbc guard further down -- jdbc's id, typed explicitly, was already gone from $targets by
  # the time anything checked whether it had been named on purpose, so the --all discovery path ran
  # instead and skipped it as if the caller had never named it. A command that discards what you
  # typed without saying so belongs to the same defect family as the one this script exists to
  # avoid; refusing the combination outright, loudly, is the fix -- not inventing a merged meaning
  # for "build this one id, and also all the others" that nobody asked this script to have.
  if [ "$build_all" = "1" ] && [ -n "$targets" ]; then
    echo "build: --all and an explicit extension id are mutually exclusive (got --all together" \
      "with:${targets}); pass one or the other." >&2
    exit 2
  fi
  if [ "$build_all" = "1" ]; then
    targets=$(known_extensions)
  fi
  if [ -z "$targets" ]; then
    echo "Nothing to build: pass an extension id or --all" >&2
    exit 2
  fi

  if [ -n "$pending_driver_jar" ]; then
    echo "JDBC driver bundling requires both --driver-jar and --driver-sha256" >&2
    exit 2
  fi
  seen_driver_names="|"
  tab=$(printf '\t')
  while IFS="$tab" read -r driver_jar driver_sha256; do
    [ -n "$driver_jar" ] || continue
    driver_name=$(basename "$driver_jar")
    case "$driver_name" in
      *.jar) ;;
      *) echo "JDBC driver artifact must be a .jar file" >&2; exit 2 ;;
    esac
    case "$seen_driver_names" in
      *"|$driver_name|"*) echo "Duplicate JDBC driver filename/driverId: $driver_name" >&2; exit 2 ;;
    esac
    seen_driver_names="$seen_driver_names$driver_name|"
    plugin_cli verify-sha256 "$driver_jar" "$driver_sha256"
  done <"$driver_plan"

  # --all discovers jdbc the same way it discovers every other extension (known_extensions() sees
  # its NodePackage like any other), but jdbc's bundle is closed: it cannot be produced without a
  # driver jar the operator supplies. Before this fix that guard ran once, before the build loop,
  # against the FULL --all target list -- so a plain `--all` always tripped it and exited before a
  # single extension was built: not a partial build, a build that produced nothing.
  #
  # The fix below skips jdbc out of the target list when --all discovered it without a driver, and
  # says so on stderr, naming the extension and the reason -- an --all that quietly omits something
  # is the same defect family as a silent truncation. This block can only ever run against ids that
  # --all itself discovered: the mutual-exclusion guard above already rejects --all combined with
  # an explicit id, so by the time execution reaches here $targets never contains something the
  # caller typed by hand. `./plugin.sh build jdbc` on its own (no --all) never enters this block at
  # all -- it reaches the guard further down and fails loudly with the message there, exactly as
  # before this fix and as it must keep doing: silencing an explicit request would be worse than
  # the bug this block exists to close.
  if [ "$build_all" = "1" ]; then
    filtered_targets=""
    skipped_jdbc=0
    skipped_ai=0
    for extension_id in $targets; do
      module_dir=$(resolve_extension_dir "$extension_id") || {
        echo "Unknown extension: $extension_id" >&2
        exit 1
      }
      actual_id=$(extension_id_of_dir "$module_dir")
      if [ "$actual_id" = "jdbc" ] && [ "$driver_count" -eq 0 ]; then
        skipped_jdbc=1
        continue
      fi
      if [ "$actual_id" = "$AI_EXTENSION_ID" ]; then
        skipped_ai=1
        continue
      fi
      filtered_targets="$filtered_targets $extension_id"
    done
    targets=$filtered_targets
    if [ "$skipped_jdbc" = "1" ]; then
      echo "Skipping jdbc (discovered via --all): its bundle is closed and requires a driver;" \
        "--driver-jar/--driver-sha256 were not given. Build it explicitly with" \
        "./plugin.sh build jdbc --driver-jar <jar> --driver-sha256 <hex> to include it." >&2
    fi
    if [ "$skipped_ai" = "1" ]; then
      echo "Skipping $AI_EXTENSION_ID (discovered via --all): the AI node bundle is never supplied" \
        "with the product or swept into an image by a batch command. Build it explicitly with" \
        "./plugin.sh build $AI_EXTENSION_ID, then install it and name it in" \
        "RAVENROOT_ENABLED_PLUGINS." >&2
    fi
  fi

  saw_jdbc=0
  for extension_id in $targets; do
    module_dir=$(resolve_extension_dir "$extension_id") || {
      echo "Unknown extension: $extension_id" >&2
      exit 1
    }
    if [ "$(extension_id_of_dir "$module_dir")" = "jdbc" ]; then
      saw_jdbc=$((saw_jdbc + 1))
    fi
  done
  if [ "$saw_jdbc" -gt 0 ] && [ "$driver_count" -eq 0 ]; then
    echo "Building jdbc requires --driver-jar and --driver-sha256" >&2
    exit 2
  fi
  if [ "$driver_count" -gt 0 ] && [ "$saw_jdbc" -ne 1 ]; then
    echo "JDBC driver options require exactly one jdbc build target" >&2
    exit 2
  fi

  package_ready=0
  if [ "$build_all" = "1" ] && [ -n "$targets" ]; then
    reactor_modules=""
    target_count=0
    for extension_id in $targets; do
      module_dir=$(resolve_extension_dir "$extension_id") || {
        echo "Unknown extension: $extension_id" >&2
        rm -f "$driver_plan"
        exit 1
      }
      module=${module_dir#"$REACTOR_DIR/"}
      if [ -z "$reactor_modules" ]; then
        reactor_modules=$module
      else
        reactor_modules="$reactor_modules,$module"
      fi
      target_count=$((target_count + 1))
    done

    echo "Building $target_count extensions in one Maven reactor invocation..." >&2
    skip_flag=""
    if [ "$skip_tests" = "1" ]; then
      skip_flag="-DskipTests"
    fi
    # Maven accepts a comma-separated -pl selector. -am builds their shared prerequisites once,
    # so common tests no longer restart for every bundle assembled below.
    # shellcheck disable=SC2086  # $skip_flag is intentionally either empty or one literal flag
    if ! ( cd "$REACTOR_DIR" && mvn -q -B -pl "$reactor_modules" -am package $skip_flag ); then
      rm -f "$driver_plan"
      return 1
    fi
    package_ready=1
  fi

  for extension_id in $targets; do
    module_dir=$(resolve_extension_dir "$extension_id") || {
      echo "Unknown extension: $extension_id" >&2
      exit 1
    }
    actual_id=$(extension_id_of_dir "$module_dir")
    pinned_dependency_plan=""
    if [ "$actual_id" = "jdbc" ]; then
      pinned_dependency_plan=$driver_plan
    fi
    build_one "$extension_id" "$skip_tests" "$pinned_dependency_plan" "$package_ready"
  done
  rm -f "$driver_plan"
}

if [ $# -lt 1 ]; then
  usage
  exit 2
fi
command=$1
shift
case "$command" in
  list) cmd_list "$@" ;;
  build) cmd_build "$@" ;;
  validate) cmd_validate "$@" ;;
  check-published) cmd_check_published "$@" ;;
  install) cmd_install "$@" ;;
  remove) cmd_remove "$@" ;;
  bundle-dir) cmd_bundle_dir "$@" ;;
  -h|--help|help) usage ;;
  *)
    echo "Unknown command: $command" >&2
    usage
    exit 2
    ;;
esac
