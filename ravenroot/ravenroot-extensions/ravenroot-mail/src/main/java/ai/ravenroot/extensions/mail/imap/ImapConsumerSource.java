package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.api.security.SecretValue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;

/** One worker owns protocol I/O, durable admission, checkpoint order and generation transitions. */
final class ImapConsumerSource implements InboundSource {
    enum State { STOPPED, STARTING, READY, BACKING_OFF, RECONNECTING, STOPPING, FAILED }
    private static final int MAX_RESOLVER_TASKS = 32;
    private static final ConcurrentHashMap<String, Object> LEASES = new ConcurrentHashMap<>();
    private static final java.util.concurrent.Semaphore RESOLVER_SLOTS =
            new java.util.concurrent.Semaphore(MAX_RESOLVER_TASKS, true);
    private static final ConcurrentHashMap<String, Object> RESOLVER_LEASES = new ConcurrentHashMap<>();

    private final NodeConfiguration configuration;
    private final CredentialResolver credentials;
    private final ImapProfileResolver profiles;
    private final ImapConsumerPolicyResolver policies;
    private final ImapConsumerProtocol protocol;
    private final Executor executor;
    @SuppressWarnings("unused") private final Clock clock;
    private final IntConsumer reconnectBackoffObserver;
    private final DoubleSupplier reconnectJitter;
    private final ReservedNetworkPolicy destinationPolicy;
    private final Object lifecycle = new Object();

    private volatile State state = State.STOPPED;
    private volatile ImapConsumerProtocol.Owner owner;
    private volatile ImapConsumerProtocol.Opening opening;
    private volatile boolean stopRequested;
    private long generation;
    private CompletableFuture<Void> startFlight = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> stopFlight = CompletableFuture.completedFuture(null);

    ImapConsumerSource(NodeConfiguration configuration, CredentialResolver credentials,
                       ImapProfileResolver profiles, ImapConsumerPolicyResolver policies,
                       ImapConsumerProtocol protocol, Executor executor, Clock clock) {
        this(configuration, credentials, profiles, policies, protocol, executor, clock,
                ignored -> { }, new java.security.SecureRandom()::nextDouble);
    }

    ImapConsumerSource(NodeConfiguration configuration, CredentialResolver credentials,
                       ImapProfileResolver profiles, ImapConsumerPolicyResolver policies,
                       ImapConsumerProtocol protocol, Executor executor, Clock clock,
                       IntConsumer reconnectBackoffObserver, DoubleSupplier reconnectJitter) {
        this(configuration, credentials, profiles, policies, protocol, executor, clock,
                reconnectBackoffObserver, reconnectJitter,
                ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }

    ImapConsumerSource(NodeConfiguration configuration, CredentialResolver credentials,
                       ImapProfileResolver profiles, ImapConsumerPolicyResolver policies,
                       ImapConsumerProtocol protocol, Executor executor, Clock clock,
                       IntConsumer reconnectBackoffObserver, DoubleSupplier reconnectJitter,
                       ReservedNetworkPolicy destinationPolicy) {
        this.configuration = Objects.requireNonNull(configuration);
        this.credentials = Objects.requireNonNull(credentials);
        this.profiles = Objects.requireNonNull(profiles);
        this.policies = Objects.requireNonNull(policies);
        this.protocol = Objects.requireNonNull(protocol);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.reconnectBackoffObserver = Objects.requireNonNull(reconnectBackoffObserver);
        this.reconnectJitter = Objects.requireNonNull(reconnectJitter);
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy);
    }

    @Override public CompletionStage<Void> start(InboundSourceContext context) {
        Objects.requireNonNull(context);
        synchronized (lifecycle) {
            if (owner != null || opening != null || !stopFlight.isDone()) {
                if (state == State.STARTING) return startFlight;
                if (serving()) return CompletableFuture.completedFuture(null);
                return stopFlight.thenCompose(ignored -> start(context));
            }
            if (state == State.STARTING) return startFlight;
            if (serving()) return CompletableFuture.completedFuture(null);
            stopRequested = false;
            state = State.STARTING;
            startFlight = new CompletableFuture<>();
            stopFlight = new CompletableFuture<>();
            try { executor.execute(() -> run(context, startFlight, stopFlight)); }
            catch (RuntimeException rejected) {
                stopRequested = true;
                state = State.FAILED;
                startFlight.completeExceptionally(new IllegalStateException("imap-consumer-submission-failed"));
                stopFlight.complete(null);
            }
            return startFlight;
        }
    }

