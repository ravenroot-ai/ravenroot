package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.ObservedKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A deployment runtime whose effects and attempts are recorded on the filesystem, so "the action
 * happened once" can be asserted across a process boundary.
 *
 * <h2>Why a file and not a counter</h2>
 * <p>Every in-process fixture proves per-generation idempotence inside one JVM, where the fixture's
 * own memory is the thing being trusted. Issue 91 criterion 2 is about a process that died: the
 * evidence has to outlive it, and the surviving owner has to consult the <em>same</em> evidence the
 * dead one wrote. A directory shared by both processes is that evidence, and
 * {@link Files#createFile} is the primitive that makes duplication detectable rather than merely
 * unlikely — the second attempt to create a marker fails, atomically, at the filesystem.</p>
 *
 * <p>Two records are kept for the reason the in-process fixture keeps two counters: {@code attempts}
 * grows every time the port is called, and a marker is created only when the operation actually did
 * something. A correct recovery shows two attempts and one marker; a duplicated action would show two
 * of each, and could not, because the second creation throws.</p>
 */
final class MarkerLifecycleTarget implements DeploymentLifecycleTarget {

    private static final String ATTEMPTS = "attempts.log";

    private final Path directory;

    MarkerLifecycleTarget(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException unusable) {
            throw new UncheckedIOException(unusable);
        }
    }

    /** Every invocation any process made through this port, in the order they were appended. */
    List<String> attempts() {
        Path log = directory.resolve(ATTEMPTS);
        try {
            return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** Only the invocations that actually did something, whichever process performed them. */
    List<String> effects() {
        try (var entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals(ATTEMPTS))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    @Override
    public CompletionStage<Void> start(long graphVersion, long deploymentGeneration) {
        return effect("start-" + graphVersion + "-g" + deploymentGeneration);
    }

    @Override
    public CompletionStage<Void> closeAdmission(long deploymentGeneration) {
        return effect("closeAdmission-g" + deploymentGeneration);
    }

    @Override
    public CompletionStage<Void> openAdmission(long deploymentGeneration) {
        return effect("openAdmission-g" + deploymentGeneration);
    }

    @Override
    public CompletionStage<Void> barrier(long deploymentGeneration) {
        return effect("barrier-g" + deploymentGeneration);
    }

    @Override
    public CompletionStage<Boolean> drain(Duration bound, long deploymentGeneration) {
        return effect("drain-g" + deploymentGeneration).thenApply(ignored -> true);
    }

    @Override
    public CompletionStage<Void> terminateDomain(long deploymentGeneration) {
        return effect("terminateDomain-g" + deploymentGeneration);
    }

    @Override
    public CompletionStage<Reading> observe() {
        String started = effects().stream().filter(name -> name.startsWith("start-")).findFirst().orElse(null);
        boolean terminated = effects().stream().anyMatch(name -> name.startsWith("terminateDomain-"));
        if (started == null || terminated) {
            return CompletableFuture.completedFuture(new Reading(ObservedKind.COLD, null, 0));
        }
        long version = Long.parseLong(started.split("-")[1]);
        return CompletableFuture.completedFuture(new Reading(ObservedKind.READY, version, 0));
    }

    /** Records the attempt, then performs the effect only if no other process already did. */
    private CompletionStage<Void> effect(String marker) {
        record(marker);
        apply(marker);
        return CompletableFuture.completedFuture(null);
    }

    /** Appends one attempt line. Called before the effect, so a killed process still leaves a trace. */
    void record(String marker) {
        try {
            Files.writeString(directory.resolve(ATTEMPTS), marker + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    /**
     * Creates the marker for one operation at one generation.
     *
     * @return whether this call is the one that performed the effect; {@code false} means another
     *         process, or an earlier call in this one, had already done it.
     */
    boolean apply(String marker) {
        try {
            Files.createFile(directory.resolve(marker));
            return true;
        } catch (FileAlreadyExistsException alreadyDone) {
            return false;
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }
}
