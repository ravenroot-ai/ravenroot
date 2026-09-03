package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Confined provision, deterministic integration, and remote-base verification operations. */
final class GitWorkspaceService {
    private static final String REMOTE_BASE = "refs/ravenroot/base";
    private static final String RESULT_VERSION = "git-workspace.result.v1";
    private static final String IDENTITY_NAME = "Ravenroot Git Workspace";
    private static final String IDENTITY_EMAIL = "git-workspace@ravenroot.invalid";

    private final GitWorkspaceProfile profile;
    private final NodePackageServices services;
    private final NodeMessage message;
    private final GitWorkspaceRuntime.Control control;
    private final GitWorkspaceStore store;
    private final GitCommandRunner git;

    GitWorkspaceService(GitWorkspaceProfile profile, NodePackageServices services,
                        NodeMessage message, GitWorkspaceRuntime.Control control) {
        this.profile = profile;
        this.services = services;
        this.message = message;
        this.control = control;
        this.store = new GitWorkspaceStore(profile);
        this.git = new GitCommandRunner(profile, store.home(), store.hooks(), control);
    }

    NodeResult execute(GitWorkspaceRequest request) {
        try {
            return store.locked(control, () -> executeLocked(request));
        } catch (GitWorkspaceStore.WorkspaceConflict conflict) {
            return result(request, GitWorkspaceStore.Result.CONFLICT, null);
        }
    }

