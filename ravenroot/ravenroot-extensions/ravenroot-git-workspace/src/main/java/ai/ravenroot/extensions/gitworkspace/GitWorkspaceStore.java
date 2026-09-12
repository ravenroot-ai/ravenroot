package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/** Private durable layout, association records, and cross-process fencing. */
final class GitWorkspaceStore {
    private static final String VERSION = "git-workspace-state.v2";
    private static final PayloadLimits STATE_LIMITS = new PayloadLimits(16 * 1024, 3, 32, 4096, 8192, 128);
    private final Path root;
    private final Path privateRoot;
    private final Path repository;
    private final Path workspaces;
    private final Path associations;
    private final Path home;
    private final Path hooks;
    private final Path lock;
    private final UserPrincipal owner;
    private final Object lockIdentity;
    private final Map<Path, Object> directoryIdentities;

    GitWorkspaceStore(GitWorkspaceProfile profile) {
        root = profile.root();
        owner = owner(root);
        privateRoot = directory(root.resolve(".ravenroot-git-workspace-v1"), owner);
        String profileKey = digest(profile.tenant() + "\0" + profile.name() + "\0" + profile.remote());
        Path profiles = directory(privateRoot.resolve("profiles"), owner);
        Path owned = directory(profiles.resolve(profileKey), owner);
        repository = owned.resolve("repository.git");
        workspaces = directory(owned.resolve("workspaces"), owner);
        associations = directory(owned.resolve("associations"), owner);
        home = directory(owned.resolve("home"), owner);
        hooks = directory(owned.resolve("hooks-disabled"), owner);
        lock = regularFile(owned.resolve("repository.lock"), owner);
        lockIdentity = fileKey(lock);
        directoryIdentities = Map.of(root, fileKey(root), privateRoot, fileKey(privateRoot),
                profiles, fileKey(profiles), owned, fileKey(owned), workspaces, fileKey(workspaces),
                associations, fileKey(associations), home, fileKey(home), hooks, fileKey(hooks));
    }

    Path repository() { assertIntegrity(); return repository; }
    Path home() { assertIntegrity(); return home; }
    Path hooks() { assertIntegrity(); return hooks; }

    Path workspace(String taskId) {
        assertIntegrity();
        return workspaces.resolve(digest(taskId));
    }

    String workspaceIdentity(String taskId) { return digest(taskId); }

    String relativeWorkspace(String taskId) {
        return root.relativize(workspace(taskId)).toString().replace(root.getFileSystem().getSeparator(), "/");
    }

