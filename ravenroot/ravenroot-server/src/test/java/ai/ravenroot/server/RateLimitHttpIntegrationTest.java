package ai.ravenroot.server;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.audit.StructuredAuthorizationLogger;
import ai.ravenroot.server.audit.StructuredRateLimitLogger;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import ai.ravenroot.server.ratelimit.RateLimitAuditSink;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end limit behaviour over real HTTP: the 429 contract, burst, sustained rate and tenant
 * fairness.
 *
 * <p>The limiter is driven by a virtual clock the test advances by hand. That is what makes the burst
 * and sustained-rate assertions exact rather than approximate: "one second of refill buys exactly
 * {@code rate} more requests" is a statement about arithmetic, not about how fast CI happens to be, so
 * these tests carry no timing flakiness and need no tolerance windows.</p>
 */
class RateLimitHttpIntegrationTest {
    /** A graph that starts, runs one pass-through node and completes, so it publishes a terminal event. */
    private static final String EXECUTABLE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="ratelimit-test" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="future"><data key="kind">BEHAVIOR</data><data key="behavior">future-behavior</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="future"><data key="outcome">continue</data></edge>
                <edge id="e2" source="future" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    private final AtomicLong nanos = new AtomicLong();

    @Test
    void aBurstIsAbsorbedAndTheNextRequestIsThrottledWithACoherentRetryAfter() throws Exception {
        var limits = limits(builder -> builder.address(2, 5));
        var audit = new RecordingAudit();
        try (var fixture = fixture(limits, audit)) {
            var client = HttpClient.newHttpClient();

            for (int index = 0; index < 5; index++) {
                assertEquals(200, fixture.get(client, "/v1/status", "tenant-a").statusCode(),
                        "request " + index + " was inside the configured burst of 5 and should have been served");
            }

            var throttled = fixture.get(client, "/v1/status", "tenant-a");

            assertEquals(429, throttled.statusCode());
            assertEquals("1", throttled.headers().firstValue("Retry-After").orElseThrow(),
                    "Retry-After must be delta-seconds; at 2 tokens per second the wait for one token is under a second "
                            + "and is reported as the 1-second floor rather than 0");
            assertTrue(throttled.body().contains("ADDRESS_RATE_LIMIT_EXCEEDED"), throttled.body());
            assertTrue(audit.codes().contains("ADDRESS_RATE_LIMIT_EXCEEDED"),
                    "a throttled request was not audited");
        }
    }

