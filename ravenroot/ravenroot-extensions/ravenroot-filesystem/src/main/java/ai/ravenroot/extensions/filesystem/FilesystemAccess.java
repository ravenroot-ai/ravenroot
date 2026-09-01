package ai.ravenroot.extensions.filesystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Descriptor-relative filesystem primitives. No target path is resolved to an ambient absolute path. */
final class FilesystemAccess {
    static final String TEMP_PREFIX = FilesystemTempNames.PREFIX;
    private static final ReentrantLock[] TARGET_LOCKS = locks();
    private final Hooks hooks;

    FilesystemAccess() { this(Hooks.NONE); }
    FilesystemAccess(Hooks hooks) { this.hooks = hooks; }

    record Read(byte[] body, String sha256) { }
    record Write(long bytes, String sha256, boolean replaced) { }

    Read read(FilesystemProfile profile, FilesystemPaths.Parsed path, long maxBytes, InvocationState state) {
        try (OpenedParent opened = open(profile, path)) {
            state.requireRunning();
            hooks.afterParentOpened(path);
            cleanupExpiredTemps(profile, opened.parent);
            rejectSymlink(opened.parent, path.leaf());
            requireRegularFile(opened.parent, path.leaf());
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = opened.parent.newByteChannel(path.leaf(), options)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 8192));
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                long total = 0;
                while (true) {
                    state.requireRunning();
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), maxBytes - total + 1));
                    int count = channel.read(buffer);
                    if (count < 0) break;
                    if (count == 0) continue;
                    total += count;
                    if (total > maxBytes) throw FilesystemNodeException.of(FilesystemNodeException.Reason.TOO_LARGE);
                    output.write(buffer.array(), 0, count);
                }
                byte[] bytes = output.toByteArray();
                return new Read(bytes, sha256(bytes));
            }
        } catch (FilesystemNodeException typed) {
            throw typed;
        } catch (NoSuchFileException missing) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.NOT_FOUND);
        } catch (IOException unavailable) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.TEMPORARY_IO, unavailable);
        }
    }

    Write write(FilesystemProfile profile, FilesystemPaths.Parsed path, byte[] body, WriteMode mode,
                InvocationState state) {
        try (OpenedParent opened = open(profile, path)) {
            state.requireRunning();
            hooks.afterParentOpened(path);
            cleanupExpiredTemps(profile, opened.parent);
            rejectSymlink(opened.parent, path.leaf());
            Path temporary = FilesystemTempNames.create(profile, path.leaf());
            boolean tempPresent = false;
            try {
                try (SeekableByteChannel channel = opened.parent.newByteChannel(temporary,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    tempPresent = true;
                    ByteBuffer source = ByteBuffer.wrap(body);
                    while (source.hasRemaining()) {
                        state.requireRunning();
                        channel.write(source);
                    }
                    if (!(channel instanceof FileChannel fileChannel)) {
                        throw FilesystemNodeException.of(FilesystemNodeException.Reason.SECURITY_UNSUPPORTED);
                    }
                    fileChannel.force(true);
                }
                ReentrantLock lock = targetLock(profile, path);
                lock.lockInterruptibly();
                try {
                    state.requireRunning();
                    hooks.beforeMove(path);
                    rejectSymlink(opened.parent, path.leaf());
                    boolean targetExists = exists(opened.parent, path.leaf());
                    if (mode == WriteMode.CREATE_NEW && targetExists) {
                        throw FilesystemNodeException.of(FilesystemNodeException.Reason.CONFLICT);
                    }
                    if (mode == WriteMode.REPLACE && !targetExists) {
                        throw FilesystemNodeException.of(FilesystemNodeException.Reason.NOT_FOUND);
                    }
                    if (!state.beginMove()) throw FilesystemNodeException.of(FilesystemNodeException.Reason.TIMEOUT);
                    hooks.afterMoveBegan(path);
                    try {
                        opened.parent.move(temporary, opened.parent, path.leaf());
                        tempPresent = false;
                        state.moveCompleted();
                    } catch (FileAlreadyExistsException conflict) {
                        state.moveFailed();
                        if (mode == WriteMode.REPLACE) {
                            throw FilesystemNodeException.of(
                                    FilesystemNodeException.Reason.ATOMIC_REPLACE_UNSUPPORTED, conflict);
                        }
                        throw FilesystemNodeException.of(FilesystemNodeException.Reason.CONFLICT);
                    } catch (IOException unknown) {
                        state.moveFailed();
                        throw FilesystemNodeException.of(FilesystemNodeException.Reason.AMBIGUOUS_FINAL_MOVE, unknown);
                    }
                } finally {
                    lock.unlock();
                }
                return new Write(body.length, sha256(body), mode == WriteMode.REPLACE);
            } finally {
                if (tempPresent) deleteTemp(profile, opened.parent, temporary);
            }
        } catch (FilesystemNodeException typed) {
            throw typed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.TIMEOUT);
        } catch (IOException unavailable) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.TEMPORARY_IO, unavailable);
        }
    }

    private OpenedParent open(FilesystemProfile profile, FilesystemPaths.Parsed path) throws IOException {
        hooks.beforeRootOpen(path);
        if (java.nio.file.Files.isSymbolicLink(profile.root())) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.SYMLINK_REFUSED);
        }
        DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(profile.root());
        if (!(stream instanceof SecureDirectoryStream<Path> root)) {
            stream.close();
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.SECURITY_UNSUPPORTED);
        }
        List<SecureDirectoryStream<Path>> handles = new ArrayList<>();
        handles.add(root);
        SecureDirectoryStream<Path> current = root;
        try {
            BasicFileAttributeView rootView = root.getFileAttributeView(BasicFileAttributeView.class);
            Object openedRootKey = rootView == null ? null : rootView.readAttributes().fileKey();
            if (profile.rootFileKey() == null || openedRootKey == null) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.SECURITY_UNSUPPORTED);
            }
            if (!profile.rootFileKey().equals(openedRootKey)) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.OUTSIDE_ROOT);
            }
            for (String component : path.parents()) {
                Path relative = profile.root().getFileSystem().getPath(component);
                rejectSymlink(current, relative);
                SecureDirectoryStream<Path> next = current.newDirectoryStream(relative, LinkOption.NOFOLLOW_LINKS);
                handles.add(next);
                current = next;
            }
            return new OpenedParent(current, handles);
        } catch (IOException | RuntimeException failed) {
            closeReverse(handles);
            throw failed;
        }
    }

    private static void rejectSymlink(SecureDirectoryStream<Path> parent, Path leaf) throws IOException {
        BasicFileAttributeView view = parent.getFileAttributeView(leaf, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        try {
            if (view.readAttributes().isSymbolicLink()) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.SYMLINK_REFUSED);
            }
        } catch (NoSuchFileException absent) {
            // Absence is valid here: read/open or the selected write mode classifies it later.
        }
    }

    private static boolean exists(SecureDirectoryStream<Path> parent, Path leaf) throws IOException {
        try {
            BasicFileAttributes attributes = parent.getFileAttributeView(leaf, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (attributes.isSymbolicLink()) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.SYMLINK_REFUSED);
            }
            if (!attributes.isRegularFile()) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.AUTHORITY_REFUSED);
            }
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    private static void requireRegularFile(SecureDirectoryStream<Path> parent, Path leaf) throws IOException {
        try {
            BasicFileAttributes attributes = parent.getFileAttributeView(leaf, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (attributes.isSymbolicLink()) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.SYMLINK_REFUSED);
            }
            if (!attributes.isRegularFile()) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.AUTHORITY_REFUSED);
            }
        } catch (NoSuchFileException absent) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.NOT_FOUND);
        }
    }

    private static void deleteTemp(FilesystemProfile profile, SecureDirectoryStream<Path> parent, Path temporary) {
        if (!FilesystemTempNames.isOwnedBy(profile, temporary)) return;
        try {
            rejectSymlink(parent, temporary);
            parent.deleteFile(temporary);
        } catch (IOException | RuntimeException ignored) {
            // Cleanup is best effort. A later sweep only considers the exact owned grammar.
        }
    }

    private static void cleanupExpiredTemps(FilesystemProfile profile, SecureDirectoryStream<Path> parent) {
        FileTime cutoff = FileTime.fromMillis(System.currentTimeMillis() - java.time.Duration.ofHours(24).toMillis());
        int inspected = 0;
        try {
            for (Path listed : parent) {
                if (++inspected > 10_000) return;
                Path name = listed.getFileName();
                if (!FilesystemTempNames.isOwnedBy(profile, name)) continue;
                BasicFileAttributes attributes;
                try {
                    attributes = parent.getFileAttributeView(name, BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).readAttributes();
                } catch (NoSuchFileException raced) {
                    continue;
                }
                if (attributes.isRegularFile() && attributes.lastModifiedTime().compareTo(cutoff) < 0) {
                    try { parent.deleteFile(name); } catch (IOException raced) { /* leave for a later bounded sweep */ }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Cleanup cannot widen authority or make the requested operation less safe.
        }
    }

    private static ReentrantLock targetLock(FilesystemProfile profile, FilesystemPaths.Parsed path) {
        int hash = 31 * profile.root().hashCode() + path.relative().hashCode();
        return TARGET_LOCKS[hash & (TARGET_LOCKS.length - 1)];
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
        return locks;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void closeReverse(List<SecureDirectoryStream<Path>> handles) {
        for (int index = handles.size() - 1; index >= 0; index--) {
            try { handles.get(index).close(); } catch (IOException ignored) { }
        }
    }

    private record OpenedParent(SecureDirectoryStream<Path> parent,
                                List<SecureDirectoryStream<Path>> handles) implements AutoCloseable {
        @Override public void close() { closeReverse(handles); }
    }

    enum WriteMode { CREATE_NEW, REPLACE }

    interface Hooks {
        Hooks NONE = new Hooks() { };
        default void beforeRootOpen(FilesystemPaths.Parsed path) { }
        default void afterParentOpened(FilesystemPaths.Parsed path) { }
        default void beforeMove(FilesystemPaths.Parsed path) { }
        default void afterMoveBegan(FilesystemPaths.Parsed path) { }
    }

    static final class InvocationState {
        private enum Phase { RUNNING, TIMED_OUT, MOVING, MOVE_COMPLETED, MOVE_FAILED, DONE }
        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);

        void requireRunning() {
            if (phase.get() != Phase.RUNNING) throw FilesystemNodeException.of(FilesystemNodeException.Reason.TIMEOUT);
        }
        boolean beginMove() { return phase.compareAndSet(Phase.RUNNING, Phase.MOVING); }
        void moveCompleted() { phase.compareAndSet(Phase.MOVING, Phase.MOVE_COMPLETED); }
        void moveFailed() { phase.compareAndSet(Phase.MOVING, Phase.MOVE_FAILED); }
        boolean timeout() { return phase.compareAndSet(Phase.RUNNING, Phase.TIMED_OUT); }
        boolean moving() { return phase.get() == Phase.MOVING; }
        void finish() { phase.compareAndSet(Phase.RUNNING, Phase.DONE); }
    }
}
