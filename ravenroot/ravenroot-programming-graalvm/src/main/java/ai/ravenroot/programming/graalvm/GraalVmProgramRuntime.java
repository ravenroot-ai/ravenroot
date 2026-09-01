package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramDeadlineExceededException;
import ai.ravenroot.api.programming.ProgramLanguageDescriptor;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.programming.ProgramRuntimeUnavailableException;
import ai.ravenroot.api.programming.ProgramSourceRejectedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Executes JavaScript only through a capability-attested external sandbox supervisor. */
public final class GraalVmProgramRuntime implements ProgramRuntime {
    private static final int MAX_DIAGNOSTIC_BYTES = 16 * 1024;
    /**
     * The reader's ceiling and the worker's own buffer bound are now literally the same
     * constant. They were the same NUMBER before, declared twice, which is the arrangement that
     * lets a response the worker was willing to produce be one the reader is not willing to accept.
     */
    private static final int MAX_RESPONSE_BYTES = ProgramWireProtocol.MAX_RESPONSE_BYTES;
    private static final Duration REAP_TIMEOUT = Duration.ofSeconds(2);

    private final SandboxSupervisorLauncher launcher;
    private final SandboxPolicy policy;

    /**
     * Retained only for source compatibility. It deliberately has no unsandboxed fallback and always fails closed.
     */
    @Deprecated
    public GraalVmProgramRuntime(Path javaExecutable, Duration timeout, int maxHeapMegabytes) {
        this(new MissingLauncher(), policyFor(javaExecutable, timeout, maxHeapMegabytes));
    }

    public GraalVmProgramRuntime(SandboxSupervisorLauncher launcher, SandboxPolicy policy) {
        if (launcher == null || policy == null) throw new IllegalArgumentException("Sandbox launcher and policy are required");
        this.launcher = launcher;
        this.policy = policy;
    }

