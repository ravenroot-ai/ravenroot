# Ravenroot confined Git workspace extension

This optional, independently installable node package contributes the single `git-workspace`
behavior. It provisions one durable workspace from an immutable remote base, integrates an approved
workspace commit into its one issue branch, and verifies that the accepted commit or identical tree
content is present in freshly fetched configured base history.

The graph selects only an opaque `workspaceProfile`. Its payload cannot name a filesystem root,
remote, executable, credential, ref prefix, deadline, output ceiling, concurrency ceiling, object
format, or history-scan bound. The package is provider-neutral: it invokes Git's local transport and
object/ref operations and contains no GitHub API, pull-request, publication, or human-task behavior.

## Operator profile

The tenant-scoped environment key is
`RAVENROOT_GIT_WORKSPACE_PROFILE_<TENANT_HEX>_<PROFILE_HEX>`, using the strict upper-case UTF-8 hex
encoding from `EnvironmentKeyCodec`. Its value is canonical padded Base64 containing one strict JSON
object:

```json
{
  "root": "/srv/ravenroot/git-authority",
  "remote": "https://scm.example.invalid/team/repository.git",
  "baseRef": "refs/heads/dev",
  "issueRefPrefix": "refs/heads/issues/",
  "gitExecutable": "/usr/bin/git",
  "objectFormat": "sha1",
  "deadlineMs": 30000,
  "maxConcurrency": 4,
  "maxOutputBytes": 262144,
  "historyScanLimit": 1000,
  "credentialRef": "opaque-credential-reference",
  "credentialUsername": "git"
}
```

`credentialRef` and `credentialUsername` must either both be present or both be absent. HTTPS and
credentialless absolute `file:` remotes are supported. URL user-info, query strings and fragments
are refused. The root and executable must already exist as ordinary absolute paths. The root must be
dedicated to this package and writable only by the Ravenroot operator identity. Object format is
`sha1` or `sha256`; deadlines are 100 ms through five minutes; concurrency is 1 through 64; output is
1 KiB through 1 MiB; and verification scans 1 through 10,000 first-parent commits.

The package declares `credential-resolution` as a required operator service. A credentialless
profile never resolves a lease. A credentialed fetch resolves its opaque reference only after the
cross-process repository lock is held, writes the one-use secret only to `git credential approve`
stdin, and exposes it to Git through an invocation-owned Unix credential-cache socket in a mode-0700
directory. The secret is never placed in argv, environment variables, URLs, Git config, graph data,
payloads, results, durable state, or diagnostics. The cache is rejected, stopped, reaped, and removed
before completion. Platforms that cannot prove the directory owner, Unix socket type, and file
identities fail closed.

## Payloads and results

Every request has `contract: "git-workspace.v1"`, `taskId`, a full lower-case `baseRevision`, and an
`issueBranch` within the configured prefix. Operations add only the following field:

- `provision` adds no field. The requested base must be reachable from the freshly fetched base ref.
- `integrate` adds `approvedRevision`. It must be the clean workspace `HEAD`.
- `verify` adds `acceptedRevision`. Ravenroot computes its tree; callers cannot supply a reviewed
  tree, patch identifier, provider metadata, or fuzzy match.

Results use `git-workspace.result.v1`, repeat the operation, task and issue branch, expose only the
root-relative private workspace name and a non-sensitive revision, and select `continue`, `conflict`,
or `unmerged`. Failures expose stable reason tokens and never raw Git output.

Provision records ownership before creating the issue ref and uses a compare-and-swap from the null
OID. Integration checks a merge with `merge-tree`, creates a deterministic two-parent commit when
needed, persists its expected and target tips, then compare-and-swaps only the recorded issue ref.
Conflicts and concurrent ref movement do not mutate that ref. Verification fetches the configured
base again, accepts the commit when it is reachable, or compares its internally computed full tree
with the bounded first-parent history. No patch, path subset, or provider-side status is equivalent.

Association records atomically retain the task/base/branch binding, operation identity and phase,
fence generation, expected and target tips, accepted commit and tree, and reconciled outcome. A
restart therefore completes an already-published CAS, resumes a recorded safe CAS, or returns
`conflict` without guessing. Schema versions, exact keys, task-to-filename digests, directory
identities, linked-worktree Git directories, executable identity, and repository-local config are
validated before use.

## Confinement and lifecycle

Every repository command names an explicit `--git-dir`; workspace commands also name an explicit
`--work-tree`. The child environment is rebuilt from a minimal allowlist. System/global config,
terminal prompts, ambient credential helpers, hooks, fsmonitor, external diff/merge drivers,
submodules, maintenance, signing, redirects and protocols other than the configured HTTPS or file
transport are disabled. The repository config has a small allowlist, preventing URL rewrites,
filters, helpers, hooks, and arbitrary commands from becoming durable ambient behavior.

Provisioned workspaces and their association are intentionally retained. Version 1 has no cleanup
operation and performs no automatic destructive workspace or ref cleanup. Operators may inspect and
remove a validated association, workspace, and issue ref under their own change procedure. Ravenroot
removes only its exact invocation-owned credential socket directory. Cancellation and timeout cancel
credential resolution, terminate and forcibly reap the invocation's known descendant process tree,
drain bounded stdout/stderr concurrently, and release locks and concurrency permits only after the
owned tree is gone. Unrelated sibling processes are never targeted.

Focused verification:

```sh
mvn -f ravenroot/pom.xml -pl ravenroot-extensions/ravenroot-git-workspace -am \
  '-Dtest=GitWorkspace*Test,NodeActionBinaryCompatibilityTest,NodeActionCancellationForwardingTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