    Optional<Association> association(String taskId) {
        assertIntegrity();
        Path state = statePath(taskId);
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.isSymbolicLink(state) || !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
        try {
            if (!owner.equals(Files.getOwner(state, LinkOption.NOFOLLOW_LINKS))) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
            }
        } catch (IOException invalid) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
        try {
            return Optional.of(parse(state, taskId));
        } catch (RuntimeException invalid) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
    }

    void reserve(Association requested) {
        Optional<Association> existing = association(requested.taskId());
        if (existing.isPresent()) {
            if (!existing.get().sameBinding(requested)) conflict();
            return;
        }
        try (var stream = Files.newDirectoryStream(associations, "*.json")) {
            int count = 0;
            for (Path candidate : stream) {
                if (++count > 10_000) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
                String file = candidate.getFileName().toString();
                String task = file.substring(0, file.length() - ".json".length());
                Association other = associationByPath(candidate);
                if (other.issueBranch().equals(requested.issueBranch())
                        && !digest(requested.taskId()).equals(task)) conflict();
            }
        } catch (IOException failed) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
        write(requested);
    }

    void ready(Association association) {
        write(association.complete(Result.CONTINUE));
    }

    void save(Association association) {
        write(association);
    }

    <T> T locked(GitWorkspaceRuntime.Control control, Callable<T> operation) {
        assertIntegrity();
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock held = null;
            try {
                while (held == null) {
                    assertIntegrity();
                    control.check();
                    try { held = channel.tryLock(); }
                    catch (OverlappingFileLockException busy) { held = null; }
                    if (held == null) Thread.sleep(Math.min(25L, control.remainingMillis()));
                }
                assertIntegrity();
                return operation.call();
            } finally {
                if (held != null && held.isValid()) held.release();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            control.check();
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.CANCELLED);
        } catch (GitWorkspaceFailure failure) {
            throw failure;
        } catch (WorkspaceConflict conflict) {
            throw conflict;
        } catch (Exception failure) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
    }

    void validateWorkspacePath(Path workspace) {
        assertIntegrity();
        if (!workspace.normalize().startsWith(workspaces) || Files.isSymbolicLink(workspace)) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private Association associationByPath(Path path) {
        String file = path.getFileName().toString();
        if (!file.matches("[0-9a-f]{64}\\.json")) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
        Association result = parse(path, null);
        if (!file.equals(digest(result.taskId()) + ".json")) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
        return result;
    }

    private void write(Association association) {
        Map<String, PayloadValue> values = new LinkedHashMap<>();
        values.put("version", new PayloadValue.TextValue(VERSION));
        values.put("taskId", new PayloadValue.TextValue(association.taskId()));
        values.put("workspaceIdentity", new PayloadValue.TextValue(association.workspaceIdentity()));
        values.put("baseRevision", new PayloadValue.TextValue(association.baseRevision()));
        values.put("issueBranch", new PayloadValue.TextValue(association.issueBranch()));
        values.put("phase", new PayloadValue.TextValue(association.phase().name()));
        values.put("fenceGeneration", new PayloadValue.TextValue(Long.toString(association.fenceGeneration())));
        values.put("operationId", new PayloadValue.TextValue(association.operationId()));
        values.put("expectedRefTip", new PayloadValue.TextValue(association.expectedRefTip()));
        values.put("acceptedRevision", new PayloadValue.TextValue(association.acceptedRevision()));
        values.put("acceptedTree", new PayloadValue.TextValue(association.acceptedTree()));
        values.put("targetRefTip", new PayloadValue.TextValue(association.targetRefTip()));
        values.put("result", new PayloadValue.TextValue(association.result().name()));
        byte[] bytes = PayloadJson.write(new PayloadValue.MapValue(values)).getBytes(StandardCharsets.UTF_8);
        Path target = statePath(association.taskId());
        Path temporary = associations.resolve(".tmp-" + digest(association.taskId() + System.nanoTime()));
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
            }
            try (FileChannel file = FileChannel.open(temporary, Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE), PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
            }
            forceDirectory(associations);
        } catch (GitWorkspaceFailure failure) {
            throw failure;
        } catch (IOException failed) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private Path statePath(String taskId) {
        return associations.resolve(digest(taskId) + ".json");
    }

    private Association parse(Path state, String expectedTask) {
        try {
            if (Files.isSymbolicLink(state) || !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException();
            }
            byte[] bytes = Files.readAllBytes(state);
            PayloadValue decoded = PayloadJson.read(bytes, STATE_LIMITS);
            if (!(decoded instanceof PayloadValue.MapValue object)) throw new IllegalArgumentException();
            Map<String, PayloadValue> values = object.entries();
            Set<String> exact = Set.of("version", "taskId", "workspaceIdentity", "baseRevision", "issueBranch", "phase",
                    "fenceGeneration", "operationId", "expectedRefTip", "acceptedRevision",
                    "acceptedTree", "targetRefTip", "result");
            if (!values.keySet().equals(exact) || !VERSION.equals(text(values, "version"))) {
                throw new IllegalArgumentException();
            }
            Association result = new Association(text(values, "taskId"), text(values, "workspaceIdentity"),
                    text(values, "baseRevision"),
                    text(values, "issueBranch"), Phase.valueOf(text(values, "phase")),
                    Long.parseLong(text(values, "fenceGeneration")), text(values, "operationId"),
                    text(values, "expectedRefTip"), text(values, "acceptedRevision"),
                    text(values, "acceptedTree"), text(values, "targetRefTip"),
                    Result.valueOf(text(values, "result")));
            if (expectedTask != null && !result.taskId().equals(expectedTask)) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException | IOException invalid) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
    }

    private void assertIntegrity() {
        try {
            for (Map.Entry<Path, Object> expected : directoryIdentities.entrySet()) {
                Path path = expected.getKey();
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || Files.isSymbolicLink(path)
                        || !java.util.Objects.equals(expected.getValue(), attributes.fileKey())
                        || !owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IOException();
                }
            }
            if (Files.isSymbolicLink(lock) || !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)
                    || !java.util.Objects.equals(lockIdentity, fileKey(lock))
                    || !owner.equals(Files.getOwner(lock, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException();
            }
        } catch (IOException replaced) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private static Path directory(Path path, UserPrincipal owner) {
        try {
            try { Files.createDirectory(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------"))); }
            catch (java.nio.file.FileAlreadyExistsException concurrent) { /* validate the winner below */ }
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException();
            }
            if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException();
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failed) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private static Path regularFile(Path path, UserPrincipal owner) {
        try {
            try { Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------"))); }
            catch (java.nio.file.FileAlreadyExistsException concurrent) { /* validate the winner below */ }
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException();
            }
            if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException();
            return path;
        } catch (IOException failed) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException unsupported) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
        }
    }

    private static String text(Map<String, PayloadValue> values, String key) {
        if (!(values.get(key) instanceof PayloadValue.TextValue value)) throw new IllegalArgumentException();
        return value.value();
    }

    private static Object fileKey(Path path) {
        try {
            Object key = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            if (key == null) throw new IOException();
            return key;
        } catch (IOException unsupported) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private static UserPrincipal owner(Path path) {
        try { return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS); }
        catch (IOException unavailable) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void conflict() {
        throw new WorkspaceConflict();
    }

    enum Phase { PROVISIONING, READY, INTEGRATING, VERIFYING }
    enum Result { PENDING, CONTINUE, CONFLICT, UNMERGED }

    record Association(String taskId, String workspaceIdentity, String baseRevision, String issueBranch, Phase phase,
                       long fenceGeneration, String operationId, String expectedRefTip,
                       String acceptedRevision, String acceptedTree, String targetRefTip, Result result) {
        Association {
            if (taskId == null || !taskId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || !digest(taskId).equals(workspaceIdentity)
                    || !oid(baseRevision, false, false, 0) || !GitWorkspaceProfile.safeRef(issueBranch)
                    || phase == null || fenceGeneration < 1 || !digestValue(operationId)
                    || !oid(expectedRefTip, true, true, baseRevision.length())
                    || !oid(acceptedRevision, true, false, baseRevision.length())
                    || !oid(acceptedTree, true, false, baseRevision.length())
                    || !oid(targetRefTip, true, false, baseRevision.length()) || result == null
                    || phase == Phase.READY && result == Result.PENDING
                    || phase != Phase.READY && result != Result.PENDING
                    || phase == Phase.PROVISIONING && (!acceptedRevision.isEmpty() || !acceptedTree.isEmpty())) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.STATE_CORRUPT);
            }
        }

        static Association initial(GitWorkspaceRequest request) {
            return new Association(request.taskId(), digest(request.taskId()), request.baseRevision(), request.issueBranch(),
                    Phase.PROVISIONING, 1, operationId(request), "", "", "", "", Result.PENDING);
        }

        boolean sameBinding(Association other) {
            return taskId.equals(other.taskId) && workspaceIdentity.equals(other.workspaceIdentity)
                    && baseRevision.equals(other.baseRevision)
                    && issueBranch.equals(other.issueBranch);
        }
        Association begin(GitWorkspaceRequest request, Phase next, String expectedTip,
                          String accepted, String tree) {
            return new Association(taskId, workspaceIdentity, baseRevision, issueBranch, next, fenceGeneration + 1,
                    operationId(request), empty(expectedTip), empty(accepted), empty(tree), "", Result.PENDING);
        }

        Association withTarget(String target) {
            return new Association(taskId, workspaceIdentity, baseRevision, issueBranch, phase, fenceGeneration, operationId,
                    expectedRefTip, acceptedRevision, acceptedTree, empty(target), Result.PENDING);
        }

        Association complete(Result completed) {
            return new Association(taskId, workspaceIdentity, baseRevision, issueBranch, Phase.READY, fenceGeneration, operationId,
                    expectedRefTip, acceptedRevision, acceptedTree, targetRefTip, completed);
        }

        private static String operationId(GitWorkspaceRequest request) {
            return digest(request.operation().name() + "\0" + request.taskId() + "\0" + request.baseRevision()
                    + "\0" + request.issueBranch() + "\0" + empty(request.approvedRevision()));
        }

        private static String empty(String value) { return value == null ? "" : value; }

        private static boolean oid(String value, boolean emptyAllowed, boolean zeroAllowed, int requiredLength) {
            if (value == null || value.isEmpty()) return emptyAllowed && value != null;
            return (requiredLength == 0 ? value.length() == 40 || value.length() == 64
                    : value.length() == requiredLength) && value.matches("[0-9a-f]+")
                    && (zeroAllowed || !value.chars().allMatch(character -> character == '0'));
        }

        private static boolean digestValue(String value) {
            return value != null && value.length() == 64 && value.matches("[0-9a-f]+");
        }
    }

    static final class WorkspaceConflict extends RuntimeException {
        WorkspaceConflict() { super(null, null, false, false); }
    }
}
