# Node runtime selection required by .nvmrc — single source of truth.
#
# This is not an executable: it is included with `.` (dot) by a script that has already defined
# PROJECT_DIR, the repository root. It lives here rather than in service.sh because two distinct
# scripts build the same UI: service.sh before the container image, dev.sh before the development
# bench. With two copies, one becomes stale, and the symptom — a UI built with a Node version other
# than CI uses — never identifies the stale copy.
#
# It does not modify the system installation: it looks for the required Node where it already lives
# (nvm, a versioned Homebrew keg) and prepends it to the current process PATH only. If it cannot
# find it, it explains how to install it and exits instead of building with the wrong version.

select_node_runtime() {
  required_major=$(tr -d '[:space:]' < "$PROJECT_DIR/.nvmrc")
  current_version=$(node --version 2>/dev/null || true)
  case "$current_version" in
    v"$required_major".*) return 0 ;;
  esac

  # Respect an existing nvm installation without requiring the caller to remember `nvm use`.
  nvm_dir=${NVM_DIR:-"$HOME/.nvm"}
  if [ -s "$nvm_dir/nvm.sh" ]; then
    # nvm is not guaranteed to be nounset-clean while it initialises.
    set +u
    # shellcheck disable=SC1090
    . "$nvm_dir/nvm.sh"
    nvm use "$required_major" >/dev/null 2>&1 || true
    set -u
  fi

  # Homebrew installs versioned formulae as keg-only. Prefer the requested keg when present;
  # `brew install node@24` therefore works without globally relinking or replacing another Node.
  if command -v brew >/dev/null 2>&1; then
    brew_node_prefix=$(brew --prefix "node@$required_major" 2>/dev/null || true)
    if [ -n "$brew_node_prefix" ] && [ -x "$brew_node_prefix/bin/node" ]; then
      PATH="$brew_node_prefix/bin:$PATH"
      export PATH
    fi
  fi

  current_version=$(node --version 2>/dev/null || true)
  case "$current_version" in
    v"$required_major".*) return 0 ;;
  esac
  echo "Ravenroot UI requires Node $required_major.x from .nvmrc; found ${current_version:-no Node runtime}." >&2
  echo "Install it (for example: 'brew install node@$required_major' or 'nvm install $required_major') and retry." >&2
  exit 2
}
