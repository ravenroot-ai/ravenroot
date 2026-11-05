package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.CredentialResolver;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shipped provider-authenticated push hints. No event content is admitted as repository state. */
public final class GraphAuthoringWebhookHttpApi implements AutoCloseable {
    public static final String PATH = "/v1/graph-authoring-webhooks";
    private static final int MAX_BYTES = 256 * 1024;
    private static final PayloadLimits LIMITS =
            new PayloadLimits(MAX_BYTES, 24, 10_000, 20_000, MAX_BYTES, 512);
    private static final long REPLAY_SECONDS = 300;
    private final GraphAuthoringConfiguration source;
    private final GraphAuthoringWebhookConfiguration configuration;
    private final CredentialResolver credentials;
    private final GraphAuthoringRepository repository;
    private final Clock clock;
    private final Map<String, Set<String>> refs;
    private final Semaphore requests = new Semaphore(16);
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> pending = new LinkedHashSet<>();
    private final LinkedHashMap<String, Delivery> deliveries = new LinkedHashMap<>();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    Thread.ofVirtual().name("authoring-webhook-", 0).factory());
    private final java.util.concurrent.ScheduledThreadPoolExecutor deadlines =
            new java.util.concurrent.ScheduledThreadPoolExecutor(
                    1,
                    runnable -> {
                        Thread thread = new Thread(runnable, "authoring-webhook-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Map<HttpExchange, AuthoringAdmission> active =
            new java.util.concurrent.ConcurrentHashMap<>();
    // JDK21 HttpServer body streams use monitors around blocking reads. Dedicated bounded
    // platform readers prevent slow deliveries from pinning the shared virtual-thread carriers.
    private final ThreadPoolExecutor requestWorkers =
            new ThreadPoolExecutor(
                    16,
                    16,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    runnable -> {
                        Thread thread = new Thread(runnable, "authoring-webhook-request");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder accepted = new LongAdder(),
            duplicates = new LongAdder(),
            rejected = new LongAdder(),
            failures = new LongAdder();
    private boolean running;

    private record Delivery(String digest, long time) {}

    public GraphAuthoringWebhookHttpApi(
            GraphAuthoringConfiguration source,
            GraphAuthoringWebhookConfiguration configuration,
            CredentialResolver credentials,
            GraphAuthoringRepository repository) {
        this(source, configuration, credentials, repository, Clock.systemUTC());
    }

    GraphAuthoringWebhookHttpApi(
            GraphAuthoringConfiguration source,
            GraphAuthoringWebhookConfiguration configuration,
            CredentialResolver credentials,
            GraphAuthoringRepository repository,
            Clock clock) {
        this.source = java.util.Objects.requireNonNull(source);
        this.configuration = java.util.Objects.requireNonNull(configuration);
        this.credentials = java.util.Objects.requireNonNull(credentials);
        this.repository = java.util.Objects.requireNonNull(repository);
        this.clock = java.util.Objects.requireNonNull(clock);
        if (!configuration.enabled()
                || source.mode() != GraphAuthoringConfiguration.Mode.GIT
                || source.tenantNamespaces().size() > 256
                || source.provider() == GraphAuthoringConfiguration.Provider.GITHUB
                        && configuration.authentication()
                                != GraphAuthoringWebhookConfiguration.Authentication.SIGNATURE)
            throw new IllegalArgumentException("Invalid authoring webhook composition");
        var mapping = new LinkedHashMap<String, Set<String>>();
        source.tenantNamespaces()
                .forEach(
                        (tenant, namespace) ->
                                mapping.put(
                                        "refs/heads/"
                                                + source.draftBranch()
                                                + "/"
                                                + namespace.replace('/', '-'),
                                        Set.of(tenant)));
        mapping.put("refs/heads/" + source.releaseBranch(), source.tenantNamespaces().keySet());
        refs = Map.copyOf(mapping);
        requireCredentialAvailable();
        deadlines.setRemoveOnCancelPolicy(true);
        requestWorkers.allowCoreThreadTimeOut(true);
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!requests.tryAcquire()) {
            respond(exchange, 429);
            return;
        }
        var deadline =
                new java.util.concurrent.atomic.AtomicReference<
                        java.util.concurrent.ScheduledFuture<?>>();
        var task =
                new AuthoringAdmission(
                        requests,
                        () -> {
                            try {
                                handleAdmitted(exchange);
                            } catch (IOException disconnected) {
                                exchange.close();
                            } finally {
                                var ticket = deadline.get();
                                if (ticket != null) ticket.cancel(false);
                                active.remove(exchange);
                            }
                        });
        active.put(exchange, task);
        try {
            if (closed.get()) throw new java.util.concurrent.RejectedExecutionException();
            deadline.set(
                    deadlines.schedule(
                            () -> {
                                task.cancel(true);
                                active.remove(exchange);
                                exchange.close();
                            },
                            source.requestTimeout().toMillis(),
                            TimeUnit.MILLISECONDS));
            requestWorkers.execute(task);
        } catch (RuntimeException refused) {
            task.cancel(false);
            active.remove(exchange);
            var ticket = deadline.get();
            if (ticket != null) ticket.cancel(false);
            respond(exchange, 503);
        }
    }

    private void handleAdmitted(HttpExchange exchange) throws IOException {
        try {
            if (closed.get()) throw new Rejected(503);
            if (!PATH.equals(exchange.getRequestURI().getRawPath())
                    || exchange.getRequestURI().getRawQuery() != null) throw new Rejected(404);
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                throw new Rejected(405);
            }
            if (exchange.getRequestHeaders().containsKey("Authorization")
                    || exchange.getRequestHeaders().containsKey("Cookie")) throw new Rejected(403);
            if (!header(exchange, "Content-Type")
                            .toLowerCase(java.util.Locale.ROOT)
                            .matches("application/json(?:\\s*;\\s*charset=utf-8)?")
                    || exchange.getRequestHeaders().containsKey("Content-Encoding"))
                throw new Rejected(415);
            String length = optionalHeader(exchange, "Content-Length");
            if (!length.isEmpty()) {
                try {
                    if (Long.parseLong(length) < 0 || Long.parseLong(length) > MAX_BYTES)
                        throw new Rejected(413);
                } catch (NumberFormatException invalid) {
                    throw new Rejected(400);
                }
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new Rejected(413);
            String delivery = authenticate(exchange, bytes);
            Map<?, ?> event;
            try {
                Object parsed = PayloadJson.read(bytes, LIMITS).toJava();
                if (!(parsed instanceof Map<?, ?> map)) throw new Rejected(400);
                event = map;
            } catch (Rejected rejected) {
                throw rejected;
            } catch (RuntimeException invalid) {
                throw new Rejected(400);
            }
            boolean github = source.provider() == GraphAuthoringConfiguration.Provider.GITHUB;
            Map<?, ?> project = object(event.get(github ? "repository" : "project"));
            String expectedProject =
                    source.repositoryOwner().isEmpty()
                            ? source.repositoryName()
                            : source.repositoryOwner() + "/" + source.repositoryName();
            String actualProject =
                    github
                            ? string(project.get("full_name"))
                            : source.repositoryOwner().isEmpty()
                                    ? String.valueOf(project.get("id"))
                                    : string(project.get("path_with_namespace"));
            if (!(github
                    ? expectedProject.equalsIgnoreCase(actualProject)
                    : expectedProject.equals(actualProject))) throw new Rejected(403);
            String kind = header(exchange, github ? "X-GitHub-Event" : "X-Gitlab-Event");
            if (github && kind.equals("ping")) {
                respond(exchange, 204);
                return;
            }
            String ref = string(event.get("ref"));
            if (github && kind.equals("delete") && "branch".equals(event.get("ref_type")))
                ref = "refs/heads/" + ref;
            else if (!(github
                    ? kind.equals("push")
                    : kind.equals("Push Hook") && "push".equals(event.get("object_kind"))))
                throw new Rejected(400);
            Set<String> tenants = refs.get(ref);
            if (tenants == null) throw new Rejected(403);
            // Tenant-like payload fields, changed-file arrays and revisions confer no authority.
            enqueue(delivery, digest(bytes), tenants);
            respond(exchange, 202);
        } catch (Rejected failure) {
            rejected.increment();
            respond(exchange, failure.status);
        } catch (RuntimeException failure) {
            rejected.increment();
            respond(exchange, 503);
        } finally {
            if (exchange.getResponseCode() < 0) exchange.close();
        }
    }

    private void requireCredentialAvailable() {
        try (var secret = credentials.resolve(configuration.credentialReference()).orElseThrow()) {
            char[] chars = secret.copy();
            byte[] bytes = null;
            try {
                bytes = new String(chars).getBytes(StandardCharsets.UTF_8);
                if (bytes.length < 16 || bytes.length > 4096) throw new IllegalArgumentException();
                if (source.provider() == GraphAuthoringConfiguration.Provider.GITLAB
                        && configuration.authentication()
                                == GraphAuthoringWebhookConfiguration.Authentication.SIGNATURE) {
                    String encoded = new String(chars);
                    if (!encoded.startsWith("whsec_")) throw new IllegalArgumentException();
                    Arrays.fill(bytes, (byte) 0);
                    bytes = Base64.getDecoder().decode(encoded.substring(6));
                    if (bytes.length < 16 || bytes.length > 128)
                        throw new IllegalArgumentException();
                }
            } finally {
                Arrays.fill(chars, '\0');
                if (bytes != null) Arrays.fill(bytes, (byte) 0);
            }
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("Authoring webhook credential is unavailable");
        }
    }

    private String authenticate(HttpExchange exchange, byte[] body) {
        boolean github = source.provider() == GraphAuthoringConfiguration.Provider.GITHUB;
        String delivery =
                header(
                        exchange,
                        github
                                ? "X-GitHub-Delivery"
                                : configuration.authentication()
                                                == GraphAuthoringWebhookConfiguration.Authentication
                                                        .TOKEN
                                        ? "X-Gitlab-Event-UUID"
                                        : "webhook-id");
        if (!delivery.matches("[A-Za-z0-9_-]{1,128}")) throw new Rejected(400);
        try (var secret = credentials.resolve(configuration.credentialReference()).orElseThrow()) {
            char[] chars = secret.copy();
            byte[] key = null;
            try {
                key = new String(chars).getBytes(StandardCharsets.UTF_8);
                if (key.length < 16 || key.length > 4096) throw new Rejected(403);
                if (!github
                        && configuration.authentication()
                                == GraphAuthoringWebhookConfiguration.Authentication.TOKEN) {
                    if (!MessageDigest.isEqual(
                            key,
                            header(exchange, "X-Gitlab-Token").getBytes(StandardCharsets.UTF_8)))
                        throw new Rejected(403);
                    return delivery;
                }
                byte[] message = body;
                String expected;
                if (github) expected = "sha256=" + HexFormat.of().formatHex(hmac(key, message));
                else {
                    String timestamp = header(exchange, "webhook-timestamp");
                    long time;
                    try {
                        time = Long.parseLong(timestamp);
                    } catch (NumberFormatException invalid) {
                        throw new Rejected(403);
                    }
                    long now = clock.instant().getEpochSecond();
                    if (time < now - REPLAY_SECONDS
                            || time > now + REPLAY_SECONDS
                            || !timestamp.matches("[0-9]{1,12}")) throw new Rejected(403);
                    String encoded = new String(chars);
                    if (!encoded.startsWith("whsec_")) throw new Rejected(403);
                    Arrays.fill(key, (byte) 0);
                    key = Base64.getDecoder().decode(encoded.substring(6));
                    if (key.length < 16 || key.length > 128) throw new Rejected(403);
                    byte[] prefix =
                            (delivery + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
                    message = new byte[prefix.length + body.length];
                    System.arraycopy(prefix, 0, message, 0, prefix.length);
                    System.arraycopy(body, 0, message, prefix.length, body.length);
                    expected = "v1," + Base64.getEncoder().encodeToString(hmac(key, message));
                }
                String signatures =
                        header(exchange, github ? "X-Hub-Signature-256" : "webhook-signature");
                boolean valid = false;
                for (String signature : signatures.split(" "))
                    valid |=
                            MessageDigest.isEqual(
                                    expected.getBytes(StandardCharsets.US_ASCII),
                                    signature.getBytes(StandardCharsets.US_ASCII));
                if (!valid) throw new Rejected(403);
                return delivery;
            } finally {
                Arrays.fill(chars, '\0');
                if (key != null) Arrays.fill(key, (byte) 0);
            }
        } catch (Rejected failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw new Rejected(403);
        }
    }

    private void enqueue(String id, String digest, Set<String> tenants) {
        lock.lock();
        try {
            if (closed.get()) throw new Rejected(503);
            long now = clock.instant().getEpochSecond();
            deliveries.entrySet().removeIf(entry -> entry.getValue().time() < now - REPLAY_SECONDS);
            Delivery previous = deliveries.get(id);
            if (previous != null) {
                if (!previous.digest().equals(digest)) throw new Rejected(400);
                duplicates.increment();
                return;
            }
            deliveries.put(id, new Delivery(digest, now));
            while (deliveries.size() > 1024)
                deliveries.remove(deliveries.keySet().iterator().next());
            pending.addAll(tenants);
            if (!running) {
                running = true;
                try {
                    worker.execute(this::drain);
                } catch (RuntimeException rejected) {
                    running = false;
                    pending.clear();
                    deliveries.remove(id);
                    throw new Rejected(503);
                }
            }
            accepted.increment();
        } finally {
            lock.unlock();
        }
    }

    private void drain() {
        while (!closed.get()) {
            String tenant;
            lock.lock();
            try {
                if (pending.isEmpty()) {
                    running = false;
                    return;
                }
                tenant = pending.iterator().next();
                pending.remove(tenant);
            } finally {
                lock.unlock();
            }
            try {
                GraphAuthoringRepositories.signal(repository, tenant)
                        .toCompletableFuture()
                        .get(
                                source.requestTimeout().toMillis() * (source.retryLimit() + 2L),
                                TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                failures.increment();
            }
        }
    }

    public Map<String, Long> statistics() {
        lock.lock();
        try {
            return Map.of(
                    "accepted",
                    accepted.sum(),
                    "duplicates",
                    duplicates.sum(),
                    "rejected",
                    rejected.sum(),
                    "failures",
                    failures.sum(),
                    "pending",
                    (long) pending.size(),
                    "activeRequests",
                    (long) (16 - requests.availablePermits()),
                    "retainedDeliveries",
                    (long) deliveries.size(),
                    "queuedWorkers",
                    (long) worker.getQueue().size(),
                    "queuedRequests",
                    (long) requestWorkers.getQueue().size());
        } finally {
            lock.unlock();
        }
    }

    private static byte[] hmac(byte[] key, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (java.security.GeneralSecurityException invalid) {
            throw new IllegalStateException("Webhook authentication unavailable");
        }
    }

    private static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new Rejected(400);
    }

    private static String string(Object value) {
        if (value instanceof String text && text.length() <= 1024) return text;
        throw new Rejected(400);
    }

    private static String optionalHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (values == null) return "";
        if (values.size() != 1 || values.getFirst().length() > 1024) throw new Rejected(400);
        return values.getFirst();
    }

    private static String header(HttpExchange exchange, String name) {
        String value = optionalHeader(exchange, name);
        if (value.isBlank()) throw new Rejected(403);
        return value;
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (status >= 400) exchange.getResponseHeaders().set("Connection", "close");
        if (status == 429) exchange.getResponseHeaders().set("Retry-After", "1");
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static final class Rejected extends RuntimeException {
        final int status;

        Rejected(int status) {
            this.status = status;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            deadlines.shutdownNow();
            requestWorkers.shutdownNow();
            active.forEach(
                    (exchange, task) -> {
                        task.cancel(true);
                        exchange.close();
                    });
            active.clear();
            worker.shutdownNow();
            lock.lock();
            try {
                pending.clear();
                deliveries.clear();
            } finally {
                lock.unlock();
            }
        }
    }
}
