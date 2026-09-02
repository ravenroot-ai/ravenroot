package ai.ravenroot.server.ingress;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressPrincipal;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import ai.ravenroot.api.ingress.IngressRouteLease;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.ManagedIngress;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Server-owned, single-process route authority. It is prepared before bind and only receives its
 * {@link HttpServer} once the composition root has created the listener. This deliberately refuses
 * multi-replica state: its lease map is process memory and cannot safely coordinate another pod.
 */
public final class ManagedIngressRegistry implements ManagedIngress, AutoCloseable {
    /** Dedicated composition-root namespace; core, probe and static-UI routes can never be shadowed. */
    public static final String MANAGED_PATH_ROOT = "/managed";
    private static final Duration MAX_RELEASE_DRAIN = Duration.ofMillis(100);
    private final Map<String, IngressAuthorityDeclaration> declarations;
    private final Map<String, IngressRequestProjectionPolicy> projectionPolicies;
    private final LifecycleHooks lifecycleHooks;
    private final Map<String, Lease> leases = new HashMap<>();
    private final Map<String, Long> retiredGeneration = new HashMap<>();
    private HttpServer server;
    private Function<HttpHandler, HttpHandler> protectedContext;
    private boolean closed;

    private ManagedIngressRegistry(Map<String, IngressAuthorityDeclaration> declarations,
                                   Map<String, IngressRequestProjectionPolicy> projectionPolicies,
                                   LifecycleHooks lifecycleHooks) {
        this.declarations = Map.copyOf(declarations);
        this.projectionPolicies = Map.copyOf(projectionPolicies);
        this.lifecycleHooks = Objects.requireNonNull(lifecycleHooks, "lifecycleHooks");
    }

    /** Validates all declarations before any listener is bound. */
    public static ManagedIngressRegistry prepare(List<IngressAuthorityDeclaration> declarations,
                                                 boolean singleReplica) {
        return prepare(declarations, "main", singleReplica);
    }

    /** Validates declarations against the composition root's selected listener. */
    public static ManagedIngressRegistry prepare(List<IngressAuthorityDeclaration> declarations,
                                                 String listenerId, boolean singleReplica) {
        return prepare(declarations, Map.of(), listenerId, singleReplica, LifecycleHooks.NONE);
    }

    /** Validates declarations and their operator-owned projection policies before listener bind. */
    public static ManagedIngressRegistry prepare(List<IngressAuthorityDeclaration> declarations,
                                                 Map<String, IngressRequestProjectionPolicy> projectionPolicies,
                                                 String listenerId, boolean singleReplica) {
        return prepare(declarations, projectionPolicies, listenerId, singleReplica, LifecycleHooks.NONE);
    }

    static ManagedIngressRegistry prepareWithLifecycleHooks(List<IngressAuthorityDeclaration> declarations,
                                                            boolean singleReplica,
                                                            LifecycleHooks lifecycleHooks) {
        return prepare(declarations, Map.of(), "main", singleReplica, lifecycleHooks);
    }

    private static ManagedIngressRegistry prepare(List<IngressAuthorityDeclaration> declarations,
                                                  Map<String, IngressRequestProjectionPolicy> projectionPolicies,
                                                  String listenerId, boolean singleReplica,
                                                  LifecycleHooks lifecycleHooks) {
        if (!singleReplica) throw new IllegalStateException("managed ingress requires single replica");
        listenerId = token(listenerId, "listenerId");
        var byPackage = new LinkedHashMap<String, IngressAuthorityDeclaration>();
        for (IngressAuthorityDeclaration declaration : List.copyOf(declarations)) {
            if (!listenerId.equals(declaration.listenerId())) {
                throw new IllegalArgumentException("ingress declaration names an unavailable listener");
            }
            if (!managedNamespace(declaration.pathPrefix())) {
                throw new IllegalArgumentException(
                        "ingress declaration must be within " + MANAGED_PATH_ROOT);
            }
            validateRegisteredPath(declaration.pathPrefix(), 320, "pathPrefix");
            if (byPackage.putIfAbsent(declaration.packageId(), declaration) != null) {
                throw new IllegalArgumentException("duplicate ingress authority for package");
            }
            for (IngressAuthorityDeclaration earlier : byPackage.values()) {
                if (earlier != declaration && prefixesOverlap(earlier.pathPrefix(), declaration.pathPrefix())) {
                    throw new IllegalArgumentException("overlapping ingress namespaces");
                }
            }
        }
        var policies = new LinkedHashMap<String, IngressRequestProjectionPolicy>();
        Objects.requireNonNull(projectionPolicies, "projectionPolicies").forEach((packageId, policy) -> {
            if (packageId == null || policy == null || !packageId.equals(policy.packageId())) {
                throw new IllegalArgumentException("ingress projection identity is invalid");
            }
            if (!byPackage.containsKey(packageId)) {
                throw new IllegalArgumentException("ingress projection has no authority declaration");
            }
            policies.put(packageId, policy);
        });
        byPackage.keySet().forEach(packageId -> policies.putIfAbsent(packageId,
                IngressRequestProjectionPolicy.defaults(packageId)));
        return new ManagedIngressRegistry(byPackage, policies, lifecycleHooks);
    }