    @Override public CompletionStage<Void> stop() {
        ImapConsumerProtocol.Opening currentOpening;
        ImapConsumerProtocol.Owner currentOwner;
        synchronized (lifecycle) {
            if (state == State.STOPPED) return CompletableFuture.completedFuture(null);
            if (state == State.STOPPING) return stopFlight;
            if (owner == null && opening == null && stopFlight.isDone()) {
                stopRequested = true;
                state = State.STOPPED;
                return CompletableFuture.completedFuture(null);
            }
            stopRequested = true;
            state = State.STOPPING;
            generation++;
            currentOpening = opening;
            currentOwner = owner;
            lifecycle.notifyAll();
        }
        if (currentOpening != null) currentOpening.cancel();
        if (currentOwner != null) try { currentOwner.wakeup(); } catch (RuntimeException ignored) { }
        return stopFlight;
    }

    @Override public CompletionStage<Void> rollback() { return stop(); }
    @Override public CompletionStage<Void> shutdown() { return stop(); }

    State state() { return state; }
    long generation() { synchronized (lifecycle) { return generation; } }
    static int activeLeases() { return LEASES.size(); }
    static int activeResolverTasks() { return MAX_RESOLVER_TASKS - RESOLVER_SLOTS.availablePermits(); }
    static int activeResolverProfiles() { return RESOLVER_LEASES.size(); }

    private boolean serving() {
        return state == State.READY || state == State.BACKING_OFF || state == State.RECONNECTING;
    }

    private void run(InboundSourceContext context, CompletableFuture<Void> ready,
                     CompletableFuture<Void> stopped) {
        Object lease = null;
        String leaseKey = null;
        Settings settings = null;
        FailureStreak reconnectFailures = new FailureStreak();
        ProjectionFailureTracker projectionFailures = new ProjectionFailureTracker();
        try {
            String profileName = configuration.requiredProperty("profile");
            ImapProfile profile = resolveProfile(context.identity().tenantId(), profileName);
            ImapConsumerPolicy policy = resolvePolicy(context.identity().tenantId(), profileName);
            settings = Settings.resolve(configuration, profile, policy);
            probeDurability(context, context.nodeId() + "/imap/durable-probe", settings.timeoutMs);
            leaseKey = leaseKey(policy.tenant(), profileName, settings.folder);
            lease = new Object();
            if (LEASES.putIfAbsent(leaseKey, lease) != null)
                throw new SourceFailure("imap-consumer-already-active");

            while (!stopRequested) {
                long sessionGeneration = nextGeneration();
                SecretValue secret = null;
                char[] password = null;
                try {
                    secret = resolveCredential(profile, sessionGeneration);
                    password = secret.copy();
                    ImapConsumerProtocol.Opening candidateOpening = new ImapConsumerProtocol.Opening();
                    synchronized (lifecycle) {
                        if (!current(sessionGeneration)) throw new SourceFailure("startup-cancelled");
                        opening = candidateOpening;
                    }
                    ImapConsumerProtocol.Owner opened = protocol.open(profile, settings.folder,
                            password, candidateOpening);
                    Arrays.fill(password, '\0');
                    password = null;
                    secret.close();
                    secret = null;
                    synchronized (lifecycle) {
                        opening = null;
                        if (!current(sessionGeneration)) {
                            candidateOpening.cancel();
                            try { opened.close(); }
                            catch (RuntimeException ignored) { }
                            break;
                        }
                        owner = opened;
                    }
                    if (!settings.folder.equals(opened.sourceFolder()))
                        throw new SourceFailure("imap-folder-not-canonical");
                    long validity = unsigned32(opened.uidValidity(), "imap-uidvalidity-invalid");
                    String sourceId = ImapMessageEvent.sourceId(context.nodeId(), profileName,
                            settings.folder, validity);
                    JournalCursor cursor = checkpoint(context, sourceId, settings.timeoutMs);
                    claimReady(context, ready, sessionGeneration);
                    consume(context, profileName, settings, sourceId, validity, cursor,
                            reconnectFailures, projectionFailures, sessionGeneration);
                } catch (ImapConsumerProtocol.Failure failure) {
                    if (stopRequested) break;
                    if (failure.permanent() || !ready.isDone()) throw new SourceFailure(failure.getMessage());
                    degrade(context, "imap-consumer-reconnecting", State.RECONNECTING);
                    closeOwner();
                    awaitReconnectBackoff(jitteredBackoff(settings, reconnectFailures.failed()));
                } finally {
                    if (password != null) Arrays.fill(password, '\0');
                    if (secret != null) try { secret.close(); } catch (RuntimeException ignored) { }
                    closeOwner();
                    ImapConsumerProtocol.Opening currentOpening = opening;
                    opening = null;
                    if (currentOpening != null) currentOpening.cancel();
                }
            }
        } catch (SourceFailure failure) {
            fail(context, ready, failure.safeReason);
        } catch (RuntimeException failure) {
            fail(context, ready, "imap-consumer-failed");
        } finally {
            synchronized (lifecycle) { generation++; owner = null; opening = null; }
            if (leaseKey != null && lease != null) LEASES.remove(leaseKey, lease);
            synchronized (lifecycle) {
                if (!ready.isDone()) ready.completeExceptionally(
                        new IllegalStateException("IMAP source stopped before readiness"));
                state = stopRequested ? State.STOPPED : State.FAILED;
                stopped.complete(null);
                lifecycle.notifyAll();
            }
        }
    }

