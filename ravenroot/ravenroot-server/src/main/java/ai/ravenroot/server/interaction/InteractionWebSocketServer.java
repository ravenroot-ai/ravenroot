package ai.ravenroot.server.interaction;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.core.humantask.HumanTaskResult;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.RequestAuthenticator;
import com.sun.net.httpserver.Headers;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Independently bound, optional server for the authenticated interaction protocol. */
public final class InteractionWebSocketServer implements AutoCloseable {
    private final InteractionWebSocketConfiguration configuration;
    private final AuthorizedRavenrootApplication application;
    private final RequestAuthenticator authenticator;
    private final BrowserOriginPolicy origins;
    private final Duration revalidationInterval;
    private final RateLimiter rateLimiter;
    private final HumanTaskService humanTasks;
    private final Consumer<String> humanTaskSweep;
    private final Clock clock;
    private final Admission admission;
    private final Semaphore backendWork;
    private final ScheduledThreadPoolExecutor scheduler = scheduler();
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Server server;
    private final ServerConnector connector;
    private final java.util.Set<InteractionSession> sessions = ConcurrentHashMap.newKeySet();

    private static ScheduledThreadPoolExecutor scheduler() {
        var result = new ScheduledThreadPoolExecutor(2,
                Thread.ofVirtual().name("ravenroot-interaction-deadline-", 0).factory());
        result.setRemoveOnCancelPolicy(true);
        result.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        result.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return result;
    }

    public InteractionWebSocketServer(InteractionWebSocketConfiguration configuration,
                                      AuthorizedRavenrootApplication application,
                                      RequestAuthenticator authenticator,
                                      BrowserOriginPolicy origins,
                                      Duration revalidationInterval,
                                      RateLimiter rateLimiter,
                                      HumanTaskService humanTasks,
                                      Consumer<String> humanTaskSweep,
                                      Clock clock) {
        this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
        this.application = java.util.Objects.requireNonNull(application, "application");
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");
        this.origins = java.util.Objects.requireNonNull(origins, "origins");
        this.revalidationInterval = java.util.Objects.requireNonNull(revalidationInterval, "revalidationInterval");
        this.rateLimiter = java.util.Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.humanTasks = java.util.Objects.requireNonNull(humanTasks, "humanTasks");
        this.humanTaskSweep = java.util.Objects.requireNonNull(humanTaskSweep, "humanTaskSweep");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.admission = new Admission(configuration.maxPendingAuthentication(),
                configuration.maxPendingAuthenticationPerAddress());
        this.backendWork = new Semaphore(configuration.maxBackendOperations(), true);

        server = new Server();
        connector = new ServerConnector(server);
        connector.setHost(configuration.bindAddress().getAddress().getHostAddress());
        connector.setPort(configuration.bindAddress().getPort());
        connector.setAcceptQueueSize(Math.min(configuration.maxConnections(), 128));
        server.addConnector(connector);
        server.addBean(new NetworkConnectionLimit(configuration.maxConnections(), server));
        var context = new ContextHandler("/");
        var upgrades = WebSocketUpgradeHandler.from(server, context, container -> {
            // The application timer below supplies a fixed, sanitized close contract.
            container.setIdleTimeout(Duration.ZERO);
            container.setMaxTextMessageSize(configuration.maxMessageBytes());
            container.setMaxBinaryMessageSize(1);
            container.setMaxFrameSize(configuration.maxOutgoingFrameBytes());
            container.setAutoFragment(false);
            container.setMaxOutgoingFrames(configuration.maxQueuedOutgoingFrames());
            container.addMapping(InteractionWebSocketConfiguration.PATH, this::create);
        });
        context.setHandler(upgrades);
        server.setHandler(context);
    }

    public void start() {
        try {
            server.start();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot start interaction WebSocket listener", failure);
        }
    }

    public int localPort() {
        return connector.getLocalPort();
    }

    int availableBackendOperations() {
        return backendWork.availablePermits();
    }