    private NodeResult executeLocked(GitWorkspaceRequest request) {
        control.check();
        ensureRepository();
        fetchBase();
        requireCommit(request.baseRevision());
        if (!ancestor(request.baseRevision(), REMOTE_BASE)) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.INVALID_INPUT);
        }
        return switch (request.operation()) {
            case PROVISION -> provision(request);
            case INTEGRATE -> integrate(request);
            case VERIFY -> verify(request);
        };
    }

    private NodeResult provision(GitWorkspaceRequest request) {
        Optional<GitWorkspaceStore.Association> found = store.association(request.taskId());
        GitWorkspaceStore.Association state;
        if (found.isEmpty()) {
            state = GitWorkspaceStore.Association.initial(request)
                    .begin(request, GitWorkspaceStore.Phase.PROVISIONING, zeroOid(), "", "")
                    .withTarget(request.baseRevision());
            store.reserve(state);
        } else {
            state = found.get();
            if (!state.sameBinding(GitWorkspaceStore.Association.initial(request))) conflict();
        }

        String current = refTip(request.issueBranch());
        if (state.phase() == GitWorkspaceStore.Phase.READY) {
            if (current == null || !workspaceValid(request.taskId())) conflict();
            return result(request, state.result(), current);
        }
        if (state.phase() != GitWorkspaceStore.Phase.PROVISIONING
                || !state.targetRefTip().equals(request.baseRevision())) conflict();
        if (current == null) {
            if (!updateRef(request.issueBranch(), request.baseRevision(), zeroOid())) conflict();
            current = request.baseRevision();
        }
        if (!current.equals(request.baseRevision())) conflict();
        Path workspace = store.workspace(request.taskId());
        store.validateWorkspacePath(workspace);
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            GitCommandRunner.Result add = git.run(repo("worktree", "add", "--detach",
                    workspace.toString(), request.issueBranch()));
            if (add.exitCode() != 0) conflict();
        }
        validateLinkedWorkspace(request.taskId());
        state = state.complete(GitWorkspaceStore.Result.CONTINUE);
        store.save(state);
        return result(request, GitWorkspaceStore.Result.CONTINUE, current);
    }

    private NodeResult integrate(GitWorkspaceRequest request) {
        GitWorkspaceStore.Association state = readyAssociation(request);
        Path gitDirectory = validateLinkedWorkspace(request.taskId());
        String head = worktreeOutput(gitDirectory, request.taskId(), "rev-parse", "--verify", "HEAD^{commit}");
        if (!head.equals(request.approvedRevision())) {
            return recordResult(request, state, GitWorkspaceStore.Result.CONFLICT, refTip(request.issueBranch()));
        }
        String dirty = worktreeOutput(gitDirectory, request.taskId(), "status", "--porcelain=v1",
                "--untracked-files=all");
        if (!dirty.isEmpty()) {
            return recordResult(request, state, GitWorkspaceStore.Result.CONFLICT, refTip(request.issueBranch()));
        }
        requireCommit(request.approvedRevision());
        String acceptedTree = tree(request.approvedRevision());
        String current = refTip(request.issueBranch());
        if (current == null) return recordResult(request, state, GitWorkspaceStore.Result.CONFLICT, null);

        if (state.phase() == GitWorkspaceStore.Phase.INTEGRATING
                && state.operationId().equals(operationId(request))) {
            NodeResult reconciled = reconcileIntegration(request, state, current);
            if (reconciled != null) return reconciled;
        } else if (state.phase() != GitWorkspaceStore.Phase.READY) {
            return recordResult(request, state, GitWorkspaceStore.Result.CONFLICT, current);
        }

        state = state.begin(request, GitWorkspaceStore.Phase.INTEGRATING,
                current, request.approvedRevision(), acceptedTree);
        store.save(state);

        String target;
        if (ancestor(request.approvedRevision(), current)) {
            target = current;
        } else if (ancestor(current, request.approvedRevision())) {
            target = request.approvedRevision();
        } else {
            GitCommandRunner.Result preflight = git.run(repo("merge-tree", "--write-tree", "--messages",
                    current, request.approvedRevision()));
            if (preflight.exitCode() == 1) {
                state = state.complete(GitWorkspaceStore.Result.CONFLICT);
                store.save(state);
                return result(request, GitWorkspaceStore.Result.CONFLICT, current);
            }
            if (preflight.exitCode() != 0) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
            String mergeTree = firstLineOid(preflight.stdout());
            long timestamp = Math.max(commitTimestamp(current), commitTimestamp(request.approvedRevision())) + 1;
            byte[] commitMessage = ("Integrate approved work for " + request.taskId() + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            target = git.run(repo("commit-tree", mergeTree, "-p", current, "-p", request.approvedRevision()),
                    commitMessage, null, new GitCommandRunner.CommitEnvironment(IDENTITY_NAME, IDENTITY_EMAIL,
                            "@" + timestamp + " +0000")).requireSuccess().trim();
            if (!GitWorkspaceRequest.oid(target, profile.objectFormat())) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
            }
        }

        state = state.withTarget(target);
        store.save(state);
        if (!target.equals(current) && !updateRef(request.issueBranch(), target, current)) {
            String observed = refTip(request.issueBranch());
            if (!target.equals(observed)) {
                state = state.complete(GitWorkspaceStore.Result.CONFLICT);
                store.save(state);
                return result(request, GitWorkspaceStore.Result.CONFLICT, observed);
            }
        }
        state = state.complete(GitWorkspaceStore.Result.CONTINUE);
        store.save(state);
        return result(request, GitWorkspaceStore.Result.CONTINUE, target);
    }

    private NodeResult reconcileIntegration(GitWorkspaceRequest request,
                                            GitWorkspaceStore.Association state, String current) {
        if (!state.acceptedRevision().equals(request.approvedRevision())) {
            return recordResult(request, state, GitWorkspaceStore.Result.CONFLICT, current);
        }
        if (state.targetRefTip().isEmpty()) {
            if (!current.equals(state.expectedRefTip())) {
                return recordResult(request, state, GitWorkspaceStore.Result.CONFLICT, current);
            }
            return null;
        }
        if (current.equals(state.targetRefTip())) {
            state = state.complete(GitWorkspaceStore.Result.CONTINUE);
            store.save(state);
            return result(request, GitWorkspaceStore.Result.CONTINUE, current);
        }
        if (!current.equals(state.expectedRefTip())
                || !updateRef(request.issueBranch(), state.targetRefTip(), state.expectedRefTip())) {
            return recordResult(request, state, GitWorkspaceStore.Result.CONFLICT, refTip(request.issueBranch()));
        }
        state = state.complete(GitWorkspaceStore.Result.CONTINUE);
        store.save(state);
        return result(request, GitWorkspaceStore.Result.CONTINUE, state.targetRefTip());
    }

    private NodeResult verify(GitWorkspaceRequest request) {
        GitWorkspaceStore.Association state = readyAssociation(request);
        requireCommit(request.approvedRevision());
        String acceptedTree = tree(request.approvedRevision());
        String current = refTip(request.issueBranch());
        state = state.begin(request, GitWorkspaceStore.Phase.VERIFYING,
                current == null ? "" : current, request.approvedRevision(), acceptedTree);
        store.save(state);
        boolean accepted = ancestor(request.approvedRevision(), REMOTE_BASE);
        if (!accepted) {
            String history = git.run(repo("log", "--first-parent", "--max-count=" + profile.historyScanLimit(),
                    "--format=%H %T", REMOTE_BASE)).requireSuccess();
            for (String line : history.lines().toList()) {
                String[] parts = line.split(" ", -1);
                if (parts.length != 2 || !GitWorkspaceRequest.oid(parts[0], profile.objectFormat())
                        || !GitWorkspaceRequest.oid(parts[1], profile.objectFormat())) {
                    throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
                }
                if (parts[1].equals(acceptedTree)) {
                    accepted = true;
                    break;
                }
            }
        }
        GitWorkspaceStore.Result outcome = accepted
                ? GitWorkspaceStore.Result.CONTINUE : GitWorkspaceStore.Result.UNMERGED;
        state = state.complete(outcome);
        store.save(state);
        return result(request, outcome, request.approvedRevision());
    }

    private GitWorkspaceStore.Association readyAssociation(GitWorkspaceRequest request) {
        GitWorkspaceStore.Association state = store.association(request.taskId()).orElseThrow(
                GitWorkspaceStore.WorkspaceConflict::new);
        if (!state.sameBinding(GitWorkspaceStore.Association.initial(request))) conflict();
        if (state.phase() == GitWorkspaceStore.Phase.PROVISIONING) {
            provision(new GitWorkspaceRequest(GitWorkspaceRequest.Operation.PROVISION, request.taskId(),
                    request.baseRevision(), request.issueBranch(), null));
            state = store.association(request.taskId()).orElseThrow(GitWorkspaceStore.WorkspaceConflict::new);
        }
        validateLinkedWorkspace(request.taskId());
        return state;
    }

    private NodeResult recordResult(GitWorkspaceRequest request, GitWorkspaceStore.Association state,
                                    GitWorkspaceStore.Result outcome, String revision) {
        store.save(state.complete(outcome));
        return result(request, outcome, revision);
    }

    private NodeResult result(GitWorkspaceRequest request, GitWorkspaceStore.Result result, String revision) {
        String outcome = result.name().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("version", RESULT_VERSION);
        payload.put("operation", request.operation().name().toLowerCase(java.util.Locale.ROOT));
        payload.put("taskId", request.taskId());
        payload.put("issueBranch", request.issueBranch());
        payload.put("workspace", store.relativeWorkspace(request.taskId()));
        payload.put("result", outcome);
        if (revision != null) payload.put("revision", revision);
        return new NodeResult(outcome, Map.copyOf(payload), Map.of());
    }

    private void ensureRepository() {
        Path repository = store.repository();
        if (!Files.exists(repository, LinkOption.NOFOLLOW_LINKS)) {
            GitCommandRunner.Result initialized = git.run(List.of("init", "--bare",
                    "--object-format=" + profile.objectFormat(), repository.toString()));
            if (initialized.exitCode() != 0) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
        }
        try {
            if (Files.isSymbolicLink(repository)
                    || !Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)
                    || !repository.equals(repository.toRealPath())
                    || Files.isSymbolicLink(repository.resolve("config"))
                    || !Files.isRegularFile(repository.resolve("config"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException();
            }
        } catch (IOException replaced) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
        if (!"true".equals(git.run(repo("rev-parse", "--is-bare-repository")).requireSuccess().trim())
                || !profile.objectFormat().equals(
                git.run(repo("rev-parse", "--show-object-format")).requireSuccess().trim())) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
        String configured = git.run(repo("config", "--local", "--name-only", "--null", "--list"))
                .requireSuccess();
        Set<String> allowed = Set.of("core.repositoryformatversion", "core.filemode", "core.bare",
                "core.ignorecase", "core.precomposeunicode", "core.logallrefupdates", "extensions.objectformat",
                "user.name", "user.email");
        for (String key : configured.split("\\x00", -1)) {
            if (!key.isEmpty() && !allowed.contains(key)) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
            }
        }
        if (!"true".equals(git.run(repo("config", "--local", "--get", "core.bare"))
                .requireSuccess().trim())
                || !"0".equals(git.run(repo("config", "--local", "--get", "core.repositoryformatversion"))
                .requireSuccess().trim())) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
    }

    private void fetchBase() {
        try (GitCredentialSession credential = GitCredentialSession.open(
                profile, store, services, message, git, control)) {
            String helper = credential == null ? null : credential.helper();
            GitCommandRunner.Result fetched = git.run(repo("fetch", "--no-tags", "--no-recurse-submodules",
                    "--no-write-fetch-head", profile.remote(), "+" + profile.baseRef() + ":" + REMOTE_BASE),
                    null, helper);
            if (fetched.exitCode() != 0) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
        }
    }

    private void requireCommit(String oid) {
        if (git.run(repo("cat-file", "-e", oid + "^{commit}")).exitCode() != 0) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.INVALID_INPUT);
        }
    }

    private boolean ancestor(String older, String newer) {
        GitCommandRunner.Result result = git.run(repo("merge-base", "--is-ancestor", older, newer));
        if (result.exitCode() == 0) return true;
        if (result.exitCode() == 1) return false;
        throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
    }

    private String tree(String commit) {
        String value = git.run(repo("rev-parse", "--verify", commit + "^{tree}")).requireSuccess().trim();
        if (!GitWorkspaceRequest.oid(value, profile.objectFormat())) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
        }
        return value;
    }

    private long commitTimestamp(String commit) {
        String value = git.run(repo("show", "-s", "--format=%ct", commit)).requireSuccess().trim();
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED); }
    }

    private String refTip(String ref) {
        GitCommandRunner.Result result = git.run(repo("rev-parse", "--verify", "--quiet", ref + "^{commit}"));
        if (result.exitCode() == 1) return null;
        if (result.exitCode() != 0) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
        String value = result.stdout().trim();
        if (!GitWorkspaceRequest.oid(value, profile.objectFormat())) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
        }
        return value;
    }

    private boolean updateRef(String ref, String target, String expected) {
        return git.run(repo("update-ref", "--no-deref", ref, target, expected)).exitCode() == 0;
    }

    private Path validateLinkedWorkspace(String taskId) {
        Path workspace = store.workspace(taskId);
        store.validateWorkspacePath(workspace);
        Path marker = workspace.resolve(".git");
        try {
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) > 4096) throw new IOException();
            String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (!value.startsWith("gitdir: ") || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IOException();
            }
            Path gitDirectory = Path.of(value.substring("gitdir: ".length()));
            if (!gitDirectory.isAbsolute()) gitDirectory = workspace.resolve(gitDirectory);
            gitDirectory = gitDirectory.normalize();
            Path allowed = store.repository().resolve("worktrees").toRealPath();
            Path real = gitDirectory.toRealPath();
            if (Files.isSymbolicLink(gitDirectory) || !real.startsWith(allowed) || !Files.isDirectory(real)) {
                throw new IOException();
            }
            return real;
        } catch (IOException | RuntimeException invalid) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private boolean workspaceValid(String taskId) {
        try { validateLinkedWorkspace(taskId); return true; }
        catch (GitWorkspaceFailure invalid) { return false; }
    }

    private String worktreeOutput(Path gitDirectory, String taskId, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("--git-dir=" + gitDirectory);
        command.add("--work-tree=" + store.workspace(taskId));
        command.addAll(List.of(arguments));
        return git.run(command).requireSuccess().stripTrailing();
    }

    private List<String> repo(String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("--git-dir=" + store.repository());
        command.addAll(List.of(arguments));
        return command;
    }

    private String zeroOid() {
        return "0".repeat("sha256".equals(profile.objectFormat()) ? 64 : 40);
    }

    private String firstLineOid(String output) {
        String value = output.lines().findFirst().orElse("").trim();
        if (!GitWorkspaceRequest.oid(value, profile.objectFormat())) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
        }
        return value;
    }

    private static String operationId(GitWorkspaceRequest request) {
        return GitWorkspaceStore.digest(request.operation().name() + "\0" + request.taskId() + "\0"
                + request.baseRevision() + "\0" + request.issueBranch() + "\0"
                + (request.approvedRevision() == null ? "" : request.approvedRevision()));
    }

    private static <T> T conflict() {
        throw new GitWorkspaceStore.WorkspaceConflict();
    }
}
