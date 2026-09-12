package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.CredentialLease;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Invocation-owned credential cache whose secret enters Git only over a private stdin/socket channel. */
final class GitCredentialSession implements AutoCloseable {
    private final GitWorkspaceProfile profile;
    private final GitCommandRunner git;
    private final GitWorkspaceRuntime.Control control;
    private final Path directory;
    private final Path socket;
    private final String helper;
    private final Process daemon;
    private final Object directoryIdentity;
    private Object socketIdentity;

    private GitCredentialSession(GitWorkspaceProfile profile, GitCommandRunner git, GitWorkspaceRuntime.Control control,
                                 Path directory, Path socket, Process daemon) {
        this.profile = profile;
        this.git = git;
        this.control = control;
        this.directory = directory;
        this.socket = socket;
        this.helper = "cache --socket=" + shellQuote(socket.toString());
        this.daemon = daemon;
        try {
            this.directoryIdentity = Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            if (directoryIdentity == null) throw new IOException();
        } catch (IOException invalid) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    static GitCredentialSession open(GitWorkspaceProfile profile, GitWorkspaceStore store,
                                     NodePackageServices services, NodeMessage message,
                                     GitCommandRunner git, GitWorkspaceRuntime.Control control) {
        if (profile.credentialRef() == null) return null;
        OutboundCall<CredentialLease> resolving = services.credentials().resolve(message,
                profile.credentialRef(), Duration.ofMillis(control.remainingMillis()));
        control.credential(resolving);
        CredentialLease lease;
        try {
            lease = resolving.completion().toCompletableFuture().get(control.remainingMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException caught) {
            Thread.currentThread().interrupt();
            resolving.cancel();
            control.check();
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.CANCELLED);
        } catch (TimeoutException timeout) {
            resolving.cancel();
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.DEADLINE_EXCEEDED);
        } catch (ExecutionException | RuntimeException refused) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
        control.credential(null);
        try (lease) {
            char[] secret = lease.copy();
            try {
                return start(profile, store, git, control, secret);
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
    }

    private static GitCredentialSession start(GitWorkspaceProfile profile, GitWorkspaceStore store,
                                              GitCommandRunner git, GitWorkspaceRuntime.Control control,
                                              char[] secret) {
        for (char value : secret) {
            if (value == '\0' || value == '\n' || value == '\r') {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
            }
        }
        Path directory;
        try {
            directory = Files.createTempDirectory(store.home(), "credential-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (Files.isSymbolicLink(directory)
                    || !directory.equals(directory.toRealPath())) throw new IOException();
            UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
            if (!owner.equals(Files.getOwner(store.home(), LinkOption.NOFOLLOW_LINKS))) throw new IOException();
        } catch (IOException | UnsupportedOperationException failed) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
        Path socket = directory.resolve("socket");
        Process daemon;
        try {
            daemon = git.startDaemon(List.of("credential-cache--daemon", socket.toString()));
        } catch (RuntimeException failure) {
            try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
            throw failure;
        }
        GitCredentialSession session;
        try {
            session = new GitCredentialSession(profile, git, control, directory, socket, daemon);
        } catch (RuntimeException failed) {
            daemon.destroyForcibly();
            try { daemon.waitFor(1, TimeUnit.SECONDS); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            try { Files.deleteIfExists(socket); } catch (IOException ignored) { }
            try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
            throw failed;
        }
        byte[] input = credentialInput(profile, secret, true);
        try {
            session.awaitSocket();
            session.validateSocket();
            git.runSecret(List.of("credential", "approve"), input, session.helper()).requireSuccess();
            return session;
        } catch (RuntimeException failure) {
            session.close();
            throw failure;
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    String helper() {
        validateSocket();
        return helper;
    }

    long daemonPid() {
        return daemon.pid();
    }

    @Override
    public void close() {
        boolean interrupted = false;
        try {
            if (daemon.isAlive()) {
                byte[] reject = credentialInput(profile, new char[0], false);
                try { git.runSecret(List.of("credential", "reject"), reject, helper()).requireSuccess(); }
                finally { Arrays.fill(reject, (byte) 0); }
                git.run(List.of("credential-cache", "--socket=" + socket, "exit"));
                daemon.waitFor(Math.min(1_000, control.remainingMillis()), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException caught) {
            interrupted = true;
        } catch (RuntimeException ignored) {
            // The invocation control performs the authoritative descendant reap before completion.
        } finally {
            if (daemon.isAlive()) daemon.destroyForcibly();
            try {
                if (!daemon.waitFor(2, TimeUnit.SECONDS)) {
                    throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
                }
            } catch (InterruptedException caught) {
                interrupted = true;
                daemon.destroyForcibly();
                try { daemon.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException repeated) { interrupted = true; }
            }
            control.settled(daemon);
            try {
                Files.deleteIfExists(socket);
                Files.delete(directory);
            } catch (IOException cleanupFailed) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitSocket() {
        while (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            control.check();
            if (!daemon.isAlive()) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
            try { Thread.sleep(Math.min(5, control.remainingMillis())); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                control.check();
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.CANCELLED);
            }
        }
        if (Files.isSymbolicLink(socket)) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        try {
            socketIdentity = Files.readAttributes(socket, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            if (socketIdentity == null) throw new IOException();
        } catch (IOException invalid) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private void validateSocket() {
        try {
            var directoryAttributes = Files.readAttributes(directory,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var socketAttributes = Files.readAttributes(socket,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object modeValue = Files.getAttribute(socket, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            if (!(modeValue instanceof Number number) || (number.intValue() & 0170000) != 0140000
                    || Files.isSymbolicLink(socket) || !java.util.Objects.equals(directoryIdentity,
                    directoryAttributes.fileKey()) || !java.util.Objects.equals(socketIdentity,
                    socketAttributes.fileKey()) || !Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS)
                    .equals(Files.getOwner(socket, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException();
            }
        } catch (IOException | UnsupportedOperationException invalid) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    static byte[] credentialInput(GitWorkspaceProfile profile, char[] secret, boolean password) {
        URI remote = URI.create(profile.remote());
        String path = remote.getRawPath();
        while (path.startsWith("/")) path = path.substring(1);
        String fields = "protocol=https\nhost=" + remote.getRawAuthority() + "\npath=" + path
                + "\nusername=" + profile.credentialUsername() + (password ? "\npassword=" : "");
        byte[] prefix = fields.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = encode(secret);
        byte[] result = Arrays.copyOf(prefix, prefix.length + encoded.length + 2);
        System.arraycopy(encoded, 0, result, prefix.length, encoded.length);
        result[result.length - 2] = '\n';
        result[result.length - 1] = '\n';
        Arrays.fill(prefix, (byte) 0);
        Arrays.fill(encoded, (byte) 0);
        return result;
    }

    private static byte[] encode(char[] value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            if (bytes.hasArray()) Arrays.fill(bytes.array(), (byte) 0);
            return result;
        } catch (CharacterCodingException invalid) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