    private Object create(ServerUpgradeRequest request, ServerUpgradeResponse response,
                          org.eclipse.jetty.util.Callback callback) {
        Headers headers = headers(org.eclipse.jetty.http.HttpFields.asMap(request.getHeaders()));
        String query = request.getHttpURI().getQuery();
        var remote = request.getConnectionMetaData().getRemoteSocketAddress();
        if (!(remote instanceof InetSocketAddress peer)) return reject(response, callback, 400);
        String peerAddress = peer.getAddress().getHostAddress();
        var peerLimit = rateLimiter.checkAddress(peerAddress);
        if (!peerLimit.isAllowed()) return reject(response, callback, peerLimit.status());
        var shape = rateLimiter.checkRequestShape(headers, query);
        if (!shape.isAllowed()) return reject(response, callback, shape.status());
        if (request.getHeaders().get("Authorization") != null || query != null) {
            return reject(response, callback, 400);
        }
        if (!request.getSubProtocols().equals(List.of(InteractionWebSocketConfiguration.SUBPROTOCOL))) {
            return reject(response, callback, 400);
        }
        var origin = origins.evaluate(request.getHeaders().getValuesList("Origin"));
        if (!origin.accepted()) return reject(response, callback, origin.status());
        var resolved = rateLimiter.resolveClient(peer, headers);
        if (!(resolved instanceof ai.ravenroot.server.ratelimit.TrustedProxyConfiguration.Resolution.Client client)) {
            return reject(response, callback, 400);
        }
        if (!peerAddress.equals(client.address())) {
            var addressLimit = rateLimiter.checkAddress(client.address());
            if (!addressLimit.isAllowed()) return reject(response, callback, addressLimit.status());
        }
        Admission.Lease pending = admission.acquire(client.address());
        if (pending == null) return reject(response, callback, 503);
        response.setAcceptedSubProtocol(InteractionWebSocketConfiguration.SUBPROTOCOL);
        response.setExtensions(List.of());
        var session = new InteractionSession(pending);
        sessions.add(session);
        return session;
    }

    private static Object reject(ServerUpgradeResponse response, org.eclipse.jetty.util.Callback callback,
                                 int status) {
        response.setStatus(status);
        callback.succeeded();
        return null;
    }

    private static Headers headers(Map<String, List<String>> source) {
        var result = new Headers();
        source.forEach((name, values) -> result.put(name, new ArrayList<>(values)));
        return result;
    }