    public static GraalVmProgramRuntime fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * The real body of {@link #fromEnvironment()}, split out over a {@code Map} rather than
     * reading {@code System.getenv()} directly -- the same seam {@code BackupRestoreConfiguration},
     * {@code UnknownBehaviorPolicy} and {@code NodePackageLoader} already use elsewhere in this
     * codebase for the identical reason: a real process environment cannot be set from inside a test
     * JVM, and the startup probe below requires direct test coverage. Package-private: this is a
     * test seam for this module, not a second public entry point.
     */
    static GraalVmProgramRuntime fromEnvironment(java.util.Map<String, String> environment) {
        String launcher = environment.get("RAVENROOT_GRAAL_SANDBOX_SUPERVISOR");
        SandboxSupervisorLauncher configured = launcher == null || launcher.isBlank() ? new MissingLauncher()
                : new SandboxSupervisorProcessLauncher(Path.of(launcher));
        // Only a launcher an operator actually pointed somewhere is probed here. MissingLauncher
        // is the documented, intentional "nothing configured" state -- its own verifyCapability() always
        // fails the same fixed way, so logging that at every boot would be noise repeating a fact the
        // operator already chose. A CONFIGURED path that is not a usable supervisor is different: that
        // is a deployment error, and before this the earliest anything said so was the first user's
        // Validate request, not the boot log the operator is actually watching while standing the
        // deployment up. The check is the same verifyCapability() a request already pays for -- a
        // process spawn bounded at 2s -- run once more here, at startup, before any request exists.
        //
        // The catch below is the whole point and must never become a throw: a startup probe that can
        // abort the server would turn a diagnostic into an outage, for a component (program artifacts)
        // that is optional. GraalVmProgramRuntimeFromEnvironmentTest asserts the return, not just the
        // log line, for exactly this reason.
        if (configured instanceof SandboxSupervisorProcessLauncher) {
            try {
                configured.verifyCapability();
            } catch (IOException unusable) {
                logSandboxUnavailable("startup", configured, unusable);
            }
            // Same reasoning as the capability probe just above: a launcher an operator
            // pointed somewhere is a deployment they intend to run Python programs on, so the
            // directory GraalVmWorkerMain's own default will try to extract the standard library
            // into is worth checking here, at the boot log the operator is watching, instead of
            // letting it surface for the first time as a ModuleNotFoundError on someone's first
            // Validate. MissingLauncher is skipped for the same "not configured is not broken"
            // reason the capability probe above skips it.
            //
            checkResourceCacheStartup(java.nio.file.Path.of("/opt/ravenroot"),
                    GraalVmWorkerMain.DEFAULT_RESOURCE_CACHE_DIR,
                    environment.get(GraalVmWorkerMain.RESOURCE_CACHE_ENV));
        }
        Path java = Path.of(environment.getOrDefault("RAVENROOT_GRAAL_JAVA",
                Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        Duration timeout = Duration.ofMillis(
                integerEnvironment(environment, "RAVENROOT_PROGRAM_TIMEOUT_MS", 5_000, 100, 300_000));
        int memory = integerEnvironment(environment, "RAVENROOT_PROGRAM_MAX_HEAP_MB", 64, 32, 1024);
        return new GraalVmProgramRuntime(configured, policyFor(java, timeout, memory));
    }

    /**
     * Was {@code graalvm-js-worker}. This identifier is not internal: {@code
     * ProgramNodeBehaviorFactory} writes it into the execution's attributes as {@code
     * program.runtime}, so it is read afterwards by whoever inspects a run, and a worker that
     * executes Python while still naming JavaScript records a false statement in every such run.
     *
     * <p>The change from the old value is required (the old one is now untrue); which value replaces it
     * is a data decision, because runs recorded before and after carry different strings for the same
     * runtime and nothing reconciles them. {@code graalvm-worker} is the narrowest choice that stops
     * the false claim without inventing a taxonomy, which is why it is here rather than something
     * more descriptive. This line is the only place to change that identifier.
     */
    @Override public String id() { return "graalvm-worker"; }

    @Override
    public String compatibilityFingerprint() {
        String contract = id() + "\0" + SandboxPolicy.PROTOCOL_VERSION + "\0" + launcher.describe()
                + "\0" + policy.deadline().toMillis() + "\0" + policy.cpuMillis() + "\0"
                + policy.memoryMiB() + "\0" + policy.maxPids() + "\0" + policy.maxFiles() + "\0"
                + policy.tmpfsMiB() + "\0" + policy.maxOutputBytes() + "\0"
                + policy.workerIdentity() + "\0" + policy.jreIdentity();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contract.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Delegates to {@link ProgramLanguage#descriptor()} for each declared value rather than
     * hand-listing ids and starters a second time here -- {@link ProgramLanguage} is already the
     * single place that knows which languages this adapter executes (see its own Javadoc), and this
     * method exists so that fact reaches an editor instead of staying an implementation detail no
     * caller outside this package could see.
     */
    @Override public List<ProgramLanguageDescriptor> supportedLanguages() {
        return Arrays.stream(ProgramLanguage.values()).map(ProgramLanguage::descriptor).toList();
    }

    @Override public java.util.concurrent.CompletionStage<Void> validate(GeneratedArtifact artifact) {
        requireState(artifact, ArtifactState.GENERATED); return invoke(ProgramWireProtocol.Mode.VALIDATE, () -> artifact, null, null).thenApply(ignored -> null);
    }
    @Override public java.util.concurrent.CompletionStage<Object> test(GeneratedArtifact artifact, ProgramRequest request) {
        requireState(artifact, ArtifactState.VALIDATED); return invoke(ProgramWireProtocol.Mode.TEST, () -> artifact, request, null);
    }

    /**
     * The artifact is not resolved here -- {@code admission::redeem} is passed down and
     * called inside {@link #invokeSupervisor} immediately before the source is written to the worker.
     * Resolving it at this point would restore the stale snapshot the admission exists to remove.
     *
     * <p>{@code validate} and {@code test} above legitimately pass a fixed snapshot: they run on the
     * control plane under an {@code ArtifactRegistry} reservation, which already holds the artifact
     * against concurrent transitions, and neither is reachable from a graph run.
     */
    @Override public java.util.concurrent.CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request) {
        if (admission == null) throw new IllegalArgumentException("Program admission is required");
        return invoke(ProgramWireProtocol.Mode.EXECUTE, admission::redeem, request, admission);
    }

    private CompletableFuture<Object> invoke(ProgramWireProtocol.Mode mode, java.util.function.Supplier<GeneratedArtifact> source,
                                             ProgramRequest request, ProgramAdmission admission) {
        var session = new AtomicReference<SandboxSupervisorLauncher.SandboxSupervisorSession>();
        var cleaned = new AtomicBoolean();
        var cancelled = new AtomicBoolean();
        var result = new CancellableFuture(() -> { cancelled.set(true); cleanup(session.get(), cleaned, SandboxSupervisorLauncher.SandboxTermination.CANCELLED); });
        if (admission != null) {
            // The other half of the control: an execution admitted a microsecond before a retirement
            // is legitimately admitted and its source may already be in the worker, so redemption
            // cannot stop it. Cancelling it is the only remaining remedy.
            admission.onRevoked(() -> result.cancel(true));
            result.whenComplete((ignored, error) -> admission.close());
        }
        Thread.startVirtualThread(() -> {
            try { result.complete(invokeSupervisor(mode, source, request, session, cleaned, cancelled)); }
            catch (Throwable error) { if (!result.isCancelled()) result.completeExceptionally(error); }
        });
        return result;
    }

    private Object invokeSupervisor(ProgramWireProtocol.Mode mode, java.util.function.Supplier<GeneratedArtifact> source,
                                    ProgramRequest request,
                                    AtomicReference<SandboxSupervisorLauncher.SandboxSupervisorSession> sessionRef,
                                    AtomicBoolean cleaned, AtomicBoolean cancelled) throws Exception {
        final long start = System.nanoTime();
        final long deadline = start + policy.deadline().toNanos();
        // This is the capability gate: with RAVENROOT_GRAAL_SANDBOX_SUPERVISOR unset the constructor
        // installs MissingLauncher, this
        // line throws SANDBOX_LAUNCHER_MISSING, and the source below is never read at all. The
        // failure used to reach the author as "the request was rejected as invalid", which sent them
        // to look at a source nothing had compiled. Typing it here is what lets the HTTP layer say
        // whose problem it is; the token becomes this exception's message and is also written to the
        // server log below. Previously, the comment on this line claimed the token was "kept as the
        // message for the server-side record", but no such record existed: reproduced by
        // stripping the mounted script's execute bit, the container's log carried no line naming
        // SANDBOX_, the launcher or this exception. logSandboxUnavailable is that record now.
        try {
            launcher.verifyCapability();
        } catch (IOException unusable) {
            logSandboxUnavailable("request", launcher, unusable);
            throw new ProgramRuntimeUnavailableException(
                    ProgramRuntimeUnavailableException.Reason.SANDBOX_UNAVAILABLE,
                    unusable.getMessage(), unusable);
        }
        checkDeadline(start, deadline, cancelled, "before_launch");
        var session = launcher.launch(policy);
        sessionRef.set(session);
        try {
            // The window this check closes contains only launcher.launch(policy) on the line above --
            // that is ProcessBuilder.start()
            // and nothing else -- so it is among the NARROWEST of the seven deadline sites, not the
            // widest. GraalPy's measured 2929 ms cold start happens inside the child process, and the
            // first point at which this adapter can observe it is session.await() below, which reports
            // it as "sandbox_outcome".
            //
            // An earlier version of this comment claimed the opposite -- that a cold start on a loaded
            // machine is what this check dies at -- and that claim was then copied into ErrorCode, into
            // RavenrootServer and into the error contract by a reader who trusted it instead of opening
            // SandboxSupervisorProcessLauncher. It is left recorded because the wrong version was the
            // more plausible-sounding one. Measured over two full reactor runs: sandbox_outcome five
            // times each, after_launch never once.
            checkDeadline(start, deadline, cancelled, "after_launch");
            // The redemption point. Authoritative state is re-read HERE, on the far side
            // of process launch and virtual-thread scheduling, and the source that goes to the worker
            // on the next line is the one redemption returned. Nothing can intervene between the two
            // because there is no interval -- the check is the acquisition. Moving this above
            // launcher.launch(policy), or hoisting it into execute(), reopens the window.
            GeneratedArtifact artifact = source.get();
            verifyArtifact(artifact);
            // Escape site one of two: Future.get(timeout) answers a RAW TimeoutException, which
            // is not the shape the rest of this class uses for a deadline. Unwrapped, it fell past
            // every typed branch of RavenrootServer.artifactFailureCode and reached the author as
            // "the request was rejected as invalid", obscuring the actual timeout.
            try {
                writeRequestBounded(session.workerInput(), mode, artifact, request, remaining(deadline));
            } catch (TimeoutException expired) {
                throw deadlineExceeded("write_request", start, expired);
            }
            checkDeadline(start, deadline, cancelled, "after_request_write");
            var diagnostics = readBoundedAsync(session.diagnostics(), MAX_DIAGNOSTIC_BYTES);
            SandboxSupervisorLauncher.SandboxOutcome outcome = session.await(remaining(deadline));
            // A DEADLINE_EXCEEDED outcome is the same fact as the checks above, so it must not
            // keep travelling as the IllegalStateException that artifactFailureCode reads as a state
            // conflict. The other outcomes are left exactly as they were: reclassifying them is a
            // separate classification question.
            //
            // WHAT THIS OUTCOME DOES NOT ESTABLISH, because this comment used to say it did. It called
            // this "the supervisor's own verdict", and the only production implementation does not
            // warrant that: SandboxSupervisorProcessLauncher's session answers DEADLINE_EXCEEDED from
            // !process.waitFor(remaining) alone, which establishes only that the child had not exited
            // within the remaining budget. That IS the caller's own timeout -- exactly what
            // SandboxSupervisorContract#theSupervisorEnforcesTheDeclaredDeadline requires a conforming
            // supervisor NOT to leave the deadline to. So at this stage WHETHER THE WORKER EVER RAN IS
            // UNKNOWN: a supervisor stuck in setup, or a worker still inside GraalPy's cold start,
            // arrives here with the program never executed.
            // Do not infer progress from this outcome; it is also the stage this adapter reaches in
            // practice, five times per full reactor run.
            if (outcome == SandboxSupervisorLauncher.SandboxOutcome.DEADLINE_EXCEEDED)
                throw deadlineExceeded("sandbox_outcome", start, null);
            if (outcome != SandboxSupervisorLauncher.SandboxOutcome.COMPLETED) throw sandboxFailure(outcome);
            byte[] response = SandboxSupervisorProtocol.readWorkerResponse(session.supervisorControl(), MAX_RESPONSE_BYTES);
            // Escape site two of two: the wait on worker diagnostics.
            try {
                diagnostics.get(Math.max(1, remaining(deadline).toMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException expired) {
                throw deadlineExceeded("diagnostics", start, expired);
            }
            checkDeadline(start, deadline, cancelled, "after_response");
            try { return ProgramWireProtocol.readResponse(new ByteArrayInputStream(response)); }
            catch (ProgramWireProtocol.ProgramWorkerException error) { throw rejection(error); }
        } catch (Throwable error) {
            cleanup(session, cleaned, cancelled.get() ? SandboxSupervisorLauncher.SandboxTermination.CANCELLED
                    : SandboxSupervisorLauncher.SandboxTermination.IO_FAILURE);
            throw error;
        } finally { try { session.close(); } catch (IOException ignored) { } }
    }

    /**
     * Neither token this deployment's HTTP response ever leaves reaches a caller -- {@code
     * ErrorCode.PROGRAM_SANDBOX_UNAVAILABLE} answers a fixed, server-authored literal on purpose, so
     * no path or internal token leaves this deployment -- so the reason has to reach an operator some
     * other way, or it reaches nobody. One JSON line to stderr, matching the shape {@code
     * RavenrootServer}'s own structured operational log lines already use, naming which of {@code
     * verifyCapability()}'s two conditions failed and the launcher's own path -- not just the token,
     * which is all the previous comment on the call site promised and which, measured, was never
     * actually written anywhere.
     */
    private static void logSandboxUnavailable(String stage, SandboxSupervisorLauncher launcher, IOException failure) {
        System.err.println("{\"event\":\"program_sandbox_unavailable\",\"stage\":\"" + stage
                + "\",\"reason\":\"" + jsonEscape(String.valueOf(failure.getMessage()))
                + "\",\"launcher\":\"" + jsonEscape(launcher.describe()) + "\"}");
    }

    /**
     * The startup resource-cache check, split over its two external inputs (the same
     * seam {@link #fromEnvironment(java.util.Map)} and {@code
     * GraalVmWorkerMain#applyResourceCacheDefault(Path, String)} already use, for the identical
     * reason: {@code imageRoot}'s existence cannot be set from inside a test JVM). Package-private:
     * a test seam for this module, not a second public entry point.
     *
     * <p><b>Both checks run, unconditionally of each other.</b> An earlier version was an if/else
     * that checked only {@code override} when one was set. That fails silently because
     * {@code RAVENROOT_GRAAL_RESOURCE_CACHE_DIR} set on this server
     * container does NOT reach the worker in the shipped stack ({@code
     * SandboxSupervisorProcessLauncher} clears the environment before spawning the supervisor --
     * see its Javadoc and {@code GraalVmWorkerMain}'s), so an operator who sets it here is checking
     * a path the worker will never actually use. The if/else let that operator see a clean startup
     * log -- override writable, so silent -- while the worker fell back to {@code defaultDirectory},
     * which could be unwritable for a reason the override check never looked at; the first Validate
     * would still fail with the exact {@code ModuleNotFoundError} this check exists to prevent, and
     * nothing at startup would have said so. {@code defaultDirectory} is therefore always checked
     * when {@code imageRoot} exists (mirroring {@code applyResourceCacheDefault}'s own guard, since
     * that is the directory the worker resorts to whenever the override does not reach it);
     * {@code override}, when set, is checked ADDITIONALLY, as a best-effort diagnostic for an
     * integrator's own supervisor that does choose to forward it.
     */
    static void checkResourceCacheStartup(Path imageRoot, String defaultDirectory, String override) {
        if (java.nio.file.Files.isDirectory(imageRoot)) {
            checkResourceCacheDirectory(defaultDirectory);
        }
        if (override != null && !override.isBlank()) {
            checkResourceCacheDirectory(override);
        }
    }

    /**
     * Verifies, at startup, that {@link GraalVmWorkerMain}'s resource-cache directory (see
     * its Javadoc) exists or can be created and is writable -- diagnosing at the boot log the
     * exact condition that otherwise surfaces only as a Python worker's {@code
     * ModuleNotFoundError} on someone's first Validate, naming the directory rather than a module.
     *
     * <p>Deliberately advisory, like {@link #logSandboxUnavailable}: this process and the worker
     * process the configured supervisor eventually spawns are not guaranteed to share a filesystem
     * view under a real, integrator-supplied SEC-11 supervisor, so a pass here is not a guarantee
     * and a failure here is not certain to recur in the worker -- but for the shipped, unmodified
     * stack (this server and the worker sharing the same container's filesystem, as they do with
     * {@code deploy/dev/sandbox-supervisor.sh} and every deployment descriptor this repository
     * ships) it is the same directory and the same answer. Never throws: a diagnostic that could
     * abort startup would turn an optional component's misconfiguration into an outage for the
     * whole server, matching the sandbox capability probe's boundary above.
     */
    private static void checkResourceCacheDirectory(String configured) {
        Path directory = Path.of(configured);
        try {
            java.nio.file.Files.createDirectories(directory);
            if (!java.nio.file.Files.isWritable(directory)) {
                throw new IOException("not a writable directory");
            }
        } catch (IOException | RuntimeException failure) {
            System.err.println("{\"event\":\"program_resource_cache_unavailable\",\"stage\":\"startup\""
                    + ",\"directory\":\"" + jsonEscape(directory.toString())
                    + "\",\"reason\":\"" + jsonEscape(String.valueOf(failure.getMessage())) + "\"}");
        }
    }

    /**
     * The earlier version escaped only backslash, quote and the two newline forms, which
     * left every OTHER control character -- a tab, most conspicuously -- passed through literally. A
     * third-party {@link SandboxSupervisorLauncher#describe()} or {@code IOException} message
     * containing one would have produced invalid JSON on exactly the line this module's own
     * documentation tells the operator to read. Mirrors {@code RavenrootServer}'s own {@code escape()}:
     * every character below {@code 0x20} is escaped, not a hand-picked subset of them.
     */
    private static String jsonEscape(String value) {
        var escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }

    private static void cleanup(SandboxSupervisorLauncher.SandboxSupervisorSession session, AtomicBoolean cleaned,
                                SandboxSupervisorLauncher.SandboxTermination termination) {
        if (session == null || !cleaned.compareAndSet(false, true)) return;
        try { session.terminate(termination); session.await(REAP_TIMEOUT); }
        catch (Exception ignored) { /* authoritative supervisor acknowledgement is required before returning */ }
        try { session.close(); } catch (IOException ignored) { }
    }

    /**
     * A worker refusal becomes the typed rejection when — and only when — the worker itself
     * classified it as belonging to the artifact's source.
     *
     * <p>{@link IllegalArgumentException} is kept for everything else, unchanged, so a refusal this
     * adapter cannot attribute to the author still arrives as the same type and the same
     * {@code "<Type>: <message>"} text it always did. What the caller gains is a second, narrower
     * type it can act on, not a reclassification of the first.</p>
     *
     * <p>{@code detail()} rather than {@code getMessage()}: the author is diagnosing their own code,
     * and {@code PolyglotException:} in front of {@code IndentationError: …} is this adapter's
     * plumbing, not part of the compiler's answer.</p>
     */
    private static RuntimeException rejection(ProgramWireProtocol.ProgramWorkerException error) {
        if (!error.sourceRejected()) return new IllegalArgumentException(error.getMessage(), error);
        var rejected = new ProgramSourceRejectedException(error.detail(), error.line(), error.column());
        rejected.initCause(error);
        return rejected;
    }

    private static IllegalStateException sandboxFailure(SandboxSupervisorLauncher.SandboxOutcome outcome) {
        return new IllegalStateException("SANDBOX_" + outcome.name());
    }
    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }
    private void checkDeadline(long start, long deadline, AtomicBoolean cancelled, String stage) {
        if (cancelled.get()) throw new java.util.concurrent.CancellationException("SANDBOX_CANCELLED");
        if (System.nanoTime() >= deadline) throw deadlineExceeded(stage, start, null);
    }

    /**
     * The single place a deadline becomes an exception, so the classification cannot drift
     * apart between the four explicit checks, the two bounded waits and the DEADLINE_EXCEEDED outcome
     * (which is not "the supervisor's own verdict" -- see the comment at that call site).
     *
     * <p>The log line is written here rather than at the call sites for the same reason as
     * {@link #logSandboxUnavailable}: the caller-facing message is a server-authored literal owned by
     * {@code ErrorCode}, so the budget and the elapsed wait reach an operator through this line or
     * they reach nobody. Writing it here also makes it impossible to add a seventh deadline site that
     * silently logs nothing.</p>
     */
    private ProgramDeadlineExceededException deadlineExceeded(String stage, long start, Throwable cause) {
        Duration budget = policy.deadline();
        Duration waited = Duration.ofNanos(Math.max(0, System.nanoTime() - start));
        logDeadlineExceeded(stage, budget, waited);
        return cause == null
                ? new ProgramDeadlineExceededException(stage, budget, waited)
                : new ProgramDeadlineExceededException(stage, budget, waited, cause);
    }

    /**
     * <b>A diagnostic must never be the reason something fails.</b>
     * The classification is the fix; this line is only the operator's copy of it, so losing the copy
     * must not lose the fix. Hence the catch — {@code System.err} can be redirected to a closed or
     * full stream by an embedder, and that must not turn a timeout into a different exception.
     */
    private static void logDeadlineExceeded(String stage, Duration budget, Duration waited) {
        try {
            System.err.println("{\"event\":\"program_deadline_exceeded\",\"stage\":\"" + jsonEscape(stage)
                    + "\",\"budgetMs\":" + budget.toMillis() + ",\"waitedMs\":" + waited.toMillis() + "}");
        } catch (RuntimeException ignored) {
            /* see Javadoc: the record is best-effort, the classification is not */
        }
    }
    private static CompletableFuture<byte[]> readBoundedAsync(java.io.InputStream input, int maxBytes) {
        var future = new CompletableFuture<byte[]>(); Thread.startVirtualThread(() -> {
            try (input; var output = new ByteArrayOutputStream()) { byte[] buffer = new byte[8192]; int total = 0, read;
                while ((read = input.read(buffer)) >= 0) { total += read; if (total > maxBytes) throw new IOException("SANDBOX_OUTPUT_LIMIT"); output.write(buffer, 0, read); }
                future.complete(output.toByteArray());
            } catch (Throwable error) { future.completeExceptionally(error); }
        }); return future;
    }
    private static void writeRequestBounded(java.io.OutputStream input, ProgramWireProtocol.Mode mode,
                                            GeneratedArtifact artifact, ProgramRequest request, Duration remaining)
            throws Exception {
        var written = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try (input) { ProgramWireProtocol.writeRequest(input, mode, artifact, request); written.complete(null); }
            catch (Throwable error) { written.completeExceptionally(error); }
        });
        written.get(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS);
    }
    private static SandboxPolicy policyFor(Path java, Duration timeout, int memory) {
        if (java == null || timeout == null || timeout.isNegative() || timeout.isZero() || memory < 32 || memory > 1024) throw new IllegalArgumentException("Invalid GraalVM sandbox configuration");
        Path worker = Path.of(System.getProperty("java.class.path")).toAbsolutePath();
        return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), memory, 32, 256, memory, MAX_RESPONSE_BYTES,
                worker, identity(worker), java, identity(java));
    }
    private static String identity(Path path) { return HexFormat.of().formatHex(sha256(path.toString().getBytes(StandardCharsets.UTF_8))); }
    private static void requireState(GeneratedArtifact artifact, ArtifactState expected) {
        if (artifact == null) throw new IllegalArgumentException("Artifact cannot be null");
        if (artifact.state() != expected) throw new SecurityException("Artifact " + artifact.id() + " is " + artifact.state() + ", not " + expected);
    }
    private static void verifyArtifact(GeneratedArtifact artifact) {
        // The control-plane door. The worker holds the other one independently; see
        // ProgramLanguage for why the check is duplicated and the list is not.
        ProgramLanguage.of(artifact.language());
        String actual = ProgramArtifactDigest.canonical(artifact.language(), artifact.source());
        String legacy = HexFormat.of().formatHex(sha256(artifact.source().getBytes(StandardCharsets.UTF_8)));
        byte[] stored = artifact.sha256().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), stored)
                && !MessageDigest.isEqual(legacy.getBytes(StandardCharsets.US_ASCII), stored)) {
            throw new SecurityException("Artifact source hash mismatch: " + artifact.id());
        }
    }
    private static byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); } }
    private static int integerEnvironment(java.util.Map<String, String> environment, String name, int defaultValue, int minimum, int maximum) { String value = environment.get(name); if (value == null || value.isBlank()) return defaultValue; try { int parsed = Integer.parseInt(value); if (parsed < minimum || parsed > maximum) throw new NumberFormatException(); return parsed; } catch (NumberFormatException error) { throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum); } }
    private static final class MissingLauncher implements SandboxSupervisorLauncher {
        @Override public void verifyCapability() throws IOException { throw new IOException("SANDBOX_LAUNCHER_MISSING"); }
        @Override public SandboxSupervisorSession launch(SandboxPolicy policy) throws IOException { throw new IOException("SANDBOX_LAUNCHER_MISSING"); }
        @Override public String describe() { return "RAVENROOT_GRAAL_SANDBOX_SUPERVISOR is not set"; }
    }
    private static final class CancellableFuture extends CompletableFuture<Object> {
        private final Runnable cancellation; CancellableFuture(Runnable cancellation) { this.cancellation = cancellation; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            // Claim the terminal CANCELLED state before cleanup unblocks the worker. Running cleanup
            // first lets that worker complete this future exceptionally in the intervening window,
            // making a cancellation which was actually accepted report false to its caller.
            if (!super.cancel(mayInterruptIfRunning)) return false;
            cancellation.run();
            return true;
        }
    }
}