    private void consume(InboundSourceContext context, String profile, Settings settings,
                         String sourceId, long uidValidity, JournalCursor initial,
                         FailureStreak reconnectFailures, ProjectionFailureTracker projectionFailures,
                         long sessionGeneration)
            throws ImapConsumerProtocol.Failure {
        JournalCursor cursor = initial;
        long scanAfter = cursor.deliveredThrough();
        while (current(sessionGeneration)) {
            ImapConsumerProtocol.Poll poll = owner.pollAfter(scanAfter, settings.batchSize,
                    settings.scanWindow);
            if (!current(sessionGeneration)) return;
            if (poll.uidValidity() != uidValidity) {
                degrade(context, "imap-uidvalidity-changed", State.RECONNECTING);
                throw new ImapConsumerProtocol.Failure(false, "imap-uidvalidity-changed");
            }
            for (ImapConsumerProtocol.Item item : poll.items()) {
                if (!current(sessionGeneration)) return;
                if (item.uid() <= cursor.deliveredThrough()) continue;
                cursor = process(context, profile, settings, sourceId, uidValidity, cursor,
                        item, reconnectFailures, projectionFailures, sessionGeneration);
                scanAfter = Math.max(scanAfter, item.uid());
            }
            scanAfter = Math.max(scanAfter, poll.scannedThrough());
            if (poll.items().isEmpty()) awaitBackoff(settings.pollIntervalMs);
        }
    }

    private JournalCursor process(InboundSourceContext context, String profile, Settings settings,
                                  String sourceId, long validity, JournalCursor cursor,
                                  ImapConsumerProtocol.Item item, FailureStreak reconnectFailures,
                                  ProjectionFailureTracker projectionFailures, long sessionGeneration)
            throws ImapConsumerProtocol.Failure {
        int attempt = 0;
        while (current(sessionGeneration)) {
            attempt++;
            ImapMessageEvent.Projected projected;
            try {
                projected = ImapMessageEvent.project(item, profile, settings.folder, validity,
                        settings.limits, attempt);
            } catch (ImapMessageEvent.Invalid invalid) {
                projected = ImapMessageEvent.poison(profile, settings.folder, validity, item.uid(),
                        invalid.safeReason(), attempt);
            } catch (ImapMessageEvent.Unavailable unavailable) {
                String failureKey = profile + "\0" + settings.folder + "\0" + validity + "\0" + item.uid();
                int failures = projectionFailures.failed(failureKey);
                context.reportDegraded(unavailable.safeReason());
                if (failures >= settings.poisonAttempts)
                    throw new SourceFailure("imap-message-projection-halted");
                throw new ImapConsumerProtocol.Failure(false, unavailable.safeReason());
            }
            IngressReceipt receipt = context.ingress().offerDurably(context.identity(),
                    IngressTarget.start(), projected.payload(), sourceId, projected.idempotentKey());
            if (!current(sessionGeneration)) return cursor;
            if (receipt.acknowledgeable()) {
                JournalCursor advanced = advance(context, cursor, item.uid(), settings.timeoutMs);
                if (!current(sessionGeneration)) return cursor;
                reconnectFailures.reset();
                projectionFailures.succeeded(profile + "\0" + settings.folder + "\0" + validity + "\0" + item.uid());
                healthy(context);
                return advanced;
            }
            if (receipt instanceof IngressReceipt.VolatileCustody)
                throw new SourceFailure("durable-ingress-lost");
            if (attempt >= settings.poisonAttempts)
                throw new SourceFailure("imap-message-poison-halted");
            degrade(context, receipt instanceof IngressReceipt.Ambiguous
                    ? "ambiguous-ingress" : "ingress-refused", State.BACKING_OFF);
            awaitBackoff(backoff(settings, attempt));
        }
        return cursor;
    }