    /** Composition-root-only bind, invoked before the server reports readiness. */
    public synchronized void bind(HttpServer server, Function<HttpHandler, HttpHandler> protectedContext) {
        if (this.server != null || closed) throw new IllegalStateException("ingress registry cannot bind");
        this.server = Objects.requireNonNull(server, "server");
        this.protectedContext = Objects.requireNonNull(protectedContext, "protectedContext");
    }

    @Override public IngressRouteAuthority authorityFor(IngressRouteOwner owner) {
        Objects.requireNonNull(owner, "trustedOwner");
        return new IngressRouteAuthority() {
            @Override public IngressRouteLease acquire(String routeId, String relativePath,
                                                       Set<String> methods, IngressRouteHandler handler) {
                return ManagedIngressRegistry.this.acquire(owner, routeId, relativePath, methods, handler, false);
            }

            @Override public IngressRouteLease acquirePrefix(String routeId, String relativePath,
                                                             Set<String> methods, IngressRouteHandler handler) {
                return ManagedIngressRegistry.this.acquire(owner, routeId, relativePath, methods, handler, true);
            }
        };
    }

    private synchronized IngressRouteLease acquire(IngressRouteOwner owner, String routeId,
                                                              String relativePath, Set<String> methods,
                                                              IngressRouteHandler handler,
                                                              boolean descendants) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(handler, "handler");
        if (server == null || closed) throw new IllegalStateException("ingress is unavailable");
        IngressAuthorityDeclaration authority = declarations.get(owner.packageId());
        if (authority == null) throw new IllegalArgumentException("package has no ingress authority");
        routeId = token(routeId, "routeId");
        String path = authority.pathPrefix() + registeredRelative(relativePath);
        methods = Set.copyOf(methods);
        if (methods.isEmpty() || methods.stream().anyMatch(method -> !method.matches("[A-Z]{3,10}"))) {
            throw new IllegalArgumentException("methods are invalid");
        }
        Long retired = retiredGeneration.get(ownerKey(owner));
        if (retired != null && owner.graphGeneration() <= retired) throw new IllegalStateException("stale graph generation");
        Lease existing = leases.get(path);
        if (existing != null) {
            if (existing.owner.equals(owner) && existing.routeId.equals(routeId)
                    && existing.descendants == descendants) return existing;
            throw new IllegalStateException("route is already leased");
        }
        long active = leases.values().stream().filter(lease -> lease.owner.packageId().equals(owner.packageId())).count();
        if (active >= authority.maxRoutes()) throw new IllegalStateException("route capacity exhausted");
        Lease lease = new Lease(path, routeId, owner, authority,
                projectionPolicies.get(owner.packageId()), methods, handler, descendants);
        // createContext is the commit point. State is not changed until it succeeded.
        server.createContext(path, protectedContext.apply(lease));
        leases.put(path, lease);
        return lease;
    }

    /** Releases every route for exactly one generation, leaving later generations untouched. */
    @Override public void retire(IngressRouteOwner owner) {
        List<Lease> retiring;
        synchronized (this) {
            retiredGeneration.merge(ownerKey(owner), owner.graphGeneration(), Math::max);
            retiring = leases.values().stream().filter(lease -> lease.owner.equals(owner)).toList();
        }
        // Context removal is immediate. The bounded drain happens without the registry monitor, so
        // a replacement generation can acquire the path while old package work is being fenced.
        retiring.forEach(Lease::release);
    }

    public synchronized List<IngressRouteInventory> inventory() {
        return leases.values().stream().map(lease -> new IngressRouteInventory(lease.routeId, lease.owner.packageId(),
                lease.owner.graphGeneration(), "ACTIVE"))
                .sorted(java.util.Comparator.comparing(IngressRouteInventory::packageId)
                        .thenComparing(IngressRouteInventory::routeId)
                        .thenComparingLong(IngressRouteInventory::graphGeneration))
                .toList();
    }

    @Override public void close() {
        List<Lease> closing;
        synchronized (this) {
            closed = true;
            closing = List.copyOf(leases.values());
        }
        closing.forEach(Lease::release);
    }
    private synchronized void release(Lease lease) {
        if (leases.remove(lease.path, lease) && server != null) server.removeContext(lease.path);
    }
    private static String registeredRelative(String value) {
        validateRegisteredPath(value, 320, "relativePath");
        return value;
    }
    private static void validateRegisteredPath(String value, int maximum, String field) {
        if (value == null || value.length() > maximum || !value.startsWith("/")
                || value.length() == 1 || value.endsWith("/")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        for (String segment : value.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || !segment.matches("[A-Za-z0-9._~-]+")) {
                throw new IllegalArgumentException(field + " is invalid");
            }
        }
    }
    private static String token(String value, String field) { if (value == null || !value.matches("[A-Za-z0-9._-]{1,160}")) throw new IllegalArgumentException(field + " is invalid"); return value; }
    private static boolean prefixesOverlap(String first, String second) { return first.startsWith(second + "/") || second.startsWith(first + "/") || first.equals(second); }
    private static boolean managedNamespace(String path) {
        return path.equals(MANAGED_PATH_ROOT) || path.startsWith(MANAGED_PATH_ROOT + "/");
    }
    private static String ownerKey(IngressRouteOwner owner) { return owner.packageId()+"\u0000"+owner.tenantId()+"\u0000"+owner.deploymentId()+"\u0000"+owner.nodeId(); }

    private static RequestProjection project(URI uri, com.sun.net.httpserver.Headers requestHeaders,
                                             String routePath,
                                             IngressRequestProjectionPolicy policy)
            throws ProjectionFailure {
        String rawPath = uri.getRawPath();
        String rawRelative = rawPath.substring(routePath.length());
        String relativePath = decode(rawRelative, policy.maxRelativePathBytes(), Component.PATH);
        Map<String, List<String>> query = query(uri.getRawQuery(), policy);
        Map<String, String> headers = headers(requestHeaders, policy);
        return new RequestProjection(relativePath, query, headers);
    }

    private static Map<String, List<String>> query(String rawQuery,
                                                   IngressRequestProjectionPolicy policy)
            throws ProjectionFailure {
        if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
        requireAsciiBytes(rawQuery, policy.maxQueryBytes(), 414);
        var values = new LinkedHashMap<String, List<String>>();
        int parameters = 0;
        int decodedBytes = 0;
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty() || ++parameters > policy.maxQueryParameters()) {
                throw new ProjectionFailure(400);
            }
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            if (rawName.isEmpty()) throw new ProjectionFailure(400);
            String name = decode(rawName, policy.maxQueryBytes(), Component.QUERY);
            String value = decode(rawValue, policy.maxQueryBytes(), Component.QUERY);
            decodedBytes = addBounded(decodedBytes, utf8Bytes(name), policy.maxQueryBytes(), 414);
            decodedBytes = addBounded(decodedBytes, utf8Bytes(value), policy.maxQueryBytes(), 414);
            values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        var immutable = new LinkedHashMap<String, List<String>>();
        values.forEach((name, entries) -> immutable.put(name, List.copyOf(entries)));
        return Collections.unmodifiableMap(immutable);
    }

    private static Map<String, String> headers(com.sun.net.httpserver.Headers incoming,
                                               IngressRequestProjectionPolicy policy)
            throws ProjectionFailure {
        var projected = new LinkedHashMap<String, String>();
        int count = 0;
        int bytes = 0;
        for (String name : policy.allowedHeaders().stream().sorted().toList()) {
            List<String> values = incoming.get(name);
            if (values == null) continue;
            if (values.size() != 1 || ++count > policy.maxHeaderCount()) {
                throw new ProjectionFailure(400);
            }
            String value = values.getFirst();
            if (value == null || hasControl(value)) throw new ProjectionFailure(400);
            int valueBytes = utf8Bytes(value);
            if (valueBytes > policy.maxHeaderValueBytes()) throw new ProjectionFailure(431);
            bytes = addBounded(bytes, utf8Bytes(name), policy.maxHeaderBytes(), 431);
            bytes = addBounded(bytes, valueBytes, policy.maxHeaderBytes(), 431);
            projected.put(name, value);
        }
        return Collections.unmodifiableMap(projected);
    }

    private static String decode(String raw, int maximumBytes, Component component)
            throws ProjectionFailure {
        requireAsciiBytes(raw, maximumBytes, component == Component.PATH ? 414 : 400);
        var bytes = new ByteArrayOutputStream(Math.min(raw.length(), maximumBytes));
        for (int index = 0; index < raw.length();) {
            char current = raw.charAt(index);
            if (current == '%') {
                if (index + 2 >= raw.length()) throw new ProjectionFailure(400);
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new ProjectionFailure(400);
                int decoded = (high << 4) | low;
                // Encoded separators change routing after the JDK selected a raw-path context;
                // encoded percent can become a second escape under a downstream decoder.
                if (decoded == '%' || (component == Component.PATH && (decoded == '/' || decoded == '\\'))) {
                    throw new ProjectionFailure(400);
                }
                bytes.write(decoded);
                index += 3;
            } else {
                bytes.write(current);
                index++;
            }
        }
        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException malformed) {
            throw new ProjectionFailure(400);
        }
        String normalized = Normalizer.normalize(decoded, Normalizer.Form.NFC);
        if (utf8Bytes(normalized) > maximumBytes || hasControl(normalized)) {
            throw new ProjectionFailure(component == Component.PATH ? 414 : 400);
        }
        if (component == Component.PATH) validateProjectedPath(normalized);
        return normalized;
    }

    private static void validateProjectedPath(String value) throws ProjectionFailure {
        if (value.isEmpty()) return;
        if (!value.startsWith("/") || value.endsWith("/") || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new ProjectionFailure(400);
        }
        for (String segment : value.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ProjectionFailure(400);
            }
        }
    }

    private static void requireAsciiBytes(String value, int maximum, int status)
            throws ProjectionFailure {
        if (value.length() > maximum) throw new ProjectionFailure(status);
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) throw new ProjectionFailure(400);
        }
    }

    private static int utf8Bytes(String value) throws ProjectionFailure {
        long bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (Character.isSurrogate(value.charAt(index))
                    && (codePoint == value.charAt(index))) {
                throw new ProjectionFailure(400);
            }
            bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes > Integer.MAX_VALUE) throw new ProjectionFailure(400);
            index += Character.charCount(codePoint);
        }
        return (int) bytes;
    }

    private static int addBounded(int current, int addition, int maximum, int status)
            throws ProjectionFailure {
        if (addition > maximum - current) throw new ProjectionFailure(status);
        return current + addition;
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f);
    }

    private enum Component { PATH, QUERY }

    private record RequestProjection(String relativePath, Map<String, List<String>> query,
                                     Map<String, String> headers) { }

    /** Sanitized route inventory suitable for readiness/operations output, never handler data. */
    public record IngressRouteInventory(String routeId, String packageId, long graphGeneration, String state) { }

    interface LifecycleHooks {
        LifecycleHooks NONE = new LifecycleHooks() { };

        default void afterPermitBeforePublication() { }

        default void beforeContextRemoval() { }
    }

    private final class Lease implements IngressRouteLease, HttpHandler {
        private final String path; private final String routeId; private final IngressRouteOwner owner;
        private final IngressAuthorityDeclaration authority; private final Set<String> methods;
        private final IngressRequestProjectionPolicy projectionPolicy;
        private final IngressRouteHandler handler; private final Semaphore admission;
        private final boolean descendants;
        private final AtomicBoolean released = new AtomicBoolean();
        private final java.util.Set<Admission> active = ConcurrentHashMap.newKeySet();
        private final ExecutorService callbacks;
        /**
         * Where package cancellation listeners run. Deliberately not {@link #callbacks}, which is
         * zero-queue and would reject a drain whenever the handler it belongs to is still occupying
         * its only worker — which is precisely the moment a cancellation happens.
         */
        private final ExecutorService cancellations;
        private final Object admissionHandshake = new Object();
        private final CountDownLatch contextRemoved = new CountDownLatch(1);
        Lease(String path, String routeId, IngressRouteOwner owner, IngressAuthorityDeclaration authority,
              IngressRequestProjectionPolicy projectionPolicy, Set<String> methods,
              IngressRouteHandler handler, boolean descendants) {
            this.path=path; this.routeId=routeId; this.owner=owner; this.authority=authority; this.methods=methods;
            this.projectionPolicy=Objects.requireNonNull(projectionPolicy, "projectionPolicy");
            this.handler=handler; this.descendants=descendants;
            this.admission=new Semaphore(authority.maxConcurrentRequests());
            this.callbacks = new ThreadPoolExecutor(authority.maxConcurrentRequests(),
                    authority.maxConcurrentRequests(), 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                    Thread.ofVirtual().name("managed-ingress-" + routeId + "-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
            // Generation-local like callbacks, so a retired lease's listeners cannot outlive it into
            // a replacement. At most one drain runs per request at a time.
            //
            // That is not a bound on how many run at once, and must not be read as one: a drain
            // outlives the admission that started it, because the permit is released while the
            // listener is still running and the next request is admitted immediately. A route with
            // maxConcurrentRequests=1 can therefore accumulate as many live drains as it accepts
            // requests while one listener lasts. Unbounded is acceptable here only because these are
            // virtual threads that hold no permit, no exchange and no part of the retirement budget.
            this.cancellations = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("managed-ingress-cancel-" + routeId + "-", 0).factory());
        }
        @Override public String routeId() { return routeId; }
        @Override public IngressRouteOwner owner() { return owner; }
        @Override public void release() {
            List<Admission> admitted;
            boolean awaitRemoval;
            synchronized (admissionHandshake) {
                if (!released.compareAndSet(false, true)) {
                    awaitRemoval = true;
                    admitted = List.of();
                } else {
                    awaitRemoval = false;
                    admitted = List.copyOf(active);
                }
            }
            // Never wait while owning the admission monitor. A group of idempotent release callers
            // can otherwise pin every virtual-thread carrier on monitor entry while the winner is
            // parked in a lifecycle hook, leaving no carrier on which that winner can remove the
            // context and release the waiters.
            if (awaitRemoval) {
                awaitContextRemoval();
                return;
            }
            // Remove routing first. Replacement may bind immediately; the old lease remains
            // fenced by its generation-local flag, exchange closures and isolated executor.
            try {
                lifecycleHooks.beforeContextRemoval();
                ManagedIngressRegistry.this.release(this);
            } finally {
                // Every concurrent/idempotent release waits for this publication. None can report
                // completion while the old JDK context is still able to win route lookup.
                contextRemoved.countDown();
            }
            admitted.forEach(Admission::cancel);
            callbacks.shutdownNow();
            cancellations.shutdownNow();
            // One budget for both pools, not one each: MAX_RELEASE_DRAIN is the bound on how long
            // retirement waits for cooperation in total, and awaiting them in series with separate
            // budgets would quietly double it.
            long until = System.nanoTime() + Math.min(MAX_RELEASE_DRAIN.toNanos(),
                    authority.requestTimeout().toNanos());
            try {
                callbacks.awaitTermination(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
                cancellations.awaitTermination(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void awaitContextRemoval() {
            boolean interrupted = false;
            while (contextRemoved.getCount() != 0) {
                try {
                    contextRemoved.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        @Override public void handle(HttpExchange exchange) throws IOException {
            // The window opens when the exchange arrives, not when the handler is invoked. Projection
            // and admission used to sit outside it, so a request could spend its budget before the
            // clock that governs it had started. One instant now governs admission, the bounded body
            // read, handler execution and response delivery.
            long timeoutNanos = authority.requestTimeout().toNanos();
            long deadline = System.nanoTime() + timeoutNanos;
            Instant deadlineInstant = Instant.now().plusNanos(timeoutNanos);
            String rawPath = exchange.getRequestURI().getRawPath();
            if (!matches(rawPath) || released.get()) { exchange.sendResponseHeaders(404, -1); return; }
            if (!methods.contains(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            RequestProjection projection;
            try {
                projection = project(exchange.getRequestURI(), exchange.getRequestHeaders(), path,
                        projectionPolicy);
                requireLive(deadline);
            } catch (ProjectionFailure refused) {
                sendNoBody(exchange, refused.status);
                return;
            } catch (DeadlineExpired expired) {
                // Nothing was admitted and no permit was taken, so there is no admission to unwind.
                sendNoBody(exchange, 504);
                return;
            }
            if (!admission.tryAcquire()) { exchange.sendResponseHeaders(429, -1); return; }
            var unit = new Admission(exchange);
            try {
                lifecycleHooks.afterPermitBeforePublication();
                synchronized (admissionHandshake) {
                    active.add(unit);
                    // Publication and release are one handshake: if retirement linearized first,
                    // this request closes itself rather than relying on release's earlier snapshot.
                    if (released.get()) {
                        unit.cancel();
                        throw new Retired();
                    }
                }
                unit.fence();
                requireLive(deadline);
                byte[] body = bounded(exchange.getRequestBody(), authority.maxRequestBytes(), deadline, unit);
                unit.fence();
                AuthenticatedPrincipal principal = ai.ravenroot.server.AuthenticatedPrincipalAttribute.require(exchange);
                if (!principal.tenantId().equals(owner.tenantId()) || !principal.scopes().containsAll(authority.requiredScopes())) {
                    unit.settle(403); return;
                }
                unit.fence();
                requireLive(deadline);
                IngressRequest request = new IngressRequest(new IngressPrincipal(principal.tenantId(),
                        principal.subject(), principal.issuer(), principal.type().name()),
                        exchange.getRequestMethod(), projection.relativePath(), projection.query(),
                        projection.headers(), body);
                IngressResponse response = invoke(request,
                        new IngressRequestContext(deadlineInstant, unit.cancellation), deadline, unit);
                unit.fence();
                // Delivery is inside the same deadline: a response produced in time but written after
                // the window closed is a late result, and a late result is fenced like any other.
                requireLive(deadline);
                if (response == null || response.status() < 100 || response.status() > 599
                        || response.body().length > authority.maxResponseBytes() || !validHeaders(response.headers())) { unit.settle(502); return; }
                response.headers().forEach((key, value) -> { if (key != null && value != null && key.matches("[A-Za-z0-9-]{1,64}") && value.length() <= 512) exchange.getResponseHeaders().set(key, value); });
                unit.fence();
                unit.deliver(response);
            } catch (Retired ignored) {
                // Every retirement path closes for itself. It never assumes the release thread saw
                // this admission, including the permit/publication interleaving above.
                unit.cancel();
            } catch (DeadlineExpired ignored) {
                // A deadline announces itself as a deadline. 502 accuses the package of failing and
                // 408 accuses the client of being slow; when the admitted window is what ran out,
                // both name the wrong party. The body is empty: a timing refusal must not leak which
                // stage lost the race.
                unit.cancelSignal();
                unit.settle(504);
            } catch (HandlerFailure ignored) {
                unit.settle(502);
            } catch (TooLarge ignored) { unit.settle(413); }
            catch (IOException disconnected) {
                // The only client disconnect com.sun.net.httpserver makes observable: a read or a
                // write against a socket that is no longer there. Best effort by construction.
                unit.cancelSignal();
                throw disconnected;
            }
            finally {
                unit.complete();
                active.remove(unit);
                admission.release();
            }
        }

        private boolean matches(String rawPath) {
            if (rawPath.equals(path)) return true;
            return descendants && rawPath.startsWith(path + "/");
        }
        private IngressResponse invoke(IngressRequest request, IngressRequestContext context,
                                       long deadline, Admission unit)
                throws HandlerFailure, Retired, DeadlineExpired {
            var invocation = new FutureTask<IngressResponse>(() -> {
                var stage = Objects.requireNonNull(handler.handle(request, context), "handler stage");
                var pending = stage.toCompletableFuture();
                try {
                    return pending.get(remaining(deadline), TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException expired) {
                    // Ours, not the package's. A handler that completes its own stage with a
                    // TimeoutException failed at something it was doing, and stays a 502; only the
                    // adapter's own clock produces this type, which no package can construct.
                    unit.cancelSignal();
                    throw new DeadlineExpired();
                } finally {
                    if (released.get() || unit.cancellation.cancelled()) pending.cancel(true);
                }
            });
            unit.track(invocation);
            try {
                callbacks.execute(invocation);
            } catch (RejectedExecutionException rejected) {
                invocation.cancel(true);
                if (released.get()) throw new Retired();
                throw new HandlerFailure();
            }
            try {
                return invocation.get(remaining(deadline), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException timedOut) {
                unit.cancelSignal();
                invocation.cancel(true);
                throw new DeadlineExpired();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                invocation.cancel(true);
                if (released.get()) throw new Retired();
                throw new HandlerFailure();
            } catch (java.util.concurrent.ExecutionException failed) {
                if (released.get()) throw new Retired();
                if (failed.getCause() instanceof DeadlineExpired expired) throw expired;
                throw new HandlerFailure();
            } catch (RuntimeException failed) {
                if (released.get()) throw new Retired();
                throw new HandlerFailure();
            } finally {
                unit.clear(invocation);
            }
        }
        private byte[] bounded(InputStream input, long limit, long deadline, Admission unit)
                throws IOException, TooLarge, DeadlineExpired, HandlerFailure, Retired {
            var read = new FutureTask<byte[]>(() -> {
                byte[] bytes = input.readNBytes(Math.toIntExact(Math.min(limit + 1, Integer.MAX_VALUE)));
                if (bytes.length > limit) throw new TooLarge();
                return bytes;
            });
            unit.track(read);
            Thread reader = Thread.ofVirtual().name("managed-ingress-body-" + routeId).start(read);
            try {
                return read.get(remaining(deadline), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException timedOut) {
                // A body that never finished arriving is the request running out of its window, not
                // a slow client that still had time: THIS branch is only reached once the absolute
                // deadline has passed, which is why the old 408 no longer has a producer here. The
                // claim is about this branch alone; the interrupt below is a different event.
                unit.cancelSignal();
                read.cancel(true);
                reader.interrupt();
                throw new DeadlineExpired();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                read.cancel(true);
                reader.interrupt();
                if (released.get()) throw new Retired();
                // An interrupt is not evidence the window closed — an executor shutdown interrupts
                // too. Ask the clock instead of assuming: requireLive throws DeadlineExpired only
                // when the deadline really has passed, and otherwise this is a read that failed for
                // a reason belonging to neither the client nor the timer, which is a 502.
                requireLive(deadline);
                throw new HandlerFailure();
            } catch (java.util.concurrent.ExecutionException failed) {
                if (failed.getCause() instanceof TooLarge tooLarge) throw tooLarge;
                if (failed.getCause() instanceof IOException ioFailure) throw ioFailure;
                throw new IOException("managed request body read failed");
            } catch (RuntimeException failed) {
                if (released.get()) throw new Retired();
                throw failed;
            } finally {
                unit.clear(read);
            }
        }
        private long remaining(long deadline) throws java.util.concurrent.TimeoutException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
            return remaining;
        }

        private void requireLive(long deadline) throws DeadlineExpired {
            if (deadline - System.nanoTime() <= 0) throw new DeadlineExpired();
        }

        private final class Admission {
            private final HttpExchange exchange;
            private final AtomicReference<Future<?>> phase = new AtomicReference<>();
            private final AtomicBoolean completed = new AtomicBoolean();
            private final AtomicBoolean settled = new AtomicBoolean();
            private final ExchangeCancellation cancellation = new ExchangeCancellation(cancellations);

            private Admission(HttpExchange exchange) {
                this.exchange = exchange;
            }

            private void track(Future<?> future) throws Retired {
                phase.set(future);
                if (released.get() || completed.get()) {
                    future.cancel(true);
                    throw new Retired();
                }
            }

            private void clear(Future<?> future) {
                phase.compareAndSet(future, null);
            }

            private void fence() throws Retired {
                if (released.get() || completed.get()) throw new Retired();
            }

            /**
             * Publishes cancellation to the package. Every cause funnels through here — deadline,
             * retirement, deployment stop or rollback, observable disconnect — so "exactly once" is
             * a property of the single compare-and-set below rather than of who called first.
             */
            private void cancelSignal() {
                cancellation.cancel();
            }

            private void cancel() {
                cancelSignal();
                Future<?> current = phase.get();
                if (current != null) current.cancel(true);
                exchange.close();
            }

            /** One terminal status per exchange; a loser writes nothing and disturbs nothing. */
            private void settle(int status) {
                if (settled.compareAndSet(false, true)) sendNoBody(exchange, status);
            }

            private void deliver(IngressResponse response) throws IOException {
                if (!settled.compareAndSet(false, true)) return;
                exchange.sendResponseHeaders(response.status(), response.body().length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(response.body()); }
            }

            private void complete() {
                completed.set(true);
            }
        }
    }

    /**
     * The cooperative half of managed-ingress cancellation, handed to a package through
     * {@link IngressRequestContext}.
     *
     * <p>It is fired by the adapter and read by the package; there is no method a package could call
     * to cancel someone else's request, and none to un-cancel its own.</p>
     */
    private static final class ExchangeCancellation implements CancellationSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean draining = new AtomicBoolean();
        private final Queue<Runnable> listeners = new ConcurrentLinkedQueue<>();
        private final ExecutorService drains;

        private ExchangeCancellation(ExecutorService drains) {
            this.drains = Objects.requireNonNull(drains, "drains");
        }

        @Override public boolean cancelled() {
            return cancelled.get();
        }

        @Override public void onCancel(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            listeners.add(listener);
            // Registering after the fact still runs, and the queue hands each listener to exactly
            // one drain, so a listener racing the firing thread cannot run twice or not at all.
            if (cancelled.get()) schedule();
        }

        /** Flips synchronously — a handler polling {@code cancelled()} must see it at once. */
        private void cancel() {
            if (cancelled.compareAndSet(false, true)) schedule();
        }

        /**
         * Listeners never run on the thread that fired the cancellation.
         *
         * <p>That thread is always one of the adapter's own and always has a bound to keep: on the
         * retirement path it is the caller of {@code retire}/{@code close}, whose budget is
         * {@link #MAX_RELEASE_DRAIN}; on the deadline and disconnect paths it is the exchange's
         * dispatch thread, which still has to answer and release a route permit. Running package
         * code inline on either handed the package control over a bound that is not its to set — a
         * listener that ignores interruption could stall retirement past its budget, or hold an
         * exchange and its permit open long past the deadline it was given.</p>
         *
         * <p>At most one drain is in flight per request. A listener that blocks therefore delays only
         * the other listeners of its own request, which belong to the same package, and cannot
         * multiply into a thread per registration.</p>
         */
        private void schedule() {
            if (listeners.isEmpty() || !draining.compareAndSet(false, true)) return;
            try {
                drains.execute(this::drain);
            } catch (RejectedExecutionException torndown) {
                // The lease is already gone, so its exchange is closed and its work fenced. Delivery
                // is best effort by contract; at-most-once is the guarantee, at-least-once is not.
                draining.set(false);
            }
        }

        private void drain() {
            try {
                Runnable listener;
                while ((listener = listeners.poll()) != null) {
                    try {
                        listener.run();
                    } catch (RuntimeException | Error ignored) {
                        // A listener that throws cannot suppress the others and cannot fail a request
                        // that is already being unwound. Package code owns its own reporting.
                    }
                }
            } finally {
                draining.set(false);
                // A listener registered while this drain was running may have been queued after the
                // final poll saw the queue empty; re-arm rather than lose it.
                if (!listeners.isEmpty() && cancelled.get()) schedule();
            }
        }
    }
    static boolean validHeaders(Map<String, String> headers) {
        if (headers.size() > 32) return false;
        int bytes = 0;
        for (var entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || !entry.getKey().matches("[A-Za-z0-9-]{1,64}")
                    || entry.getValue().length() > 512) return false;
            bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length
                    + entry.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 8_192) return false;
        }
        return true;
    }
    private static final class TooLarge extends Exception { }
    /**
     * The absolute deadline won, at whichever stage. Package-private to this file on purpose: a
     * package cannot construct it, so it can never make its own failure look like a timing refusal.
     */
    private static final class DeadlineExpired extends Exception { }
    private static final class HandlerFailure extends Exception { }
    private static final class Retired extends Exception { }
    private static final class ProjectionFailure extends Exception {
        private final int status;

        private ProjectionFailure(int status) {
            this.status = status;
        }
    }

    private static void sendNoBody(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            exchange.close();
        }
    }
}
