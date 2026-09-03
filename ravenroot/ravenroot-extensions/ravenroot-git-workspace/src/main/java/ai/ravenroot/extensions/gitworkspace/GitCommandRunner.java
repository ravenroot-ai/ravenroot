package ai.ravenroot.extensions.gitworkspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Executes only constructed Git commands with a scrubbed environment and bounded output. */
final class GitCommandRunner {
    private final GitWorkspaceProfile profile;
    private final Path home;
    private final Path hooks;
    private final GitWorkspaceRuntime.Control control;
    private final Object executableIdentity;

    GitCommandRunner(GitWorkspaceProfile profile, Path home, Path hooks,
                     GitWorkspaceRuntime.Control control) {
        this.profile = profile;
        this.home = home;
        this.hooks = hooks;
        this.control = control;
        try {
            this.executableIdentity = Files.readAttributes(profile.gitExecutable(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            if (executableIdentity == null) throw new IOException();
        } catch (IOException unsupported) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    Result run(List<String> arguments) {
        return run(arguments, null, null, null);
    }

    Result run(List<String> arguments, byte[] input, String credentialHelper) {
        return run(arguments, input, credentialHelper, null);
    }

    Result runSecret(List<String> arguments, byte[] input, String credentialHelper) {
        try {
            return run(arguments, input, credentialHelper, null, true);
        } finally {
            if (input != null) Arrays.fill(input, (byte) 0);
        }
    }

    Result run(List<String> arguments, byte[] input, String credentialHelper,
               CommitEnvironment commitEnvironment) {
        return run(arguments, input, credentialHelper, commitEnvironment, false);
    }

    private Result run(List<String> arguments, byte[] input, String credentialHelper,
                       CommitEnvironment commitEnvironment, boolean rejectOutput) {
        control.check();
        ProcessBuilder builder = builder(arguments, credentialHelper);
        Map<String, String> environment = builder.environment();
        if (commitEnvironment != null) {
            environment.put("GIT_AUTHOR_NAME", commitEnvironment.name());
            environment.put("GIT_AUTHOR_EMAIL", commitEnvironment.email());
            environment.put("GIT_AUTHOR_DATE", commitEnvironment.date());
            environment.put("GIT_COMMITTER_NAME", commitEnvironment.name());
            environment.put("GIT_COMMITTER_EMAIL", commitEnvironment.email());
            environment.put("GIT_COMMITTER_DATE", commitEnvironment.date());
        }
        final Process process;
        try {
            process = builder.start();
        } catch (IOException unavailable) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_UNAVAILABLE);
        } finally {
            environment.clear();
        }
        control.own(process);
        AtomicLong total = new AtomicLong();
        AtomicBoolean overflow = new AtomicBoolean();
        ByteArrayOutputStream stdout = rejectOutput ? null : new ByteArrayOutputStream();
        Thread out = drain(process.getInputStream(), stdout, total, overflow);
        Thread err = drain(process.getErrorStream(), null, total, overflow);
        try {
            try (var stdin = process.getOutputStream()) {
                if (input != null) stdin.write(input);
            } finally {
                if (rejectOutput && input != null) Arrays.fill(input, (byte) 0);
            }
            int exit = process.waitFor();
            joinDrain(out);
            joinDrain(err);
            control.check();
            if (overflow.get()) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.OUTPUT_LIMIT_EXCEEDED);
            }
            if (rejectOutput && total.get() != 0) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
            }
            return new Result(exit, stdout == null ? "" : stdout.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            control.terminateProcess();
            control.check();
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.CANCELLED);
        } catch (IOException failed) {
            control.terminateProcess();
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
        } finally {
            control.settled(process);
        }
    }

    Process startDaemon(List<String> arguments) {
        control.check();
        ProcessBuilder builder = builder(arguments, null);
        final Process process;
        try {
            process = builder.start();
        } catch (IOException unavailable) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_UNAVAILABLE);
        } finally {
            builder.environment().clear();
        }
        control.own(process);
        AtomicLong total = new AtomicLong();
        AtomicBoolean overflow = new AtomicBoolean();
        drain(process.getInputStream(), null, total, overflow);
        drain(process.getErrorStream(), null, total, overflow);
        if (!process.isAlive() || overflow.get()) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
        return process;
    }

    private ProcessBuilder builder(List<String> arguments, String credentialHelper) {
        validateExecutable();
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(profile.gitExecutable().toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(profile.root().toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("HOME", home.toString());
        environment.put("XDG_CONFIG_HOME", home.toString());
        environment.put("LC_ALL", "C");
        environment.put("LANG", "C");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "never");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        List<Map.Entry<String, String>> configuration = configuration(credentialHelper);
        environment.put("GIT_CONFIG_COUNT", Integer.toString(configuration.size()));
        int index = 0;
        for (Map.Entry<String, String> entry : configuration) {
            environment.put("GIT_CONFIG_KEY_" + index, entry.getKey());
            environment.put("GIT_CONFIG_VALUE_" + index, entry.getValue());
            index++;
        }
        return builder;
    }

    private void validateExecutable() {
        try {
            BasicFileAttributes attributes = Files.readAttributes(profile.gitExecutable(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(profile.gitExecutable())
                    || !java.util.Objects.equals(executableIdentity, attributes.fileKey())) throw new IOException();
        } catch (IOException replaced) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.AUTHORITY_REFUSED);
        }
    }

    private Thread drain(java.io.InputStream source, ByteArrayOutputStream retained, AtomicLong total,
                         AtomicBoolean overflow) {
        return Thread.ofVirtual().name("ravenroot-git-workspace-output").start(() -> {
            byte[] buffer = new byte[8192];
            try (source) {
                for (int read; (read = source.read(buffer)) >= 0;) {
                    if (read == 0) continue;
                    long observed = total.addAndGet(read);
                    if (observed > profile.maxOutputBytes()) {
                        if (overflow.compareAndSet(false, true)) control.terminateProcess();
                        continue;
                    }
                    if (retained != null) retained.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                if (!overflow.get()) control.terminateProcess();
            }
        });
    }

    private void joinDrain(Thread drain) throws InterruptedException {
        drain.join(control.remainingMillis());
        if (drain.isAlive()) {
            control.terminateProcess();
            drain.join(control.remainingMillis());
        }
        if (drain.isAlive()) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
    }

    private List<Map.Entry<String, String>> configuration(String credentialHelper) {
        List<Map.Entry<String, String>> values = new ArrayList<>();
        values.add(Map.entry("core.hooksPath", hooks.toString()));
        values.add(Map.entry("core.fsmonitor", "false"));
        values.add(Map.entry("core.autocrlf", "false"));
        values.add(Map.entry("credential.helper", ""));
        if (credentialHelper != null) {
            values.add(Map.entry("credential.helper", credentialHelper));
            values.add(Map.entry("credential.useHttpPath", "true"));
        }
        values.add(Map.entry("credential.interactive", "never"));
        values.add(Map.entry("gc.auto", "0"));
        values.add(Map.entry("maintenance.auto", "false"));
        values.add(Map.entry("fetch.recurseSubmodules", "false"));
        values.add(Map.entry("submodule.recurse", "false"));
        values.add(Map.entry("protocol.allow", "never"));
        values.add(Map.entry("protocol.https.allow", "always"));
        if (profile.remote().startsWith("file:")) values.add(Map.entry("protocol.file.allow", "always"));
        values.add(Map.entry("diff.external", ""));
        values.add(Map.entry("diff.trustExitCode", "false"));
        values.add(Map.entry("commit.gpgSign", "false"));
        values.add(Map.entry("tag.gpgSign", "false"));
        values.add(Map.entry("merge.autoStash", "false"));
        values.add(Map.entry("rerere.enabled", "false"));
        values.add(Map.entry("http.followRedirects", "false"));
        return values;
    }

    record Result(int exitCode, String stdout) {
        String requireSuccess() {
            if (exitCode != 0) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
            return stdout;
        }
    }

    record CommitEnvironment(String name, String email, String date) { }
}