    @Override
    public void close() {
        server.setStopTimeout(configuration.shutdownTimeout().toMillis());
        try {
            server.stop();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot stop interaction WebSocket listener", failure);
        } finally {
            sessions.forEach(session -> session.close(1001, "server shutdown"));
            scheduler.shutdownNow();
            workers.shutdown();
            try {
                if (!workers.awaitTermination(configuration.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public final class InteractionSession implements Session.Listener {
        private enum State { AUTHENTICATING, READY, CLOSED }
        private record Delivery(UUID eventId, Instant sentAt) { }
        private record OutboundMessage(List<String> parts, Runnable onSent) { }
        private final Admission.Lease pending;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final String connectionRequestId = UUID.randomUUID().toString();
        private final StringBuilder inbound = new StringBuilder();
        private final ArrayDeque<String> inboundMessages = new ArrayDeque<>();
        private final LinkedHashMap<Long, Delivery> unacknowledged = new LinkedHashMap<>();
        private final LinkedHashMap<Long, UUID> acknowledged = new LinkedHashMap<>();
        private final AtomicInteger pendingCommands = new AtomicInteger();
        private final AtomicBoolean maintenanceQueued = new AtomicBoolean();
        private final AtomicBoolean replayRunning = new AtomicBoolean();
        private final AtomicLong authorityEpoch = new AtomicLong();
        private final ArrayDeque<OutboundMessage> outbound = new ArrayDeque<>();
        private int inboundBytes;
        private int partialBytes;
        private boolean processing;
        private int outboundFrames;
        private int outboundBytes;
        private int fragments;
        private boolean sending;
        private volatile State state = State.AUTHENTICATING;
        private volatile Session session;
        private volatile AuthenticatedPrincipal principal;
        private volatile Headers credential;
        private final AtomicReference<RateLimiter.Lease> streamLease = new AtomicReference<>();
        private volatile long replayOffset;
        private volatile Instant revalidateAt;
        private boolean replayActive;
        private volatile boolean authorizationValid;
        private final AtomicReference<ScheduledFuture<?>> authenticationTimer = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> lifetimeTimer = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> idleTimer = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> maintenanceTimer = new AtomicReference<>();

        private InteractionSession(Admission.Lease pending) {
            this.pending = pending;
        }

        @Override
        public void onWebSocketOpen(Session opened) {
            session = opened;
            opened.setMaxFrameSize(configuration.maxOutgoingFrameBytes());
            opened.setAutoFragment(false);
            opened.setMaxOutgoingFrames(configuration.maxQueuedOutgoingFrames());
            installTimer(authenticationTimer, scheduler.schedule(() -> {
                if (state == State.AUTHENTICATING) close(1008, "authentication required");
            }, configuration.authenticationDeadline().toMillis(), TimeUnit.MILLISECONDS));
            installTimer(lifetimeTimer, scheduler.schedule(() -> close(1000, "session lifetime complete"),
                    configuration.absoluteLifetime().toMillis(), TimeUnit.MILLISECONDS));
            touchIdle();
            opened.demand();
        }

        @Override
        public synchronized void onWebSocketPartialText(String fragment, boolean fin) {
            if (closed.get()) return;
            touchIdle();
            fragments++;
            partialBytes += fragment.getBytes(StandardCharsets.UTF_8).length;
            if (fragments > configuration.maxFragments() || partialBytes > configuration.maxMessageBytes()) {
                close(1009, "message too large");
                return;
            }
            inbound.append(fragment);
            if (fin) {
                String complete = inbound.toString();
                inbound.setLength(0);
                fragments = 0;
                int completeBytes = partialBytes;
                partialBytes = 0;
                if (inboundMessages.size() >= configuration.maxPendingCommands()
                        || inboundBytes + completeBytes > configuration.maxQueuedIncomingBytes()) {
                    close(1013, "inbound queue full");
                    return;
                }
                inboundMessages.add(complete);
                inboundBytes += completeBytes;
                if (!processing) {
                    processing = true;
                    if (!submitBackend(this::drainInbound)) {
                        processing = false;
                        close(1013, "backend capacity unavailable");
                        return;
                    }
                }
            }
            Session current = session;
            if (current != null && current.isOpen()) current.demand();
        }

        private void drainInbound() {
            while (true) {
                String message;
                synchronized (this) {
                    message = inboundMessages.poll();
                    if (message == null || closed.get()) {
                        processing = false;
                        return;
                    }
                    inboundBytes -= message.getBytes(StandardCharsets.UTF_8).length;
                }
                process(message);
            }
        }

        @Override
        public void onWebSocketBinary(java.nio.ByteBuffer payload, Callback callback) {
            callback.succeed();
            close(1003, "binary messages are unsupported");
        }

        private void process(String text) {
            if (closed.get()) return;
            try {
                InteractionProtocol.Inbound message = InteractionProtocol.parse(text,
                        configuration.maxMessageBytes());
                switch (state) {
                    case AUTHENTICATING -> authenticate(message);
                    case READY -> ready(message);
                    case CLOSED -> { }
                }
            } catch (InteractionProtocol.ProtocolFailure failure) {
                close(failure.closeCode(), failure.getMessage());
            } catch (AuthenticationException failure) {
                close(1008, "authentication failed");
            } catch (RuntimeException failure) {
                close(1011, "internal error");
            }
        }

        private void authenticate(InteractionProtocol.Inbound message) throws AuthenticationException {
            if (!(message instanceof InteractionProtocol.Authenticate authentication)) {
                close(1002, "authenticate first");
                return;
            }
            var authHeaders = new Headers();
            authHeaders.set("Authorization", "Bearer " + authentication.bearer());
            AuthenticatedPrincipal authenticated = authenticator.authenticate(authHeaders);
            if (closed.get()) return;
            if (!clock.instant().isBefore(authenticated.expiresAt())) throw new AuthenticationException("expired");
            var identityLimit = rateLimiter.checkIdentity(authenticated.tenantId(), authenticated.subject());
            if (!identityLimit.isAllowed()) {
                close(1013, "capacity unavailable");
                return;
            }
            RateLimiter.Lease lease = rateLimiter.acquireStreamSlot(authenticated.tenantId(), authenticated.subject());
            if (!lease.granted()) {
                lease.close();
                close(1013, "capacity unavailable");
                return;
            }
            if (closed.get()) {
                lease.close();
                return;
            }
            principal = authenticated;
            credential = authHeaders;
            if (!streamLease.compareAndSet(null, lease)) {
                lease.close();
                close(1011, "internal error");
                return;
            }
            pending.close();
            authorizationValid = true;
            state = State.READY;
            revalidateAt = nextRevalidation(authenticated);
            if (closed.get()) {
                authorizationValid = false;
                state = State.CLOSED;
                credential = null;
                principal = null;
                RateLimiter.Lease installed = streamLease.getAndSet(null);
                if (installed != null) installed.close();
                return;
            }
            cancelTimer(authenticationTimer);
            enqueue(InteractionProtocol.authenticated());
            long pollMillis = configuration.replayPollInterval().toMillis();
            installTimer(maintenanceTimer, scheduler.scheduleWithFixedDelay(this::scheduleMaintenance,
                    pollMillis, pollMillis,
                    TimeUnit.MILLISECONDS));
        }

        private void scheduleMaintenance() {
            if (closed.get() || !maintenanceQueued.compareAndSet(false, true)) return;
            if (!submitBackend(() -> {
                try {
                    maintain();
                } finally {
                    maintenanceQueued.set(false);
                }
            })) {
                maintenanceQueued.set(false);
            }
        }

        private boolean submitBackend(Runnable operation) {
            if (!backendWork.tryAcquire()) return false;
            try {
                workers.submit(() -> {
                    try {
                        operation.run();
                    } finally {
                        backendWork.release();
                    }
                });
                return true;
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                backendWork.release();
                return false;
            }
        }

        private void resume(InteractionProtocol.Inbound message) throws AuthenticationException {
            if (!(message instanceof InteractionProtocol.Resume resume)) {
                close(1002, "resume required");
                return;
            }
            ensureAuthorized();
            RequestContext context = connectionContext(principal);
            try {
                application.authorizeExecutionEvents(context);
            } catch (SecurityException denied) {
                close(1008, "authorization lost");
                return;
            }
            ensureAuthorized();
            synchronized (this) {
                if (replayActive) {
                    close(1002, "resume already selected");
                    return;
                }
                replayOffset = resume.afterJournalOffset();
                replayActive = true;
            }
            replay();
        }

        private void ready(InteractionProtocol.Inbound message) throws AuthenticationException {
            ensureAuthorized();
            AuthenticatedPrincipal current = principal;
            var limit = rateLimiter.checkIdentity(current.tenantId(), current.subject());
            if (!limit.isAllowed()) {
                close(1013, "capacity unavailable");
                return;
            }
            if (message instanceof InteractionProtocol.Acknowledge acknowledgement) {
                if (!replayActive) {
                    close(1002, "resume required before acknowledgement");
                    return;
                }
                acknowledge(acknowledgement);
            } else if (message instanceof InteractionProtocol.Resume) {
                resume(message);
            } else if (message instanceof InteractionProtocol.Command command) {
                command(command);
            } else {
                close(1002, "message is invalid in current state");
            }
        }

        private void acknowledge(InteractionProtocol.Acknowledge acknowledgement)
                throws AuthenticationException {
            synchronized (this) {
                Delivery expected = unacknowledged.get(acknowledgement.journalOffset());
                if (expected == null) {
                    UUID duplicate = acknowledged.get(acknowledgement.journalOffset());
                    if (acknowledgement.eventId().equals(duplicate)) return;
                    close(1002, "acknowledgement is invalid");
                    return;
                }
                if (expected.sentAt() == null || !expected.eventId().equals(acknowledgement.eventId())) {
                    close(1002, "acknowledgement is invalid");
                    return;
                }
                var accepted = new ArrayList<Map.Entry<Long, Delivery>>();
                for (var entry : unacknowledged.entrySet()) {
                    if (entry.getKey() <= acknowledgement.journalOffset()) {
                        accepted.add(Map.entry(entry.getKey(), entry.getValue()));
                    }
                }
                accepted.forEach(entry -> {
                    unacknowledged.remove(entry.getKey());
                    acknowledged.put(entry.getKey(), entry.getValue().eventId());
                });
                while (acknowledged.size() > configuration.maxUnacknowledgedEvents()) {
                    acknowledged.remove(acknowledged.firstEntry().getKey());
                }
            }
            replay();
        }

        private void command(InteractionProtocol.Command command) throws AuthenticationException {
            ensureAuthorized();
            if (pendingCommands.incrementAndGet() > configuration.maxPendingCommands()) {
                pendingCommands.decrementAndGet();
                close(1013, "capacity unavailable");
                return;
            }
            try {
                RequestContext context = commandContext(principal);
                HumanTaskResult result = switch (command.name()) {
                    case "human-task.resolve" -> {
                        int authorizedLimit = humanTasks.authorizedResponseBodyLimit(context, command.taskId())
                                .orElse(-1);
                        if (authorizedLimit < 0) {
                            yield new HumanTaskResult(HumanTaskResult.Code.UNAUTHORIZED, null, null);
                        }
                        if (command.payload().length > authorizedLimit) {
                            yield new HumanTaskResult(HumanTaskResult.Code.PAYLOAD_REFUSED, null, null);
                        }
                        ensureAuthorized();
                        yield humanTasks.resolve(context, command.taskId(), command.generation(),
                                OpaquePayload.of(command.payload(), command.contentType()),
                                command.comment() == null ? "" : command.comment());
                    }
                    case "human-task.deny" -> humanTasks.deny(context, command.taskId(), command.generation(),
                            command.comment() == null ? "" : command.comment());
                    case "human-task.cancel" -> humanTasks.cancel(context, command.taskId(), command.generation(),
                            command.comment() == null ? "" : command.comment());
                    default -> throw new IllegalStateException("unreachable command");
                };
                if (result.resumeTraversalId() != null) humanTaskSweep.accept(context.tenantId());
                ensureAuthorized();
                enqueue(InteractionProtocol.commandResult(command, result));
            } finally {
                pendingCommands.decrementAndGet();
            }
        }

        private void maintain() {
            if (closed.get() || state == State.AUTHENTICATING) return;
            try {
                ensureAuthorized();
                if (replayActive) application.authorizeExecutionEvents(connectionContext(principal));
                ensureAuthorized();
                Instant oldest;
                synchronized (this) {
                    oldest = oldestSentAt();
                }
                if (oldest != null && !clock.instant().isBefore(oldest.plus(configuration.acknowledgementDeadline()))) {
                    close(1013, "acknowledgement timeout");
                    return;
                }
                if (replayActive) replay();
            } catch (AuthenticationException | SecurityException denied) {
                close(1008, "authorization lost");
            } catch (RuntimeException failure) {
                close(1011, "internal error");
            }
        }

        private void replay() throws AuthenticationException {
            if (!replayRunning.compareAndSet(false, true)) return;
            long requestedAfter;
            int limit;
            synchronized (this) {
                if (!replayActive || state != State.READY || closed.get() || !authorizationValid
                        || unacknowledged.size() >= configuration.maxUnacknowledgedEvents()) {
                    replayRunning.set(false);
                    return;
                }
                requestedAfter = replayOffset;
                limit = configuration.maxUnacknowledgedEvents() - unacknowledged.size();
            }
            try {
                List<ai.ravenroot.api.application.DurableExecutionEvent> events;
                try {
                    events = application.durableEventsAfter(connectionContext(principal), requestedAfter, limit);
                } catch (ExecutionStoreException failure) {
                    if (failure.failure() instanceof ExecutionStoreFailure.JournalTruncated truncated) {
                        ensureAuthorized();
                        application.authorizeExecutionEvents(connectionContext(principal));
                        ensureAuthorized();
                        synchronized (this) {
                            replayActive = false;
                            enqueue(InteractionProtocol.streamTruncated(truncated.retainedFrom()),
                                    () -> close(1008, "replay cursor unavailable"));
                        }
                        return;
                    }
                    throw failure;
                }
                ensureAuthorized();
                application.authorizeExecutionEvents(connectionContext(principal));
                ensureAuthorized();
                synchronized (this) {
                    if (replayOffset != requestedAfter || closed.get() || !replayActive) return;
                    for (var event : events) {
                        String wire = InteractionProtocol.event(event);
                        if (wire.getBytes(StandardCharsets.UTF_8).length > configuration.maxMessageBytes()) {
                            close(1009, "event exceeds configured message limit");
                            return;
                        }
                        unacknowledged.put(event.journalOffset(), new Delivery(event.eventId(), null));
                        if (!enqueue(wire, () -> markSent(event.journalOffset(), event.eventId()))) {
                            unacknowledged.remove(event.journalOffset());
                            return;
                        }
                        replayOffset = event.journalOffset();
                    }
                }
            } finally {
                replayRunning.set(false);
            }
        }

        private synchronized boolean enqueue(String message) {
            return enqueue(message, () -> { });
        }

        private synchronized boolean enqueue(String message, Runnable onSent) {
            if (closed.get() || !authorizationValid) return false;
            List<String> parts = utf8Fragments(message, configuration.maxOutgoingFrameBytes());
            int bytes = message.getBytes(StandardCharsets.UTF_8).length;
            if (outboundFrames + parts.size() > configuration.maxQueuedOutgoingFrames()
                    || outboundBytes + bytes > configuration.maxQueuedOutgoingBytes()) {
                close(1013, "outbound queue full");
                return false;
            }
            outbound.add(new OutboundMessage(parts, onSent));
            outboundFrames += parts.size();
            outboundBytes += bytes;
            if (!sending) sendNext();
            return true;
        }

        private synchronized void sendNext() {
            if (closed.get()) return;
            OutboundMessage message = outbound.peek();
            if (message == null) {
                sending = false;
                return;
            }
            sending = true;
            sendPart(message, 0);
        }

        private void sendPart(OutboundMessage message, int index) {
            Session current = session;
            if (current == null || !current.isOpen() || !eligibleForDelivery()) {
                close(1008, "authorization lost");
                return;
            }
            String part = message.parts().get(index);
            boolean fin = index == message.parts().size() - 1;
            current.sendPartialText(part, fin, Callback.from(() -> {
                synchronized (InteractionSession.this) {
                    if (!eligibleForDelivery()) {
                        close(1008, "authorization lost");
                        return;
                    }
                    outboundFrames--;
                    outboundBytes -= part.getBytes(StandardCharsets.UTF_8).length;
                    if (fin) {
                        outbound.remove();
                        message.onSent().run();
                        touchIdle();
                        sendNext();
                    } else {
                        sendPart(message, index + 1);
                    }
                }
            }, failure -> close(1011, "send failed")));
        }

        private synchronized void markSent(long offset, UUID eventId) {
            Delivery delivery = unacknowledged.get(offset);
            if (delivery != null && delivery.eventId().equals(eventId)) {
                unacknowledged.put(offset, new Delivery(eventId, clock.instant()));
            }
        }

        private Instant oldestSentAt() {
            for (Delivery delivery : unacknowledged.values()) {
                if (delivery.sentAt() != null) return delivery.sentAt();
            }
            return null;
        }

        private void ensureAuthorized() throws AuthenticationException {
            long epoch = authorityEpoch.get();
            AuthenticatedPrincipal expected = principal;
            Instant due = revalidateAt;
            Headers retainedCredential = credential;
            if (!authorizationValid || closed.get() || expected == null || due == null) {
                throw new AuthenticationException("closed");
            }
            Instant now = clock.instant();
            if (!now.isBefore(expected.expiresAt()) || !now.isBefore(due)) {
                AuthenticatedPrincipal current = authenticator.revalidate(retainedCredential);
                now = clock.instant();
                synchronized (this) {
                    if (authorityEpoch.get() != epoch || closed.get() || !authorizationValid
                            || !sameIdentity(expected, current) || !now.isBefore(current.expiresAt())) {
                        authorizationValid = false;
                        throw new AuthenticationException("changed");
                    }
                    principal = current;
                    revalidateAt = nextRevalidation(current);
                }
            }
        }

        private boolean eligibleForDelivery() {
            AuthenticatedPrincipal current = principal;
            Instant due = revalidateAt;
            Instant now = clock.instant();
            return !closed.get() && authorizationValid && current != null && due != null
                    && now.isBefore(current.expiresAt()) && now.isBefore(due);
        }

        private synchronized void touchIdle() {
            if (!closed.get()) {
                installTimer(idleTimer, scheduler.schedule(() -> close(1000, "session idle"),
                        configuration.idleTimeout().toMillis(), TimeUnit.MILLISECONDS));
            }
        }

        private void installTimer(AtomicReference<ScheduledFuture<?>> slot, ScheduledFuture<?> installed) {
            ScheduledFuture<?> replaced = slot.getAndSet(installed);
            if (replaced != null) replaced.cancel(false);
            if (closed.get() && slot.compareAndSet(installed, null)) installed.cancel(false);
        }

        private static void cancelTimer(AtomicReference<ScheduledFuture<?>> slot) {
            ScheduledFuture<?> installed = slot.getAndSet(null);
            if (installed != null) installed.cancel(false);
        }

        private Instant nextRevalidation(AuthenticatedPrincipal current) {
            Instant interval = clock.instant().plus(revalidationInterval);
            return current.expiresAt().isBefore(interval) ? current.expiresAt() : interval;
        }

        private RequestContext connectionContext(AuthenticatedPrincipal current) {
            return context(connectionRequestId, current);
        }

        private RequestContext commandContext(AuthenticatedPrincipal current) {
            return context(UUID.randomUUID().toString(), current);
        }

        private RequestContext context(String trustedRequestId, AuthenticatedPrincipal current) {
            return new RequestContext(trustedRequestId, current.subject(),
                    current.type() == AuthenticatedPrincipal.Type.USER ? PrincipalType.USER : PrincipalType.WORKLOAD,
                    current.issuer(), current.tenantId(), current.roles(), current.scopes());
        }

        private void close(int code, String reason) {
            if (!closed.compareAndSet(false, true)) return;
            authorityEpoch.incrementAndGet();
            authorizationValid = false;
            state = State.CLOSED;
            credential = null;
            principal = null;
            cancelTimer(authenticationTimer);
            cancelTimer(lifetimeTimer);
            cancelTimer(idleTimer);
            cancelTimer(maintenanceTimer);
            pending.close();
            RateLimiter.Lease lease = streamLease.getAndSet(null);
            if (lease != null) lease.close();
            sessions.remove(this);
            Session current = session;
            if (current != null && current.isOpen()) current.close(code, reason, Callback.NOOP);
            try {
                workers.submit(this::clearRetainedState);
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                // Listener shutdown already owns the worker lifecycle.
            }
        }

        private synchronized void clearRetainedState() {
            authorizationValid = false;
            state = State.CLOSED;
            RateLimiter.Lease lease = streamLease.getAndSet(null);
            if (lease != null) lease.close();
                credential = null;
                principal = null;
                inbound.setLength(0);
                inboundMessages.clear();
                outbound.clear();
                unacknowledged.clear();
                acknowledged.clear();
                inboundBytes = 0;
                outboundBytes = 0;
                outboundFrames = 0;
        }

        @Override public void onWebSocketClose(int statusCode, String reason, Callback callback) {
            close(statusCode, "closed");
            callback.succeed();
        }
        @Override public void onWebSocketError(Throwable cause) { close(1011, "internal error"); }
    }

    private static boolean sameIdentity(AuthenticatedPrincipal expected, AuthenticatedPrincipal actual) {
        return expected.subject().equals(actual.subject()) && expected.type() == actual.type()
                && expected.issuer().equals(actual.issuer()) && expected.tenantId().equals(actual.tenantId())
                && expected.roles().equals(actual.roles()) && expected.scopes().equals(actual.scopes());
    }

    static List<String> utf8Fragments(String value, int maxBytes) {
        var result = new ArrayList<String>();
        var part = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes && !part.isEmpty()) {
                result.add(part.toString());
                part.setLength(0);
                bytes = 0;
            }
            part.append(character);
            bytes += size;
            offset += Character.charCount(codePoint);
        }
        result.add(part.toString());
        return List.copyOf(result);
    }

    private static final class Admission {
        private final int globalLimit;
        private final int perAddressLimit;
        private int global;
        private final Map<String, Integer> addresses = new java.util.HashMap<>();
        Admission(int globalLimit, int perAddressLimit) {
            this.globalLimit = globalLimit;
            this.perAddressLimit = perAddressLimit;
        }
        synchronized Lease acquire(String address) {
            int count = addresses.getOrDefault(address, 0);
            if (global >= globalLimit || count >= perAddressLimit) return null;
            global++;
            addresses.put(address, count + 1);
            return new Lease(address);
        }
        final class Lease implements AutoCloseable {
            private final String address;
            private boolean released;
            Lease(String address) { this.address = address; }
            @Override public void close() {
                synchronized (Admission.this) {
                    if (released) return;
                    released = true;
                    global--;
                    int remaining = addresses.get(address) - 1;
                    if (remaining == 0) addresses.remove(address); else addresses.put(address, remaining);
                }
            }
        }
    }
}