    private JournalCursor advance(InboundSourceContext context, JournalCursor expected,
                                  long uid, int timeoutMs) {
        try {
            return await(context.ingress().advanceSourceCheckpoint(expected, uid), timeoutMs,
                    "imap-checkpoint-conflict");
        } catch (SourceFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new SourceFailure("imap-checkpoint-conflict"); }
    }

    private JournalCursor checkpoint(InboundSourceContext context, String sourceId, int timeoutMs) {
        try {
            JournalCursor cursor = await(context.ingress().sourceCheckpoint(context.identity(), sourceId),
                    timeoutMs, "durable-ingress-required");
            // TrustedIngress owns the durable namespace and may scope its opaque destination with the
            // deployment id. The caller supplies sourceId to select that namespace; it must not try
            // to reverse or second-guess the returned storage key.
            if (!context.identity().tenantId().equals(cursor.tenantId())
                    || cursor.deliveredThrough() > 0xffff_ffffL) {
                throw new SourceFailure("imap-checkpoint-invalid");
            }
            return cursor;
        } catch (SourceFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new SourceFailure("durable-ingress-required"); }
    }

    private void probeDurability(InboundSourceContext context, String sourceId, int timeoutMs) {
        checkpoint(context, sourceId, timeoutMs);
    }

    private <T> T await(CompletionStage<T> stage, int timeoutMs, String failureReason) {
        CompletableFuture<T> future = stage.toCompletableFuture();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (stopRequested) {
                future.cancel(true);
                throw new SourceFailure("startup-cancelled");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                future.cancel(true);
                throw new SourceFailure(failureReason);
            }
            try {
                return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException retry) {
                // Re-check stop/generation at a bounded cadence.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new SourceFailure("startup-cancelled");
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new SourceFailure(failureReason);
            }
        }
    }

    private void claimReady(InboundSourceContext context, CompletableFuture<Void> ready,
                            long sessionGeneration) {
        synchronized (lifecycle) {
            if (!current(sessionGeneration) || owner == null)
                throw new SourceFailure("startup-cancelled");
            state = State.READY;
            context.reportHealthy();
            ready.complete(null);
        }
    }

    private void healthy(InboundSourceContext context) {
        synchronized (lifecycle) {
            if (!stopRequested && state != State.READY) {
                state = State.READY;
                context.reportHealthy();
            }
        }
    }

    private void degrade(InboundSourceContext context, String reason, State target) {
        synchronized (lifecycle) {
            if (stopRequested) return;
            state = target;
            context.reportDegraded(reason);
        }
    }

    private void fail(InboundSourceContext context, CompletableFuture<Void> ready, String reason) {
        synchronized (lifecycle) {
            if (stopRequested || state == State.STOPPING) return;
            state = State.FAILED;
            context.reportDegraded(reason);
            ready.completeExceptionally(new IllegalStateException(reason));
        }
    }

    private void closeOwner() {
        ImapConsumerProtocol.Owner current = owner;
        owner = null;
        if (current != null) try { current.close(); }
        catch (RuntimeException ignored) { }
    }

    private long nextGeneration() { synchronized (lifecycle) { return ++generation; } }
    private boolean current(long expected) {
        synchronized (lifecycle) { return !stopRequested && generation == expected; }
    }

    private void awaitBackoff(int millis) {
        synchronized (lifecycle) {
            if (stopRequested) return;
            try { lifecycle.wait(millis); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                stopRequested = true;
            }
        }
    }

    private void awaitReconnectBackoff(int millis) {
        try { reconnectBackoffObserver.accept(millis); } catch (RuntimeException ignored) { }
        awaitBackoff(millis);
    }

    private int jitteredBackoff(Settings settings, int attempt) {
        int cap = backoff(settings, attempt == Integer.MAX_VALUE ? attempt : attempt + 1);
        int minimum = Math.max(settings.retryBackoffMs, (cap + 1) / 2);
        if (minimum >= cap) return cap;
        double sample;
        try { sample = reconnectJitter.getAsDouble(); }
        catch (RuntimeException unavailable) { sample = 0.5d; }
        if (!Double.isFinite(sample) || sample < 0.0d || sample >= 1.0d) sample = 0.5d;
        long width = (long) cap - minimum + 1;
        return minimum + (int) Math.min(width - 1, (long) (sample * width));
    }

    private static int backoff(Settings settings, int attempt) {
        long multiplier = 1L << Math.min(30, Math.max(0, attempt - 1));
        return (int) Math.min(settings.maxRetryBackoffMs, settings.retryBackoffMs * multiplier);
    }

    private ImapProfile resolveProfile(String tenant, String name) {
        try {
            ImapProfile profile = profiles.resolve(tenant, name)
                    .orElseThrow(() -> new SourceFailure("imap-profile-unavailable"));
            if (!tenant.equals(profile.tenant()) || !name.equals(profile.id()))
                throw new SourceFailure("imap-profile-unavailable");
            destinationPolicy.requireAllowedLiteral(profile.host());
            return profile;
        } catch (SourceFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new SourceFailure("imap-profile-unavailable"); }
    }

    private ImapConsumerPolicy resolvePolicy(String tenant, String name) {
        try {
            ImapConsumerPolicy policy = policies.resolve(tenant, name)
                    .orElseThrow(() -> new SourceFailure("imap-consumer-policy-unavailable"));
            if (!tenant.equals(policy.tenant()) || !name.equals(policy.profile()))
                throw new SourceFailure("imap-consumer-policy-unavailable");
            return policy;
        } catch (SourceFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new SourceFailure("imap-consumer-policy-unavailable"); }
    }

    private SecretValue resolveCredential(ImapProfile profile, long sessionGeneration) {
        ResolverAdmission admission = ResolverAdmission.acquire(profile.tenant(), profile.id());
        if (!admission.acquired()) throw new SourceFailure("credential-resolver-busy");
        CredentialHandoff handoff = new CredentialHandoff();
        Thread resolver;
        try {
            ClassLoader pluginLoader = ImapConsumerSource.class.getClassLoader();
            resolver = Thread.ofVirtual().name("ravenroot-imap-consumer-credential").unstarted(() -> {
                Thread thread = Thread.currentThread();
                ClassLoader previous = thread.getContextClassLoader();
                try {
                    thread.setContextClassLoader(pluginLoader);
                    CredentialOutcome outcome;
                    try {
                        Optional<SecretValue> resolved = credentials.resolve(profile.credentialRef());
                        outcome = new CredentialOutcome(resolved == null || resolved.isEmpty()
                                ? null : resolved.get());
                    } catch (RuntimeException unavailable) {
                        outcome = new CredentialOutcome(null);
                    }
                    handoff.publish(outcome);
                } finally {
                    thread.setContextClassLoader(previous);
                    admission.release();
                }
            });
            handoff.submitted(resolver);
            resolver.start();
        } catch (RuntimeException submissionFailure) {
            handoff.abandon();
            admission.release();
            throw new SourceFailure("credential-unavailable");
        }
        return handoff.await(() -> current(sessionGeneration),
                Math.min(AngusImapConsumerProtocol.MAX_IO_TIMEOUT_MS, profile.connectTimeoutMs()));
    }

    private static String leaseKey(String tenant, String profile, String folder) {
        return encode(tenant) + "/" + encode(profile) + "/" + encode(folder);
    }

    private static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long unsigned32(long value, String reason) {
        if (value < 1 || value > 0xffff_ffffL) throw new SourceFailure(reason);
        return value;
    }

    private record Settings(String folder, int pollIntervalMs, int batchSize, int scanWindow,
                            int retryBackoffMs, int maxRetryBackoffMs, int poisonAttempts,
                            int timeoutMs, ImapMessageEvent.Limits limits) {
        static Settings resolve(NodeConfiguration c, ImapProfile profile, ImapConsumerPolicy policy) {
            if (!MailImapConsumeNodeBehavior.knownConfiguration().containsAll(c.properties().keySet()))
                throw new SourceFailure("unknown-graph-property");
            if (!profile.folders().contains(policy.folder()))
                throw new SourceFailure("imap-folder-not-authorized");
            String requestedFolder = c.property("folder", "");
            if (!requestedFolder.isEmpty() && !requestedFolder.equals(policy.folder()))
                throw new SourceFailure("imap-folder-not-authorized");
            int poll = floor(c.property("pollIntervalMs", ""), policy.pollIntervalMs(), 60_000,
                    "invalid-poll-interval");
            int batch = tighten(c.property("batchSize", ""),
                    Math.min(policy.batchSize(), profile.maxResults()), 1, "invalid-batch-size");
            if (!"1".equals(c.property("maxInFlight", "1")))
                throw new SourceFailure("invalid-max-in-flight");
            int retry = floor(c.property("retryBackoffMs", ""), policy.retryBackoffMs(), 60_000,
                    "invalid-retry-backoff");
            int maxRetry = floor(c.property("maxRetryBackoffMs", ""),
                    Math.max(policy.maxRetryBackoffMs(), retry), 60_000,
                    "invalid-max-retry-backoff");
            if (maxRetry < retry) throw new SourceFailure("invalid-max-retry-backoff");
            int poison = tighten(c.property("poisonAttempts", ""), policy.poisonAttempts(), 1,
                    "invalid-poison-attempts");
            String contentMode = c.property("contentMode", "metadata");
            if (!contentMode.equals("metadata") && !contentMode.equals("preview"))
                throw new SourceFailure("invalid-content-mode");
            if (contentMode.equals("preview") && !policy.contentMode().equals("preview"))
                throw new SourceFailure("content-preview-forbidden");
            int preview = 0;
            // Deliberately do not read a hidden previewChars value unless its condition holds.
            if (contentMode.equals("preview")) {
                preview = tighten(c.requiredProperty("previewChars"),
                        Math.min(policy.maxPreviewChars(), profile.maxPreviewChars()), 1,
                        "invalid-preview-chars");
            }
            if (!"require-durable".equals(c.property("checkpointPolicy", "require-durable")))
                throw new SourceFailure("invalid-checkpoint-policy");
            return new Settings(policy.folder(), poll, batch, policy.scanWindow(), retry, maxRetry,
                    poison, Math.min(30_000, profile.readTimeoutMs()),
                    new ImapMessageEvent.Limits(policy.maxMessageBytes(), contentMode, preview,
                            allowedHeaders(c, policy)));
        }

        private static Set<String> allowedHeaders(NodeConfiguration configuration,
                                                  ImapConsumerPolicy policy) {
            if (!configuration.properties().containsKey("allowedHeaders"))
                return policy.allowedHeaders();
            final Set<String> requested;
            try {
                requested = ImapConsumerPolicy.parseHeaders(
                        configuration.property("allowedHeaders", ""));
            } catch (RuntimeException invalid) {
                throw new SourceFailure("invalid-allowed-headers");
            }
            if (!policy.allowedHeaders().containsAll(requested))
                throw new SourceFailure("imap-headers-not-authorized");
            return requested;
        }

        private static int tighten(String raw, int ceiling, int minimum, String reason) {
            if (raw == null || raw.isBlank()) return ceiling;
            try {
                int value = Integer.parseInt(raw);
                if (value < minimum || value > ceiling) throw new NumberFormatException();
                return value;
            } catch (RuntimeException invalid) { throw new SourceFailure(reason); }
        }

        private static int floor(String raw, int minimum, int ceiling, String reason) {
            if (raw == null || raw.isBlank()) return minimum;
            try {
                int value = Integer.parseInt(raw);
                if (value < minimum || value > ceiling) throw new NumberFormatException();
                return value;
            } catch (RuntimeException invalid) { throw new SourceFailure(reason); }
        }
    }

    private static final class FailureStreak {
        private int attempts;
        int failed() { if (attempts < Integer.MAX_VALUE) attempts++; return attempts; }
        void reset() { attempts = 0; }
    }

    private record CredentialOutcome(SecretValue secret) { }

    private record ResolverAdmission(String key, Object token, boolean acquired) {
        static ResolverAdmission acquire(String tenant, String profile) {
            if (!RESOLVER_SLOTS.tryAcquire()) return new ResolverAdmission("", null, false);
            String key = encode(tenant) + "/" + encode(profile);
            Object token = new Object();
            if (RESOLVER_LEASES.putIfAbsent(key, token) != null) {
                RESOLVER_SLOTS.release();
                return new ResolverAdmission("", null, false);
            }
            return new ResolverAdmission(key, token, true);
        }

        void release() {
            if (acquired && RESOLVER_LEASES.remove(key, token)) RESOLVER_SLOTS.release();
        }
    }

    /** Cancellable one-result handoff; an abandoned resolver can never leak a late secret. */
    private static final class CredentialHandoff {
        private Thread resolver;
        private CredentialOutcome outcome;
        private boolean abandoned;

        synchronized void submitted(Thread value) {
            resolver = value;
            if (abandoned) value.interrupt();
        }

        void publish(CredentialOutcome value) {
            SecretValue late = null;
            synchronized (this) {
                if (abandoned) late = value.secret();
                else { outcome = value; notifyAll(); }
            }
            close(late);
        }

        SecretValue await(java.util.function.BooleanSupplier current, int timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            Thread interrupt = null;
            SecretValue late = null;
            CredentialOutcome available = null;
            boolean interrupted = false;
            String failure = "credential-unavailable";
            synchronized (this) {
                while (true) {
                    if (!current.getAsBoolean()) {
                        failure = "startup-cancelled";
                        abandoned = true;
                        interrupt = resolver;
                        if (outcome != null) late = outcome.secret();
                        outcome = null;
                        break;
                    }
                    if (outcome != null) {
                        available = outcome;
                        outcome = null;
                        break;
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        abandoned = true;
                        interrupt = resolver;
                        break;
                    }
                    long waitNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100));
                    long millis = TimeUnit.NANOSECONDS.toMillis(waitNanos);
                    int nanos = (int) (waitNanos - TimeUnit.MILLISECONDS.toNanos(millis));
                    try { wait(millis, nanos); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            if (interrupt != null) interrupt.interrupt();
            close(late);
            if (interrupted) Thread.currentThread().interrupt();
            if (available == null || available.secret() == null) throw new SourceFailure(failure);
            return available.secret();
        }

        void abandon() {
            Thread interrupt;
            SecretValue late = null;
            synchronized (this) {
                abandoned = true;
                interrupt = resolver;
                if (outcome != null) late = outcome.secret();
                outcome = null;
            }
            if (interrupt != null) interrupt.interrupt();
            close(late);
        }

        private static void close(SecretValue secret) {
            if (secret != null) try { secret.close(); } catch (RuntimeException ignored) { }
        }
    }

    /** Serial source => at most one failed projection identity can be pending across reconnects. */
    static final class ProjectionFailureTracker {
        private String key;
        private int attempts;
        int failed(String value) {
            if (!Objects.equals(key, value)) { key = value; attempts = 0; }
            if (attempts < Integer.MAX_VALUE) attempts++;
            return attempts;
        }
        void succeeded(String value) { if (Objects.equals(key, value)) { key = null; attempts = 0; } }
        int size() { return key == null ? 0 : 1; }
    }

    private static final class SourceFailure extends RuntimeException {
        private final String safeReason;
        SourceFailure(String safeReason) { super(safeReason); this.safeReason = safeReason; }
    }
}