    /** Retry-After must be an integer count of seconds, never an HTTP-date. */
    @Test
    void retryAfterIsDeltaSecondsAndNeverZero() throws Exception {
        var limits = limits(builder -> builder.address(1, 1));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();
            assertEquals(200, fixture.get(client, "/v1/status", "tenant-a").statusCode());

            var throttled = fixture.get(client, "/v1/status", "tenant-a");

            String retryAfter = throttled.headers().firstValue("Retry-After").orElseThrow();
            long seconds = Long.parseLong(retryAfter);
            assertTrue(seconds >= 1, "Retry-After was " + seconds + ", which invites an immediate retry");
        }
    }

    /** The rejection body must be a fixed vocabulary, never anything the caller supplied. */
    @Test
    void theThrottledBodyIsBoundedAndReflectsNothingFromTheRequest() throws Exception {
        var limits = limits(builder -> builder.address(1, 1));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();
            String marker = "reflect-me-" + "x".repeat(2_000);
            fixture.get(client, "/v1/status?probe=" + marker, "tenant-a");

            var throttled = fixture.get(client, "/v1/status?probe=" + marker, "tenant-a");

            assertEquals(429, throttled.statusCode());
            assertFalse(throttled.body().contains("reflect-me"),
                    "the rejection reflected caller-supplied text, so the body is not bounded by construction");
            assertTrue(throttled.body().length() < 200,
                    "rejection body was " + throttled.body().length() + " bytes; it must stay small and constant");
        }
    }

    /**
     * Sustained rate, asserted exactly. One second of virtual time must buy exactly {@code rate} further
     * requests — no more (the limit leaks) and no fewer (the limit over-throttles).
     */
    @Test
    void sustainedLoadIsServedAtExactlyTheConfiguredRate() throws Exception {
        var limits = limits(builder -> builder.address(3, 3));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();
            drain(fixture, client, "tenant-a");

            for (int second = 0; second < 5; second++) {
                nanos.addAndGet(Duration.ofSeconds(1).toNanos());

                int served = 0;
                while (fixture.get(client, "/v1/status", "tenant-a").statusCode() == 200) {
                    served++;
                }

                assertEquals(3, served, "second " + second + " served " + served
                        + " requests where the configured sustained rate is exactly 3 per second");
            }
        }
    }

    /**
     * One tenant exceeding its budget must leave another tenant's budget
     * untouched, which is what per-tenant keying buys and what a single global bucket would not.
     */
    @Test
    void oneTenantExceedingItsBudgetDoesNotStarveAnother() throws Exception {
        var limits = limits(builder -> builder.address(100_000, 100_000).tenant(5, 5));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();

            int noisyServed = 0;
            int noisyThrottled = 0;
            for (int index = 0; index < 200; index++) {
                if (fixture.get(client, "/v1/status", "tenant-noisy").statusCode() == 200) {
                    noisyServed++;
                } else {
                    noisyThrottled++;
                }
            }

            assertEquals(5, noisyServed, "the noisy tenant was served beyond its own budget");
            assertTrue(noisyThrottled > 0, "the noisy tenant was never throttled, so the test proves nothing");

            // The quiet tenant's own budget must be intact and entirely available.
            var quietStatuses = new ArrayList<Integer>();
            for (int index = 0; index < 5; index++) {
                quietStatuses.add(fixture.get(client, "/v1/status", "tenant-quiet").statusCode());
            }

            assertEquals(List.of(200, 200, 200, 200, 200), quietStatuses,
                    "a neighbouring tenant lost part of its budget to the noisy tenant, which is starvation");
        }
    }

    /** Within one tenant, one principal must not be able to drain the tenant's whole budget. */
    @Test
    void onePrincipalDoesNotStarveAnotherInsideTheSameTenant() throws Exception {
        var limits = limits(builder -> builder.address(100_000, 100_000).tenant(1_000, 1_000).principal(4, 4));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();

            int served = 0;
            for (int index = 0; index < 100; index++) {
                if (fixture.get(client, "/v1/status", "tenant-a", "alice").statusCode() == 200) {
                    served++;
                }
            }
            assertEquals(4, served, "one principal consumed more than its own budget");

            var bobStatuses = new ArrayList<Integer>();
            for (int index = 0; index < 4; index++) {
                bobStatuses.add(fixture.get(client, "/v1/status", "tenant-a", "bob").statusCode());
            }

            assertEquals(List.of(200, 200, 200, 200), bobStatuses,
                    "a second principal in the same tenant was starved by the first");
        }
    }

    /**
     * {@link RavenrootServer#verifyRequestHeaderCapTookEffect()} sends its startup self-check to an
     * unregistered path rather than {@code /health} on this same server, which is wrapped in
     * {@code publicContext}, so its own deliberately oversized probe header tripped
     * {@link RateLimiter#checkRequestShape} ({@code 431 HEADER_VALUE_TOO_LARGE}) and that rejection was
     * audited exactly like a real one. Measured directly: one
     * {@code code=HEADER_VALUE_TOO_LARGE status=431 path=/health clientAddress=127.0.0.1} record per
     * boot. In production that sink is {@code AuditTrailRateLimitSink}, the durable, tamper-evident audit
     * trail: every start deposited a record indistinguishable from a real attempt. The probe targets a
     * throwaway, context-less {@code HttpServer} instead, so this test's own construction of a
     * fixture -- which starts a real {@code RavenrootServer} and so runs the same verification -- must
     * leave the audit sink completely empty before any request of the test's own has been sent.
     */
    @Test
    void startingTheServerWritesNoAuditRecordOfItsOwn() throws Exception {
        var audit = new RecordingAudit();
        try (var fixture = fixture(limits(builder -> builder), audit)) {
            assertEquals(List.of(), audit.events(),
                    "RavenrootServer#start()'s own header-cap self-check wrote an audit record before any "
                            + "real request was ever sent -- indistinguishable, in a durable audit trail, from "
                            + "an actual oversized-header attempt against this address; events: " + audit.events());
        }
    }

    /**
     * {@code jdk.httpserver.maxConnections=200} is not a safe memory backstop for the header cap (see
     * {@link RavenrootServer}'s own Javadoc, "What 2 MiB
     * actually costs -- and why jdk.httpserver.maxConnections is not the answer", for the full
     * measurement and why it was reverted). Once that ceiling is reached, {@code ServerImpl}'s accept
     * loop calls {@code accept()} and immediately {@code chan.close()} -- no bytes written -- so a
     * perfectly ordinary {@code GET /health}, reached only because 200 unrelated sockets happened to be
     * open at the same time, died with the same empty-wire failure for a different cause. This test
     * pins that reproduction directly: 200 idle connections (no request sent on any of them -- reaching
     * the ceiling here needs no abuse, only an accept-queue full of ordinary, still-open sockets), then a
     * fresh, complete {@code GET /health}. Before the revert, this failed with the connection reset;
     * after, {@code jdk.httpserver.maxConnections} has no product-set ceiling to reach, so it still
     * doesn't, no matter how many idle connections came before it.
     */
    @Test
    void manyIdleConnectionsDoNotBreakAnOrdinaryHealthCheck() throws Exception {
        try (var fixture = fixture(limits(builder -> builder), new RecordingAudit())) {
            int port = fixture.server().port();
            var idle = new ArrayList<java.net.Socket>();
            try {
                for (int i = 0; i < 200; i++) {
                    var socket = new java.net.Socket();
                    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
                    idle.add(socket);
                }

                String response = rawGet(port, "/health");

                assertTrue(response.startsWith("HTTP/1.1 200 "), response);
            } finally {
                for (var socket : idle) {
                    try {
                        socket.close();
                    } catch (java.io.IOException ignored) {
                        // Best-effort cleanup; the assertion above has already run.
                    }
                }
            }
        }
    }

    @Test
    void anOversizedQueryIsRejectedWithoutBeingProcessed() throws Exception {
        var audit = new RecordingAudit();
        try (var fixture = fixture(limits(builder -> builder), audit)) {
            var client = HttpClient.newHttpClient();

            var response = fixture.get(client, "/v1/status?probe=" + "a".repeat(8_000), "tenant-a");

            assertEquals(414, response.statusCode());
            assertTrue(response.body().contains("QUERY_TOO_LARGE"), response.body());
            var event = audit.events().stream()
                    .filter(candidate -> candidate.code().equals("QUERY_TOO_LARGE"))
                    .findFirst().orElseThrow();
            assertEquals("127.0.0.1", event.clientAddress());
            assertFalse(event.forwarded());
            assertEquals(RateLimitAuditEvent.UNKNOWN, event.tenantId());
            assertEquals(correlationIn(response.body()), event.requestId());
        }
    }

    @Test
    void aRejectedProxyTopologyUsesThePreResolutionContextAndOneCorrelationId() throws Exception {
        var audit = new RecordingAudit();
        var limits = limits(builder -> builder);
        var limiter = new RateLimiter(limits,
                new TrustedProxyConfiguration(2, Set.of("127.0.0.1")), audit, nanos::get);
        InetAddress ipv4Loopback = InetAddress.getByName("127.0.0.1");
        try (var fixture = fixture(limits, limiter, tenantAuthenticator(), ipv4Loopback)) {
            String refused = rawRequest(fixture.server().port(), "GET", "tenant-a:alice",
                    "203.0.113.6", ipv4Loopback,
                    new java.util.concurrent.ConcurrentLinkedQueue<>());

            assertTrue(refused.startsWith("HTTP/1.1 400 "), refused);
            assertTrue(refused.contains("FORWARDED_CHAIN_TOO_SHORT"), refused);
            var event = audit.events().stream()
                    .filter(candidate -> candidate.code().equals("FORWARDED_CHAIN_TOO_SHORT"))
                    .findFirst().orElseThrow();
            assertEquals(RateLimitAuditEvent.UNKNOWN, event.clientAddress());
            assertFalse(event.forwarded());
            assertEquals(RateLimitAuditEvent.UNKNOWN, event.tenantId());
            assertEquals(RateLimitAuditEvent.UNKNOWN, event.subject());
            assertEquals(correlationIn(refused), event.requestId());
        }
    }

    /**
     * The break point must be measured from {@code Request.headers()}, not deduced from
     * {@code Request.readLine()} alone. The previously assumed value of {@code 389,089} characters
     * ("one character over the old JDK cap") is wrong on both counts. The exception comes from
     * {@code Request.headers()}, not
     * {@code readLine()}: {@code headers()} seeds its own byte counter from
     * {@code startLine.length() + 32} and then spends {@code fieldLength + 32} per header line the
     * client sends, so the request line and the headers draw from the <strong>same</strong> budget --
     * the query is only the easiest way to build an oversized request. A request line of 389,088
     * characters, sent with no other headers, is exactly what the
     * old Javadoc predicted would succeed; sent with the {@code Host}, {@code Authorization} and
     * {@code Connection} headers this test (and every raw request in this class) actually sends, it does
     * not -- those extra bytes are already spending part of the same 389,120-byte budget before the
     * query is even considered.
     *
     * <p>388,891 / 388,892 is the real boundary for <em>this exact client</em>, found by bisection
     * against a live {@code RavenrootServer} with {@code sun.net.httpserver.maxReqHeaderSize} left at
     * the JDK's own default: {@code "GET /v1/status?" + "a".repeat(388_891) + " HTTP/1.1"}, followed by
     * {@code Host}, {@code Authorization: Bearer tenant-a:alice} and {@code Connection: close}, is read
     * successfully and reaches {@link RateLimiter#checkRequestShape}, which answers
     * {@code 414 QUERY_TOO_LARGE}. <strong>This test sends one character more -- 388,892 -- because that
     * is the size that actually reproduces the failure</strong>: under the old cap it breaks the
     * connection with nothing on the wire; 388,891 succeeds and therefore does not exercise the boundary.
     * Confirmed directly: with {@code sun.net.httpserver.maxReqHeaderSize} forced back to the JDK's own
     * 389,120-byte default ({@code -Dravenroot.surefire.extraArgLine=-Dsun.net.httpserver.maxReqHeaderSize=389120}),
     * this exact request breaks the connection and this assertion fails; with the cap this module sets
     * (the normal case), it passes. <strong>Neither number is portable to a different client</strong> --
     * more headers, a longer {@code Host}, a cookie, all spend the same budget faster and move the
     * boundary down. The boundary is therefore measured against this product rather than inferred from
     * {@code ServerConfig}'s documented default, and "the boundary
     * depends on which client is asking" is that constraint made concrete, not an approximation of a
     * single true number this test failed to find.</p>
     *
     * <p>Sent over a raw socket, not {@link HttpClient}: {@code HttpClient}'s default request also
     * attempts an HTTP/2 (h2c) upgrade over plaintext, adding {@code Connection}/{@code Upgrade}/
     * {@code HTTP2-Settings} headers whose exact byte count is not part of the client's own public
     * contract. Using {@code HttpClient} silently spends extra header budget and observes a break point
     * about 200 bytes different from the
     * one it claimed. A raw socket sends exactly the bytes this Javadoc describes and no others.</p>
     */
    @Test
    void aQueryAtTheMeasuredOldJdkCapBoundaryStillGetsAStructuredRejection() throws Exception {
        try (var fixture = fixture(limits(builder -> builder), new RecordingAudit())) {
            String response = rawGet(fixture.server().port(), "/v1/status?" + "a".repeat(388_892));

            assertTrue(response.startsWith("HTTP/1.1 414 "), response);
            assertTrue(response.contains("QUERY_TOO_LARGE"), response);
        }
    }

    /**
     * Raising the JDK cap is a mitigation, not a cure. The residual above the new 2 MiB cap must be
     * declared rather than hidden: a request far
     * past 2 MiB still breaks the raw connection today, for the identical reason -- nothing in this process
     * runs before {@code sun.net.httpserver} finishes parsing the request line. This is the accepted,
     * documented edge; eliminating it fully requires replacing the JDK's built-in HTTP layer, which
     * is out of scope here.
     *
     * <p>3 MiB is deliberately not the exact new boundary: for this same raw-socket client, that is
     * measured (see {@link RavenrootServer}'s Javadoc) at 2,096,923 (succeeds) / 2,096,924 (fails) --
     * one byte different from the round 2 MiB configured value, for the same reason the old boundary
     * above is not the round 380 KiB either. This test only needs to show the residual exists, well past
     * where any legitimate request or the limiter's own defaults would ever land, not pin it to the byte
     * the way the regression test above does.</p>
     */
    @Test
    void aQueryFarPastTheNewCapStillBreaksTheRawConnection() throws Exception {
        try (var fixture = fixture(limits(builder -> builder), new RecordingAudit())) {
            int port = fixture.server().port();

            assertThrows(java.io.IOException.class,
                    () -> rawGet(port, "/v1/status?" + "a".repeat(3 * 1024 * 1024)),
                    "a request far past the new 2 MiB cap was expected to still break the raw connection "
                            + "(the documented, accepted residual above the configured cap) rather than receive a response");
        }
    }

    @Test
    void tooManyQueryParametersAreRejected() throws Exception {
        try (var fixture = fixture(limits(builder -> builder), new RecordingAudit())) {
            var client = HttpClient.newHttpClient();
            var query = new StringBuilder("?a=1");
            for (int index = 0; index < 100; index++) {
                query.append("&p").append(index).append("=1");
            }

            var response = fixture.get(client, "/v1/status" + query, "tenant-a");

            assertEquals(414, response.statusCode());
            assertTrue(response.body().contains("TOO_MANY_QUERY_PARAMETERS"), response.body());
        }
    }

    @Test
    void anOversizedHeaderValueIsRejected() throws Exception {
        try (var fixture = fixture(limits(builder -> builder), new RecordingAudit())) {
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(fixture.uri("/v1/status"))
                            .header("Authorization", "Bearer tenant-a")
                            .header("X-Probe", "a".repeat(9_000))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(431, response.statusCode());
            assertTrue(response.body().contains("HEADER_VALUE_TOO_LARGE"), response.body());
        }
    }

    /**
     * The query string is the easiest way to build an oversized request, not the only one. Headers need
     * checking separately: if they break the connection the same way, the
     * defect is not about the payload's position, and the mitigation must cover both forms. It is the
     * same underlying JDK cap ({@code sun.net.httpserver.maxReqHeaderSize}) applied to the request line
     * and the header block alike -- {@code Request.readLine()} for the former, {@code Request.headers()}
     * for the latter, both inside the same class, both governed by the same system property -- so an
     * oversized single header value reproduces the identical unstructured connection death, and the same
     * configured property in {@link RavenrootServer}'s static initializer covers both. At the JDK-default
     * cap, this exact request reproduces the same failure through a header instead of the query string.
     */
    @Test
    void aHeaderValueOverTheOldJdkCapAlsoGetsAStructuredRejectionNotJustAQueryDoes() throws Exception {
        try (var fixture = fixture(limits(builder -> builder), new RecordingAudit())) {
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(fixture.uri("/v1/status"))
                            .header("Authorization", "Bearer tenant-a")
                            // 400,000 chars is comfortably past the 389,120-byte (380 KiB) old JDK default
                            // -- unlike the query test above, this one does not need to pin the exact byte
                            // boundary, only to show the same class of defect reproduces through a header.
                            .header("X-Probe", "a".repeat(400_000))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(431, response.statusCode());
            assertTrue(response.body().contains("HEADER_VALUE_TOO_LARGE"), response.body());
        }
    }

    /** The unauthenticated surfaces are limited too, being the cheapest amplification targets. */
    @Test
    void theUnauthenticatedHealthEndpointIsRateLimited() throws Exception {
        var limits = limits(builder -> builder.address(1, 2));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();

            assertEquals(200, fixture.plainGet(client, "/health").statusCode());
            assertEquals(200, fixture.plainGet(client, "/health").statusCode());

            var throttled = fixture.plainGet(client, "/health");
            assertEquals(429, throttled.statusCode(),
                    "the unauthenticated health endpoint was unlimited");
            assertTrue(throttled.headers().firstValue("Retry-After").isPresent());
        }
    }

    /** A rejection before authentication cannot name a tenant, and must not invent one. */
    @Test
    void aPreAuthenticationRejectionIsAuditedWithoutInventingAnIdentity() throws Exception {
        var limits = limits(builder -> builder.address(1, 1));
        var audit = new RecordingAudit();
        try (var fixture = fixture(limits, audit)) {
            var client = HttpClient.newHttpClient();
            assertEquals(200, fixture.plainGet(client, "/health").statusCode());
            var refused = fixture.plainGet(client, "/health");
            assertEquals(429, refused.statusCode(), refused.body());

            var rejection = audit.events().stream()
                    .filter(event -> event.code().equals("ADDRESS_RATE_LIMIT_EXCEEDED"))
                    .findFirst().orElseThrow();

            assertEquals(RateLimitAuditEvent.UNKNOWN, rejection.tenantId());
            assertEquals(RateLimitAuditEvent.UNKNOWN, rejection.subject());
            assertEquals("127.0.0.1", rejection.clientAddress());
            assertFalse(rejection.forwarded());
            assertEquals(429, rejection.status());
            assertTrue(rejection.retryAfterSeconds() >= 1);
            assertEquals(correlationIn(refused.body()), rejection.requestId(),
                    "the unauthenticated error and its audit record must name the same request");
        }
    }

    /** A post-authentication rejection must carry the identity, or the record is not actionable. */
    @Test
    void aPostAuthenticationRejectionIsAuditedWithItsTenantAndSubject() throws Exception {
        var limits = limits(builder -> builder.address(100_000, 100_000).tenant(1, 1));
        var audit = new RecordingAudit();
        try (var fixture = fixture(limits, audit)) {
            var client = HttpClient.newHttpClient();
            fixture.get(client, "/v1/status", "tenant-a", "alice");
            fixture.get(client, "/v1/status", "tenant-a", "alice");

            var rejection = audit.events().stream()
                    .filter(event -> event.code().equals("TENANT_RATE_LIMIT_EXCEEDED"))
                    .findFirst().orElseThrow();

            assertEquals("tenant-a", rejection.tenantId());
            assertEquals("alice", rejection.subject());
            assertEquals("tenant", rejection.scope());
        }
    }

    /**
     * Two exchanges matched to one JDK route must never borrow each other's network identity.
     *
     * <p>The authenticator gates make the interleaving exact: A has finished client resolution before
     * B starts; B has then finished its own resolution before A is allowed to reach the exhausted
     * principal budget; and B remains inside authentication until A's refusal is complete. Both
     * requests are real HTTP requests through {@code publicContext} and {@code protectedRequest}.
     * This is the schedule that exposes route-context attributes: B's write is the last one on the
     * shared JDK context, so an A refusal that reads the attribute back reports B's address.</p>
     */
    @Test
    void simultaneousRequestsOnOneRouteKeepTheirOwnClientAndAuditIdentity() throws Throwable {
        var limits = limits(builder -> builder.principal(1, 1));
        var audit = new RecordingAudit();
        var trustedProxy = new TrustedProxyConfiguration(1, Set.of("127.0.0.1"));
        var limiter = new RateLimiter(limits, trustedProxy, audit, nanos::get);
        var racing = new java.util.concurrent.atomic.AtomicBoolean();
        var aEnteredAuthentication = new java.util.concurrent.CountDownLatch(1);
        var bEnteredAuthentication = new java.util.concurrent.CountDownLatch(1);
        var releaseB = new java.util.concurrent.CountDownLatch(1);
        RequestAuthenticator authenticator = headers -> {
            String bearer = headers.getFirst("Authorization");
            if (racing.get() && "Bearer tenant-a:alice".equals(bearer)) {
                aEnteredAuthentication.countDown();
                awaitAuthenticationGate(bEnteredAuthentication, "request B never entered authentication");
            } else if (racing.get() && "Bearer tenant-b:bob".equals(bearer)) {
                bEnteredAuthentication.countDown();
                awaitAuthenticationGate(releaseB, "request B was never released after request A's refusal");
            }
            return tenantAuthenticator().authenticate(headers);
        };
        InetAddress ipv4Loopback = InetAddress.getByName("127.0.0.1");
        try (var fixture = fixture(limits, limiter, authenticator, ipv4Loopback)) {
            var requests = java.util.concurrent.Executors.newFixedThreadPool(2);
            var submitted = new ArrayList<java.util.concurrent.Future<String>>();
            var activeSockets = new java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket>();
            Throwable testFailure = null;
            try {
                String primedA = rawRequest(fixture.server().port(), "GET", "tenant-a:alice",
                        "203.0.113.6", ipv4Loopback, activeSockets);
                String primedB = rawRequest(fixture.server().port(), "GET", "tenant-b:bob",
                        "203.0.113.7", ipv4Loopback, activeSockets);
                assertTrue(primedA.startsWith("HTTP/1.1 200 "), primedA);
                assertTrue(primedB.startsWith("HTTP/1.1 200 "), primedB);

                racing.set(true);
                var requestA = requests.submit(() -> rawRequest(
                        fixture.server().port(), "GET", "tenant-a:alice", "203.0.113.6",
                        ipv4Loopback, activeSockets));
                submitted.add(requestA);
                assertTrue(aEnteredAuthentication.await(10, java.util.concurrent.TimeUnit.SECONDS),
                        "request A never reached authentication after resolving its client");

                var requestB = requests.submit(() -> rawRequest(
                        fixture.server().port(), "GET", "tenant-b:bob", "203.0.113.7",
                        ipv4Loopback, activeSockets));
                submitted.add(requestB);
                assertTrue(bEnteredAuthentication.await(10, java.util.concurrent.TimeUnit.SECONDS),
                        "request B never reached authentication after resolving its client");

                String refusedA = requestA.get(10, java.util.concurrent.TimeUnit.SECONDS);
                releaseB.countDown();
                String refusedB = requestB.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(refusedA.startsWith("HTTP/1.1 429 "), refusedA);
                assertTrue(refusedB.startsWith("HTTP/1.1 429 "), refusedB);
                assertTrue(refusedA.contains("PRINCIPAL_RATE_LIMIT_EXCEEDED"), refusedA);
                assertTrue(refusedB.contains("PRINCIPAL_RATE_LIMIT_EXCEEDED"), refusedB);
                assertEquals(2, audit.events().size(), "only the two measured principal refusals are audited");

                RateLimitAuditEvent auditA = audit.event("tenant-a", "alice");
                RateLimitAuditEvent auditB = audit.event("tenant-b", "bob");
                String correlationA = correlationIn(refusedA);
                String correlationB = correlationIn(refusedB);
                assertEquals(correlationA, auditA.requestId(), "A's error and audit cannot name different requests");
                assertEquals(correlationB, auditB.requestId(), "B's error and audit cannot name different requests");
                assertFalse(correlationA.isBlank());
                assertFalse(correlationB.isBlank());
                assertNotEquals(correlationA, correlationB, "simultaneous requests reused one correlation id");
                assertEquals("PRINCIPAL_RATE_LIMIT_EXCEEDED", auditA.code());
                assertEquals("tenant-a", auditA.tenantId());
                assertEquals("alice", auditA.subject());
                assertEquals("203.0.113.6", auditA.clientAddress(),
                        "request A borrowed request B's address from their shared JDK route context");
                assertTrue(auditA.forwarded());
                assertEquals("PRINCIPAL_RATE_LIMIT_EXCEEDED", auditB.code());
                assertEquals("tenant-b", auditB.tenantId());
                assertEquals("bob", auditB.subject());
                assertEquals("203.0.113.7", auditB.clientAddress());
                assertTrue(auditB.forwarded());
            } catch (Throwable failure) {
                testFailure = failure;
                throw failure;
            } finally {
                bEnteredAuthentication.countDown();
                releaseB.countDown();
                Throwable cleanupFailure = drainRequests(requests, submitted, activeSockets);
                if (cleanupFailure != null) {
                    if (testFailure == null) throw cleanupFailure;
                    testFailure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    /** The forwarded flag and address in a real public-route refusal come from the trusted resolution. */
    @Test
    void aTrustedForwardedRejectionAuditsTheResolvedClientAndCorrelation() throws Exception {
        var limits = limits(builder -> builder.address(1, 1));
        var audit = new RecordingAudit();
        var limiter = new RateLimiter(limits,
                new TrustedProxyConfiguration(1, Set.of("127.0.0.1")), audit, nanos::get);
        InetAddress ipv4Loopback = InetAddress.getByName("127.0.0.1");
        try (var fixture = fixture(limits, limiter, tenantAuthenticator(), ipv4Loopback)) {
            String first = rawRequest(fixture.server().port(), "GET", "tenant-a:alice", "203.0.113.6",
                    ipv4Loopback, new java.util.concurrent.ConcurrentLinkedQueue<>());
            String refused = rawRequest(fixture.server().port(), "GET", "tenant-a:alice", "203.0.113.6",
                    ipv4Loopback, new java.util.concurrent.ConcurrentLinkedQueue<>());
            assertTrue(first.startsWith("HTTP/1.1 200 "), first);
            assertTrue(refused.startsWith("HTTP/1.1 429 "), refused);

            RateLimitAuditEvent event = audit.events().stream()
                    .filter(candidate -> candidate.code().equals("ADDRESS_RATE_LIMIT_EXCEEDED"))
                    .findFirst().orElseThrow();
            assertEquals("203.0.113.6", event.clientAddress());
            assertTrue(event.forwarded());
            assertEquals(RateLimitAuditEvent.UNKNOWN, event.tenantId());
            assertEquals(RateLimitAuditEvent.UNKNOWN, event.subject());
            assertEquals(correlationIn(refused), event.requestId());
        }
    }

    /**
     * Streams are limited by simultaneous count, not by rate. A caller that opens one stream and holds
     * it forever stays inside every rate budget while occupying a connection, a subscription and a
     * buffer, so a count is the only limit that binds.
     */
    @Test
    void anAdditionalStreamBeyondTheConcurrencyLimitIsRefused() throws Exception {
        var limits = limits(builder -> builder.streams(1, 1));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();

            var open = client.sendAsync(HttpRequest.newBuilder(fixture.uri("/v1/events"))
                            .header("Authorization", "Bearer tenant-a:alice").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            var first = open.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, first.statusCode(), "the first stream should have been accepted");

            try {
                var second = fixture.get(client, "/v1/events", "tenant-a", "alice");

                assertEquals(429, second.statusCode(), "a second concurrent stream was accepted beyond the limit");
                assertTrue(second.body().contains("STREAM_CONCURRENCY_EXCEEDED"), second.body());
                assertTrue(second.headers().firstValue("Retry-After").isPresent());
            } finally {
                first.body().close();
            }
        }
    }

    /** Submissions are budgeted far more tightly than ordinary requests, and the limit precedes the body. */
    @Test
    void submissionsAreThrottledOnTheirOwnTighterBudget() throws Exception {
        var limits = limits(builder -> builder.submissions(1, 1));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();

            var first = fixture.post(client, "/v1/executions", "tenant-a", "<graphml/>");
            assertFalse(first.statusCode() == 429, "the first submission should be inside the budget");

            var second = fixture.post(client, "/v1/executions", "tenant-a", "<graphml/>");

            assertEquals(429, second.statusCode());
            assertTrue(second.body().contains("SUBMISSION_RATE_LIMIT_EXCEEDED"), second.body());
            assertTrue(second.headers().firstValue("Retry-After").isPresent());
        }
    }

    /**
     * The case the new admission control could most easily have broken: ordinary graphs on a real engine.
     *
     * <p>Per-tenant accounting is keyed on the identifier the engine stamps its events with. If the
     * key on {@code EXECUTION_STARTED} and the key on the terminal event ever stopped matching, nothing
     * would fail immediately — entries would simply accumulate, and a tenant that had run
     * {@code tenantActiveExecutions()} perfectly ordinary graphs would then be refused for the life of
     * the process. So this submits well past the tenant share and asserts both that every one is
     * accepted and that the accounting comes back to zero.</p>
     */
    @Test
    void repeatedRealExecutionsReleaseTheirAccountingAndKeepBeingAccepted() throws Exception {
        var limits = limits(builder -> builder.activeExecutions(4));
        var limiter = limiter(limits, new RecordingAudit());
        assertEquals(3, limits.tenantActiveExecutions(),
                "the probe must submit past the per-tenant share for the assertion below to mean anything");
        try (var fixture = fixture(limits, limiter)) {
            var client = HttpClient.newHttpClient();

            for (int index = 0; index < 12; index++) {
                var accepted = fixture.post(client, "/v1/executions", "tenant-a", EXECUTABLE_GRAPH);
                assertEquals(202, accepted.statusCode(), "submission " + index + " of an ordinary graph was "
                        + "refused: " + accepted.body());
                await(() -> limiter.activeExecutions().activeFor("tenant-a") == 0,
                        "an ordinary execution that ran to completion never released its accounting entry, "
                                + "so entries accumulate and the tenant will be refused permanently");
            }

            assertEquals(0, limiter.activeExecutions().total());
            assertEquals(0, limiter.activeExecutions().expiredEntries(),
                    "an execution had to be aged out, which means a terminal event was not matched to its entry");
        }
    }

    /** A tenant flooding submissions must not consume another tenant's submission budget. */
    @Test
    void oneTenantsSubmissionFloodDoesNotConsumeAnothersBudget() throws Exception {
        var limits = limits(builder -> builder.submissions(2, 2));
        try (var fixture = fixture(limits, new RecordingAudit())) {
            var client = HttpClient.newHttpClient();

            for (int index = 0; index < 50; index++) {
                fixture.post(client, "/v1/executions", "tenant-noisy", "<graphml/>");
            }

            var quiet = fixture.post(client, "/v1/executions", "tenant-quiet", "<graphml/>");

            assertFalse(quiet.statusCode() == 429,
                    "a neighbouring tenant's submission budget was consumed by the noisy tenant");
        }
    }

    /**
     * The server returns a stream slot when its handler ends.
     *
     * <p>This has to be asserted at the server, not at the limiter: the limiter's own release is
     * exercised below, but nothing there says the <em>server</em> ever calls it. If it stopped calling
     * it, every tenant would be permanently locked out of {@code /v1/events} after
     * {@code RAVENROOT_RATELIMIT_TENANT_STREAMS} connections, and a limiter-only test would still
     * pass.</p>
     *
     * <p>It is deterministic in outcome without being a timing assertion. The gate count is observable,
     * so the test states a condition and waits a bounded time for it: it either becomes true — at
     * whatever speed this machine notices the closed socket — or the test fails. A slot that is never
     * returned never satisfies it, however long CI takes.</p>
     */
    @Test
    void theServerReturnsAStreamSlotWhenTheStreamEnds() throws Exception {
        var limits = limits(builder -> builder.streams(1, 1));
        var limiter = limiter(limits, new RecordingAudit());
        try (var fixture = fixture(limits, limiter)) {
            var client = HttpClient.newHttpClient();

            var first = client.sendAsync(HttpRequest.newBuilder(fixture.uri("/v1/events"))
                                    .header("Authorization", "Bearer tenant-a:alice").GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream())
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, first.statusCode());
            assertEquals(1, limiter.openPrincipalStreams("tenant-a", "alice"),
                    "an accepted stream did not take a slot, so this test could not detect a leak");

            first.body().close();

            await(() -> limiter.openPrincipalStreams("tenant-a", "alice") == 0,
                    "the server never returned the principal stream slot after the stream ended");
            await(() -> limiter.openTenantStreams("tenant-a") == 0,
                    "the server never returned the tenant stream slot after the stream ended");

            var second = client.sendAsync(HttpRequest.newBuilder(fixture.uri("/v1/events"))
                                    .header("Authorization", "Bearer tenant-a:alice").GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream())
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertEquals(200, second.statusCode(),
                        "the slot was reported free but a new stream was still refused");
            } finally {
                second.body().close();
            }
        }
    }

    /** Waits a bounded time for an observable condition; a condition that never holds fails the test. */
    private static void await(java.util.function.BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        org.junit.jupiter.api.Assertions.fail(message);
    }

    /**
     * Slot release at the limiter, asserted exactly.
     *
     * <p>Complementary to the server-level test above rather than a substitute for it: this one pins
     * that a returned permit is genuinely re-usable and that a double close cannot return it twice.</p>
     */
    @Test
    void aReleasedStreamSlotBecomesAvailableAgain() {
        var limits = limits(builder -> builder.streams(1, 1));
        try (var limiter = new RateLimiter(limits, TrustedProxyConfiguration.direct(),
                RateLimitAuditSink.discarding(), nanos::get)) {
            for (int attempt = 0; attempt < 5; attempt++) {
                try (var slot = limiter.acquireStreamSlot("tenant-a", "alice")) {
                    assertTrue(slot.granted(),
                            "attempt " + attempt + " was refused, so an earlier slot was never returned");
                    try (var second = limiter.acquireStreamSlot("tenant-a", "alice")) {
                        assertFalse(second.granted(), "the concurrency limit of 1 admitted a second holder");
                    }
                }
            }
        }
    }

    /** The audit record must be one JSON line joinable to the rest of the SEC-07 trail. */
    @Test
    void theStructuredRecordIsOneJsonLineCarryingTheRequestId() {
        var buffer = new ByteArrayOutputStream();
        new StructuredRateLimitLogger(new PrintStream(buffer, true))
                .record(new RateLimitAuditEvent(Instant.EPOCH, "request-1", "203.0.113.7", true,
                        "tenant-a", "alice", "GET", "/v1/status", "TENANT_RATE_LIMIT_EXCEEDED", "tenant",
                        429, 3));

        String line = buffer.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
        assertFalse(line.contains("\n"), "the record spanned more than one line");
        assertTrue(line.contains("\"event\":\"rate_limit_rejection\""), line);
        assertTrue(line.contains("\"requestId\":\"request-1\""), line);
        assertTrue(line.contains("\"clientAddress\":\"203.0.113.7\""), line);
        assertTrue(line.contains("\"retryAfterSeconds\":3"), line);
    }

    private void drain(Fixture fixture, HttpClient client, String tenant) throws Exception {
        while (fixture.get(client, "/v1/status", tenant).statusCode() == 200) {
            // Spend the initial burst so the next assertion measures sustained refill only.
        }
    }

    /**
     * Sends {@code GET <target> HTTP/1.1} over a raw loopback socket with exactly three headers --
     * {@code Host}, {@code Authorization: Bearer tenant-a:alice}, {@code Connection: close} -- and
     * returns everything read until the server closes the connection. Used instead of {@link HttpClient}
     * wherever a test's assertion depends on the exact byte count of the request (see
     * {@link #aQueryAtTheMeasuredOldJdkCapBoundaryStillGetsAStructuredRejection}'s Javadoc for why:
     * {@code HttpClient} attempts an h2c upgrade whose extra headers are not part of its public byte-
     * count contract). Throws {@link java.io.IOException} if the connection is reset or closes before
     * anything is read -- the raw connection death under test, distinguishable from a normal response
     * by the caller ({@code assertThrows} vs. inspecting the returned text).
     */
    private static String rawGet(int port, String target) throws java.io.IOException {
        try (var socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 10_000);
            socket.setSoTimeout(10_000);
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Authorization: Bearer tenant-a:alice\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = socket.getInputStream().read(buffer)) != -1) {
                response.write(buffer, 0, read);
            }
            if (response.size() == 0) {
                throw new java.io.IOException("connection closed with nothing read (target=" + target.length()
                        + " chars)");
            }
            return response.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String rawRequest(int port, String method, String bearer, String forwardedFor,
                                     InetAddress destination,
                                     java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket> activeSockets)
            throws java.io.IOException {
        var socket = new java.net.Socket();
        activeSockets.add(socket);
        try (socket) {
            socket.connect(new InetSocketAddress(destination, port), 10_000);
            socket.setSoTimeout(10_000);
            String request = method + " /v1/status HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Authorization: Bearer " + bearer + "\r\n"
                    + "X-Forwarded-For: " + forwardedFor + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            activeSockets.remove(socket);
        }
    }

    private static String correlationIn(String response) {
        var matcher = java.util.regex.Pattern.compile("\\\"correlationId\\\":\\\"([^\\\"]+)\\\"")
                .matcher(response);
        if (!matcher.find()) throw new AssertionError("response has no correlation id: " + response);
        return matcher.group(1);
    }

    private static void awaitAuthenticationGate(java.util.concurrent.CountDownLatch gate, String failure)
            throws ai.ravenroot.server.security.AuthenticationException {
        try {
            if (!gate.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new ai.ravenroot.server.security.AuthenticationException(failure);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ai.ravenroot.server.security.AuthenticationException(
                    "authentication gate was interrupted", interrupted);
        }
    }

    private static Throwable drainRequests(
            java.util.concurrent.ExecutorService executor,
            List<java.util.concurrent.Future<String>> requests,
            java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket> activeSockets) {
        Throwable failure = null;
        executor.shutdown();
        long drainDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        for (var request : requests) {
            try {
                request.get(Math.max(1L, drainDeadline - System.nanoTime()),
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (Throwable drainFailure) {
                if (failure == null) failure = drainFailure;
                else failure.addSuppressed(drainFailure);
                request.cancel(true);
            }
        }
        for (var socket : activeSockets) {
            try {
                socket.close();
            } catch (java.io.IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                var timeout = new AssertionError("raw HTTP request executor did not terminate");
                if (failure == null) failure = timeout;
                else failure.addSuppressed(timeout);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            boolean terminated = false;
            try {
                terminated = executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException repeated) {
                interrupted.addSuppressed(repeated);
            } finally {
                Thread.currentThread().interrupt();
            }
            if (!terminated) {
                interrupted.addSuppressed(new AssertionError("interrupted request executor did not terminate"));
            }
            if (failure == null) failure = interrupted;
            else failure.addSuppressed(interrupted);
        }
        return failure;
    }

    private RateLimiter limiter(RateLimitConfiguration limits, RateLimitAuditSink audit) {
        return new RateLimiter(limits, TrustedProxyConfiguration.direct(), audit, nanos::get);
    }

    private Fixture fixture(RateLimitConfiguration limits, RateLimitAuditSink audit) {
        return fixture(limits, limiter(limits, audit));
    }

    private Fixture fixture(RateLimitConfiguration limits, RateLimiter limiter) {
        return fixture(limits, limiter, tenantAuthenticator());
    }

    private Fixture fixture(RateLimitConfiguration limits, RateLimiter limiter,
                            RequestAuthenticator authenticator) {
        return fixture(limits, limiter, authenticator, InetAddress.getLoopbackAddress());
    }

    private Fixture fixture(RateLimitConfiguration limits, RateLimiter limiter,
                            RequestAuthenticator authenticator, InetAddress bindAddress) {
        var engine = new PekkoExecutionEngine("ratelimit-http-test-" + System.nanoTime());
        var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
        var server = new RavenrootServer(
                new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(bindAddress, 0), null, true,
                authenticator, httpSecurity(), Clock.systemUTC(),
                new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter);
        server.start();
        return new Fixture(server, engine);
    }

    private record Fixture(RavenrootServer server, PekkoExecutionEngine engine) implements AutoCloseable {
        URI uri(String path) {
            return URI.create("http://localhost:" + server.port() + path);
        }

        HttpResponse<String> get(HttpClient client, String path, String tenant) throws Exception {
            return get(client, path, tenant, "alice");
        }

        HttpResponse<String> get(HttpClient client, String path, String tenant, String subject)
                throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path))
                            .header("Authorization", "Bearer " + tenant + ":" + subject).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(HttpClient client, String path, String tenant, String body)
                throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path))
                            .header("Authorization", "Bearer " + tenant + ":alice")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> plainGet(HttpClient client, String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            server.close();
            engine.close();
        }
    }

    /** Maps {@code Bearer <tenant>:<subject>} onto a fully scoped principal, so limits are what vary. */
    private static RequestAuthenticator tenantAuthenticator() {
        return headers -> {
            String value = headers.getFirst("Authorization");
            String token = value == null ? "tenant-a:alice" : value.replaceFirst("^Bearer ", "");
            String[] parts = token.split(":", 2);
            String tenant = parts[0].isBlank() ? "tenant-a" : parts[0];
            String subject = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "alice";
            return new AuthenticatedPrincipal(subject, AuthenticatedPrincipal.Type.USER,
                    "https://issuer.example", tenant, Set.of(Role.PLATFORM_ADMIN),
                    java.util.Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    Instant.now().plus(Duration.ofHours(1)));
        };
    }

    private static HttpSecurityConfiguration httpSecurity() {
        return new HttpSecurityConfiguration(
                new BrowserOriginPolicy(Set.of("http://127.0.0.1:1")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(30));
    }

    private static final class RecordingAudit implements RateLimitAuditSink {
        private final List<RateLimitAuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(RateLimitAuditEvent event) {
            events.add(event);
        }

        List<RateLimitAuditEvent> events() {
            return events;
        }

        List<String> codes() {
            return events.stream().map(RateLimitAuditEvent::code).toList();
        }

        RateLimitAuditEvent event(String tenantId, String subject) {
            return events.stream()
                    .filter(event -> event.tenantId().equals(tenantId) && event.subject().equals(subject))
                    .findFirst().orElseThrow();
        }
    }

    private static RateLimitConfiguration limits(java.util.function.UnaryOperator<LimitBuilder> customise) {
        return customise.apply(new LimitBuilder()).build();
    }

    /** Small builder so each test states only the limits it is about. */
    private static final class LimitBuilder {
        private int addressRate = 100_000;
        private int addressBurst = 100_000;
        private int tenantRate = 100_000;
        private int tenantBurst = 100_000;
        private int principalRate = 100_000;
        private int principalBurst = 100_000;
        private int submissionRate = 100_000;
        private int submissionBurst = 100_000;
        private int tenantStreams = RateLimitConfiguration.DEFAULTS.tenantConcurrentStreams();
        private int principalStreams = RateLimitConfiguration.DEFAULTS.principalConcurrentStreams();
        private int globalActiveExecutions = RateLimitConfiguration.DEFAULTS.globalActiveExecutions();
        private Duration executionMaxAge = RateLimitConfiguration.DEFAULTS.executionMaxAge();

        LimitBuilder activeExecutions(int global) {
            this.globalActiveExecutions = global;
            return this;
        }

        LimitBuilder executionMaxAge(Duration maxAge) {
            this.executionMaxAge = maxAge;
            return this;
        }

        LimitBuilder streams(int perTenant, int perPrincipal) {
            this.tenantStreams = perTenant;
            this.principalStreams = perPrincipal;
            return this;
        }

        LimitBuilder submissions(int rate, int burst) {
            this.submissionRate = rate;
            this.submissionBurst = burst;
            return this;
        }

        LimitBuilder address(int rate, int burst) {
            this.addressRate = rate;
            this.addressBurst = burst;
            return this;
        }

        LimitBuilder tenant(int rate, int burst) {
            this.tenantRate = rate;
            this.tenantBurst = burst;
            return this;
        }

        LimitBuilder principal(int rate, int burst) {
            this.principalRate = rate;
            this.principalBurst = burst;
            return this;
        }

        RateLimitConfiguration build() {
            var defaults = RateLimitConfiguration.DEFAULTS;
            return new RateLimitConfiguration(
                    addressRate, addressBurst,
                    tenantRate, tenantBurst,
                    principalRate, principalBurst,
                    submissionRate, submissionBurst,
                    defaults.tenantConcurrentSubmissions(), globalActiveExecutions,
                    tenantStreams, principalStreams,
                    defaults.streamQueueCapacity(),
                    defaults.maxQueryBytes(), defaults.maxQueryParameters(),
                    defaults.maxHeaderCount(), defaults.maxHeaderBytes(), defaults.maxHeaderValueBytes(),
                    defaults.maxTrackedClients(), defaults.maxTrackedTenants(),
                    defaults.maxTrackedPrincipals(), defaults.idleEntryTtl(), executionMaxAge);
        }
    }
}
