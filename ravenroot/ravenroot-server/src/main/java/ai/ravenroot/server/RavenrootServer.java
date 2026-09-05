package ai.ravenroot.server;

import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.StableEdgeId;
import ai.ravenroot.api.application.PublicExecutionDescription;
import ai.ravenroot.api.application.RuntimeActivityData;
import ai.ravenroot.api.catalog.NodeBypassProperty;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.catalog.NodeRuntimeMaxConcurrencyProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.api.error.ErrorEnvelope;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditSink;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramDeadlineExceededException;
import ai.ravenroot.api.programming.ProgramRuntimeUnavailableException;
import ai.ravenroot.api.programming.ProgramSourceRejectedException;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.core.graph.GraphMlCompatibilityException;
import ai.ravenroot.core.graph.GraphMlParseException;
import ai.ravenroot.core.graph.GraphMlRejectionDetail;
import ai.ravenroot.server.audit.GraphMlRejectionAuditEvent;
import ai.ravenroot.server.audit.GraphMlRejectionAuditSink;
import ai.ravenroot.server.audit.StructuredExecutionLogger;
import ai.ravenroot.server.audit.StructuredGraphMlRejectionLogger;
import ai.ravenroot.server.audit.StructuredAuthorizationLogger;
import ai.ravenroot.server.audit.StructuredArtifactLifecycleLogger;
import ai.ravenroot.server.audit.StructuredRateLimitLogger;
import ai.ravenroot.server.error.PayloadRejectionAuditEvent;
import ai.ravenroot.server.error.PayloadRejectionAuditSink;
import ai.ravenroot.server.error.StructuredPayloadRejectionLogger;
import ai.ravenroot.server.payload.StructuredSubmission;
import ai.ravenroot.server.payload.ArtifactTestSubmission;
import ai.ravenroot.server.payload.ProgramBuildSubmission;
import ai.ravenroot.server.ratelimit.BoundedEventQueue;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimitDecision;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.RejectingAuthenticator;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Lightweight JDK HTTP adapter. Business use cases remain in RavenrootApplication. */
public final class RavenrootServer implements AutoCloseable {
    private static final int MAX_GRAPH_BYTES = GraphMlLimits.DEFAULTS.maxBytes();
    private static final int MAX_PROGRAM_BYTES = 1024 * 1024;
    /** The same ceiling {@code AssistantTurn.TURN_LIMITS} parses under, applied before parsing. */
    private static final int MAX_ASSISTANT_TURN_BYTES =
            ai.ravenroot.server.assistant.AssistantTurn.TURN_LIMITS.maxEncodedBytes();

    /**
     * Raises the JDK's own hard cap on request-line-plus-headers size, so {@link RateLimiter}
     * gets a chance to answer an oversized request instead of the connection dying underneath it.
     *
     * <h2>The JDK request-header constraint</h2>
     *
     * <p>{@link HttpServer#create} (below) is backed by {@code com.sun.net.httpserver}, whose
     * implementation ({@code sun.net.httpserver.Request}, {@code ServerImpl.Exchange.run()}) reads and
     * bounds the request line and headers <strong>before</strong> any {@code HttpContext}, filter or
     * handler of ours -- including {@link #publicContext}, where {@link RateLimiter#checkRequestShape}
     * lives -- is ever invoked: {@code ServerImpl.Exchange.run()} constructs a {@code Request} before it
     * ever calls {@code findContext}. Both classes are package-private inside a JDK module we do not
     * control: there is no filter hook and no way to run our own check earlier <em>inside</em> that
     * implementation. ({@code com.sun.net.httpserver.spi.HttpServerProvider}, loaded via
     * {@code ServiceLoader}, is the JDK's one real extension point here -- but it lets an implementor
     * <strong>replace</strong> {@code com.sun.net.httpserver} wholesale with a different HTTP/1.1 stack,
     * not reach inside this one; this adapter does not implement such a replacement.)
     * When the cap is exceeded, {@code Request}'s constructor throws an {@code IOException}; the
     * surrounding {@code catch (Exception e)} in {@code ServerImpl.Exchange.run()} sees a null exchange
     * and calls {@code closeConnection()} directly -- a bare TCP close with no status line, no envelope,
     * no message. A query-string payload above the JDK cap therefore breaks the
     * connection instead of getting the 414 that {@link RateLimiter#checkRequestShape} already knows
     * how to send for the very same reason, one size smaller.</p>
     *
     * <h2>The measurement -- and why it has no single answer</h2>
     *
     * <p>{@code sun.net.httpserver.ServerConfig#DEFAULT_MAX_REQ_HEADER_SIZE} is
     * {@value #JDK_DEFAULT_REQUEST_HEADER_CAP_BYTES} ({@code 380 * 1024}) bytes in this JDK's own
     * {@code jdk.httpserver} module sources. The break point is not purely a function of the request
     * line's length: the exception comes from
     * {@code Request.headers()}, which seeds its own byte counter from {@code startLine.length() + 32}
     * and then adds {@code fieldLength + 32} for every header line the client sends -- so the request
     * line and the headers spend out of the <strong>same</strong> budget. The break point is therefore
     * not a property of the cap alone: it depends on which headers, and how
     * many bytes of them, the specific client sends alongside the oversized part. Measured against a
     * running {@code RavenrootServer} with this cap left at the JDK default, by bisection over a raw
     * socket sending exactly {@code Host}, {@code Authorization} and {@code Connection}: a query of
     * 388,891 characters is read successfully and reaches {@code checkRequestShape}, which answers
     * {@code 414 QUERY_TOO_LARGE}; 388,892 breaks the connection with nothing on the wire. A different
     * client -- more headers, a longer {@code Host}, a cookie -- breaks at a different, smaller number,
     * because it spends more of the same budget before the query even starts. This is exactly why the
     * number cannot be deduced from {@code ServerConfig}'s documented default alone: what
     * matters is measured against this product, with a specific client, and does not generalise to a
     * different one. {@code RateLimitHttpIntegrationTest#aQueryAtTheMeasuredOldJdkCapBoundaryStillGetsAStructuredRejection}
     * pins that exact size and failure mode for that exact client at the JDK-default cap. With the
     * configured cap below, the equivalent break point for the same client is
     * 2,096,923 (succeeds) / 2,096,924 (fails) --
     * {@code RateLimitHttpIntegrationTest#aQueryFarPastTheNewCapStillBreaksTheRawConnection} exercises a
     * size well past it, not that exact boundary, since the residual above the new cap only needs to be
     * shown to exist, while the old failure boundary is pinned to the byte.</p>
     *
     * <h2>Why 2 MiB, and what still breaks above it</h2>
     *
     * <p>The default {@link RateLimitConfiguration} rejects a raw query over 4 KiB and headers over
     * 16 KiB -- 20 KiB combined, comfortably under even the old 380 KiB JDK default. The bug was never
     * that our own limits sit above the JDK's; it is that the JDK's cap sat too low relative to sizes an
     * operator or a caller can plausibly send expecting an ordinary rejection, so some of those requests
     * never reached the limiter at all. {@value #REQUEST_HEADER_CAP_BYTES} bytes (2 MiB) is chosen to
     * leave wide headroom over that combined 20 KiB default -- and over any reasonably widened
     * deployment of it. A payload dropped into the query string
     * now lands on {@link RateLimiter#checkRequestShape} and gets a structured, explained rejection
     * instead of a dead socket. Above 2 MiB, the same unstructured close still
     * happens, for the same reason: nothing this class can do runs before {@code sun.net.httpserver}
     * finishes parsing the request line. Closing that residual fully would mean replacing the JDK's
     * built-in HTTP layer, which this adapter does not do.</p>
     *
     * <h2>What 2 MiB actually costs -- and why {@code jdk.httpserver.maxConnections} is not the answer</h2>
     *
     * <p>A 2 MiB cap does not cost only 2 MiB per pending connection.
     * {@code Request.readLine()} accumulates the oversized line one {@code char} at a time into a
     * {@code StringBuffer}, and {@code Request.headers()} accumulates header text into a {@code char[]}
     * that doubles in place when it fills -- both hold Java {@code char}s (2 bytes each, not 1), and the
     * doubling strategy means the buffer can be up to twice as large as the content it holds at the exact
     * moment the cap is detected. Measured directly (a process bounded to {@code -Xmx256m}, 16
     * unauthenticated connections each holding one line open without ever completing it): the old
     * 380 KiB cap cost roughly 45 MiB total for those 16 connections and {@code /health} kept responding;
     * this class's 2 MiB cap cost 93-114 MiB across three runs (roughly 6-7 MiB per held connection, not
     * 2) and {@code /health} stopped responding within 15 seconds. That 6-7x-the-cap peak, not 1x, is
     * what one slow, never-completing connection actually costs while it is held open.</p>
     *
     * <p>Setting {@code jdk.httpserver.maxConnections} does not safely bound the total. It is the JDK's
     * own ceiling on how many connections {@code com.sun.net.httpserver} will {@code accept()} at
     * once, and defaults to {@code -1} (no limit). {@code ServerImpl}'s accept loop, once the ceiling
     * is reached, calls {@code accept()} and
     * then {@code chan.close()} immediately -- a bare, answerless close for a different reason.
     * Measured against a live server with that ceiling set to 200: 200 idle connections (no attack, no
     * oversized anything, just open sockets) made a perfectly ordinary {@code GET /health} fail with
     * {@code SocketException: Connection reset}; released, it answered normally again. That ceiling was
     * reachable by this product's own defaults without any abuse --
     * {@code RateLimitConfiguration.DEFAULTS.tenantConcurrentStreams()} is 16 with no matching *global*
     * ceiling, so 13 ordinarily-behaved streaming tenants saturate it -- and {@code /health} and
     * {@code /ready} share that same {@code accept()} queue with every other route
     * (see {@code deploy/kubernetes/ravenroot.yaml}'s liveness/readiness wiring), so reaching it risks
     * exactly the pod restart the {@code /ready} design avoids. The arithmetic also exceeds this
     * repository's shipped defaults: 200
     * connections at the measured 6-7 MiB peak is roughly 1.2-1.4 GiB worst case, while
     * {@code deploy/kubernetes/ravenroot.yaml} and {@code deploy/helm/ravenroot/values.yaml} both ship
     * {@code limits.memory: 1Gi} -- the declared worst case sat <em>above</em> the memory ceiling the
     * repository itself ships, so the "backstop" did not bring the exposure under the survival threshold
     * on the deployment this repository actually produces, it only relocated it.</p>
     *
     * <p><strong>The underlying stall also exists at the 380 KiB JDK default.</strong> The same
     * 16-held-connection setup measured roughly
     * 68 MiB, {@code /health} stalling at the same 15-second timeout) -- {@link RateLimiter#checkAddress}
     * only charges a request after {@code sun.net.httpserver} has finished parsing it, so a connection
     * that never finishes is never charged, cap or no cap on header size. Raising
     * {@code maxReqHeaderSize} widens the band in which one held connection costs more (389 KiB-2 MiB now
     * costs 6-7 MiB instead of dying near 380 KiB); it does not create the underlying unboundedness. This
     * class declares that unboundedness a residual and does not set
     * {@code jdk.httpserver.maxConnections} at all, for the same reason the residual above 2 MiB itself
     * is declared rather than chased: the JDK's {@code accept()}-time enforcement of that ceiling has the
     * identical structural problem -- {@code accept()} then {@code close()}, before any
     * context, filter or handler of ours runs -- so setting it trades a rare, attacker-shaped silent
     * death for a common, defaults-shaped one, on the connections that carry the liveness probe. A
     * A properly configurable, per-deployment ceiling must mirror every other limit in
     * {@link RateLimitConfiguration} and be sized against an operator's own memory budget rather than a
     * constant this class picks; see {@code docs/deployment.md}
     * and {@code docs/qa/what-the-testkits-do-not-cover.md} for the full numbers behind this choice.</p>
     *
     * <h2>Why a connection-count backstop is not used here</h2>
     *
     * <p>{@code jdk.httpserver.maxConnections} enforces at {@code accept()} time, before anything of
     * ours runs. It therefore reproduces the same answerless-close failure rather than preserving the
     * structured-rejection contract. A per-deployment ceiling would have to preserve that contract.</p>

     *
     * <h2>Set here, verified at {@link #start()} -- because "set" does not mean "in effect"</h2>
     *
     * <p>A static initializer on this class is not guaranteed to run before every
     * {@link HttpServer#create} in the process. If anything else in the same JVM touches
     * {@code com.sun.net.httpserver} first, {@code sun.net.httpserver.ServerConfig} reads every
     * one of these system properties exactly once, in a static initializer <em>of its own</em>, the
     * first time anything in the {@code sun.net.httpserver} package is touched by <strong>any</strong>
     * code in the process -- not the first time this class is touched. If some other
     * {@code com.sun.net.httpserver.HttpServer} is created first (in this module's own test suite,
     * {@code security/TestOidcProvider.java} does exactly that, directly, without going through this
     * class), {@code ServerConfig} locks onto whatever the property held at that earlier moment --
     * typically unset, so the JDK default -- and {@code System.setProperty} below has no further effect
     * on it: it changes what {@code System.getProperty} reports, not what
     * {@code ServerConfig.getMaxReqHeaderSize()} already cached. The property assignment is then
     * silently inert: every request between 380 KiB and the intended cap dies without a response, and nothing would say
     * so. Measured directly: creating a foreign {@code HttpServer} before the first
     * {@code RavenrootServer} reproduces exactly that -- the property reads
     * {@value #REQUEST_HEADER_CAP_BYTES} while the live cap is still 389,120.</p>
     *
     * <p>Because this cannot be prevented by ordering code within this class alone, {@link #start()}
     * verifies the <em>effective</em> cap instead of assuming the property took effect --
     * {@link #verifyRequestHeaderCapTookEffect()} sends one real, self-addressed probe request sized
     * between the JDK default and the intended cap and fails fast with a diagnostic
     * {@link IllegalStateException} if it dies instead of being answered.
     * {@code JdkHeaderCapOrderingHazardTest} demonstrates the hazard exactly this way -- a fresh JVM,
     * a foreign {@code HttpServer.create()} before the first {@code RavenrootServer} -- rather than
     * assuming it, because a test that instead relied on this suite's own class-loading order would
     * pass or fail depending on which test class Surefire happens to run first, which is exactly the
     * failure mode being guarded against. This module's own test suite closes, at the source, every
     * foreign {@code HttpServer.create()} known to run inside its own shared Surefire JVM -- two sites.
     * {@code security/TestOidcProvider.java}'s own static initializer touches this class
     * ({@code Class.forName(RavenrootServer.class.getName())}, which the JLS specifies to run this
     * class's static initializer, unlike a bare {@code .class} literal) before creating its own foreign
     * {@code HttpServer}, and {@code JdkHeaderCapConnectFailureClassificationTest} does the same before
     * its own. A third {@code HttpServer.create()} lives in this module, in
     * {@code JdkHeaderCapOrderingHazardBoundary}, but it is not a third instance of this hazard: it runs
     * in its own freshly launched {@code java} process (see {@code JdkHeaderCapOrderingHazardTest}), never
     * inside this module's shared Surefire JVM, so it cannot lose or win this race at all. Each in-process
     * guard wins its own race regardless of which test class Surefire schedules first, but the guard is
     * per call site, not general: it does nothing for a {@code HttpServer.create()} added to this module's
     * test sources tomorrow without the same guard -- see
     * {@code docs/qa/what-the-testkits-do-not-cover.md} for the measurement behind that limit. Setting the
     * property on the {@code java} command line -- guaranteed to apply before any class loads, so it
     * cannot lose this race at all -- is not available the same way here: {@code ravenroot-server/pom.xml}
     * cannot add its own {@code -D} to the forked test JVM (Maven's argLine composition is reactor-wide,
     * by design; see {@code scripts/check_argline.py}), which is why an in-process guard at each known
     * foreign call site, plus this runtime verification as the general-purpose backstop, is what covers
     * this module's own suite. The command-line recommendation is real for a deployment, though, where it
     * is this class's own process and nothing stops an operator from setting it: see
     * {@code docs/deployment.md}.</p>
     *
     * <p>Read, not overwritten, if already set: an operator's own {@code -D} takes precedence, and so
     * does a test that needs a different value -- {@code JdkHeaderCapOrderingHazardTest} also covers
     * this directly.</p>
     */
    private static final int JDK_DEFAULT_REQUEST_HEADER_CAP_BYTES = 380 * 1024;
    private static final int REQUEST_HEADER_CAP_BYTES = 2 * 1024 * 1024;
    /**
     * Extra bytes of margin {@link #verifyRequestHeaderCapTookEffect()} subtracts from the intended cap
     * before sizing its probe, so the probe's own request framing (method, path, protocol version, the
     * {@code Host}/{@code Connection} headers it sends, and the JDK's 32-bytes-per-field overhead) can
     * never itself be mistaken for an ineffective configured cap. Measured framing overhead for that probe is a
     * few hundred bytes; this leaves roughly an order of magnitude of headroom.
     */
    private static final int HEADER_CAP_PROBE_SAFETY_MARGIN_BYTES = 8_192;
    private static final java.util.concurrent.atomic.AtomicBoolean HEADER_CAP_VERIFIED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    static {
        if (System.getProperty("sun.net.httpserver.maxReqHeaderSize") == null) {
            System.setProperty("sun.net.httpserver.maxReqHeaderSize",
                    Integer.toString(REQUEST_HEADER_CAP_BYTES));
        }
        // jdk.httpserver.maxConnections is deliberately NOT set here -- see this class's own Javadoc,
        // "What 2 MiB actually costs -- and why jdk.httpserver.maxConnections is not the answer", for
        // why an accept-time connection cap does not preserve structured rejections.
    }

    private final RavenrootApplication application;
    private final AuthorizedRavenrootApplication authorizedApplication;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AutoCloseable auditSubscription;
    private final AutoCloseable executionAccounting;
    /** Closes {@code decisionalEvents}' subscription; see the widest constructor. */
    private final AutoCloseable decisionalEventSubscription;
    private final RequestAuthenticator authenticator;
    private final HttpSecurityConfiguration httpSecurity;
    private final RateLimiter rateLimiter;
    private final ai.ravenroot.server.readiness.ReadinessGate readinessGate;
    private final Duration httpStopDelay;
    /** API-02: the bound {@code /v1/drain} waits for {@code ExecutionEngine.drain()} to settle
     * before reporting {@code TIMED_OUT} -- the same knob {@code GracefulShutdown}'s shutdown-only
     * drain already uses ({@code ReadinessConfiguration#drainGracePeriod()}), now also reachable
     * on-demand rather than only from process shutdown. */
    private final Duration drainBound;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    /** Installed only by the composition root before {@link #start()}; packages never see {@code HttpServer}. */
    private ai.ravenroot.server.ingress.ManagedIngressRegistry managedIngress;
    /** Installed only by the packaged composition before start; absent hosts expose no approval authority. */
    private ai.ravenroot.core.approval.ToolApprovalService toolApprovals;
    private java.util.function.Consumer<String> toolApprovalSweep = ignored -> { };
    /** Installed only when the execution store supports first-class durable human tasks. */
    private ai.ravenroot.core.humantask.HumanTaskService humanTasks;
    private java.util.function.Consumer<String> humanTaskSweep = ignored -> { };
    /** Installed only by the packaged composition when durable agent authority is enabled. */
    /**
     * The manifest projection, or {@code null} when this host composes no manifest store and the
     * route below answers {@code 501} rather than inventing a state it cannot observe.
     */
    private ai.ravenroot.core.manifest.ExecutionManifestService executionManifests;
    private ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentAuthorityControl;
    private ai.ravenroot.api.application.ExecutionControlAuditSink agentAuthorityControlAudit;
    /**
     * Injectable: the narrower constructors below default to the stdout
     * {@link StructuredGraphMlRejectionLogger}/{@code StructuredPayloadRejectionLogger}, but
     * {@code RavenrootServerMain} supplies the {@code AuditTrail}-backed sinks instead, the same way it
     * already does for {@link StructuredAuthorizationLogger}, {@link StructuredArtifactLifecycleLogger}
     * and {@link StructuredRateLimitLogger}. These are fields rather than subscriptions, so gating
     * {@link StructuredExecutionLogger} would leave them untouched.
     */
    private final GraphMlRejectionAuditSink graphMlRejections;
    private final PayloadRejectionAuditSink payloadRejections;
    /**
     * Payload budgets for this adapter. Fixed at the defaults rather than configurable, for now: an
     * environment variable that widens a denial-of-service budget deserves the same treatment the rate
     * limiter's budgets get, and adding it here without that treatment would look configurable while
     * being merely loosenable.
     */
    private final PayloadLimits payloadLimits = PayloadLimits.DEFAULTS;
    /** Populated by every {@link #apiContext} call; see {@link #registeredRoutes()}. */
    private final List<ai.ravenroot.server.spec.RouteDescriptor> registeredRoutes = new java.util.ArrayList<>();
    /**
     * ADR 0025 authoring assistant. Always present and never null -- every constructor
     * resolves to one, defaulting to {@link ai.ravenroot.server.assistant.AssistantService#fromEnvironment}
     * so a deployment that configured nothing still <em>answers</em> {@code GET /v1/assistant} with a
     * named inert reason rather than a 404 the panel would have to interpret as an absent build.
     */
    private final ai.ravenroot.server.assistant.AssistantService assistant;

    /**
     * Where a credential an author entered from the interface lives, or {@code null} when this
     * host composed none.
     *
     * <p>Unlike {@link #assistant}, this one is <b>not</b> defaulted to an inert instance, and the
     * difference is deliberate. That one answers a question a client asks about the deployment, so an
     * inert answer is a useful answer. This one is a place to put a secret: a default instance would be a second, unconfigured store that a caller could write to
     * and then find empty on the next boot. Absent means the route answers {@code UNKNOWN_RESOURCE},
     * which is what a client cannot distinguish from an unserved path.</p>
     */
    private final ai.ravenroot.server.credential.UserCredentialStore credentials;

    /**
     * The rule that a submitted graph may only name stored credentials
     * the submitter owns. See {@link ai.ravenroot.server.credential.CredentialAdmission} for why the
     * check is here, on the request path, rather than inside the resolver.
     */
    private final ai.ravenroot.server.credential.CredentialAdmission credentialAdmission;

    /** Client address resolved once per exchange, so post-authentication audits can name the caller. */
    private static final String CLIENT_ADDRESS = RavenrootServer.class.getName() + ".clientAddress";
    private static final String CLIENT_FORWARDED = RavenrootServer.class.getName() + ".clientForwarded";

    public RavenrootServer(RavenrootApplication application, int port) {
        this(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), port), null,
                new RejectingAuthenticator());
    }

    /**
     * Creates the HTTP adapter and optionally serves UI assets from {@code uiDirectory}.
     * When the directory is absent, assets embedded under {@code /ui} in the runtime JAR
     * are used. API routes always take precedence over the static UI fallback.
     */
    public RavenrootServer(RavenrootApplication application, int port, Path uiDirectory) {
        this(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), port), uiDirectory,
                new RejectingAuthenticator());
    }

    public RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                           RequestAuthenticator authenticator) {
        this(application, address, uiDirectory, authenticator, true);
    }

    public RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                           RequestAuthenticator authenticator, boolean artifactDualControl) {
        this(application, address, uiDirectory, artifactDualControl, authenticator,
                defaultHttpSecurity(address.getPort()));
    }

    public RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                           boolean artifactDualControl, RequestAuthenticator authenticator,
                           HttpSecurityConfiguration httpSecurity) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity,
                Clock.systemUTC());
    }

    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    boolean artifactDualControl, RequestAuthenticator authenticator,
                    HttpSecurityConfiguration httpSecurity, Clock clock) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                new DefaultAuthorizationService(new StructuredAuthorizationLogger(System.out)));
    }

    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    boolean artifactDualControl, RequestAuthenticator authenticator,
                    HttpSecurityConfiguration httpSecurity, Clock clock, AuthorizationService authorization) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                authorization, defaultRateLimiter());
    }

    /**
     * Limits are always present. Every other constructor funnels here and supplies
     * {@link #defaultRateLimiter()} when none was given, so there is no way to build a server with rate
     * limiting switched off — a caller can only substitute a differently configured limiter. An
     * embedded or test deployment that forgets to configure anything still gets the defaults.
     */
    public RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                           boolean artifactDualControl, RequestAuthenticator authenticator,
                           HttpSecurityConfiguration httpSecurity, Clock clock,
                           AuthorizationService authorization, RateLimiter rateLimiter) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                authorization, rateLimiter, new StructuredArtifactLifecycleLogger(System.out));
    }

    /**
     * Widest <em>public</em> constructor: additionally accepts the artifact-lifecycle audit sink, so
     * SEC-13 can route it into the durable audit trail instead of
     * {@link StructuredArtifactLifecycleLogger}'s stdout line without breaking the narrower, existing
     * public constructor above, which keeps that default. Defaults {@link
     * ai.ravenroot.server.readiness.ReadinessGate} to {@link ai.ravenroot.server.readiness.ReadinessGate#engineOnly}
     * -- draining is always real (it reads the real engine through {@code application}), and no store
     * check runs unless a caller uses the widest constructor below to supply one (PLAT-02).
     */
    public RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                           boolean artifactDualControl, RequestAuthenticator authenticator,
                           HttpSecurityConfiguration httpSecurity, Clock clock,
                           AuthorizationService authorization, RateLimiter rateLimiter,
                           ArtifactLifecycleAuditSink artifactAudit) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                authorization, rateLimiter, artifactAudit,
                ai.ravenroot.server.readiness.ReadinessGate.engineOnly(() -> application.status().state()),
                ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().httpStopDelay());
    }

    /**
     * Widest <em>public</em> constructor. {@code readinessGate} (PLAT-02) backs
     * {@code /ready}, so a composition root that has a real store to check (today:
     * {@code RavenrootServerMain}, against the audit trail -- see
     * {@link ai.ravenroot.server.readiness.StoreLivenessCheck}'s Javadoc for exactly what that does
     * and does not cover) can supply one without the narrower constructors above needing to know
     * anything changed. {@code httpStopDelay} is the bound {@link #close()} passes to
     * {@code HttpServer.stop(int)}: how long an in-flight HTTP exchange, already accepted before
     * shutdown began, is given to finish before the listener is torn down regardless. {@code stop(0)}
     * -- the previous, hardcoded behaviour -- gives it no time at all, which is not a drain. Defaults
     * the two rejection sinks below to their stdout implementations, exactly as the narrower
     * constructor above defaults {@code artifactAudit}.
     */
    public RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                           boolean artifactDualControl, RequestAuthenticator authenticator,
                           HttpSecurityConfiguration httpSecurity, Clock clock,
                           AuthorizationService authorization, RateLimiter rateLimiter,
                           ArtifactLifecycleAuditSink artifactAudit,
                           ai.ravenroot.server.readiness.ReadinessGate readinessGate, Duration httpStopDelay) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                authorization, rateLimiter, artifactAudit, readinessGate, httpStopDelay,
                new StructuredGraphMlRejectionLogger(System.out), new StructuredPayloadRejectionLogger(System.out));
    }

    /**
     * Constructor that additionally accepts the two rejection-detail
     * sinks, so their {@code diagnosticDetail()} content -- required by its own source contract to
     * reach only a server-side sink -- can be routed into the durable audit trail instead of
     * {@link StructuredGraphMlRejectionLogger}/{@code StructuredPayloadRejectionLogger}'s stdout
     * lines, without breaking the narrower, existing public constructor above, which keeps that
     * default. Defaults the decisional-event sink below to a no-op, exactly as the narrower
     * constructor above defaults this one's own two rejection sinks.
     */
    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    boolean artifactDualControl, RequestAuthenticator authenticator,
                    HttpSecurityConfiguration httpSecurity, Clock clock,
                    AuthorizationService authorization, RateLimiter rateLimiter,
                    ArtifactLifecycleAuditSink artifactAudit,
                    ai.ravenroot.server.readiness.ReadinessGate readinessGate, Duration httpStopDelay,
                    GraphMlRejectionAuditSink graphMlRejections, PayloadRejectionAuditSink payloadRejections) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                authorization, rateLimiter, artifactAudit, readinessGate, httpStopDelay, graphMlRejections,
                payloadRejections, event -> { },
                ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().drainGracePeriod(),
                event -> { });
    }

    /**
     * Widest constructor. Accepts a decisional-event sink subscribed <em>alongside</em>
     * {@link StructuredExecutionLogger} and whatever {@code TelemetrySupport} installs, never in
     * place of either. It adds a destination for {@code EXECUTION_STARTED}/
     * {@code EXECUTION_COMPLETED}/{@code EXECUTION_FAILED}/{@code JOIN_FAILED}; it does not move the
     * per-node event stream those two already carry. {@code RavenrootServerMain} supplies
     * {@code AuditTrailExecutionSink}; every other constructor resolves here with a no-op that
     * discards every event not consumed by {@link #auditSubscription} or
     * {@link #executionAccounting}.
     *
     * <p>API-02 additionally supplies {@code drainBound} (the wait
     * {@code /v1/drain} gives {@code ExecutionEngine.drain()} before reporting {@code TIMED_OUT}) and
     * {@code controlAudit} (routes cancel/drain's {@code CONTROL}-category records to the durable audit
     * trail). The narrower constructor above defaults both, exactly as it already defaults
     * {@code decisionalEvents}: {@code ReadinessConfiguration.defaults().drainGracePeriod()} and a no-op
     * sink respectively.</p>
     */
    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    boolean artifactDualControl, RequestAuthenticator authenticator,
                    HttpSecurityConfiguration httpSecurity, Clock clock,
                    AuthorizationService authorization, RateLimiter rateLimiter,
                    ArtifactLifecycleAuditSink artifactAudit,
                    ai.ravenroot.server.readiness.ReadinessGate readinessGate, Duration httpStopDelay,
                    GraphMlRejectionAuditSink graphMlRejections, PayloadRejectionAuditSink payloadRejections,
                    java.util.function.Consumer<ExecutionEvent> decisionalEvents, Duration drainBound,
                    ai.ravenroot.api.application.ExecutionControlAuditSink controlAudit) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                authorization, rateLimiter, artifactAudit, readinessGate, httpStopDelay, graphMlRejections,
                payloadRejections, decisionalEvents, drainBound, controlAudit,
                ai.ravenroot.server.assistant.AssistantService.fromEnvironment(System.getenv()));
    }

    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    boolean artifactDualControl, RequestAuthenticator authenticator,
                    HttpSecurityConfiguration httpSecurity, Clock clock,
                    AuthorizationService authorization, RateLimiter rateLimiter,
                    ArtifactLifecycleAuditSink artifactAudit,
                    ai.ravenroot.server.readiness.ReadinessGate readinessGate, Duration httpStopDelay,
                    GraphMlRejectionAuditSink graphMlRejections, PayloadRejectionAuditSink payloadRejections,
                    java.util.function.Consumer<ExecutionEvent> decisionalEvents, Duration drainBound,
                    ai.ravenroot.api.application.ExecutionControlAuditSink controlAudit,
                    ai.ravenroot.server.embed.EmbedBrowserConfiguration embedConfiguration) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                authorization, rateLimiter, artifactAudit, readinessGate, httpStopDelay, graphMlRejections,
                payloadRejections, decisionalEvents, drainBound, controlAudit,
                ai.ravenroot.server.assistant.AssistantService.fromEnvironment(System.getenv()),
                embedConfiguration, null);
    }

    /**
     * Narrow test seam: an authenticator, an authorization service and an assistant,
     * with every other collaborator at the same default the public constructors already choose.
     *
     * <p>It exists because the two properties this feature most needs tested — "a denial to the user
     * is a denial to the panel" and "the credential never reaches the client" — both require
     * substituting the authorization service <em>and</em> the assistant on a live server, and the
     * true-widest constructor takes eighteen arguments. A test that has to spell out sixteen defaults
     * to vary two of them is a test nobody writes a second one of.</p>
     */
    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    RequestAuthenticator authenticator, AuthorizationService authorization,
                    ai.ravenroot.server.assistant.AssistantService assistant) {
        this(application, address, uiDirectory, true, authenticator, defaultHttpSecurity(address.getPort()),
                Clock.systemUTC(), authorization, defaultRateLimiter(),
                new StructuredArtifactLifecycleLogger(System.out),
                ai.ravenroot.server.readiness.ReadinessGate.engineOnly(() -> application.status().state()),
                ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().httpStopDelay(),
                new StructuredGraphMlRejectionLogger(System.out),
                new StructuredPayloadRejectionLogger(System.out), event -> { },
                ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().drainGracePeriod(),
                event -> { }, assistant);
    }

    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    RequestAuthenticator authenticator, AuthorizationService authorization,
                    ai.ravenroot.server.embed.EmbedBrowserConfiguration embedConfiguration) {
        this(application, address, uiDirectory, true, authenticator, defaultHttpSecurity(address.getPort()),
                Clock.systemUTC(), authorization, defaultRateLimiter(),
                new StructuredArtifactLifecycleLogger(System.out),
                ai.ravenroot.server.readiness.ReadinessGate.engineOnly(() -> application.status().state()),
                ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().httpStopDelay(),
                new StructuredGraphMlRejectionLogger(System.out),
                new StructuredPayloadRejectionLogger(System.out), event -> { },
                ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().drainGracePeriod(),
                event -> { }, ai.ravenroot.server.assistant.AssistantService.fromEnvironment(Map.of()),
                embedConfiguration, null);
    }

    /**
     * Widest constructor with the ADR 0025 authoring assistant service,
     * so a test can drive every inert state and every provider failure without an environment or a
     * network, exactly as the narrower constructor above defaults the audit sinks.
     *
     * <p>The default is {@code AssistantService.fromEnvironment(System.getenv())} rather than
     * {@code AssistantService.inert()}: reading the operator's environment is what makes the feature
     * usable on a real deployment without a new composition root, and an environment with no
     * {@code RAVENROOT_ASSISTANT_*} variables produces a service that is inert anyway — so the default
     * is safe for every test and every embedded host that has not configured one.</p>
     */
    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    boolean artifactDualControl, RequestAuthenticator authenticator,
                    HttpSecurityConfiguration httpSecurity, Clock clock,
                    AuthorizationService authorization, RateLimiter rateLimiter,
                    ArtifactLifecycleAuditSink artifactAudit,
                    ai.ravenroot.server.readiness.ReadinessGate readinessGate, Duration httpStopDelay,
                    GraphMlRejectionAuditSink graphMlRejections, PayloadRejectionAuditSink payloadRejections,
                    java.util.function.Consumer<ExecutionEvent> decisionalEvents, Duration drainBound,
                    ai.ravenroot.api.application.ExecutionControlAuditSink controlAudit,
                    ai.ravenroot.server.assistant.AssistantService assistant) {
        this(application, address, uiDirectory, artifactDualControl, authenticator, httpSecurity, clock,
                authorization, rateLimiter, artifactAudit, readinessGate, httpStopDelay, graphMlRejections,
                payloadRejections, decisionalEvents, drainBound, controlAudit, assistant,
                ai.ravenroot.server.embed.EmbedBrowserConfiguration.disabled(), null);
    }

    /**
     * Narrow test seam: an authenticator, an authorization service and a credential
     * store, with every other collaborator at the default the public constructors choose.
     *
     * <p>The two properties it must prove — "a second author sees and uses nothing of the
     * first's" and "the value reaches no response, log or event" — both need a <em>live</em> server
     * with a substituted authenticator, because they are statements about two different callers
     * against one process. A test that could only call the store directly could not make either.</p>
     */
    RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    RequestAuthenticator authenticator, AuthorizationService authorization,
                    ai.ravenroot.server.credential.UserCredentialStore credentials) {
        this(application, address, uiDirectory, true, authenticator, defaultHttpSecurity(address.getPort()),
                Clock.systemUTC(), authorization, defaultRateLimiter(),
                new StructuredArtifactLifecycleLogger(System.out),
                ai.ravenroot.server.readiness.ReadinessGate.engineOnly(() -> application.status().state()),
                ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().httpStopDelay(),
                new StructuredGraphMlRejectionLogger(System.out),
                new StructuredPayloadRejectionLogger(System.out), event -> { },
                ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().drainGracePeriod(),
                event -> { },
                ai.ravenroot.server.assistant.AssistantService.fromEnvironment(Map.of()),
                ai.ravenroot.server.embed.EmbedBrowserConfiguration.disabled(), credentials);
    }

    /**
     * Widest composition seam. Additionally accepts the embed browser configuration — a typed
     * collaborator a composition root supplies and the shipped one defaults.
     *
     * <p>It exists so that a capability is <b>supplied from outside</b> rather than switched on
     * inside: embed routes exist only when a configuration says so. It is not a flag.</p>
     *
     * <p>This seam intentionally carries no {@code ModelProviderService}. P3 of
     * {@code ReleaseArtifactBoundaryChecks} measures <em>reachability</em>, not execution, so arming
     * code placed inside the artifact behind a condition would red the gate on its own. The removed
     * model-provider plane used the vocabulary "which adapter identifier is installed", which a
     * bundle node does not speak; P3 still forbids that call.</p>
     *
     * <p>{@code embedConfiguration} defaults to its inert form
     * ({@code EmbedBrowserConfiguration.disabled()}), so every narrower constructor keeps exactly the
     * behaviour it had before it was added.</p>
     */
    public RavenrootServer(RavenrootApplication application, InetSocketAddress address, Path uiDirectory,
                    boolean artifactDualControl, RequestAuthenticator authenticator,
                    HttpSecurityConfiguration httpSecurity, Clock clock,
                    AuthorizationService authorization, RateLimiter rateLimiter,
                    ArtifactLifecycleAuditSink artifactAudit,
                    ai.ravenroot.server.readiness.ReadinessGate readinessGate, Duration httpStopDelay,
                    GraphMlRejectionAuditSink graphMlRejections, PayloadRejectionAuditSink payloadRejections,
                    java.util.function.Consumer<ExecutionEvent> decisionalEvents, Duration drainBound,
                    ai.ravenroot.api.application.ExecutionControlAuditSink controlAudit,
                    ai.ravenroot.server.assistant.AssistantService assistant,
                    ai.ravenroot.server.embed.EmbedBrowserConfiguration embedConfiguration,
                    ai.ravenroot.server.credential.UserCredentialStore credentials) {
        this.assistant = java.util.Objects.requireNonNull(assistant, "assistant");
        // Null is a real composition, not an oversight: a host that composes no credential
        // store gets no /v1/credentials context at all (see the registration below) and a permissive
        // admission, which is the absent-adapter contract's "where no broker is configured the endpoint is
        // not mounted" applied to this credential store instead of the broker that ADR
        // imagined. RavenrootServerMain always composes one.
        this.credentials = credentials;
        this.credentialAdmission = credentials == null
                ? ai.ravenroot.server.credential.CredentialAdmission.permissive()
                : new ai.ravenroot.server.credential.CredentialAdmission(credentials);
        this.graphMlRejections = java.util.Objects.requireNonNull(graphMlRejections, "graphMlRejections");
        this.payloadRejections = java.util.Objects.requireNonNull(payloadRejections, "payloadRejections");
        java.util.Objects.requireNonNull(decisionalEvents, "decisionalEvents");
        this.rateLimiter = java.util.Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.readinessGate = java.util.Objects.requireNonNull(readinessGate, "readinessGate");
        this.httpStopDelay = java.util.Objects.requireNonNull(httpStopDelay, "httpStopDelay");
        this.drainBound = java.util.Objects.requireNonNull(drainBound, "drainBound");
        this.application = application;
        this.authorizedApplication = new AuthorizedRavenrootApplication(application,
                java.util.Objects.requireNonNull(authorization, "authorization"),
                java.util.Objects.requireNonNull(artifactAudit, "artifactAudit"), artifactDualControl,
                AuthorizedRavenrootApplication.DEFAULT_EXECUTION_OWNERSHIP_LIMIT,
                java.util.Objects.requireNonNull(controlAudit, "controlAudit"));
        this.agentAuthorityControlAudit = controlAudit;
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");
        this.httpSecurity = java.util.Objects.requireNonNull(httpSecurity, "httpSecurity");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        try {
            server = HttpServer.create(java.util.Objects.requireNonNull(address, "address"), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create HTTP server", exception);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        auditSubscription = application.subscribeToExecutionEvents(new StructuredExecutionLogger(System.out));
        // Admission for /v1/executions is decided from this stream. The engine already publishes a
        // tenant and terminal event types, so per-tenant accounting needs no new engine contract.
        executionAccounting = application.subscribeToExecutionEvents(
                rateLimiter.activeExecutions()::observe);
        // A fourth independent subscriber on the same fan-out as the two above. Each
        // subscription is its own listener (ExecutionMonitor.publish isolates one listener's failure
        // from the rest), so adding this one changes nothing about what StructuredExecutionLogger or
        // TelemetrySupport's bridge, when installed, receive.
        decisionalEventSubscription = application.subscribeToExecutionEvents(decisionalEvents);
        // /health and the static UI are the only unauthenticated surfaces, which makes them the cheapest
        // amplification targets on the server. They are limited too, by address, for exactly that reason.
        server.createContext("/health", publicContext(exchange -> json(exchange, 200, "{\"status\":\"UP\"}")));
        // Deliberately a SEPARATE route from /health, not a redefinition of it.
        // /health is wired as the LIVENESS probe in every deployment surface (Docker HEALTHCHECK,
        // Kubernetes, Helm) -- a liveness probe that starts failing on a readiness condition (store
        // degraded, draining) restarts the pod instead of removing it from the load balancer, which
        // is the outage this design exists to prevent, not cause. /ready gets the identical
        // address-based limiting as /health because it is unauthenticated for the same reason and is
        // exactly as cheap an amplification target.
        server.createContext("/ready", publicContext(this::ready));
        apiContext("/v1/status", this::status);
        apiContext("/v1/runtime", this::runtime);
        apiContext("/v1/agent-authority", this::agentAuthorityControl);
        apiContext("/v1/node-types", this::nodeTypes);
        apiContext("/v1/human-tasks", this::humanTasks);
        apiContext("/v1/program-languages", this::programLanguages);
        apiContext("/v1/program-artifacts", this::programArtifacts);
        apiContext("/v1/graphs/inspect", this::inspectGraph);
        apiContext("/v1/executions", this::startExecution);
        apiContext("/v1/source-sessions", this::sourceSessions);
        apiContext("/v1/deployments", this::deployments);
        // API-02: /v1/executions/{id}/cancel is handled inside startExecution's own dispatch
        // (see its Javadoc) rather than as a second JDK HttpServer context, mirroring how
        // /v1/program-artifacts/{id}/{operation} is dispatched inside programArtifacts -- there is no
        // separate RouteTable-registered context for it, only a documented sub-route entry.
        apiContext("/v1/drain", this::drainServer);
        apiContext("/v1/events", this::executionEvents);
        // Registered after /v1/events and deliberately as its own context: the JDK HttpServer matches by
        // longest prefix, so this more specific path wins for /v1/events/recent while /v1/events keeps
        // every other suffix. This is the opposite choice from /v1/executions/{id}, which must be
        // dispatched inside its parent because {id} is a variable segment that cannot be a context.
        apiContext("/v1/events/recent", this::recentExecutionEvents);
        // ADR 0025. Two contexts, not one with internal dispatch: the JDK HttpServer matches by
        // longest prefix, so "/v1/assistant/messages" reaches its own handler while "/v1/assistant"
        // keeps the shorter path -- and both are then table-driven and CORS-preflighted individually,
        // which the artifact-operation switch could not be.
        apiContext("/v1/assistant", this::assistantStatus);
        apiContext("/v1/assistant/messages", this::assistantMessages);
        // Longest-prefix matching keeps this off the two above; see the comment on the
        // registration block.
        apiContext("/v1/assistant/connection", this::assistantConnection);
        // One context for the family, with /{id}/verify dispatched inside it -- {id} is a
        // variable segment and cannot be a context of its own, the same shape /v1/executions/{id} and
        // the program-artifact operations already take.
        // Registered unconditionally; the handler answers 404 when this host composed no store.
        // Conditional registration would break an invariant this codebase depends on.
        // RouteTableSpecServerAgreementTest states it: for a
        // registersContext=true entry, table-server agreement is true BY CONSTRUCTION, because
        // apiContext cannot register a context without a table entry and registeredRoutes() is
        // exactly what was registered. A conditional registration makes that construction hold only
        // for some compositions, and the test that proves it becomes a test of whichever composition
        // it happened to build.
        //
        // Nothing is lost by moving the condition into the handler: a client cannot distinguish an
        // unregistered path from a registered one that answers UNKNOWN_RESOURCE, which is the whole
        // of the absent-adapter contract.
        apiContext("/v1/credentials", this::credentials);
        if (java.util.Objects.requireNonNull(embedConfiguration, "embedConfiguration").active()) {
            var embed = new ai.ravenroot.server.embed.EmbedBrowserHttpHandler(embedConfiguration);
            // S2S only: authentication applies, general browser CORS deliberately does not.
            server.createContext(ai.ravenroot.server.embed.EmbedBrowserHttpHandler.CREATE_PATH,
                    publicContext(exchange -> {
                        if (!ai.ravenroot.server.embed.EmbedBrowserHttpHandler.requireExactPath(exchange,
                                ai.ravenroot.server.embed.EmbedBrowserHttpHandler.CREATE_PATH)) return;
                        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
                        exchange.getResponseHeaders().set("Pragma", "no-cache");
                        protectedRequest(embed::createSession).handle(exchange);
                    }));
            server.createContext(ai.ravenroot.server.embed.EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH,
                    publicContext(exchange -> {
                        if (!ai.ravenroot.server.embed.EmbedBrowserHttpHandler.requireExactPath(exchange,
                                ai.ravenroot.server.embed.EmbedBrowserHttpHandler.ACKNOWLEDGEMENT_PATH)) return;
                        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
                        exchange.getResponseHeaders().set("Pragma", "no-cache");
                        protectedRequest(embed::acknowledgeParent).handle(exchange);
                    }));
            // Browser routes perform their own exact viewer Origin/Sec-Fetch checks and emit no CORS.
            server.createContext(ai.ravenroot.server.embed.EmbedBrowserHttpHandler.LAUNCH_PATH,
                    publicContext(embed::launch));
            server.createContext(ai.ravenroot.server.embed.EmbedBrowserHttpHandler.EXCHANGE_PATH,
                    publicContext(embed::exchange));
            server.createContext(ai.ravenroot.server.embed.EmbedBrowserHttpHandler.PROJECTION_PATH,
                    publicContext(embed::projection));
        }
        server.createContext("/", publicContext(new StaticUiHandler(uiDirectory)));
    }

    /**
     * API-05: {@code path} is looked up in {@link ai.ravenroot.server.spec.RouteTable#ALL},
     * whose entry is what drives this registration and is collected into
     * {@link #registeredRoutes} for {@code RouteTableSpecServerAgreementTest} to read back. A path
     * with no table entry fails fast, here, rather than registering silently undocumented — the
     * mechanism that makes "adding a route without the table doesn't register it" true.
     */
    private void apiContext(String path, com.sun.net.httpserver.HttpHandler handler) {
        var descriptor = ai.ravenroot.server.spec.RouteTable.ALL.stream()
                .filter(candidate -> candidate.path().equals(path) && candidate.registersContext())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No RouteTable entry (with registersContext=true) for '" + path + "' -- add one "
                                + "before registering this context"));
        registeredRoutes.add(descriptor);
        // A JDK context is a longest-prefix match. Hand-declared operation routes such as
        // /v1/source-sessions/{id} therefore share this registered context, and their methods must
        // participate in its CORS preflight even though they correctly remain separate OpenAPI
        // paths. Using only the base descriptor would allow POST while making the editor's
        // authenticated GET/DELETE unreachable whenever UI and service use different origins.
        Set<String> contextMethods = ai.ravenroot.server.spec.RouteTable.ALL.stream()
                .filter(candidate -> candidate.path().equals(path)
                        || (!candidate.registersContext() && candidate.path().startsWith(path + "/")))
                .flatMap(candidate -> candidate.methods().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        server.createContext(path, publicContext(exchange -> {
            if (httpSecurity.browserOrigins().handlePreflight(exchange, contextMethods)) {
                return;
            }
            if (!httpSecurity.browserOrigins().acceptActual(exchange)) {
                return;
            }
            protectedRequest(handler).handle(exchange);
        }));
    }

    /**
     * Every {@link ai.ravenroot.server.spec.RouteDescriptor} this instance actually registered a
     * context from, in registration order. Exists for {@code RouteTableSpecServerAgreementTest}: the
     * table-driven half of "table, spec and live server agree" is this list equaling
     * {@code RouteTable.ALL}'s {@code registersContext() == true} subset, which is true by
     * construction (see {@link #apiContext}) rather than merely asserted.
     */
    public List<ai.ravenroot.server.spec.RouteDescriptor> registeredRoutes() {
        return List.copyOf(registeredRoutes);
    }

    /**
     * Applies the limits that do not need an identity, then delegates.
     *
     * <p>Order matters and is deliberate. The client address is resolved first because every later
     * limit and every audit record is keyed on it, and because resolution is self-bounding: the
     * forwarded chain has its own entry and byte caps, so it cannot be the unbounded work it is
     * guarding against. The address budget is charged next, before the header and query checks, so that
     * even a flood of malformed requests is throttled rather than merely rejected one by one. Only then
     * is request shape examined, and only after all of that does CORS or authentication run — both of
     * which are more expensive than any check above them.</p>
     */
    private com.sun.net.httpserver.HttpHandler publicContext(com.sun.net.httpserver.HttpHandler handler) {
        return secured(exchange -> {
            var resolution = rateLimiter.resolveClient(exchange.getRemoteAddress(), exchange.getRequestHeaders());
            if (resolution instanceof TrustedProxyConfiguration.Resolution.Rejected rejected) {
                // A forwarded chain that contradicts the configured topology is never downgraded to a
                // peer-keyed fallback: that would collapse every client behind the proxy onto one key.
                refuse(exchange, RateLimitDecision.rejected(400, rejected.code(), "request"), null, false);
                return;
            }
            var client = (TrustedProxyConfiguration.Resolution.Client) resolution;
            exchange.setAttribute(CLIENT_ADDRESS, client.address());
            exchange.setAttribute(CLIENT_FORWARDED, client.forwarded());

            var addressBudget = rateLimiter.checkAddress(client.address());
            if (!addressBudget.isAllowed()) {
                refuse(exchange, addressBudget, client.address(), client.forwarded());
                return;
            }
            var shape = rateLimiter.checkRequestShape(exchange.getRequestHeaders(),
                    exchange.getRequestURI().getRawQuery());
            if (!shape.isAllowed()) {
                refuse(exchange, shape, client.address(), client.forwarded());
                return;
            }
            handler.handle(exchange);
        });
    }

    private com.sun.net.httpserver.HttpHandler secured(com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            httpSecurity.responseHeaders().apply(exchange.getResponseHeaders());
            handler.handle(exchange);
        };
    }

    /**
     * Sends a refusal and records it.
     *
     * <p>The body is assembled from a fixed vocabulary of codes and carries nothing derived from the
     * request, so it is bounded by construction rather than by a length check. {@code Retry-After} is
     * sent in delta-seconds whenever the decision carries retry advice.</p>
     */
    private void refuse(HttpExchange exchange, RateLimitDecision decision, String clientAddress,
                        boolean forwarded) throws IOException {
        AuthenticatedPrincipal principal =
                exchange.getAttribute(AuthenticatedPrincipalAttribute.NAME) instanceof AuthenticatedPrincipal known
                        ? known : null;
        rateLimiter.audit().record(new RateLimitAuditEvent(clock.instant(),
                AuthenticatedPrincipalAttribute.requestId(exchange), clientAddress, forwarded,
                principal == null ? RateLimitAuditEvent.UNKNOWN : principal.tenantId(),
                principal == null ? RateLimitAuditEvent.UNKNOWN : principal.subject(),
                exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                decision.code(), decision.scope(), decision.status(), decision.retryAfterSeconds()));
        if (decision.retryAfterSeconds() > 0) {
            exchange.getResponseHeaders().set("Retry-After", Long.toString(decision.retryAfterSeconds()));
        }
        fail(exchange, decision.status(), ErrorEnvelope.ofServerCode(decision.code(),
                ErrorCode.REQUEST_LIMIT_EXCEEDED, AuthenticatedPrincipalAttribute.requestId(exchange)));
    }

    /** Refusal for a path that has already resolved the client address onto the exchange. */
    private void refuse(HttpExchange exchange, RateLimitDecision decision) throws IOException {
        refuse(exchange, decision, clientAddress(exchange), forwarded(exchange));
    }

    private static String clientAddress(HttpExchange exchange) {
        return exchange.getAttribute(CLIENT_ADDRESS) instanceof String address
                ? address : RateLimitAuditEvent.UNKNOWN;
    }

    private static boolean forwarded(HttpExchange exchange) {
        return exchange.getAttribute(CLIENT_FORWARDED) instanceof Boolean flag && flag;
    }

    /** Limits enforced with the defaults when no limiter is supplied. */
    private static RateLimiter defaultRateLimiter() {
        return new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                new StructuredRateLimitLogger(System.out));
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("server is already started");
        }
        server.start();
        verifyRequestHeaderCapTookEffect();
    }

    /**
     * Installs a prevalidated, single-process managed ingress authority before bind/readiness.
     * This is intentionally a composition-root seam rather than a package callback: the adapter
     * retains listener, authentication, rate-limit and TLS authority.
     */
    synchronized void installManagedIngress(ai.ravenroot.server.ingress.ManagedIngressRegistry registry) {
        if (started.get() || managedIngress != null) {
            throw new IllegalStateException("managed ingress must be installed before server start");
        }
        managedIngress = java.util.Objects.requireNonNull(registry, "registry");
        registry.bind(server, handler -> publicContext(exchange -> protectedRequest(handler).handle(exchange)));
    }

    /** Installs the tenant-scoped durable approval reference monitor before the listener starts. */
    synchronized void installToolApprovals(ai.ravenroot.core.approval.ToolApprovalService approvals) {
        installToolApprovals(approvals, ignored -> { });
    }

    synchronized void installToolApprovals(ai.ravenroot.core.approval.ToolApprovalService approvals,
                                           java.util.function.Consumer<String> sweep) {
        if (started.get()) throw new IllegalStateException("tool approvals must be installed before start");
        if (toolApprovals != null) throw new IllegalStateException("tool approvals are already installed");
        toolApprovals = java.util.Objects.requireNonNull(approvals, "approvals");
        toolApprovalSweep = java.util.Objects.requireNonNull(sweep, "sweep");
    }

    /** Installs the transport-neutral human-task authority before listener start. */
    synchronized void installHumanTasks(ai.ravenroot.core.humantask.HumanTaskService tasks,
                                        java.util.function.Consumer<String> sweep) {
        if (started.get()) throw new IllegalStateException("human tasks must be installed before start");
        if (humanTasks != null) throw new IllegalStateException("human tasks are already installed");
        humanTasks = java.util.Objects.requireNonNull(tasks, "tasks");
        humanTaskSweep = java.util.Objects.requireNonNull(sweep, "sweep");
    }

    /**
     * Installs the execution-manifest projection before listener start.
     *
     * <p>Installed rather than constructed, for the reason the tool-approval and human-task
     * authorities are: a host that composes no manifest store must not have to name one, and every
     * existing constructor must keep working unchanged.</p>
     */
    synchronized void installExecutionManifests(
            ai.ravenroot.core.manifest.ExecutionManifestService manifests) {
        if (started.get()) throw new IllegalStateException("execution manifests must be installed before start");
        if (executionManifests != null) {
            throw new IllegalStateException("execution manifests are already installed");
        }
        executionManifests = java.util.Objects.requireNonNull(manifests, "manifests");
    }

    /** Installs the authenticated store-global agent-authority control before listener start. */
    synchronized void installAgentAuthorityControl(
            ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService control) {
        if (started.get()) throw new IllegalStateException("agent authority control must be installed before start");
        if (agentAuthorityControl != null) {
            throw new IllegalStateException("agent authority control is already installed");
        }
        agentAuthorityControl = java.util.Objects.requireNonNull(control, "control");
    }

    /** Test seam that observes the same sanitized control events as the production audit trail. */
    synchronized void installAgentAuthorityControl(
            ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService control,
            ai.ravenroot.api.application.ExecutionControlAuditSink audit) {
        installAgentAuthorityControl(control);
        agentAuthorityControlAudit = java.util.Objects.requireNonNull(audit, "audit");
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Confirms {@code sun.net.httpserver.maxReqHeaderSize} actually took effect in this JVM without
     * assuming the static initializer above won the race -- see its Javadoc ("Set here, verified at
     * {@code start()}") for why the race can be lost silently. Runs at most once per JVM <em>on a
     * definitive outcome</em>: the underlying state ({@code sun.net.httpserver.ServerConfig}'s own static
     * initializer) is fixed for the process's lifetime the first time anything reads it, so a confirmed
     * "took effect" is true for every {@code RavenrootServer} constructed afterward too. An inconclusive
     * run (see the posture note below) is retried on the next call instead of being cached, since nothing
     * was actually established.
     *
     * <h2>Probes a throwaway server, not this instance's own</h2>
     *
     * <p>Sending the probe to {@code this.server}'s own {@code /health} would reach a registered
     * context. {@code ServerImpl.Exchange.run()} calls {@code findContext} <em>after</em>
     * {@code Request.headers()} finishes parsing, so a successful probe would match {@code /health},
     * which is wrapped in {@code publicContext} exactly like every other route, which runs
     * {@link RateLimiter#checkRequestShape}. The oversized {@code X-Ravenroot-Startup-Probe} header this
     * probe must send to prove the cap is always far above every configured business limit, so
     * {@code checkRequestShape} always rejected it -- {@code 431 HEADER_VALUE_TOO_LARGE} -- and that
     * rejection is audited exactly like a real one: in production, {@code AuditTrailRateLimitSink} wrote
     * one durable, tamper-evident record per boot, indistinguishable from an actual client sending an
     * oversized header, and spent one token from the loopback address's own rate-limit bucket. Measured
     * directly: one {@code code=HEADER_VALUE_TOO_LARGE status=431 path=/health clientAddress=127.0.0.1}
     * record, every single start.</p>
     *
     * <p>This method therefore never reaches a registered context. It opens its own, separate,
     * throwaway {@code HttpServer} -- zero {@code createContext} calls, so {@code findContext} always
     * returns null and {@code ServerImpl} answers with its own plain {@code 404 Not Found} entirely
     * inside the JDK, never touching {@link #publicContext}, {@link RateLimiter} or any audit sink of
     * this class's own. {@code sun.net.httpserver.maxReqHeaderSize} is a JVM-wide system property, not
     * per-{@code HttpServer}-instance, so probing a second, unrelated server proves exactly the same fact
     * about the one JVM both instances share. It is closed immediately after the one probe request.</p>
     *
     * <h2>Verifies behaviourally, not by inspecting JDK internals</h2>
     *
     * <p>{@code sun.net.httpserver.ServerConfig} is a package-private class in the {@code jdk.httpserver}
     * module, not exported to this one, so reflection would need
     * {@code --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED} on every deployment's command
     * line -- a requirement this class has no way to enforce and no business imposing. Instead it sends
     * one real HTTP request to the throwaway server above, sized between the JDK's own 380 KiB default
     * and the cap this class intends. Any byte coming back -- regardless of status code, here always a
     * plain {@code 404} -- proves the effective cap is at least that large; silence (EOF with nothing
     * read) proves it is not.</p>
     *
     * <h2>Startup posture: fatal only on direct proof</h2>
     *
     * <p>Two outcomes are <strong>direct proof</strong> that the cap did not take effect, and both
     * require this probe's {@code connect()} to have already succeeded: {@code read() == -1}, a clean
     * EOF after the probe bytes were sent with nothing returned; and a
     * {@link java.net.SocketException} while writing or reading that established connection. Across
     * three measurements with the cap stuck at the JDK default,
     * {@code sun.net.httpserver.Request} detected overflow mid-parse and closed the connection while
     * the probe was still writing, producing {@code SocketException: Broken pipe}. A refused connect
     * is not proof: {@link java.net.ConnectException} is itself a {@code SocketException} subtype, but
     * the request never reached the probe server. The separate {@code connect()} try/catch therefore
     * always classifies connect failure as inconclusive; see
     * {@code JdkHeaderCapConnectFailureClassificationTest}. A
     * {@link java.net.SocketTimeoutException} or any other {@link IOException} is also inconclusive,
     * because it can indicate slow loopback, a network sandbox, ephemeral-port exhaustion or another
     * condition unrelated to the cap. Treating those outcomes as proof would prevent startup without
     * establishing that the cap is wrong.</p>
     *
     * <p>This method fails fast ({@link IllegalStateException}, after stopping the real listener below so
     * an embedder catching the exception is not left with an open, unanswered socket) only on the direct
     * proof. On an inconclusive probe it logs one structured line to {@code System.err} and lets
     * {@link #start()} proceed -- {@code System.setProperty} did happen, so the cap is <em>probably</em>
     * in effect, only not confirmed.</p>
     */
    private void verifyRequestHeaderCapTookEffect() {
        if (HEADER_CAP_VERIFIED.get()) {
            return;
        }
        long intended;
        try {
            intended = Long.parseLong(System.getProperty("sun.net.httpserver.maxReqHeaderSize", "0"));
        } catch (NumberFormatException notANumber) {
            // Not this class's property to have set, and not this class's job to validate an operator's
            // malformed override -- sun.net.httpserver.ServerConfig already ignores an unparsable value
            // and falls back to its own default, which this probe cannot distinguish from "intended".
            // The same holds for a value that parses fine here but does not fit in an int: this
            // class reads the property with Long.parseLong so it never throws for one, but
            // sun.net.httpserver.ServerConfig itself reads it with Integer.getInteger, whose own,
            // separate NumberFormatException on the out-of-range value it swallows the same way, falling
            // back to the same default. Checked explicitly below because it cannot share this catch --
            // the value never fails to parse as a long -- but it is the identical case for the identical
            // reason.
            return;
        }
        if (intended > Integer.MAX_VALUE || intended < Integer.MIN_VALUE) {
            return;
        }
        long probeSize = intended - HEADER_CAP_PROBE_SAFETY_MARGIN_BYTES;
        if (probeSize <= JDK_DEFAULT_REQUEST_HEADER_CAP_BYTES) {
            // Nothing this probe can distinguish: the intended cap is at or below the JDK's own built-in
            // default, so even a JVM where ServerConfig is stuck at that default behaves no more
            // restrictively than intended -- there is no "took effect or not" question to answer.
            HEADER_CAP_VERIFIED.set(true);
            return;
        }

        HttpServer probeServer;
        try {
            probeServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            probeServer.start();
        } catch (IOException cannotCreateProbeServer) {
            logHeaderCapVerificationInconclusive(intended, "could not create the self-check probe server",
                    cannotCreateProbeServer);
            return;
        }
        HeaderCapProbeResult result;
        try {
            result = probeHeaderCap(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), probeServer.getAddress().getPort()),
                    (int) probeSize);
        } finally {
            probeServer.stop(0);
        }
        if (result.outcome() == HeaderCapProbeOutcome.INCONCLUSIVE) {
            logHeaderCapVerificationInconclusive(intended, result.inconclusiveReason(), result.cause());
            return;
        }
        if (result.outcome() == HeaderCapProbeOutcome.CAP_CONFIRMED_WRONG) {
            // Direct proof, not an inconclusive probe: stop the real listener before this instance ever
            // answers a real request under a cap that is not what it claims, and before returning control
            // to a caller who might otherwise assume start() failing this way still left nothing
            // listening.
            try {
                server.stop(0);
            } catch (RuntimeException stopFailedAfterAlreadyProvingTheDefect) {
                // The defect is already proven and about to be reported; a failure to stop the listener
                // cleanly must not replace that diagnostic with a different one.
            }
            throw new IllegalStateException(
                    "#435: sun.net.httpserver.maxReqHeaderSize did not take effect in this JVM. This "
                            + "process set (or an operator configured) it to " + intended + " bytes, but a "
                            + probeSize + "-byte startup probe was silently disconnected instead of "
                            + "answered -- which only happens while the JDK's own request-line-plus-headers "
                            + "cap is still at its " + JDK_DEFAULT_REQUEST_HEADER_CAP_BYTES + "-byte "
                            + "(380 KiB) built-in default. sun.net.httpserver.ServerConfig reads this "
                            + "property exactly once, in its own static initializer, the first time "
                            + "anything in the sun.net.httpserver package is touched anywhere in this "
                            + "process -- something else (another com.sun.net.httpserver.HttpServer, "
                            + "created before this one) already did that before this class's own static "
                            + "initializer had a chance to run. Every request between 380 KiB and the "
                            + "intended cap will now break its connection with no status line and no "
                            + "envelope: #435 in full, and silently, because the property alone claims "
                            + "success. Set sun.net.httpserver.maxReqHeaderSize on the java command line "
                            + "(applies before any class loads, so it cannot lose this race) instead of "
                            + "relying on in-process ordering; see docs/deployment.md. This listener has "
                            + "already been stopped.",
                    result.cause());
        }
        HEADER_CAP_VERIFIED.set(true);
    }

    /** One probe's outcome: proof either way, or nothing established. See {@link #probeHeaderCap}. */
    enum HeaderCapProbeOutcome {
        CAP_CONFIRMED_WRONG, CAP_CONFIRMED_OK, INCONCLUSIVE
    }

    /**
     * Package-private, not private, so {@code JdkHeaderCapConnectFailureClassificationTest} can pin the
     * connect-vs-established distinction directly against a real connect-refused port rather than
     * inferring it from {@code java.net}'s exception class hierarchy. {@code inconclusiveReason} is set only when
     * {@code outcome} is {@link HeaderCapProbeOutcome#INCONCLUSIVE}; {@code cause} may be {@code null}
     * for either a clean {@code CAP_CONFIRMED_WRONG} ({@code read() == -1}, no exception at all) or a
     * clean {@code CAP_CONFIRMED_OK}.
     */
    record HeaderCapProbeResult(HeaderCapProbeOutcome outcome, String inconclusiveReason, Exception cause) {
    }

    /**
     * Sends one self-check probe to {@code target} and classifies the result -- no logging, no throwing,
     * no touching {@link #server}, so the classification itself is directly testable and the side effects
     * a real startup needs live only in {@link #verifyRequestHeaderCapTookEffect()}, which calls this.
     * See that method's Javadoc ("Startup posture") for what each outcome means and the measurement
     * behind it; {@code connect()} is deliberately in its own {@code try}/{@code catch}; whose failure is
     * always {@link HeaderCapProbeOutcome#INCONCLUSIVE} regardless of exception type, because
     * {@link java.net.ConnectException} is itself a {@link java.net.SocketException} and must never be
     * classified alongside the write/read failure that is this method's actual direct proof.
     */
    static HeaderCapProbeResult probeHeaderCap(InetSocketAddress target, int probeSize) {
        var socket = new java.net.Socket();
        try {
            socket.connect(target, 5_000);
        } catch (IOException connectFailed) {
            try {
                socket.close();
            } catch (IOException ignoredCloseFailureOnAnAlreadyFailedSocket) {
                // Nothing to add: the socket never connected in the first place.
            }
            return new HeaderCapProbeResult(HeaderCapProbeOutcome.INCONCLUSIVE,
                    "could not connect to the self-check probe server", connectFailed);
        }
        try (socket) {
            socket.setSoTimeout(5_000);
            String probe = "GET /__ravenroot_startup_probe__ HTTP/1.1\r\n"
                    + "Host: " + target.getHostString() + ":" + target.getPort() + "\r\n"
                    + "X-Ravenroot-Startup-Probe: " + "a".repeat(probeSize) + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(probe.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            boolean confirmedWrong = socket.getInputStream().read() == -1;
            return new HeaderCapProbeResult(
                    confirmedWrong ? HeaderCapProbeOutcome.CAP_CONFIRMED_WRONG
                            : HeaderCapProbeOutcome.CAP_CONFIRMED_OK,
                    null, null);
        } catch (java.net.SocketTimeoutException inconclusiveTimeout) {
            // A timeout waiting for a response that never came, without the socket being actively reset,
            // is not this probe concluding anything: it could be a slow loopback, a network sandbox, or
            // any number of causes unrelated to the cap. Treated as inconclusive, matching the
            // SocketException/IOException catches below, even though the JLS class hierarchy would
            // otherwise route it there anyway (this catch exists only to keep the distinction explicit in
            // the source, not to change behaviour).
            return new HeaderCapProbeResult(HeaderCapProbeOutcome.INCONCLUSIVE,
                    "self-addressed probe timed out rather than confirming or refuting the cap",
                    inconclusiveTimeout);
        } catch (java.net.SocketException activelyReset) {
            // Only reachable once the connect() above has already succeeded (its own IOException,
            // including java.net.ConnectException -- a SocketException subtype -- is caught and returned
            // as INCONCLUSIVE above, never here): a SocketException at this point is about the connection
            // this probe itself established, not about reaching the probe server at all. Measured across
            // three runs: with the cap genuinely stuck at the JDK default, this
            // branch fires as a WRITE failure, not a read failure -- sun.net.httpserver.Request detects
            // the overflow mid-parse and closes the connection while this probe is still writing its
            // oversized header, so the OS answers with SocketException: Broken pipe, not a read() == -1
            // EOF (that branch above is real but was never observed to fire in practice; both are treated
            // as the same direct proof).
            return new HeaderCapProbeResult(HeaderCapProbeOutcome.CAP_CONFIRMED_WRONG, null, activelyReset);
        } catch (IOException otherProbeFailure) {
            return new HeaderCapProbeResult(HeaderCapProbeOutcome.INCONCLUSIVE,
                    "self-addressed probe failed outright rather than confirming or refuting the cap",
                    otherProbeFailure);
        }
    }

    /**
     * The non-fatal half of the documented contract: an inconclusive probe is not evidence the cap is wrong, so it
     * does not abort startup, but it must not be silent either -- an operator who only ever sees this
     * line and never the fatal path above has no way to know verification never actually confirmed
     * anything. One line, {@code System.err}, matching the JSON-ish shape this class's other structured
     * output already uses.
     */
    private static void logHeaderCapVerificationInconclusive(long intendedBytes, String reason, Exception cause) {
        System.err.println("{\"event\":\"header_cap_verification_inconclusive\",\"intendedBytes\":" + intendedBytes
                + ",\"reason\":\"" + reason.replace("\"", "'") + "\",\"cause\":\""
                + String.valueOf(cause).replace("\"", "'").replace("\n", " ") + "\"}");
    }

    private com.sun.net.httpserver.HttpHandler protectedRequest(com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            try {
                var principal = authenticator.authenticate(exchange.getRequestHeaders());
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME, principal);
            } catch (AuthenticationException denied) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                fail(exchange, ErrorCode.AUTHENTICATION_REQUIRED);
                return;
            }
            // Tenant first, then principal within it. The tenant budget is what stops one tenant
            // starving the others; the principal budget is what stops one user draining its own tenant.
            // Either alone leaves a starvation path open, so both are charged.
            var principal = AuthenticatedPrincipalAttribute.require(exchange);
            var identityBudget = rateLimiter.checkIdentity(principal.tenantId(), principal.subject());
            if (!identityBudget.isAllowed()) {
                refuse(exchange, identityBudget);
                return;
            }
            try {
                handler.handle(exchange);
            } catch (ai.ravenroot.api.security.AuthorizationDeniedException denied) {
                fail(exchange, ErrorCode.ACCESS_DENIED);
            }
        };
    }

    /**
     * Unauthenticated by design (a probe carries no credentials) and separate from
     * {@code /health} by design (see the route registration above). 200 with {@code "ready":true}
     * when {@link ai.ravenroot.server.readiness.ReadinessGate#evaluate()} says so; 503 with
     * {@code "ready":false} and the blocking {@code state} otherwise -- 503 because that is the
     * response Kubernetes' readiness probe (and any conforming health-check client) already
     * interprets as "not ready", with no probe-specific contract to invent.
     */
    private void ready(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        var report = readinessGate.evaluate();
        String dependencies = report.dependencies().stream()
                .map(dependency -> "\"" + escape(dependency.name()) + "\":{\"up\":" + dependency.up()
                        + ",\"detail\":\"" + escape(dependency.detail()) + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        json(exchange, report.ready() ? 200 : 503, "{\"ready\":" + report.ready() + ",\"state\":\""
                + report.state() + "\",\"dependencies\":{" + dependencies + "}}");
    }

    private void status(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        var status = authorizedApplication.status(AuthenticatedPrincipalAttribute.requestContext(exchange));
        json(exchange, 200, "{\"state\":\"" + escape(status.state()) + "\",\"executionEngine\":\""
                + escape(status.executionEngine()) + "\",\"capabilities\":["
                + status.capabilities().stream().sorted().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]}");
    }

    /**
     * {@code GET /v1/assistant}, under the assistant-availability contract: what this deployment says about its own
     * authoring assistant.
     *
     * <p>Authenticated like every other {@code /v1} route, which is what makes the panel's
     * {@code not-signed-in} state reachable at all — an expired session produces a 401 here, and
     * {@code assistant-client.js} maps that to exactly that reason.</p>
     *
     * <p><b>The response can never carry the provider credential.</b> Its body is
     * {@code AssistantAvailability#toJson}, which is three booleans, a provider display name and a
     * product-authored sentence; there is no branch on this route that reads
     * {@code AssistantCredential}. {@code AssistantRouteTest#theProviderCredentialNeverReachesTheClientOrTheLog} plants a
     * distinctive value and greps this response — headers and body — for it.</p>
     */
    private void assistantStatus(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        if (!assistant.offered()) {
            // The operator switched the service off. 404 rather than a 200 saying "off", because
            // `assistant-client.js` already maps 404 to `service-unavailable` -- the same answer it
            // gives for a build with no route at all, which is the same fact from the panel's side.
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        // Per author, not per deployment: in OAuth mode the credential belongs to an author, so "is
        // this panel usable" has a different answer for each of them. The subject comes from the authenticated
        // principal, which is why this route being authenticated is load-bearing rather than
        // conventional.
        json(exchange, 200, assistant
                .availability(AuthenticatedPrincipalAttribute.requestContext(exchange).subject())
                .toJson());
    }

    /**
     * {@code /v1/assistant/connection}: the author's own connection to the model provider.
     *
     * <p>Three methods on one path, because they are three views of ONE pending connection rather
     * than three resources: {@code POST} starts one, {@code GET} reports on it, and {@code DELETE}
     * abandons it.</p>
     *
     * <h2>What this route will not put on the wire</h2>
     * <ul>
     *   <li><b>The device code.</b> RFC 8628 gives the exchange two secrets, and only the user code
     *       is meant to be seen. {@link ai.ravenroot.server.assistant.oauth.AssistantConnection.Prompt}
     *       has no field for the other one, so there is no expression here that could serialize it —
     *       the same structural approach {@code DeviceGrant#toString} takes to logging.</li>
     *   <li><b>The token.</b> A finished connection answers {@code {"state":"linked"}} and nothing
     *       else. The credential goes to the server-side store, which is the per-author credential contract's
     *       "never has a browser-readable representation in any mode", stated as a route that has
     *       nowhere to write one.</li>
     *   <li><b>Anything the provider wrote.</b> The failure names come from this build's own enum;
     *       an authorization server's error body is never echoed, because it can quote the request
     *       back.</li>
     * </ul>
     *
     * <h2>A deployment with no connection path refuses, and says which kind of refusal it is</h2>
     * <p>{@code AssistantService#connection()} is null unless the operator supplies the provider's
     * device-flow endpoints, which deliberately have no defaults. The refusal is {@code CONFLICT}
     * rather than {@code UNKNOWN_RESOURCE} because the route exists and
     * the deployment is not configured for it — and because the panel maps the latter to "this
     * deployment has no assistant at all", which would be a different and false statement. In
     * practice the panel does not reach here: the same absence makes
     * {@code AssistantService#availability} report the operator's gap, so no Connect control is
     * offered. This is the guard for the client that asks anyway.</p>
     */
    private void assistantConnection(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST, GET, DELETE, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!assistant.offered()) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        var connection = assistant.connection();
        if (connection == null) {
            fail(exchange, ErrorCode.CONFLICT);
            return;
        }
        String subject = AuthenticatedPrincipalAttribute.requestContext(exchange).subject();
        switch (exchange.getRequestMethod()) {
            case "POST" -> beginAssistantConnection(exchange, connection, subject);
            case "GET" -> reportAssistantConnection(exchange, connection, subject);
            case "DELETE" -> {
                connection.abandon(subject);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
            default -> {
                exchange.getResponseHeaders().set("Allow", "POST, GET, DELETE");
                fail(exchange, ErrorCode.METHOD_NOT_ALLOWED);
            }
        }
    }

    private void beginAssistantConnection(HttpExchange exchange,
            ai.ravenroot.server.assistant.oauth.AssistantConnection connection, String subject)
            throws IOException {
        ai.ravenroot.server.assistant.oauth.AssistantConnection.Prompt prompt;
        try {
            prompt = connection.begin(subject);
        } catch (ai.ravenroot.server.assistant.oauth.AssistantDeviceAuthorization
                .DeviceAuthorizationException refused) {
            // The exception's message is authored by this build and never interpolates the
            // provider's, but it is still not echoed: the envelope carries the code's own words, and
            // the specific cause stays server-side against the correlation id like every other
            // assistant failure.
            System.out.println("{\"event\":\"assistant.connection.failed\",\"failure\":\""
                    + refused.failure() + "\",\"correlationId\":\""
                    + escape(AuthenticatedPrincipalAttribute.requestId(exchange)) + "\"}");
            fail(exchange, ErrorCode.CONFLICT);
            return;
        }
        json(exchange, 200, "{\"userCode\":\"" + escape(prompt.userCode())
                + "\",\"verificationUri\":\"" + escape(String.valueOf(prompt.verificationUri()))
                + "\",\"verificationUriComplete\":"
                + (prompt.verificationUriComplete() == null
                        ? "null"
                        : "\"" + escape(String.valueOf(prompt.verificationUriComplete())) + "\"")
                + ",\"interval\":" + prompt.pollInterval().toSeconds()
                + ",\"expiresIn\":" + prompt.expiresIn().toSeconds() + "}");
    }

    private void reportAssistantConnection(HttpExchange exchange,
            ai.ravenroot.server.assistant.oauth.AssistantConnection connection, String subject)
            throws IOException {
        var progress = connection.poll(subject);
        // Exhaustive over the sealed type, so a fourth kind of progress cannot be added without
        // deciding what the panel is told about it, the same property `AssistantAvailability`'s
        // switch has.
        String body = switch (progress) {
            case ai.ravenroot.server.assistant.oauth.AssistantConnection.Progress.Linked ignored ->
                    "{\"state\":\"linked\"}";
            case ai.ravenroot.server.assistant.oauth.AssistantConnection.Progress.None ignored ->
                    "{\"state\":\"none\"}";
            case ai.ravenroot.server.assistant.oauth.AssistantConnection.Progress.Waiting waiting ->
                    "{\"state\":\"waiting\",\"reason\":\"" + waiting.failure().name()
                            + "\",\"retryAfter\":" + waiting.retryAfter().toSeconds() + "}";
        };
        json(exchange, 200, body);
    }

    /**
     * {@code POST /v1/assistant/messages}: the author's turn, answered by the model or named as
     * a failure.
     *
     * <p>Three properties are worth naming because each is a defect if it stops holding:</p>
     * <ul>
     *   <li><b>No 2xx is ever sent without words.</b> The only success path writes
     *       {@code AssistantOutcome.Reply}, whose constructor refuses blank text. There is no
     *       expression here that can produce an empty assistant turn.</li>
     *   <li><b>An authorization denial escapes.</b> {@code AuthorizationDeniedException} raised inside
     *       a tool propagates out of {@code AssistantService#send} to
     *       {@link #protectedRequest}, which answers {@code ACCESS_DENIED} — the identical response the
     *       author's own UI call would have received. That is "a denial to the user is a denial to the
     *       panel", achieved by not catching rather than by re-implementing.</li>
     *   <li><b>The body is bounded before it is parsed.</b> Read with a cap, then parsed by the
     *       bounded reader, so neither a huge body nor a deeply nested one is materialised first.</li>
     * </ul>
     */
    private void assistantMessages(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) {
            return;
        }
        if (!assistant.offered()) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        if (!assistant.availability(
                AuthenticatedPrincipalAttribute.requestContext(exchange).subject()).ready()) {
            // Defensive: the panel disables its composer in every inert state, so reaching here means a
            // client sent anyway. Refused rather than composed -- nothing is sent to a provider.
            //
            // Per author for the same reason as the status route. Asking the deployment-level
            // question here refused every turn in an OAuth deployment, connected author or not,
            // because the deployment has no credential of its own to report on.
            fail(exchange, ErrorCode.CONFLICT);
            return;
        }
        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_ASSISTANT_TURN_BYTES + 1);
        }
        if (body.length > MAX_ASSISTANT_TURN_BYTES) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        ai.ravenroot.server.assistant.AssistantTurn turn;
        try {
            turn = ai.ravenroot.server.assistant.AssistantTurn.read(body);
        } catch (IllegalArgumentException rejected) {
            // PayloadException extends IllegalArgumentException, so this clause covers a budget
            // rejection, a malformed body and a well-formed body that is not an assistant turn.
            // The classified payload reason is deliberately collapsed to one wire code: an author
            // cannot act differently on DEPTH_LIMIT_EXCEEDED than on MALFORMED, and the classified
            // detail belongs in a server-side record rather than on the panel.
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        var outcome = assistant.send(AuthenticatedPrincipalAttribute.requestContext(exchange),
                authorizedApplication, turn);
        switch (outcome) {
            case ai.ravenroot.server.assistant.AssistantOutcome.Reply reply ->
                    json(exchange, 200, reply.toJson());
            case ai.ravenroot.server.assistant.AssistantOutcome.Proposal proposal ->
                    json(exchange, 200, proposal.toJson());
            case ai.ravenroot.server.assistant.AssistantOutcome.Failure failure -> {
                recordAssistantFailure(exchange, failure);
                // The reason travels on the response, not only in the log. `code` is coarse by design
                // -- status-bearing, declared per route, rendered into the checked-in spec -- so seven
                // reasons mapped onto four codes collapsed three distinctions, and the panel could not
                // tell a refusal from an exhausted tool loop. `assistantReason` carries the whole
                // vocabulary beside it, injectively.
                ErrorCode code = assistantFailureCode(failure.reason());
                fail(exchange, code.status(),
                        ErrorEnvelope.of(code, AuthenticatedPrincipalAttribute.requestId(exchange))
                                .withAssistantReason(failure.reason().wireToken()));
            }
        }
    }

    /**
     * The named reason, recorded server-side.
     *
     * <p><b>The wire carries the reason and the panel reads it.</b> The response sets
     * {@code ErrorEnvelope.assistantReason} to {@link
     * ai.ravenroot.server.assistant.AssistantOutcome.Reason#wireToken()}, one token per reason across
     * the eight-value vocabulary, including {@code ASSISTANT_ADAPTER_DEFECT}, and
     * {@code assistant-client.js} reads that field and maps it through {@code assistantFailureText},
     * so each reason reaches the panel as its own sentence rather than as a generic error.
     * {@code assistant-session.test.js} pins the two vocabularies against each other, so a token added
     * on one side and not the other fails rather than degrading quietly.</p>
     *
     * <p>So this line is not the only record of the distinction — the panel now has its own. It is
     * the record that exists on the server, where an operator reading logs needs it. The line carries
     * the reason name and the correlation id and <b>nothing derived from the provider's own
     * response</b>.</p>
     */
    private void recordAssistantFailure(HttpExchange exchange,
                                        ai.ravenroot.server.assistant.AssistantOutcome.Failure failure) {
        System.out.println("{\"event\":\"assistant.turn.failed\",\"reason\":\""
                + failure.reason().name() + "\",\"requestId\":\""
                + escape(AuthenticatedPrincipalAttribute.requestId(exchange)) + "\"}");
    }

    /**
     * The wire code for a named assistant failure.
     *
     * <p>Exhaustive over {@code AssistantOutcome.Reason}, so a new reason must be given a status here
     * or this does not compile. The mapping is coarse — several distinct reasons share a status —
     * while {@code assistantReason} preserves the injective reason token beside it.</p>
     */
    private static ErrorCode assistantFailureCode(
            ai.ravenroot.server.assistant.AssistantOutcome.Reason reason) {
        return switch (reason) {
            case INVALID_TURN -> ErrorCode.INVALID_REQUEST;
            case PROVIDER_REFUSED, TOOL_LOOP_EXHAUSTED, MODEL_PROPOSAL_INVALID -> ErrorCode.CONFLICT;
            case PROVIDER_REJECTED, PROVIDER_UNREADABLE, EGRESS_REFUSED, ADAPTER_DEFECT ->
                    ErrorCode.INTERNAL_ERROR;
            case PROVIDER_UNAVAILABLE -> ErrorCode.REQUEST_INTERRUPTED;
        };
    }

    private void runtime(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        var snapshot = authorizedApplication.runtimeSnapshot(AuthenticatedPrincipalAttribute.requestContext(exchange));
        // activeNodeInstances counts arrivals in flight per node, not instances -- see
        // RuntimeSnapshot's own Javadoc. Kept under this wire name for compatibility.
        String nodes = snapshot.activeNodeInstances().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> "\"" + escape(entry.getKey()) + "\":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        json(exchange, 200, "{\"activeExecutions\":" + snapshot.activeExecutions()
                + ",\"activeNodeInstances\":{" + nodes + "}}");
    }

    private void agentAuthorityControl(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        String suffix = exchange.getRequestURI().getPath().substring("/v1/agent-authority".length());
        if (agentAuthorityControl == null
                || !("/trip".equals(suffix) || "/reset".equals(suffix))) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
        authorizedApplication.authorizeAgentAuthorityControl(context);
        boolean trip = "/trip".equals(suffix);
        String action = trip ? "agent-authority-trip" : "agent-authority-reset";
        auditAgentAuthorityControl(context, action,
                ai.ravenroot.api.application.ExecutionControlAuditEvent.Disposition.ATTEMPT, "", true);
        long epoch;
        String state = trip ? "KILLED" : "ACTIVE";
        try {
            epoch = trip ? agentAuthorityControl.trip(context) : agentAuthorityControl.reset(context);
        } catch (RuntimeException failure) {
            auditAgentAuthorityControl(context, action,
                    ai.ravenroot.api.application.ExecutionControlAuditEvent.Disposition.FAILED, "", false);
            throw failure;
        }
        auditAgentAuthorityControl(context, action,
                ai.ravenroot.api.application.ExecutionControlAuditEvent.Disposition.SUCCEEDED, state, false);
        json(exchange, 200, "{\"state\":\"" + state + "\",\"epoch\":" + epoch + "}");
    }

    private void auditAgentAuthorityControl(
            ai.ravenroot.api.security.RequestContext context, String action,
            ai.ravenroot.api.application.ExecutionControlAuditEvent.Disposition disposition,
            String detail, boolean required) {
        var event = new ai.ravenroot.api.application.ExecutionControlAuditEvent(
                clock.instant(), context.requestId(), context.subject(), context.tenantId(), action,
                "agent-authority", "global", disposition, detail);
        try {
            agentAuthorityControlAudit.record(event);
        } catch (RuntimeException unavailable) {
            if (required) {
                throw new ai.ravenroot.api.security.AuthorizationDeniedException(
                        "agent authority control audit unavailable");
            }
        }
    }

    private void nodeTypes(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
        var sources = authorizedApplication.nodeTypeSources(context);
        String body = authorizedApplication.nodeTypes(context)
                .stream().map(type -> nodeTypeJson(type, sources.get(type.behavior())))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        json(exchange, 200, body);
    }

    /**
     * {@code GET /v1/program-languages}: the program-language catalog, read straight from the
     * composed runtime through {@code AuthorizedRavenrootApplication#supportedProgramLanguages}, never
     * hand-listed here or in the editor. An empty array is a legitimate answer -- a runtime that has
     * not implemented {@code ProgramRuntime#supportedLanguages()} -- and the client must render "no
     * languages available" rather than assume one.
     */
    private void programLanguages(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
        String body = authorizedApplication.supportedProgramLanguages(context).stream()
                .map(RavenrootServer::programLanguageJson)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        json(exchange, 200, body);
    }

    private void programArtifacts(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/v1/program-artifacts".length());
        if ((suffix.isEmpty() || suffix.equals("/")) && "GET".equals(exchange.getRequestMethod())) {
            String body = authorizedApplication.programArtifacts(AuthenticatedPrincipalAttribute.requestContext(exchange))
                    .stream().map(RavenrootServer::artifactJson)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            json(exchange, 200, body);
            return;
        }
        if (suffix.startsWith("/builds/") && "GET".equals(exchange.getRequestMethod())) {
            String buildId = suffix.substring("/builds/".length());
            if (buildId.isBlank() || buildId.contains("/")) {
                fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
                return;
            }
            observeProgramBuild(exchange, buildId);
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            fail(exchange, ErrorCode.METHOD_NOT_ALLOWED);
            return;
        }
        try {
            if (suffix.isEmpty() || suffix.equals("/")) {
                createProgramArtifact(exchange);
                return;
            }
            if ("/build".equals(suffix)) {
                buildProgramArtifacts(exchange);
                return;
            }
            if ("/approve-batch".equals(suffix)) {
                approveProgramArtifacts(exchange);
                return;
            }
            String[] segments = suffix.split("/");
            if (segments.length != 3 || segments[1].isBlank() || segments[2].isBlank()) {
                fail(exchange, ErrorCode.UNKNOWN_ARTIFACT_OPERATION);
                return;
            }
            applyArtifactOperation(exchange, segments[1], segments[2]);
        } catch (PayloadException rejection) {
            failPayload(exchange, rejection);
        } catch (IllegalArgumentException error) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
        } catch (IllegalStateException error) {
            fail(exchange, ErrorCode.CONFLICT);
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            // Asynchronous artifact failures require classification beyond IllegalStateException. A
            // deployment with no usable sandbox is an environment error, while a source that does not
            // compile is a validation outcome answered in validateProgramArtifact and never reaches
            // here. The deployment error therefore gets a status and code that identify it as such;
            // all other failures retain their existing meaning.
            fail(exchange, artifactFailureCode(cause));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail(exchange, ErrorCode.REQUEST_INTERRUPTED);
        }
    }

    /**
     * {@code /v1/credentials} — the one place a credential value may enter this product.
     *
     * <p>{@code POST} stores one and answers with the reference the <b>server</b> minted; {@code GET}
     * lists the caller's own, by label, and never a value. There is no {@code DELETE}: forgetting the row
     * while every graph that names it keeps naming it — would be worse than none.</p>
     *
     * <p>Both are scoped to the authenticated {@link ai.ravenroot.api.security.RequestContext}, tenant
     * and subject together. The subject is the author who typed the value and the only one who may
     * see that it exists; the tenant is carried too so that two deployments sharing a directory
     * service cannot see across each other even where a subject id repeats.</p>
     */
    private void credentials(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (credentials == null || !"/v1/credentials".equals(exchange.getRequestURI().getPath())) {
            // No store composed: the same answer a client gets for a path this build does not serve.
            // See the registration site for why the condition lives here rather than there.
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        // Never cached, and said here rather than relied upon: the list carries the labels an author
        // chose for their own credentials, which is not a secret but is not shared-cache material
        // either. The same two headers the embed routes set, for the same reason.
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
        try {
            switch (exchange.getRequestMethod()) {
                case "GET" -> json(exchange, 200,
                        ai.ravenroot.server.credential.UserCredentialWire.writeCredentials(
                                credentials.listFor(context.tenantId(), context.subject())));
                case "POST" -> createCredential(exchange, context);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, POST");
                    fail(exchange, ErrorCode.METHOD_NOT_ALLOWED);
                }
            }
        } catch (PayloadException rejection) {
            failPayload(exchange, rejection);
        } catch (IllegalArgumentException error) {
            // The message is deliberately not echoed: one of the messages this reader can raise
            // names a field the caller sent, and the request body it was reading contained a
            // credential. This class has no signature that puts error text in a body -- see fail(..).
            fail(exchange, ErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * Stores one credential.
     *
     * <p>The response is built from what the store returned, not from what the caller sent. That is
     * the structural reason the value cannot come back: {@code StoredCredential} has no component for
     * it, so there is no expression in this method that evaluates to the secret.</p>
     */
    private void createCredential(HttpExchange exchange,
                                  ai.ravenroot.api.security.RequestContext context)
            throws IOException, PayloadException {
        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(
                    ai.ravenroot.server.credential.UserCredentialWire.CREATE_LIMITS.maxEncodedBytes() + 1);
        }
        if (body.length
                > ai.ravenroot.server.credential.UserCredentialWire.CREATE_LIMITS.maxEncodedBytes()) {
            // Bounded before parsing, as every other body on this server is.
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        var request = ai.ravenroot.server.credential.UserCredentialWire.readCreate(body);
        char[] value = request.value();
        try {
            var stored = credentials.mint(context.tenantId(), context.subject(), request.label(),
                    request.scheme(), request.username(), value);
            json(exchange, 201,
                    ai.ravenroot.server.credential.UserCredentialWire.writeCredential(stored));
        } finally {
            // Best-effort, and the secret-handling contract already says why it cannot be more: the value was
            // a String inside PayloadJson before this method ever saw it. Zeroing the one array this
            // code owns is still worth doing -- it shortens the window rather than closing it -- and
            // claiming more than that here would be the dishonest sentence that ADR forbids.
            java.util.Arrays.fill(value, '\0');
        }
    }

    private void createProgramArtifact(HttpExchange exchange) throws IOException {
        byte[] source;
        try (var input = exchange.getRequestBody()) {
            source = input.readNBytes(MAX_PROGRAM_BYTES + 1);
        }
        if (source.length > MAX_PROGRAM_BYTES) {
            fail(exchange, ErrorCode.PROGRAM_SOURCE_TOO_LARGE);
            return;
        }
        var parameters = query(exchange);
        var metadata = new LinkedHashMap<String, String>();
        if (!parameters.getOrDefault("name", "").isBlank()) metadata.put("name", parameters.get("name"));
        var artifact = authorizedApplication.createProgramArtifact(
                AuthenticatedPrincipalAttribute.requestContext(exchange),
                parameters.getOrDefault("language", "javascript"), new String(source, StandardCharsets.UTF_8),
                metadata);
        json(exchange, 201, artifactJson(artifact));
    }

    private void buildProgramArtifacts(HttpExchange exchange)
            throws IOException, java.util.concurrent.ExecutionException, InterruptedException {
        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_GRAPH_BYTES + 1);
        }
        if (body.length > MAX_GRAPH_BYTES) {
            fail(exchange, ErrorCode.PROGRAM_SOURCE_TOO_LARGE);
            return;
        }
        var submission = ProgramBuildSubmission.read(body, payloadLimits);
        var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
        var programs = submission.programs().stream()
                .map(program -> new ai.ravenroot.api.programming.ProgramBuildRequest(
                        program.nodeId(), program.language(), program.source(), program.testPayload()))
                .toList();
        var build = authorizedApplication.startProgramBuild(context, programs).toCompletableFuture().get();
        json(exchange, build.terminal() && build.nodes().stream()
                .allMatch(ai.ravenroot.api.programming.ProgramBuildNodeSnapshot::ready) ? 200 : 202,
                programBuildSnapshotJson(build));
    }

    private void observeProgramBuild(HttpExchange exchange, String buildId) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        var build = authorizedApplication.observeProgramBuild(
                AuthenticatedPrincipalAttribute.requestContext(exchange), buildId);
        if (build.isEmpty()) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        var snapshot = build.orElseThrow();
        json(exchange, snapshot.terminal() ? 200 : 202, programBuildSnapshotJson(snapshot));
    }

    private void approveProgramArtifacts(HttpExchange exchange) throws IOException {
        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(payloadLimits.maxEncodedBytes() + 1);
        }
        if (body.length > payloadLimits.maxEncodedBytes()) {
            failPayload(exchange, PayloadException.tooLarge(body.length, payloadLimits.maxEncodedBytes()));
            return;
        }
        var approval = ProgramBuildSubmission.readApproval(body, payloadLimits);
        var approved = authorizedApplication.approveProgramArtifacts(
                AuthenticatedPrincipalAttribute.requestContext(exchange), approval.artifactIds(), approval.reason());
        String response = approved.stream().map(RavenrootServer::artifactJson)
                .collect(java.util.stream.Collectors.joining(",", "{\"artifacts\":[", "]}"));
        json(exchange, 200, response);
    }

    private void applyArtifactOperation(HttpExchange exchange, String id, String operation)
            throws java.util.concurrent.ExecutionException, InterruptedException, IOException {
        switch (operation) {
            case "validate" -> validateProgramArtifact(exchange, id);
            case "test" -> {
                Object payload = readArtifactTestPayload(exchange);
                if (payload == null) return;
                var tested = authorizedApplication.testProgramArtifact(
                        AuthenticatedPrincipalAttribute.requestContext(exchange), id, payload)
                        .toCompletableFuture().get();
                // API-01 on the output side. The artifact under test is untrusted code, so what it
                // returns is untrusted structure: routing it through the payload model bounds its
                // depth, breadth and size before it is serialised, which the previous ad-hoc
                // valueJson() recursion did not. A structure outside the model is now a classified
                // rejection rather than a best-effort toString() of an arbitrary JVM object.
                var output = PayloadValue.fromJava(tested.output(), payloadLimits);
                json(exchange, 200, "{\"artifact\":" + artifactJson(tested.artifact())
                        + ",\"payload\":" + PayloadEnvelope.of(output).toJson()
                        + ",\"output\":" + PayloadJson.write(output) + "}");
            }
            case "approve" -> json(exchange, 200, artifactJson(authorizedApplication.approveProgramArtifact(
                    AuthenticatedPrincipalAttribute.requestContext(exchange), id,
                    query(exchange).getOrDefault("reason", ""))));
            case "activate" -> json(exchange, 200, artifactJson(authorizedApplication.activateProgramArtifact(
                    AuthenticatedPrincipalAttribute.requestContext(exchange), id)));
            case "retire" -> json(exchange, 200, artifactJson(authorizedApplication.retireProgramArtifact(
                    AuthenticatedPrincipalAttribute.requestContext(exchange), id,
                    query(exchange).getOrDefault("reason", ""))));
            default -> fail(exchange, ErrorCode.UNKNOWN_ARTIFACT_OPERATION);
        }
    }

    /**
     * Reads the artifact smoke-test value before the application is called. JSON is decoded under
     * the same payload limits as graph execution; text is intentionally never JSON-sniffed. The
     * old {@code ?payload=} spelling remains only for an empty, untyped body so existing clients
     * retain their exact text semantics while every new body is bounded and explicit.
     */
    private Object readArtifactTestPayload(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!ArtifactTestSubmission.supports(contentType)) {
            fail(exchange, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            return null;
        }
        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(payloadLimits.maxEncodedBytes() + 1);
        }
        if (body.length > payloadLimits.maxEncodedBytes()) {
            failPayload(exchange, PayloadException.tooLarge(body.length, payloadLimits.maxEncodedBytes()));
            return null;
        }
        if (body.length == 0 && contentType == null) {
            return query(exchange).getOrDefault("payload", "");
        }
        return ArtifactTestSubmission.read(body, contentType, payloadLimits).toJava();
    }

    /**
     * The code for an artifact operation that failed inside the runtime.
     *
     * <p>An explicit mapping over a closed reason vocabulary, for the same purpose as
     * {@link #graphMlCode}: a reason added to {@code ProgramRuntimeUnavailableException} becomes a
     * compile error here rather than a new value silently reaching the public vocabulary through a
     * default branch.</p>
     *
     * <p>The two {@code ProgramRuntimeUnavailableException} codes are 501 rather than 400 because
     * nothing about the request was wrong. They are 501 rather than 503 because each states a fact
     * about this deployment's <em>capability</em> — no runtime adapter, or no usable sandbox — whose
     * remedy is an operator action, and 501 is the status that says so.</p>
     *
     * <p><b>{@code PROGRAM_EXECUTION_TIMEOUT} is 504</b>, and the contrast is the point rather
     * than an inconsistency: a deadline is not a statement about capability. The capability was
     * verified and worked, and only the clock ran out on one run against a configured budget — the
     * identical request succeeds on an idle machine. Retrying, or raising the budget, is the correct
     * response.</p>
     *
     * <p>Deliberately not said here: <em>what</em> the run had accomplished when the budget elapsed.
     * The adapter raises the condition at seven stages, and only two of them — {@code diagnostics}
     * and {@code after_response} — establish that the worker ran at all. At {@code sandbox_outcome},
     * which is the stage this adapter actually reaches in practice, it is simply <em>not known</em>:
     * the production launcher answers that outcome from {@code !process.waitFor(remaining)} alone, so
     * a supervisor stuck in setup produces it with the program never executed. The stage goes to the
     * server log precisely because it does not belong in a fixed sentence. See
     * {@code ErrorCode.PROGRAM_EXECUTION_TIMEOUT} for the enumeration and
     * {@code docs/architecture/payload-and-error-contract.md} for the table anchored to line
     * numbers.</p>
     *
     * <p><b>The capability probe has an important classification caveat.</b>
     * {@code SandboxSupervisorProcessLauncher.verifyCapability()} bounds its capability probe at two
     * seconds, turns a missed bound into {@code SANDBOX_CAPABILITY_UNSUPPORTED} and therefore 501,
     * and runs it on every request — so under load it answers 501 for a condition a retry would
     * clear. This method cannot distinguish that probe timeout from a genuinely unsupported
     * capability because both arrive under the same reason. The 504 mapping above does not depend on
     * that limitation.</p>
     *
     * <h2>The default branch is {@code INTERNAL_ERROR}</h2>
     * <p>An unenumerated cause must not accuse the author by becoming "the request was rejected as
     * invalid". Infrastructure failures are the harmless default reading; author-attributable causes
     * are named explicitly.</p>
     *
     * <p>The inversion is safe because the causes that genuinely mean "the source or the request is
     * invalid" are few and nameable, and both are now named above rather than left to fall through:
     * {@code ProgramSourceRejectedException} (which validate answers as a 200 outcome, so it reaches
     * here only from the sibling operations) and {@code IllegalArgumentException}, which is what the
     * adapter raises for a worker refusal it cannot attribute to the author and for a malformed
     * request. Everything else — a raw {@code TimeoutException} from a third-party {@code
     * ProgramRuntime}, a {@code SecurityException} from the artifact hash check, an output-limit
     * {@code IOException} — is infrastructure, and 500 errs toward the harmless reading of it.</p>
     */
    private static ErrorCode artifactFailureCode(Throwable cause) {
        if (cause instanceof ProgramRuntimeUnavailableException unavailable) {
            return switch (unavailable.reason()) {
                case RUNTIME_NOT_INSTALLED -> ErrorCode.PROGRAM_RUNTIME_NOT_INSTALLED;
                case SANDBOX_UNAVAILABLE -> ErrorCode.PROGRAM_SANDBOX_UNAVAILABLE;
            };
        }
        if (cause instanceof ProgramDeadlineExceededException) return ErrorCode.PROGRAM_EXECUTION_TIMEOUT;
        if (cause instanceof IllegalStateException) return ErrorCode.CONFLICT;
        if (cause instanceof ProgramSourceRejectedException || cause instanceof IllegalArgumentException) {
            return ErrorCode.INVALID_REQUEST;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    /**
     * {@code POST /v1/program-artifacts/{id}/validate}.
     *
     * <h2>A source that does not compile is a result, not a protocol error</h2>
     * <p>A Python artifact with an indentation error is not an invalid request: the request is well
     * formed, names a real artifact, and the runtime's answer is "this does not compile, here is
     * where". Returning <b>400 {@code INVALID_REQUEST}</b> would drop the compiler's text and teach the
     * author nothing about the compilation failure.</p>
     *
     * <p>The diagnostic could not simply be moved into the error body: {@link ErrorEnvelope} has no
     * public entry point that accepts caller-composed message text and must not gain one. It is not
     * circumvented here — it is not involved. The rejection is reported as an <b>outcome in a 200
     * body</b>, which is the shape this API already uses twice for exactly this situation:
     * {@code POST /v1/executions/{id}/cancel} answers 200 distinguishing {@code CANCELLED},
     * {@code ALREADY_CANCELLED} and {@code ALREADY_COMPLETED}, carrying a closed vocabulary in a
     * success body. {@code ErrorEnvelope}
     * draws the boundary in its own Javadoc: it bounds what an <em>error response</em> discloses and
     * "says nothing about what a successful response … contains".</p>
     *
     * <h2>Both outcomes are wrapped, and that is the point</h2>
     * <p>The success body changes from a bare artifact to {@code {"outcome":"validated","artifact":…}}.
     * Leaving success unwrapped would have been the more compatible change and the wrong one: a client
     * could then keep ignoring {@code outcome} and read a rejection as a success. The wrapper makes the discriminator
     * unavoidable. {@code POST /v1/program-artifacts/{id}/test} already answers a wrapper for the same
     * reason, so this is the family's existing shape rather than a new one.</p>
     *
     * <p>The rejected body carries no {@code artifact} member because nothing about the artifact
     * changed — the reservation is cancelled and it is still {@code GENERATED}, so re-reading it to
     * echo it back would be a second authorization decision taken for cosmetics. {@code artifactId} is
     * present in both.</p>
     *
     * <h2>What still fails as an error, and why the distinction is structural</h2>
     * <p>Only {@link ProgramSourceRejectedException} produces the rejected outcome, and only the
     * <em>worker</em> can raise it — it marks the region that loads the source and checks its handler
     * shape. A missing sandbox launcher, an exhausted deadline or a conflicting artifact state
     * propagates to {@link #programArtifacts} untouched and is still answered exactly as before.
     * Telling an author their source does not compile when the sandbox never started is a false cause,
     * and a false cause is worse than a generic one.</p>
     */
    private void validateProgramArtifact(HttpExchange exchange, String id)
            throws java.util.concurrent.ExecutionException, InterruptedException, IOException {
        GeneratedArtifact validated;
        try {
            validated = authorizedApplication.validateProgramArtifact(
                    AuthenticatedPrincipalAttribute.requestContext(exchange), id).toCompletableFuture().get();
        } catch (java.util.concurrent.ExecutionException error) {
            if (!(error.getCause() instanceof ProgramSourceRejectedException rejected)) throw error;
            json(exchange, 200, "{\"outcome\":\"rejected\""
                    + ",\"artifactId\":\"" + escape(id) + "\""
                    + ",\"diagnostic\":\"" + escape(rejected.diagnostic()) + "\""
                    + ",\"line\":" + rejected.line()
                    + ",\"column\":" + rejected.column() + "}");
            return;
        }
        json(exchange, 200, "{\"outcome\":\"validated\""
                + ",\"artifactId\":\"" + escape(validated.id()) + "\""
                + ",\"artifact\":" + artifactJson(validated) + "}");
    }

    private static String artifactJson(GeneratedArtifact artifact) {
        String metadata = artifact.metadata().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> "\"" + escape(entry.getKey()) + "\":\"" + escape(entry.getValue()) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        return "{\"id\":\"" + escape(artifact.id()) + "\""
                + ",\"language\":\"" + escape(artifact.language()) + "\""
                + ",\"sha256\":\"" + escape(artifact.sha256()) + "\""
                + ",\"state\":\"" + artifact.state() + "\""
                + ",\"revision\":" + artifact.revision()
                + ",\"createdAt\":\"" + artifact.createdAt() + "\""
                + ",\"updatedAt\":\"" + artifact.updatedAt() + "\""
                + ",\"metadata\":" + metadata + "}";
    }

    private static String programBuildJson(ai.ravenroot.api.programming.ProgramBuildResult result) {
        String output = result.smokeOutput() == null ? "null"
                : PayloadJson.write(PayloadValue.fromJava(result.smokeOutput(), PayloadLimits.DEFAULTS));
        return "{\"nodeId\":\"" + escape(result.nodeId()) + "\""
                + ",\"artifact\":" + artifactJson(result.artifact())
                + ",\"sourceDigest\":\"" + escape(result.sourceDigest()) + "\""
                + ",\"payloadDigest\":\"" + escape(result.payloadDigest()) + "\""
                + ",\"phase\":\"" + result.phase() + "\""
                + ",\"ready\":" + result.ready()
                + ",\"reused\":" + result.reused()
                + ",\"smokeOutput\":" + output
                + ",\"diagnostic\":\"" + escape(result.diagnostic()) + "\"}";
    }

    private static String programBuildSnapshotJson(ai.ravenroot.api.programming.ProgramBuildSnapshot build) {
        String programs = build.nodes().stream().map(RavenrootServer::programBuildNodeSnapshotJson)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"buildId\":\"" + escape(build.id()) + "\""
                + ",\"revision\":" + build.revision()
                + ",\"createdAt\":\"" + build.createdAt() + "\""
                + ",\"updatedAt\":\"" + build.updatedAt() + "\""
                + ",\"terminal\":" + build.terminal()
                + ",\"programs\":" + programs + "}";
    }

    private static String programBuildNodeSnapshotJson(
            ai.ravenroot.api.programming.ProgramBuildNodeSnapshot node) {
        String output = node.smokeOutputJson().isBlank() ? "null" : node.smokeOutputJson();
        String artifactId = node.artifactId().isBlank() ? "null" : "\"" + escape(node.artifactId()) + "\"";
        return "{\"nodeId\":\"" + escape(node.plan().nodeId()) + "\""
                + ",\"artifactId\":" + artifactId
                + ",\"sourceDigest\":\"" + escape(node.plan().sourceDigest()) + "\""
                + ",\"payloadDigest\":\"" + escape(node.plan().payloadDigest()) + "\""
                + ",\"phase\":\"" + node.phase() + "\""
                + ",\"revision\":" + node.revision()
                + ",\"createdAt\":\"" + node.createdAt() + "\""
                + ",\"updatedAt\":\"" + node.updatedAt() + "\""
                + ",\"terminal\":" + node.terminal()
                + ",\"ready\":" + node.ready()
                + ",\"reused\":" + node.reused()
                + ",\"smokeOutput\":" + output
                + ",\"diagnostic\":\"" + escape(node.diagnostic()) + "\"}";
    }

    /**
     * The stable code for a GraphML rejection reason.
     *
     * <p>The mapping is explicit rather than {@code "GRAPHML_" + reason}, so that adding a reason to
     * the parser is a compile error here instead of a new code appearing in the public vocabulary
     * without anyone deciding on its status or its wording.</p>
     */
    private static ErrorCode graphMlCode(GraphMlParseException.Reason reason) {
        return switch (reason) {
            case DOCUMENT_TOO_LARGE -> ErrorCode.GRAPHML_DOCUMENT_TOO_LARGE;
            case RESOURCE_LIMIT -> ErrorCode.GRAPHML_RESOURCE_LIMIT;
            case UNSAFE_XML -> ErrorCode.GRAPHML_UNSAFE_XML;
            case MALFORMED_XML -> ErrorCode.GRAPHML_MALFORMED_XML;
            case INVALID_GRAPH -> ErrorCode.GRAPHML_INVALID_GRAPH;
            case COMPRESSED_ARCHIVE -> ErrorCode.GRAPHML_COMPRESSED_ARCHIVE;
        };
    }

    /** {@code exampleSource} routes through {@link #escape}, which already turns a newline into
     * {@code \n} (see its own body) -- a multi-line starter is not a special case here. */
    private static String programLanguageJson(ai.ravenroot.api.programming.ProgramLanguageDescriptor language) {
        return "{\"id\":\"" + escape(language.id()) + "\""
                + ",\"displayName\":\"" + escape(language.displayName()) + "\""
                + ",\"exampleSource\":\"" + escape(language.exampleSource()) + "\"}";
    }

    private static String nodeTypeJson(NodeTypeDescriptor type, ai.ravenroot.api.catalog.NodeCatalogSource source) {
        source = source == null ? ai.ravenroot.api.catalog.NodeCatalogSource.bundle("") : source;
        String properties = type.properties().stream().map(RavenrootServer::nodePropertyJson)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String capabilities = type.capabilities().stream().sorted().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String allowedNatures = type.allowedNatureIdentifiers().stream()
                .map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String commands = type.commands().stream().sorted().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        // The DECLARATION, not a resolved set: for cel-decision and http-request the outcome
        // names come from node properties, so there is no per-type answer to publish -- only the
        // editor, which has the node in front of it, can resolve them. Declaration order is preserved
        // rather than sorted, because it is the behavior's own statement of which outcome is the
        // ordinary one (see cel-decision: true before false).
        String outcomes = type.outcomes().stream().map(RavenrootServer::nodeOutcomeJson)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"behavior\":\"" + escape(type.behavior()) + "\""
                + ",\"displayName\":\"" + escape(type.displayName()) + "\""
                + ",\"category\":\"" + escape(type.category()) + "\""
                + ",\"origin\":\"" + source.origin().name() + "\""
                + ",\"bundleId\":\"" + escape(source.bundleId()) + "\""
                + ",\"description\":\"" + escape(type.description()) + "\""
                + ",\"visualType\":\"" + escape(type.visualType()) + "\""
                + ",\"agentic\":" + type.agentic()
                + ",\"capabilities\":" + capabilities
                // ADR 0024 section 2. The effective values, not the raw declaration: an editor
                // needs to render what a node actually is, and "absent" is not something it can
                // render. defaultNature is what a node with no declaration resolves to;
                // allowedNatures is the complete set graph content may choose from, which for a
                // descriptor that declared nothing is exactly the default -- fail-closed, so the
                // editor offers no choice rather than offering an escalation the server will refuse.
                // The distinction between "declared" and "absent" stays server-side: it is only
                // needed by catalog-load derivation, and publishing it would invite a consumer to
                // reimplement that derivation.
                + ",\"defaultNature\":\"" + escape(type.effectiveDefaultNature().name()) + "\""
                + ",\"allowedNatures\":" + allowedNatures
                + ",\"commands\":" + commands
                + ",\"outcomes\":" + outcomes
                // The property name an editor writes into the graph when the author picks a
                // non-default nature. Published rather than hardcoded in the UI so the two cannot
                // drift; the values above are the only legal contents.
                + ",\"natureProperty\":\"" + escape(NodeRuntimeNatureProperty.NAME) + "\""
                // This is the property name an editor writes into the graph when the author switches
                // a node off. Derived from the
                // catalog rather than hardcoded in the UI so the two cannot drift. There is no
                // companion "allowedBypassValues": unlike a nature, the legal contents are fixed by
                // the platform at exactly "true"/"false" for every node type, so a per-descriptor
                // field would publish the same two strings on every entry and invite a consumer to
                // believe a behavior could narrow them.
                + ",\"bypassProperty\":\"" + escape(NodeBypassProperty.NAME) + "\""
                + ",\"maxConcurrencyProperty\":\""
                + escape(NodeRuntimeMaxConcurrencyProperty.NAME) + "\""
                + ",\"defaultMaxConcurrency\":" + type.runtimeConcurrency().defaultValue()
                + ",\"maxConcurrencyCeiling\":" + type.runtimeConcurrency().ceiling()
                + ",\"properties\":" + properties + "}";
    }

    /**
     * One outcome declaration: a fixed {@code name}, or the {@code fromProperty} whose value
     * names the outcome. Exactly one of the two is non-empty, which
     * {@code NodeOutcomeDescriptor}'s compact constructor guarantees, so a consumer can branch on
     * {@code fromProperty} being empty without a third field to say which kind it is.
     */
    private static String nodeOutcomeJson(ai.ravenroot.api.catalog.NodeOutcomeDescriptor outcome) {
        return "{\"name\":\"" + escape(outcome.name()) + "\""
                + ",\"fromProperty\":\"" + escape(outcome.fromProperty()) + "\""
                + ",\"description\":\"" + escape(outcome.description()) + "\"}";
    }

    private static String nodePropertyJson(NodePropertyDescriptor property) {
        String values = property.allowedValues().stream().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"name\":\"" + escape(property.name()) + "\""
                + ",\"displayName\":\"" + escape(property.displayName()) + "\""
                + ",\"type\":\"" + property.type() + "\""
                + ",\"required\":" + property.required()
                + ",\"description\":\"" + escape(property.description()) + "\""
                + ",\"defaultValue\":\"" + escape(property.defaultValue()) + "\""
                // Required, but its absence means "not configured yet" rather than
                // "invalid graph". An editor should still mark the field required and should NOT
                // block saving or submitting a graph that leaves it blank.
                + ",\"adapterBinding\":" + property.adapterBinding()
                + ",\"allowedValues\":" + values
                // Absent conditions are emitted as null, never as an always-true condition.
                // A consumer must be able to tell "no condition declared" from "a condition that
                // happens to hold", because only the first means the field is unconditional.
                + ",\"visibleWhen\":" + propertyConditionJson(property.visibleWhen())
                + ",\"requiredWhen\":" + propertyConditionJson(property.requiredWhen()) + "}";
    }

    /**
     * Serializes a conditional-property condition. The {@code contract} marker travels with
     * every condition: a consumer that does not recognise it must treat the condition as
     * unsatisfiable rather than ignore it, because ignoring an unknown condition silently makes a
     * guarded field unconditional.
     */
    private static String propertyConditionJson(ai.ravenroot.api.catalog.PropertyCondition condition) {
        if (condition == null) {
            return "null";
        }
        String operands = condition.values().stream().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"contract\":\"" + escape(condition.contract()) + "\""
                + ",\"property\":\"" + escape(condition.property()) + "\""
                + ",\"operator\":\"" + condition.operator() + "\""
                + ",\"values\":" + operands + "}";
    }

    private void inspectGraph(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) {
            return;
        }
        try (var input = exchange.getRequestBody()) {
            byte[] graph = readGraphMlRequest(exchange, input);
            if (graph == null) {
                return;
            }
            var summary = authorizedApplication.inspectGraphMl(
                    AuthenticatedPrincipalAttribute.requestContext(exchange),
                    new java.io.ByteArrayInputStream(graph));
            // "valid" and "violations" distinguish validity on this exact endpoint;
            // POST /v1/graphs/inspect otherwise reports the same four counts whether
            // the document was a sound graph or not.
            json(exchange, 200, "{\"nodes\":" + summary.nodes() + ",\"edges\":" + summary.edges()
                    + ",\"startNodes\":" + summary.startNodes() + ",\"endNodes\":" + summary.endNodes()
                    + ",\"valid\":" + summary.valid() + ",\"violations\":" + stringArrayJson(summary.violations())
                    + "}");
        } catch (ai.ravenroot.core.runtime.GraphExecutionLimitException rejection) {
            failGraphExecutionLimit(exchange, rejection);
        } catch (GraphMlParseException error) {
            graphMlError(exchange, error);
        } catch (GraphMlCompatibilityException error) {
            graphMlError(exchange, error);
        } catch (PayloadException rejection) {
            failPayload(exchange, rejection);
        } catch (IllegalArgumentException error) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * Submission is limited three ways, because it is the one endpoint where a cheap request buys
     * expensive work.
     *
     * <p>The per-tenant submission bucket is the fair-share mechanism: it is an order of magnitude
     * tighter than the general request budget, and it is what keeps one tenant from consuming the
     * engine. The per-tenant concurrency lease bounds simultaneous in-flight submissions, which is the
     * part a rate alone cannot bound — parsing a 10 MiB GraphML document is work that overlaps. The
     * global active-execution ceiling is a backstop against total engine saturation.</p>
     *
     * <p>Running executions are counted per tenant <em>and</em> globally, by
     * {@link ai.ravenroot.server.ratelimit.ActiveExecutionRegistry}. The per-tenant cap is derived from
     * the global ceiling and is structurally below it, so a tenant holding executions can never be the
     * reason another tenant is refused; the global ceiling remains as the backstop against total engine
     * saturation. A global counter alone would make one tenant's load decide every other tenant's
     * availability, and its
     * source, {@code ExecutionMonitor.activeExecutions}, moves only on terminal events, so a single
     * execution that never published one closed submission for the whole process until restart. The
     * registry ages entries out and says so in the audit stream, which bounds that failure instead of
     * relocating it.</p>
     *
     * <h2>Path dispatch</h2>
     * Dispatches by path suffix, exactly like {@link #programArtifacts} does for
     * {@code /v1/program-artifacts/{id}/{operation}}. The exact {@code /v1/executions} path (empty
     * suffix) keeps the submission behaviour below unchanged.
     *
     * <p>This context receives every path under {@code /v1/executions} because the JDK HttpServer
     * matches contexts by longest prefix, so both sub-routes are routed here rather than from
     * additional contexts that would shadow this one:</p>
     * <ul>
     *   <li>{@code GET /v1/executions/{id}} — read the result</li>
     *   <li>{@code POST /v1/executions/{id}/cancel} — cancel the traversal (API-02)</li>
     * </ul>
     *
     * <p><strong>Routing happens before the method check, and that ordering is load-bearing.</strong>
     * The two sub-routes use different verbs, so a single {@code POST}-or-405 gate ahead of the
     * dispatch would answer 405 for every
     * result read. The gate therefore belongs to each branch: {@link #readExecution} requires GET
     * itself, cancel requires POST here, and submission requires POST below.</p>
     */
    private void startExecution(HttpExchange exchange) throws IOException {
        String suffix = exchange.getRequestURI().getPath().substring("/v1/executions".length());
        if (!suffix.isEmpty() && !suffix.equals("/")) {
            // suffix always starts with '/', so segments[0] is the empty string.
            String[] segments = suffix.split("/");
            if (segments.length == 2 && "live".equals(segments[1])) {
                // "live" is a reserved collection-level segment, never a valid traversal id
                // (ids are UUIDs), so no legitimate /v1/executions/{id} read is ever shadowed by it --
                // the same style of reservation "cancel" already uses one segment further in.
                if (!method(exchange, "GET")) {
                    return;
                }
                listLiveExecutions(exchange);
                return;
            }
            if (segments.length == 2 && "inventory".equals(segments[1])) {
                // "inventory" is reserved the same way "live" is -- never a valid traversal id, so
                // no legitimate /v1/executions/{id} read is ever shadowed by it.
                if (!method(exchange, "GET")) {
                    return;
                }
                listProcessInventory(exchange);
                return;
            }
            if (segments.length == 2 && !segments[1].isBlank()) {
                readExecution(exchange, segments[1]);
                return;
            }
            if (segments.length == 3 && !segments[1].isBlank()
                    && ("cancel".equals(segments[2]) || "pause".equals(segments[2])
                        || "resume".equals(segments[2]))) {
                if (!method(exchange, "POST")) {
                    return;
                }
                controlExecution(exchange, segments[1], segments[2]);
                return;
            }
            if (segments.length == 3 && !segments[1].isBlank() && "manifest".equals(segments[2])) {
                // {id} names a process instance here, exactly as it does for "traversals" and for the
                // same reason: a manifest is pinned per process instance, which is the granularity at
                // which the graph version pin is already write-once.
                if (!method(exchange, "GET")) {
                    return;
                }
                readExecutionManifest(exchange, segments[1]);
                return;
            }
            if (segments.length == 3 && !segments[1].isBlank() && "traversals".equals(segments[2])) {
                // Unlike every other sub-route under /v1/executions, {id} here names a durable
                // process instance id, not a traversal/execution id -- see
                // #readProcessInstanceTraversals's own Javadoc for why that is deliberate rather than
                // an inconsistency.
                if (!method(exchange, "GET")) {
                    return;
                }
                readProcessInstanceTraversals(exchange, segments[1]);
                return;
            }
            if (segments.length == 5 && !segments[1].isBlank()
                    && "tool-approvals".equals(segments[2]) && !segments[3].isBlank()
                    && ("approve".equals(segments[4]) || "deny".equals(segments[4])
                        || "cancel".equals(segments[4]))) {
                if (!method(exchange, "POST")) return;
                decideToolApproval(exchange, segments[1], segments[3], segments[4]);
                return;
            }
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        if (!method(exchange, "POST")) {
            return;
        }
        var principal = AuthenticatedPrincipalAttribute.require(exchange);
        var submissionBudget = rateLimiter.checkSubmissionRate(principal.tenantId());
        if (!submissionBudget.isAllowed()) {
            refuse(exchange, submissionBudget);
            return;
        }
        var admission = rateLimiter.activeExecutions().checkAdmission(principal.tenantId());
        if (!admission.isAllowed()) {
            refuse(exchange, admission);
            return;
        }
        try (var slot = rateLimiter.acquireSubmissionSlot(principal.tenantId())) {
            if (!slot.granted()) {
                refuse(exchange, slot.refusal());
                return;
            }
            String mode = query(exchange).getOrDefault("mode", "test");
            ai.ravenroot.api.application.ExecutionPolicy policy = switch (mode) {
                case "test" -> ai.ravenroot.api.application.ExecutionPolicy.TEST_PASSTHROUGH;
                case "run" -> ai.ravenroot.api.application.ExecutionPolicy.STANDARD;
                default -> null;
            };
            if (policy == null) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
            submitExecution(exchange, policy);
        }
    }

    /** Authenticated, tenant-derived decision path; no stored content is serialized. */
    private void decideToolApproval(HttpExchange exchange, String processText, String approvalText,
                                    String decision) throws IOException {
        ai.ravenroot.core.approval.ToolApprovalService service = toolApprovals;
        if (service == null) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        java.util.UUID processId;
        java.util.UUID approvalId;
        try {
            processId = java.util.UUID.fromString(processText);
            approvalId = java.util.UUID.fromString(approvalText);
        } catch (IllegalArgumentException invalid) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
        ai.ravenroot.core.approval.ToolApprovalResult result;
        try {
            result = switch (decision) {
                case "approve" -> service.approve(context, processId, approvalId);
                case "deny" -> service.deny(context, processId, approvalId);
                case "cancel" -> service.cancel(context, processId, approvalId);
                default -> throw new IllegalStateException("unreachable tool approval decision");
            };
            if (result.accepted()) toolApprovalSweep.accept(context.tenantId());
        } catch (RuntimeException failure) {
            fail(exchange, ErrorCode.INTERNAL_ERROR);
            return;
        }
        switch (result.code()) {
            case NOT_FOUND -> fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            case UNAUTHORIZED -> fail(exchange, ErrorCode.ACCESS_DENIED);
            case SCOPE_MISMATCH, UNAVAILABLE -> fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            default -> {
                String resume = result.resumeTraversalId() == null ? ""
                        : ",\"resumeTraversalId\":\"" + result.resumeTraversalId() + "\"";
                json(exchange, 200, "{\"outcome\":\""
                        + result.code().name().toLowerCase(java.util.Locale.ROOT)
                        + "\",\"approvalId\":\"" + approvalId + "\"" + resume + "}");
            }
        }
    }

    /** Bounded tenant inbox and generation-fenced decision adapter for durable human tasks. */
    private void humanTasks(HttpExchange exchange) throws IOException {
        ai.ravenroot.core.humantask.HumanTaskService service = humanTasks;
        if (service == null) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        String suffix = exchange.getRequestURI().getPath().substring("/v1/human-tasks".length());
        var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
        if (suffix.isEmpty() || "/".equals(suffix)) {
            if (!method(exchange, "GET")) return;
            var parameters = query(exchange);
            int limit;
            boolean includeTerminal;
            java.util.Optional<java.util.UUID> cursor;
            java.util.Set<ai.ravenroot.api.persistence.HumanTaskStatus> statuses;
            try {
                limit = Integer.parseInt(parameters.getOrDefault("limit", "50"));
                includeTerminal = Boolean.parseBoolean(parameters.getOrDefault("includeTerminal", "false"));
                cursor = parameters.containsKey("cursor")
                        ? java.util.Optional.of(java.util.UUID.fromString(parameters.get("cursor")))
                        : java.util.Optional.empty();
                statuses = parameters.containsKey("status")
                        ? java.util.Arrays.stream(parameters.get("status").split(",", -1))
                                .map(value -> ai.ravenroot.api.persistence.HumanTaskStatus.valueOf(
                                        value.toUpperCase(java.util.Locale.ROOT)))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                        : java.util.Set.of();
            } catch (IllegalArgumentException invalid) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
            try {
                var page = service.inbox(context, new ai.ravenroot.api.persistence.HumanTaskQuery(
                        statuses, includeTerminal, cursor, limit));
                json(exchange, 200, humanTaskPageJson(page));
            } catch (IllegalArgumentException invalid) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
            } catch (RuntimeException failure) {
                fail(exchange, ErrorCode.INTERNAL_ERROR);
            }
            return;
        }
        String[] segments = suffix.substring(1).split("/", -1);
        if (segments.length != 2 || segments[0].isBlank()
                || !("resolve".equals(segments[1]) || "deny".equals(segments[1])
                || "cancel".equals(segments[1]))) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        if (!method(exchange, "POST")) return;
        java.util.UUID taskId;
        long generation;
        try {
            taskId = java.util.UUID.fromString(segments[0]);
            generation = Long.parseLong(query(exchange).get("generation"));
            if (generation < 1) throw new IllegalArgumentException("generation");
        } catch (RuntimeException invalid) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        ai.ravenroot.core.humantask.HumanTaskResult result;
        try {
            result = switch (segments[1]) {
                case "resolve" -> service.resolve(context, taskId, generation,
                        humanTaskResponse(exchange));
                case "deny" -> service.deny(context, taskId, generation);
                case "cancel" -> service.cancel(context, taskId, generation);
                default -> throw new IllegalStateException("unreachable human-task operation");
            };
            if (result.resumeTraversalId() != null) humanTaskSweep.accept(context.tenantId());
        } catch (HumanTaskBodyTooLarge tooLarge) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        } catch (RuntimeException failure) {
            fail(exchange, ErrorCode.INTERNAL_ERROR);
            return;
        }
        switch (result.code()) {
            case NOT_FOUND, UNAVAILABLE -> fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            case UNAUTHORIZED -> fail(exchange, ErrorCode.ACCESS_DENIED);
            case PAYLOAD_REFUSED, STALE_GENERATION -> fail(exchange, ErrorCode.INVALID_REQUEST);
            default -> {
                String resume = result.resumeTraversalId() == null ? ""
                        : ",\"resumeTraversalId\":\"" + result.resumeTraversalId() + "\"";
                json(exchange, 200, "{\"outcome\":\""
                        + result.code().name().toLowerCase(java.util.Locale.ROOT)
                        + "\",\"taskId\":\"" + taskId + "\",\"generation\":"
                        + result.task().generation() + resume + "}");
            }
        }
    }

    private ai.ravenroot.api.persistence.OpaquePayload humanTaskResponse(HttpExchange exchange)
            throws IOException {
        int limit = ai.ravenroot.api.payload.PayloadLimits.DEFAULTS.maxEncodedBytes();
        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(limit + 1);
        }
        if (body.length > limit) throw new HumanTaskBodyTooLarge();
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        return ai.ravenroot.api.persistence.OpaquePayload.of(body, contentType);
    }

    private static String humanTaskPageJson(ai.ravenroot.api.persistence.HumanTaskPage page) {
        var out = new StringBuilder("{\"items\":[");
        boolean first = true;
        for (var task : page.items()) {
            if (!first) out.append(',');
            first = false;
            var request = task.request();
            out.append("{\"taskId\":\"").append(task.request().taskId())
                    .append("\",\"processInstanceId\":\"").append(task.key().processInstanceId())
                    .append("\",\"nodeId\":\"").append(escape(request.nodeId()))
                    .append("\",\"title\":\"").append(escape(request.metadata().title()))
                    .append("\",\"description\":\"").append(escape(request.metadata().description()))
                    .append("\",\"status\":\"").append(task.status().name())
                    .append("\",\"generation\":").append(task.generation())
                    .append(",\"responseContentType\":\"")
                    .append(escape(request.responseSchema().contentType()))
                    .append("\",\"responseSchema\":\"").append(escape(request.responseSchema().schema()))
                    .append("\",\"responseSchemaVersion\":\"")
                    .append(escape(request.responseSchema().schemaVersion()))
                    .append("\",\"responseKind\":\"").append(request.responseSchema().kind())
                    .append("\",\"maxResponseBytes\":").append(request.responseSchema().maxBytes())
                    .append(",\"expiresAt\":\"").append(request.expiresAt()).append("\"");
            request.escalateAt().ifPresent(value -> out.append(",\"escalateAt\":\"")
                    .append(value).append("\""));
            out.append('}');
        }
        out.append("],\"nextCursor\":");
        if (page.nextCursor().isPresent()) out.append('"').append(page.nextCursor().orElseThrow()).append('"');
        else out.append("null");
        return out.append('}').toString();
    }

    private static final class HumanTaskBodyTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Local, single-replica inbound-source lifecycle.
     *
     * <p>{@code POST /v1/source-sessions?id=...} starts idempotently from GraphML without an initial
     * traversal. {@code GET /v1/source-sessions/{id}} observes only the authenticated tenant's
     * process-local record and {@code DELETE} stops exactly that deployment domain. There is no list
     * route: this MVP is not an authoritative inventory and must not look like one.</p>
     */
    private void sourceSessions(HttpExchange exchange) throws IOException {
        String suffix = exchange.getRequestURI().getPath().substring("/v1/source-sessions".length());
        try {
            if (suffix.isEmpty() || suffix.equals("/")) {
                if (!method(exchange, "POST")) return;
                String sessionId = query(exchange).get("id");
                if (sessionId == null) {
                    fail(exchange, ErrorCode.INVALID_REQUEST);
                    return;
                }
                byte[] graph;
                try (var input = exchange.getRequestBody()) {
                    graph = readGraphMlRequest(exchange, input);
                }
                if (graph == null) return;
                var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
                credentialAdmission.require(context, graph);
                var status = authorizedApplication.startSourceSession(context, sessionId,
                        new java.io.ByteArrayInputStream(graph));
                sourceSessionJson(exchange, 202, status);
                return;
            }

            String[] segments = suffix.split("/");
            if (segments.length != 2 || segments[1].isBlank()) {
                fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
                return;
            }
            String sessionId = java.net.URLDecoder.decode(segments[1], java.nio.charset.StandardCharsets.UTF_8);
            var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                var status = authorizedApplication.sourceSession(context, sessionId);
                if (status.isEmpty()) {
                    fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
                    return;
                }
                exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
                sourceSessionJson(exchange, 200, status.orElseThrow());
                return;
            }
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                var stopped = authorizedApplication.stopSourceSession(context, sessionId)
                        .toCompletableFuture().get(35, java.util.concurrent.TimeUnit.SECONDS);
                if (stopped.isEmpty()) {
                    fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
                    return;
                }
                sourceSessionJson(exchange, 200, stopped.orElseThrow());
                return;
            }
            exchange.getResponseHeaders().set("Allow", "GET, DELETE");
            fail(exchange, ErrorCode.METHOD_NOT_ALLOWED);
        } catch (GraphMlParseException error) {
            graphMlError(exchange, error);
        } catch (GraphMlCompatibilityException error) {
            graphMlError(exchange, error);
        } catch (ai.ravenroot.core.runtime.GraphExecutionLimitException rejection) {
            failGraphExecutionLimit(exchange, rejection);
        } catch (ai.ravenroot.api.application.SourceSessionException refusal) {
            fail(exchange, refusal.reason() == ai.ravenroot.api.application.SourceSessionException.Reason.GRAPH_CONFLICT
                    ? ErrorCode.CONFLICT : ErrorCode.INVALID_REQUEST);
        } catch (ai.ravenroot.api.deployment.DeploymentAdmissionException overCap) {
            fail(exchange, ErrorCode.REQUEST_LIMIT_EXCEEDED);
        } catch (UnsupportedOperationException unsupported) {
            fail(exchange, ErrorCode.EXECUTION_POLICY_UNSUPPORTED);
        } catch (java.util.concurrent.TimeoutException timeout) {
            fail(exchange, ErrorCode.REQUEST_INTERRUPTED);
        } catch (java.util.concurrent.ExecutionException failed) {
            ai.ravenroot.core.runtime.GraphExecutionLimitException limited = graphExecutionLimitIn(failed);
            if (limited != null) failGraphExecutionLimit(exchange, limited);
            else fail(exchange, ErrorCode.INTERNAL_ERROR);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(exchange, ErrorCode.REQUEST_INTERRUPTED);
        } catch (IllegalArgumentException invalid) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
        } catch (IllegalStateException conflict) {
            fail(exchange, ErrorCode.CONFLICT);
        }
    }

    /**
     * Tenant-scoped, process-local deployment lifecycle.
     *
     * <p>{@code POST /v1/deployments?id=...} registers an immutable graph version and starts nothing;
     * {@code GET /v1/deployments} lists the caller tenant's own; {@code GET|DELETE
     * /v1/deployments/{id}} inspect and undeploy; {@code POST /v1/deployments/{id}/start|stop|restart}
     * are the lifecycle commands. Every successful response carries
     * {@code "scope":"LOCAL_PROCESS"}.</p>
     *
     * <h2>Non-disclosure is one code path, not a rule to remember</h2>
     * <p>Every lookup and every command resolves through {@code AuthorizedRavenrootApplication}, which
     * takes the tenant from the authenticated {@code RequestContext} and never from the request. An
     * unknown id, a sibling tenant's id and an id already undeployed all arrive here as the same empty
     * {@code Optional} and leave as the same {@link ErrorCode#UNKNOWN_RESOURCE} body — there is no
     * branch here that could tell them apart even if it wanted to.</p>
     *
     * <h2>Why this is not {@code /v1/executions/{id}/cancel} or {@code /v1/drain}</h2>
     * <p>Those two are unchanged and mean what they always meant: cancel ends one traversal, drain
     * ends the whole server's intake. Neither is a deployment stop, and neither is reachable from
     * here.</p>
     */
    private void deployments(HttpExchange exchange) throws IOException {
        String suffix = exchange.getRequestURI().getPath().substring("/v1/deployments".length());
        try {
            var query = query(exchange);
            // The scope is refused, never degraded: a caller that asked for a cluster guarantee must
            // not be handed a process-local one under the same name it requested.
            //
            // This is the ONLY place the scope is enforced, and deliberately so. The application
            // contract takes no scope argument, so LocalDeploymentException carries no scope reason:
            // an in-process embedder cannot express a scope and therefore has nothing to be refused
            // for. The token exists only on this wire, so the refusal belongs on this wire, before
            // any registration happens. Any other adapter that accepts a scope token owes the same
            // refusal at its own boundary rather than expecting the contract to make it.
            String requestedScope = query.get("scope");
            if (requestedScope != null
                    && !ai.ravenroot.api.application.LocalDeploymentStatus.SCOPE.equals(requestedScope)) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
            var context = AuthenticatedPrincipalAttribute.requestContext(exchange);

            if (suffix.isEmpty() || suffix.equals("/")) {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
                    json(exchange, 200, deploymentListJson(authorizedApplication.localDeployments(context)));
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET, POST");
                    fail(exchange, ErrorCode.METHOD_NOT_ALLOWED);
                    return;
                }
                String deploymentId = query.get("id");
                if (deploymentId == null) {
                    fail(exchange, ErrorCode.INVALID_REQUEST);
                    return;
                }
                byte[] graph;
                try (var input = exchange.getRequestBody()) {
                    graph = readGraphMlRequest(exchange, input);
                }
                if (graph == null) return;
                credentialAdmission.require(context, graph);
                // One success status for a create and for an idempotent rejoin alike, exactly as
                // POST /v1/source-sessions answers 202 for both. Distinguishing them with a 201 would
                // have required this handler to read the registration first, and that read is a
                // different authorization action -- so registering would have started demanding an
                // observe scope it does not otherwise need, to decorate a status code.
                var status = authorizedApplication.registerLocalDeployment(context, deploymentId,
                        new java.io.ByteArrayInputStream(graph));
                deploymentJson(exchange, 200, status);
                return;
            }

            String[] segments = suffix.split("/");
            if (segments.length < 2 || segments.length > 3 || segments[1].isBlank()) {
                fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
                return;
            }
            String deploymentId = java.net.URLDecoder.decode(segments[1], java.nio.charset.StandardCharsets.UTF_8);

            if (segments.length == 2) {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    var status = authorizedApplication.localDeployment(context, deploymentId);
                    if (status.isEmpty()) {
                        fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
                        return;
                    }
                    exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
                    deploymentJson(exchange, 200, status.orElseThrow());
                    return;
                }
                if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                    awaitDeploymentCommand(exchange,
                            authorizedApplication.undeployLocalDeployment(context, deploymentId));
                    return;
                }
                exchange.getResponseHeaders().set("Allow", "GET, DELETE");
                fail(exchange, ErrorCode.METHOD_NOT_ALLOWED);
                return;
            }

            if (!method(exchange, "POST")) return;
            java.util.concurrent.CompletionStage<java.util.Optional<
                    ai.ravenroot.api.application.LocalDeploymentStatus>> command =
                    switch (segments[2]) {
                        case "start" -> authorizedApplication.startLocalDeployment(context, deploymentId);
                        case "stop" -> authorizedApplication.stopLocalDeployment(context, deploymentId);
                        case "restart" -> authorizedApplication.restartLocalDeployment(context, deploymentId);
                        default -> null;
                    };
            if (command == null) {
                fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
                return;
            }
            awaitDeploymentCommand(exchange, command);
        } catch (GraphMlParseException error) {
            graphMlError(exchange, error);
        } catch (GraphMlCompatibilityException error) {
            graphMlError(exchange, error);
        } catch (ai.ravenroot.core.runtime.GraphExecutionLimitException rejection) {
            failGraphExecutionLimit(exchange, rejection);
        } catch (ai.ravenroot.api.application.LocalDeploymentException refusal) {
            fail(exchange, refusal.reason()
                    == ai.ravenroot.api.application.LocalDeploymentException.Reason.GRAPH_CONFLICT
                    ? ErrorCode.CONFLICT : ErrorCode.INVALID_REQUEST);
        } catch (ai.ravenroot.api.deployment.DeploymentAdmissionException overCap) {
            fail(exchange, ErrorCode.REQUEST_LIMIT_EXCEEDED);
        } catch (UnsupportedOperationException unsupported) {
            fail(exchange, ErrorCode.EXECUTION_POLICY_UNSUPPORTED);
        } catch (java.util.concurrent.TimeoutException timeout) {
            fail(exchange, ErrorCode.REQUEST_INTERRUPTED);
        } catch (java.util.concurrent.ExecutionException failed) {
            ai.ravenroot.core.runtime.GraphExecutionLimitException limited = graphExecutionLimitIn(failed);
            if (limited != null) failGraphExecutionLimit(exchange, limited);
            else fail(exchange, ErrorCode.INTERNAL_ERROR);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(exchange, ErrorCode.REQUEST_INTERRUPTED);
        } catch (IllegalArgumentException invalid) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
        } catch (IllegalStateException conflict) {
            fail(exchange, ErrorCode.CONFLICT);
        }
    }

    /**
     * Waits, bounded, for one lifecycle command and answers with the deployment's truthful state.
     *
     * <p>The bound matches {@code DELETE /v1/source-sessions/{id}}'s: a little above the deployment's
     * own {@code DEFAULT_SOURCE_STOP_BOUND}, so a source that exhausts its own budget is reported as
     * an interrupted request rather than holding this connection open indefinitely. A start answers
     * at readiness rather than immediately, which is what makes "Stop reaches authoritative local
     * STOPPED" and its counterpart for start checkable by the caller instead of pollable.</p>
     */
    private void awaitDeploymentCommand(HttpExchange exchange,
                                        java.util.concurrent.CompletionStage<java.util.Optional<
                                                ai.ravenroot.api.application.LocalDeploymentStatus>> command)
            throws IOException, InterruptedException, java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
        var settled = command.toCompletableFuture().get(35, java.util.concurrent.TimeUnit.SECONDS);
        if (settled.isEmpty()) {
            fail(exchange, ErrorCode.UNKNOWN_RESOURCE);
            return;
        }
        deploymentJson(exchange, 200, settled.orElseThrow());
    }

    private String deploymentListJson(List<ai.ravenroot.api.application.LocalDeploymentStatus> statuses) {
        return "{\"scope\":\"" + ai.ravenroot.api.application.LocalDeploymentStatus.SCOPE
                + "\",\"deployments\":[" + statuses.stream().map(RavenrootServer::deploymentObject)
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    private void deploymentJson(HttpExchange exchange, int statusCode,
                                ai.ravenroot.api.application.LocalDeploymentStatus status) throws IOException {
        json(exchange, statusCode, deploymentObject(status));
    }

    private static String deploymentObject(ai.ravenroot.api.application.LocalDeploymentStatus status) {
        String diagnostic = status.diagnostic().map(value -> "\"" + escape(value) + "\"").orElse("null");
        return "{\"deploymentId\":\"" + escape(status.deploymentId())
                + "\",\"state\":\"" + status.state().name()
                + "\",\"sourceCount\":" + status.sourceCount()
                + ",\"scope\":\"" + ai.ravenroot.api.application.LocalDeploymentStatus.SCOPE
                + "\",\"diagnostic\":" + diagnostic + "}";
    }

    private void sourceSessionJson(HttpExchange exchange, int statusCode,
                                   ai.ravenroot.api.application.SourceSessionStatus status) throws IOException {
        String diagnostic = status.diagnostic().map(value -> "\"" + escape(value) + "\"").orElse("null");
        json(exchange, statusCode, "{\"sessionId\":\"" + escape(status.sessionId())
                + "\",\"state\":\"" + status.state().name()
                + "\",\"sourceCount\":" + status.sourceCount()
                + ",\"scope\":\"" + ai.ravenroot.api.application.SourceSessionStatus.SCOPE
                + "\",\"diagnostic\":" + diagnostic + "}");
    }

    /**
     * Reads the submission in whichever representation the caller chose (API-01).
     *
     * <p>Two representations, one use case. The legacy one — GraphML in the body, text in
     * {@code ?payload=} — is untouched, down to the empty-string default, because the UI and every
     * existing client send exactly that and remain compatible. The structured
     * one is selected only by the {@link StructuredSubmission#MEDIA_TYPE} content type, so a caller
     * opts in explicitly and no existing request can be reinterpreted by accident.</p>
     *
     * <p>Both converge on one {@link PayloadEnvelope} before anything is started, which is the point:
     * the reference monitor, the audit record and the engine see one contract, and the difference
     * between a pre-API-01 client and a current one stops at this method.</p>
     */
    private void submitExecution(HttpExchange exchange,
                                 ai.ravenroot.api.application.ExecutionPolicy policy) throws IOException {
        try {
            PayloadEnvelope payload;
            byte[] graph;
            if (StructuredSubmission.selects(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                var structured = readStructuredSubmission(exchange);
                if (structured == null) {
                    return;
                }
                graph = structured.graphMl();
                payload = structured.payload();
            } else {
                try (var input = exchange.getRequestBody()) {
                    graph = readGraphMlRequest(exchange, input);
                }
                if (graph == null) {
                    return;
                }
                payload = PayloadEnvelope.legacyText(query(exchange).getOrDefault("payload", ""));
            }
            var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
            // Before an execution id is minted and before the engine sees the
            // document: a graph naming a stored credential this caller does not own is refused here,
            // because this is the last point at which the caller's identity and the document are in
            // the same method. Below this line the identity narrows to a SecurityContext and the
            // credential reference becomes a bare string with no owner attached -- see
            // CredentialAdmission for the full reasoning and for what it deliberately does not cover.
            credentialAdmission.require(context, graph);
            var submission = authorizedApplication.startGraphMl(context,
                    new java.io.ByteArrayInputStream(graph), payload, payloadLimits, policy);
            json(exchange, 202, "{\"processInstanceId\":\"" + submission.processInstanceId()
                    + "\",\"traversalId\":\"" + submission.traversalId()
                    + "\",\"executionId\":\"" + submission.executionId()
                    + "\",\"graphVersion\":\"" + escape(submission.graphVersion())
                    + "\",\"executionPolicy\":\"" + policy
                    + "\",\"payloadContract\":\"" + escape(payload.contract())
                    + "\",\"payloadKind\":\"" + payload.kind()
                    + "\",\"payloadSchema\":\"" + escape(payload.schema())
                    + "\",\"payloadSchemaVersion\":\"" + escape(payload.schemaVersion()) + "\"}");
        } catch (ai.ravenroot.server.credential.CredentialAdmission.NotYours refused) {
            // 403 and not 404: the caller is authenticated and the resource exists as far as they are
            // concerned -- it is the submission that is not permitted. Deliberately the same answer
            // whether the reference belongs to somebody else or to nobody, so the pair cannot be used
            // to enumerate which references exist.
            fail(exchange, ErrorCode.ACCESS_DENIED);
        } catch (GraphMlParseException error) {
            graphMlError(exchange, error);
        } catch (GraphMlCompatibilityException error) {
            graphMlError(exchange, error);
        } catch (PayloadException rejection) {
            failPayload(exchange, rejection);
        } catch (ai.ravenroot.core.runtime.GraphExecutionLimitException rejection) {
            failGraphExecutionLimit(exchange, rejection);
        } catch (UnsupportedOperationException unsupportedPolicy) {
            fail(exchange, ErrorCode.EXECUTION_POLICY_UNSUPPORTED);
        } catch (IllegalArgumentException error) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
        } catch (IllegalStateException error) {
            fail(exchange, ErrorCode.CONFLICT);
        }
    }

    private void failGraphExecutionLimit(HttpExchange exchange,
                                         ai.ravenroot.core.runtime.GraphExecutionLimitException rejection)
            throws IOException {
        fail(exchange, ErrorCode.GRAPH_EXECUTION_RESOURCE_LIMIT.status(),
                ErrorEnvelope.ofServerCode(rejection.reason().publicCode(),
                        ErrorCode.GRAPH_EXECUTION_RESOURCE_LIMIT,
                        AuthenticatedPrincipalAttribute.requestId(exchange)));
    }

    private static ai.ravenroot.core.runtime.GraphExecutionLimitException graphExecutionLimitIn(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ai.ravenroot.core.runtime.GraphExecutionLimitException limited) return limited;
            current = current.getCause();
        }
        return null;
    }

    /**
     * {@code GET /v1/executions/{id}} makes an execution's result observable.
     *
     * <p>Submission answers 202 with identifiers, while this endpoint exposes the result that the
     * process computes and indexes by execution id.</p>
     *
     * <h2>Four answers, none of them an empty body</h2>
     * <p>The switch is exhaustive over a sealed type, so a fifth case cannot be added upstream
     * without this method failing to compile — which is the point. {@code Found} is 200 and always
     * carries {@code defaultedNodes}, including when it is empty, so a client can tell "no nodes
     * defaulted" from "this server does not report defaulting". {@code Expired} is 410
     * {@code EXECUTION_RESULT_EXPIRED} rather than 404 because the execution provably ran and its
     * terminal status is still known; answering 404 there would tell a caller its run never happened.
     * {@code Redacted} is a distinct 410, {@code EXECUTION_RESULT_REDACTED}: the execution provably
     * ran, but its payload was never retained in the first place, rather than having aged out after
     * being retained. The two are different facts calling for different operator responses — an
     * expired result is a retention policy working as configured, a redacted one is either a size cap
     * an operator can raise or a node returning a value no remote adapter could ever persist — so
     * they carry different {@code code}s and {@code redactedExecutionJson}'s body adds
     * {@code payloadState} to say which of the two. {@code Unknown} is 404 and covers a
     * nonexistent id, another tenant's id and a fully evicted one alike — see
     * {@code ExecutionLookup.Unknown} for why those three must not be distinguishable.</p>
     *
     * <p>The tenant is never read from the path or the query. It reaches the registry only through
     * {@code AuthorizedRavenrootApplication.executionResult}, from the authenticated
     * {@code RequestContext}, and the registry is keyed by tenant and id together — so this handler
     * has no way to express a cross-tenant read even incorrectly.</p>
     */
    private void readExecution(HttpExchange exchange, String rawId) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        java.util.UUID executionId;
        try {
            executionId = java.util.UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            // Not UNKNOWN_EXECUTION: the caller's request is malformed, and answering 404 would
            // claim this server looked for the id and did not find it.
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        try {
            var lookup = authorizedApplication.executionResult(
                    AuthenticatedPrincipalAttribute.requestContext(exchange), executionId);
            switch (lookup) {
                case ai.ravenroot.api.application.ExecutionLookup.Found found ->
                        json(exchange, 200, executionOutcomeJson(found.outcome()));
                case ai.ravenroot.api.application.ExecutionLookup.Expired expired ->
                        json(exchange, ErrorCode.EXECUTION_RESULT_EXPIRED.status(),
                                expiredExecutionJson(expired,
                                        AuthenticatedPrincipalAttribute.requestId(exchange)));
                // Its own wire code and its own body field, distinct from Expired above: this
                // execution's payload was refused at write time -- for size, or because it does not
                // project onto the closed payload model -- rather than having aged out after being
                // retained. See ErrorCode#EXECUTION_RESULT_REDACTED and redactedExecutionJson's own
                // Javadoc for why the two are told apart rather than collapsed.
                case ai.ravenroot.api.application.ExecutionLookup.Redacted redacted ->
                        json(exchange, ErrorCode.EXECUTION_RESULT_REDACTED.status(),
                                redactedExecutionJson(redacted,
                                        AuthenticatedPrincipalAttribute.requestId(exchange)));
                case ai.ravenroot.api.application.ExecutionLookup.Unknown unknown ->
                        fail(exchange, ErrorCode.UNKNOWN_EXECUTION);
            }
        } catch (PayloadException rejection) {
            // A terminal payload is engine output, but it is still untrusted structure. In particular,
            // a NodeFailurePayload can carry the failing node's input, which may have been produced by
            // another node and never crossed an HTTP boundary. Refuse it through the existing typed,
            // non-reflective payload envelope: never let rendering abort the exchange or fall back to
            // an arbitrary object's toString().
            failPayload(exchange, rejection);
        }
    }

    /**
     * {@code GET /v1/executions/live} lists this tenant's live executions, read directly
     * from runtime bookkeeping so a stalled traversal that has stopped emitting still appears.
     *
     * <p>The tenant is never read from the path or the query, exactly like {@link #readExecution}:
     * it reaches {@link ai.ravenroot.api.application.AuthorizedRavenrootApplication#liveExecutions}
     * only through the authenticated {@code RequestContext}, so this handler has no way to express a
     * cross-tenant read even incorrectly. A tenant with nothing running gets back an empty array --
     * the same shape a tenant with something running under different authorization would get if
     * denied -- so the response cannot be used to learn that another tenant has work in flight.</p>
     */
    private void listLiveExecutions(HttpExchange exchange) throws IOException {
        var executions = authorizedApplication.liveExecutions(
                AuthenticatedPrincipalAttribute.requestContext(exchange));
        String body = executions.stream().map(RavenrootServer::liveExecutionJson)
                .collect(java.util.stream.Collectors.joining(",", "{\"executions\":[", "]}"));
        json(exchange, 200, body);
    }

    /**
     * {@code paused} is emitted on every row, including when it is {@code false}, for the reason
     * {@code defaultedNodes} is always emitted on a result: a field that appears only when it is true
     * cannot distinguish "this execution is not holding" from "this server does not report holds",
     * and a client that cannot tell those apart has to assume the worse of the two on every row.
     */
    private static String liveExecutionJson(ai.ravenroot.api.application.LiveExecution execution) {
        return "{\"processInstanceId\":\"" + execution.processInstanceId()
                + "\",\"traversalId\":\"" + execution.traversalId()
                + "\",\"executionId\":\"" + execution.executionId()
                + "\",\"graphVersion\":\"" + escape(execution.graphVersion())
                + "\",\"startedAt\":\"" + execution.startedAt()
                + "\",\"paused\":" + execution.paused() + "}";
    }

    /** Query parameters {@link #listProcessInventory} recognises; anything else is refused rather
     * than silently ignored -- see that method's own Javadoc for why. Names match the response's own
     * field names ({@code ownerWorkerId}, {@code deploymentId}) exactly, on purpose: an operator who
     * writes the parameter name the response just showed them must filter by it, never fall through
     * to an unfiltered page that reads as "all this work belongs to that value". */
    private static final Set<String> INVENTORY_QUERY_PARAMETERS =
            Set.of("status", "ownerWorkerId", "deploymentId", "includeTerminal", "limit", "cursor");

    /**
     * {@code GET /v1/executions/inventory}: one page of this tenant's durable, authoritative
     * process inventory -- what API, CLI, UI, audit and recovery callers are meant to share instead of
     * each maintaining an incompatible idea of what exists (acceptance criterion 7). Distinct from
     * {@link #listLiveExecutions}: this reads the store's own persisted record, not runtime bookkeeping,
     * and it survives a restart -- see {@link ai.ravenroot.api.application.RavenrootApplication#processInventoryAvailable()}
     * for the full distinction between the two.
     *
     * <p>Query parameters, every one optional, and named exactly like the fields the response itself
     * carries: {@code status} (comma-separated {@link ai.ravenroot.api.application.ProcessInstanceStatus}
     * names), {@code ownerWorkerId} (lease holder worker id), {@code deploymentId} (hosting deployment
     * id), {@code includeTerminal} ({@code true} to include {@code COMPLETED}/{@code FAILED} rows,
     * which are excluded by default), {@code limit} and {@code cursor} (opaque, from a previous page's
     * {@code nextCursor}). A parameter outside this set is refused as 400 rather than dropped: a typo
     * or a stale name silently answering with an unfiltered page would read as "everything belongs to
     * what I asked for", the wrong direction for a filter to fail in. A <em>recognised</em> name
     * carrying a blank value is refused on the same ground and is the likelier accident of the two:
     * {@code ?ownerWorkerId=} is what a script emits from a variable it never set, and dropping it
     * would answer that same unfiltered page. The response body always carries
     * {@code retainedFrom}, the tenant's inventory retention floor, so a caller can tell an instance
     * that never existed from one that expired by policy without a second request, and
     * {@code maxPageSize}, this deployment's declared page-size bound
     * ({@link ai.ravenroot.api.application.RavenrootApplication#processInventoryMaxPageSize()}), so a
     * caller paginating its own loop can read the bound instead of discovering it by bisection.</p>
     *
     * <p>501 {@link ErrorCode#PROCESS_INVENTORY_UNAVAILABLE} when this deployment has no
     * inventory-capable store composed at all -- a fact about the deployment, not the request, so it
     * is checked before the request is otherwise parsed. Malformed query input (an unknown parameter
     * name, an unrecognised status name, a non-numeric limit, a limit or cursor the store itself
     * rejects) answers 400 {@link ErrorCode#INVALID_REQUEST} -- the store's own {@code InvalidRequest}
     * classification is translated here rather than surfaced as 500, since it is a fact about the
     * caller's request.</p>
     */
    private void listProcessInventory(HttpExchange exchange) throws IOException {
        var requestContext = AuthenticatedPrincipalAttribute.requestContext(exchange);
        if (!authorizedApplication.processInventoryAvailable()) {
            fail(exchange, ErrorCode.PROCESS_INVENTORY_UNAVAILABLE);
            return;
        }
        var parameters = query(exchange);
        if (!INVENTORY_QUERY_PARAMETERS.containsAll(parameters.keySet())) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        var builder = ai.ravenroot.api.persistence.ProcessInventoryQuery.builder();
        // A recognised name carrying a blank value is refused for the same reason an unrecognised name
        // is, and it is the likelier accident of the two: `?ownerWorkerId=` is what a script emits from
        // a variable it never set. Dropping it would answer with an unfiltered page, which a caller
        // reads as "everything matches what I asked for" -- the wrong direction for a filter to fail
        // in, and the one this handler's own contract says must not happen.
        for (var parameter : parameters.entrySet()) {
            if (parameter.getValue() == null || parameter.getValue().isBlank()) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
        }
        String rawStatus = parameters.get("status");
        if (rawStatus != null) {
            for (String token : rawStatus.split(",", -1)) {
                if (token.isBlank()) {
                    // `status=RUNNING,,FAILED` is a malformed list, not a two-element one.
                    fail(exchange, ErrorCode.INVALID_REQUEST);
                    return;
                }
                try {
                    builder.status(ai.ravenroot.api.application.ProcessInstanceStatus.valueOf(token.trim()));
                } catch (IllegalArgumentException unknownStatus) {
                    fail(exchange, ErrorCode.INVALID_REQUEST);
                    return;
                }
            }
        }
        String owner = parameters.get("ownerWorkerId");
        if (owner != null) {
            builder.ownedBy(owner);
        }
        String deployment = parameters.get("deploymentId");
        if (deployment != null) {
            builder.hostedBy(deployment);
        }
        String rawIncludeTerminal = parameters.get("includeTerminal");
        if (rawIncludeTerminal != null) {
            if (!"true".equalsIgnoreCase(rawIncludeTerminal) && !"false".equalsIgnoreCase(rawIncludeTerminal)) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
            builder.includeTerminal(Boolean.parseBoolean(rawIncludeTerminal));
        }
        String rawCursor = parameters.get("cursor");
        if (rawCursor != null) {
            builder.cursor(rawCursor);
        }
        String rawLimit = parameters.get("limit");
        if (rawLimit != null) {
            try {
                builder.limit(Integer.parseInt(rawLimit.trim()));
            } catch (NumberFormatException notANumber) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
        }
        try {
            var page = authorizedApplication.processInventory(requestContext, builder.build());
            json(exchange, 200, processInventoryPageJson(page, authorizedApplication.processInventoryMaxPageSize()));
        } catch (ai.ravenroot.api.persistence.ExecutionStoreException storeFailure) {
            if (storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.InvalidRequest) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
            throw storeFailure;
        }
    }

    private static String processInventoryPageJson(ai.ravenroot.api.persistence.ProcessInventoryPage page,
                                                    int maxPageSize) {
        var body = new StringBuilder(256);
        body.append("{\"items\":[");
        var items = page.items();
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                body.append(',');
            }
            body.append(processInventoryEntryJson(items.get(index)));
        }
        body.append("],\"nextCursor\":")
                .append(page.nextCursor().map(cursor -> "\"" + escape(cursor) + "\"").orElse("null"))
                .append(",\"retainedFrom\":\"").append(page.retainedFrom()).append('"')
                .append(",\"maxPageSize\":").append(maxPageSize).append('}');
        return body.toString();
    }

    /** Bounded, non-secret fields only -- no payloads, no opaque blobs. */
    private static String processInventoryEntryJson(ai.ravenroot.api.persistence.ProcessInventoryEntry entry) {
        return "{\"tenantId\":\"" + escape(entry.key().tenantId())
                + "\",\"processInstanceId\":\"" + entry.key().processInstanceId()
                + "\",\"status\":\"" + entry.status() + "\""
                // Beside status, following the same rule as executionOutcomeJson's own
                // terminationReason: always present, including as JSON null, so the durable inventory
                // does not misreport a cancellation as an ordinary failure after a restart, once live
                // in-memory state is gone and this row is the only place left to ask.
                + ",\"terminationReason\":" + (entry.terminationReason() == null ? "null"
                        : "\"" + entry.terminationReason() + "\"")
                + ",\"cancelled\":" + entry.cancelled()
                + ",\"disposition\":\"" + entry.disposition()
                + "\",\"revision\":" + entry.revision()
                + ",\"lifecycleGeneration\":" + entry.lifecycleGeneration()
                + ",\"graphVersion\":\"" + escape(entry.graphVersionPin().reference())
                + "\",\"deploymentId\":" + optionalStringJson(entry.deploymentId())
                + ",\"workloadId\":" + optionalStringJson(entry.workloadId())
                + ",\"correlationId\":" + optionalStringJson(entry.correlationId())
                + ",\"ownerWorkerId\":" + optionalStringJson(entry.ownerWorkerId())
                + ",\"fencingToken\":" + entry.fencingToken()
                + ",\"leaseExpiresAt\":" + entry.leaseExpiresAt().map(instant -> "\"" + instant + "\"").orElse("null")
                + ",\"traversalCount\":" + entry.traversalCount()
                + ",\"createdAt\":\"" + entry.createdAt()
                + "\",\"updatedAt\":\"" + entry.updatedAt() + "\""
                + ",\"retainedUntil\":" + entry.retainedUntil().map(instant -> "\"" + instant + "\"").orElse("null")
                + "}";
    }

    private static String optionalStringJson(java.util.Optional<String> value) {
        return value.map(present -> "\"" + escape(present) + "\"").orElse("null");
    }

    /**
     * {@code GET /v1/executions/{id}/manifest}: the identity of the dependency set one process
     * instance was accepted against, and whether this runtime still resolves it.
     *
     * <p><strong>Identity and state, never the pinned configuration and never a value from it.</strong>
     * The response carries the manifest's format version, its digest, the graph content address it
     * pins, when it was pinned, and a compatibility verdict whose differences are reported as
     * dimension names alone. It carries no capability set, no execution limit, no package identity
     * and no package count: all of those describe the deployment rather than the caller's execution,
     * an authenticated tenant has no claim on them, and none of them is needed to act on the verdict.
     * The comparison's own values remain available to an operator through the server-side diagnostic
     * a refusal raises; see {@link ai.ravenroot.api.persistence.ExecutionManifestDifference}.</p>
     *
     * <p>Every value in the response is a digest, a closed enum name or a bounded token, because that
     * is all a manifest can hold; there is no field a credential could have reached, so no redaction
     * pass stands between this projection and the record it renders.</p>
     *
     * <p>404 {@link ErrorCode#UNKNOWN_PROCESS_INSTANCE} when no manifest is pinned for the instance,
     * when it belongs to another tenant, and when it was accepted before manifests were recorded —
     * all three indistinguishable by design, exactly as the traversal listing already is for its own
     * id space. 501 {@link ErrorCode#PROCESS_INVENTORY_UNAVAILABLE} when this host composes no
     * manifest store at all, so an absent route is never mistaken for an absent manifest.</p>
     */
    private void readExecutionManifest(HttpExchange exchange, String rawId) throws IOException {
        var principal = AuthenticatedPrincipalAttribute.require(exchange);
        if (executionManifests == null) {
            fail(exchange, ErrorCode.PROCESS_INVENTORY_UNAVAILABLE);
            return;
        }
        java.util.UUID processInstanceId;
        try {
            processInstanceId = java.util.UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        // The tenant comes from the authenticated principal and participates in the key, so a read
        // scoped to another tenant's instance reports absence rather than a denial. That is the whole
        // authorization check for this route: the store cannot be used as a cross-tenant oracle
        // because the address it is asked for is one this caller could only have guessed.
        var key = new ai.ravenroot.api.persistence.ExecutionKey(principal.tenantId(), processInstanceId);
        try {
            var stored = executionManifests.store().load(key).toCompletableFuture().join();
            var report = executionManifests.describe(stored,
                    ai.ravenroot.api.application.ExecutionPolicy.STANDARD);
            json(exchange, 200, executionManifestJson(stored, report));
        } catch (java.util.concurrent.CompletionException wrapped) {
            var failure = ai.ravenroot.api.persistence.ExecutionManifestStoreException.unwrap(wrapped);
            if (failure == null) {
                throw wrapped;
            }
            failExecutionManifest(exchange, failure.failure());
        } catch (ai.ravenroot.api.persistence.ExecutionManifestStoreException failure) {
            failExecutionManifest(exchange, failure.failure());
        }
    }

    /**
     * Maps a manifest-store failure onto the two outcomes this route distinguishes.
     *
     * <p>An absent manifest and one that no longer verifies are deliberately <em>not</em> the same
     * answer: the first is an execution this deployment never recorded, the second is a stored record
     * an operator has to investigate. Collapsing them would hide a corrupted row behind a 404.</p>
     */
    private void failExecutionManifest(HttpExchange exchange,
                                       ai.ravenroot.api.persistence.ExecutionManifestStoreFailure failure)
            throws IOException {
        if (failure instanceof ai.ravenroot.api.persistence.ExecutionManifestStoreFailure.NotFound) {
            fail(exchange, ErrorCode.UNKNOWN_PROCESS_INSTANCE);
            return;
        }
        fail(exchange, ErrorCode.PROCESS_INVENTORY_UNAVAILABLE);
    }

    /**
     * The caller's own execution identity and a verdict, and nothing about this deployment.
     *
     * <p><strong>Dimension names only.</strong> A difference also carries the two values that
     * disagree, and those describe the deployment rather than the execution: a node-package
     * difference's values are an installed package's identity, and the engine, store and limits
     * dimensions compare digests of operator configuration. Rendering them here would answer "what is
     * installed on your servers" to any authenticated tenant that submitted one graph. The dimension
     * alone answers the question this route exists for — can my execution still be reproduced, and
     * along which axis has it stopped being reproducible — and the dimension vocabulary already
     * distinguishes a package that is missing from one that changed.</p>
     *
     * <p>The count of pinned node packages is left out for the same reason: how many packages a
     * deployment has installed is an inventory fact, and a number is still an answer.</p>
     *
     * <p>What remains is the caller's: the format version and digest of its own manifest, the graph
     * address it already submitted or was handed back, when it was pinned, and the verdict.</p>
     */
    private static String executionManifestJson(
            ai.ravenroot.api.persistence.StoredExecutionManifest stored,
            ai.ravenroot.api.persistence.ExecutionManifestCompatibility report) {
        var manifest = stored.manifest();
        var differences = report.dimensions().stream()
                .map(dimension -> "\"" + dimension + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"manifestFormatVersion\":" + manifest.formatVersion()
                + ",\"manifestDigest\":\"" + stored.digest().value()
                + "\",\"graphVersion\":\"" + manifest.graphContentId().value()
                + "\",\"graphId\":\"" + escape(manifest.graphIdentity().graphId())
                + "\",\"graphVersionId\":\"" + escape(manifest.graphIdentity().versionId())
                + "\",\"pinnedAt\":\"" + manifest.pinnedAt()
                + "\",\"compatible\":" + report.compatible()
                + ",\"incompatibleDimensions\":" + differences
                + ",\"dimensionsTruncated\":" + report.truncated()
                + "}";
    }

    /**
     * {@code GET /v1/executions/{id}/traversals}: the durable inventory's traversal rows for
     * one process instance.
     *
     * <p><strong>{@code id} names a process instance, not a traversal/execution id.</strong> Every
     * other sub-route under {@code /v1/executions} addresses a traversal ({@code GET
     * /v1/executions/{id}} and the cancel/pause/resume trio, where {@code id == executionId ==
     * traversalId}). This route is keyed differently on purpose: the durable inventory's traversal
     * listing is {@link ai.ravenroot.api.persistence.ExecutionStore#listTraversals} scoped by
     * {@link ai.ravenroot.api.persistence.ExecutionKey}, whose second component is
     * {@code processInstanceId} -- the durable aggregate's own identity -- and a process instance can
     * contain more than one traversal, so a traversal id could not address "this instance's
     * traversals" at all. The two id spaces are both UUIDs and are not interchangeable; a client that
     * passes a traversal id here receives 404, indistinguishable from an id that never existed.</p>
     *
     * <p>404 {@link ErrorCode#UNKNOWN_PROCESS_INSTANCE} when the instance is absent, belongs to
     * another tenant, or was purged past its terminal retention window -- all three
     * indistinguishable by design, exactly as {@code GET /v1/executions/{id}} already is for its own
     * id space. 501 {@link ErrorCode#PROCESS_INVENTORY_UNAVAILABLE} when no inventory-capable store
     * is composed at all.</p>
     *
     * <p>The response also carries {@code retainedFrom}, this tenant's inventory retention floor,
     * exactly like {@code GET /v1/executions/inventory} does -- an operator diagnosing an absence
     * needs it on whichever of the two listings they happen to be holding, not only on one of them.</p>
     */
    private void readProcessInstanceTraversals(HttpExchange exchange, String rawId) throws IOException {
        var requestContext = AuthenticatedPrincipalAttribute.requestContext(exchange);
        if (!authorizedApplication.processInventoryAvailable()) {
            fail(exchange, ErrorCode.PROCESS_INVENTORY_UNAVAILABLE);
            return;
        }
        java.util.UUID processInstanceId;
        try {
            processInstanceId = java.util.UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        try {
            var traversals = authorizedApplication.processInstanceTraversals(requestContext, processInstanceId);
            var retainedFrom = authorizedApplication.processInventoryRetainedFrom(requestContext);
            var body = traversals.stream().map(RavenrootServer::traversalInventoryEntryJson)
                    .collect(java.util.stream.Collectors.joining(",", "{\"traversals\":[", "],\"retainedFrom\":\""
                            + retainedFrom + "\"}"));
            json(exchange, 200, body);
        } catch (ai.ravenroot.api.persistence.ExecutionStoreException storeFailure) {
            if (storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.NotFound) {
                fail(exchange, ErrorCode.UNKNOWN_PROCESS_INSTANCE);
                return;
            }
            throw storeFailure;
        }
    }

    /** Bounded, non-secret fields only -- no payloads, no opaque blobs. */
    private static String traversalInventoryEntryJson(ai.ravenroot.api.persistence.TraversalInventoryEntry entry) {
        return "{\"traversalId\":\"" + entry.traversalId()
                + "\",\"position\":" + entry.position()
                + ",\"ingressNodeId\":\"" + escape(entry.ingressNodeId())
                + "\",\"status\":\"" + entry.status() + "\""
                + ",\"terminationReason\":" + (entry.terminationReason() == null ? "null"
                        : "\"" + entry.terminationReason() + "\"")
                + ",\"cancelled\":" + entry.cancelled()
                + ",\"disposition\":\"" + entry.disposition()
                + "\",\"invocationCount\":" + entry.invocationCount()
                + ",\"parkedAttemptCount\":" + entry.parkedAttemptCount()
                + "}";
    }

    /**
     * Renders an outcome, routing the payload through the payload model rather than {@code toString}.
     *
     * <p>Same treatment the program-artifact test path already gives untrusted node output, and for
     * the same reason: a result payload is whatever the graph's nodes built, so bounding its depth,
     * breadth and size before serialising it is the difference between a classified rejection and a
     * best-effort dump of an arbitrary JVM object.</p>
     *
     * <p>{@code defaultedNodes} is always present, never omitted when empty. An absent field would
     * be indistinguishable from a server that does not report defaulting, which is exactly the
     * ambiguity that let a degraded run look like a clean one.</p>
     *
     * <p>{@code handledFailureNodes} and {@code handledFailure} follow that rule for the same reason
     * because a run whose node crashed and whose author routed around it completes as {@code
     * COMPLETED}, so these two fields are the only thing in this body that distinguishes it from a
     * clean run. Omitting them when empty would make "nothing failed" and "this server does not
     * report failures" the same response, and the caller reading it cannot tell which server it is
     * talking to.</p>
     *
     * <p>{@code bypassedNodes} is always present, and <b>membership in it says nothing
     * about the run as a whole.</b> Three decisions put a node there — a {@code mode=test}
     * submission, an individual edge carrying the {@code passthrough} command, and the graph author's
     * own per-node {@code execution.bypass} flag — and the array does not distinguish them. A client
     * that needs the cause reads the {@code NODE_BYPASSED} events, whose {@code publicReason} is
     * {@code command.passthrough} or {@code authored}; it must not infer "this was a test run" from a
     * non-empty array here, because a fully executing {@code mode=run} submission populates it
     * whenever the author left a node switched off.</p>
     *
     * <p>{@code untakenEdges} is always present for the same reason, but is not a node list
     * like the four above it: each entry names one outgoing edge of a node this run bypassed --
     * {@code "<source>-><target> [outcome=<outcome>]"} -- that the node's own hardcoded {@code
     * "continue"} outcome could never select. It inherits the ambiguity above exactly: the enumeration
     * above. All three causes feed this field identically.</p>
     */
    private String executionOutcomeJson(ai.ravenroot.api.application.ExecutionOutcome outcome) {
        var body = new StringBuilder("{\"processInstanceId\":\"").append(outcome.processInstanceId())
                .append("\",\"traversalId\":\"").append(outcome.traversalId())
                .append("\",\"executionId\":\"").append(outcome.executionId())
                .append("\",\"status\":\"").append(outcome.status()).append('"')
                // Beside status rather than inside it: status stays the durable lifecycle value a
                // consumer already switches over, and this qualifies it. Always present -- including
                // as JSON null when absent -- for the same reason degraded and handledFailure are
                // always present: a field a client can see is always missing must never be
                // indistinguishable from a field this server does not report at all. A cancelled
                // execution reports status FAILED here; read terminationReason beside it, never
                // status alone -- see ExecutionOutcome's own Javadoc.
                .append(",\"terminationReason\":")
                .append(outcome.terminationReason() == null ? "null"
                        : "\"" + outcome.terminationReason() + "\"")
                .append(",\"cancelled\":").append(outcome.cancelled())
                .append(",\"paused\":").append(outcome.paused())
                .append(",\"degraded\":").append(outcome.degraded())
                .append(",\"handledFailure\":").append(outcome.handledFailure())
                .append(",\"visitedNodes\":").append(stringArrayJson(outcome.visitedNodes()))
                .append(",\"defaultedNodes\":").append(stringArrayJson(outcome.defaultedNodes()))
                .append(",\"bypassedNodes\":").append(stringArrayJson(outcome.bypassedNodes()))
                .append(",\"handledFailureNodes\":").append(stringArrayJson(outcome.handledFailureNodes()))
                .append(",\"untakenEdges\":").append(stringArrayJson(outcome.untakenEdges()));
        if (outcome.payload() != null) {
            var value = PayloadValue.fromJava(outcome.payload(), payloadLimits);
            body.append(",\"payload\":").append(PayloadJson.write(value))
                    .append(",\"payloadEnvelope\":").append(PayloadEnvelope.of(value).toJson());
        }
        return body.append('}').toString();
    }

    /**
     * The 410 body for {@link ai.ravenroot.api.application.ExecutionLookup.Expired}.
     *
     * <p>{@link ErrorEnvelope} is deliberately closed -- five fixed fields, no public constructor that
     * accepts arbitrary structure -- because widening it would let some future caller smuggle
     * unbounded text through every error response this server sends. This response is not that: it is
     * one specific, additive endpoint answer, built directly rather than by asking the closed envelope
     * to carry fields it was never meant to. Its first five members reproduce {@link ErrorEnvelope}'s
     * own {@code toJson()} shape byte for byte, so an existing client reading only those fields sees no
     * difference; {@code status} and {@code terminationReason} are new members appended after them.</p>
     *
     * <p>{@code status} and {@code terminationReason} must be read together, exactly as
     * {@link ai.ravenroot.api.application.ExecutionLookup.Expired}'s own Javadoc says: a tombstone
     * reporting {@code FAILED} with no reason is an ordinary failure, and the same {@code FAILED} with
     * {@code terminationReason == "CANCELLED"} is a deliberate stop. Dropping the reason here --
     * exactly what the pre-existing code did by discarding {@code Expired} entirely in favour of a bare
     * {@link ErrorCode#EXECUTION_RESULT_EXPIRED} -- would make "visible after eviction" false at this
     * boundary even though the tombstone carries the answer.</p>
     */
    private static String expiredExecutionJson(ai.ravenroot.api.application.ExecutionLookup.Expired expired,
                                               String correlationId) {
        ErrorEnvelope envelope = ErrorEnvelope.of(ErrorCode.EXECUTION_RESULT_EXPIRED, correlationId);
        var body = new StringBuilder("{\"contract\":\"").append(escape(envelope.contract())).append('"')
                .append(",\"code\":\"").append(escape(envelope.code())).append('"')
                .append(",\"message\":\"").append(escape(envelope.message())).append('"')
                .append(",\"error\":\"").append(escape(envelope.message())).append('"')
                .append(",\"correlationId\":\"").append(escape(envelope.correlationId())).append('"')
                .append(",\"status\":\"").append(expired.status()).append('"')
                .append(",\"terminationReason\":")
                .append(expired.terminationReason() == null ? "null" : "\"" + expired.terminationReason() + "\"")
                .append(",\"cancelled\":").append(expired.cancelled());
        return body.append('}').toString();
    }

    /**
     * The 410 body for {@link ai.ravenroot.api.application.ExecutionLookup.Redacted}.
     *
     * <p>The same additive shape {@link #expiredExecutionJson} uses, and for the same reason: the
     * first five members reproduce {@link ErrorEnvelope}'s own {@code toJson()} byte for byte, and
     * {@code status}, {@code terminationReason} and {@code cancelled} carry the identical meaning
     * {@code expiredExecutionJson} gives them -- read {@code status} and {@code terminationReason}
     * together, never {@code status} alone, or a cancelled execution reads as an incident.</p>
     *
     * <p>{@code payloadState} is the member this body adds and {@code expiredExecutionJson} does not:
     * one of {@code WITHHELD} (the encoded projection exceeded the store's byte cap) or
     * {@code UNCONVERTIBLE} (the value does not project onto the closed payload model at all) --
     * {@link ai.ravenroot.api.application.ExecutionLookup.Redacted}'s canonical constructor refuses
     * every other {@link ai.ravenroot.api.persistence.ResultPayloadState}, so those are the only two
     * this method ever renders. A caller reading it can tell "raise the configured cap" from "this
     * node returns something no remote adapter could ever persist", which is exactly the distinction
     * {@code EXECUTION_RESULT_EXPIRED} alone could not make -- see
     * {@link ErrorCode#EXECUTION_RESULT_REDACTED}'s own Javadoc.</p>
     */
    private static String redactedExecutionJson(ai.ravenroot.api.application.ExecutionLookup.Redacted redacted,
                                                String correlationId) {
        ErrorEnvelope envelope = ErrorEnvelope.of(ErrorCode.EXECUTION_RESULT_REDACTED, correlationId);
        var body = new StringBuilder("{\"contract\":\"").append(escape(envelope.contract())).append('"')
                .append(",\"code\":\"").append(escape(envelope.code())).append('"')
                .append(",\"message\":\"").append(escape(envelope.message())).append('"')
                .append(",\"error\":\"").append(escape(envelope.message())).append('"')
                .append(",\"correlationId\":\"").append(escape(envelope.correlationId())).append('"')
                .append(",\"status\":\"").append(redacted.status()).append('"')
                .append(",\"terminationReason\":")
                .append(redacted.terminationReason() == null ? "null" : "\"" + redacted.terminationReason() + "\"")
                .append(",\"cancelled\":").append(redacted.cancelled())
                .append(",\"payloadState\":\"").append(redacted.payloadState()).append('"');
        return body.append('}').toString();
    }

    /** Sorted so two reads of the same unchanged execution render byte-identical bodies. */
    private static String stringArrayJson(java.util.Set<String> values) {
        return values.stream().sorted().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /**
     * Unlike the {@link java.util.Set} overload above, this one does not sort:
     * {@code GraphSummary.violations()} is a {@link List} appended in a fixed order by two producers --
     * {@code GraphManager.toNode}, per node in document order, and {@code GraphDefinition.validate()},
     * per rule -- and a {@link List} is already deterministic on repeat reads for the same document
     * without needing a sort to make it so. The {@link java.util.Set} overload does sort, and has to:
     * its callers build from a {@link java.util.Map}, whose iteration order is randomised per JVM.
     */
    private static String stringArrayJson(List<String> values) {
        return values.stream().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /**
     * The three per-traversal control operations (API-02 for cancel, plus pause and resume),
     * sharing one handler because they share every part that is not the call itself: the same path
     * shape, the same verb, the same malformed-id rule, the same authorization boundary in the layer
     * below, and the same response body.
     *
     * <p>{@code rawId} is the raw {@code {id}} path segment; a malformed UUID is a request defect
     * ({@link ErrorCode#INVALID_REQUEST}), never routed to
     * {@link ai.ravenroot.api.application.AuthorizedRavenrootApplication} at all -- an unparseable id
     * cannot be an execution's id, known or unknown, so this is not the same case as an unknown
     * traversal (which is authorization's fail-closed unknown-ownership path, reached only once
     * {@code traversalId} parses). {@link ai.ravenroot.api.security.AuthorizationDeniedException}
     * propagates to {@link #protectedRequest}, which is what actually answers that case with 403.</p>
     *
     * <p>The {@code default} arm below is cancel because the caller has already matched
     * {@code operation} against exactly three reserved segments, so cancel is the only remaining
     * case — not because an unrecognised operation is treated as a cancellation. An operation this
     * handler was never given is answered by {@link ErrorCode#UNKNOWN_RESOURCE} at the routing site,
     * before it reaches here.</p>
     */
    private void controlExecution(HttpExchange exchange, String rawId, String operation) throws IOException {
        java.util.UUID traversalId;
        try {
            traversalId = java.util.UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            fail(exchange, ErrorCode.INVALID_REQUEST);
            return;
        }
        var context = AuthenticatedPrincipalAttribute.requestContext(exchange);
        // One body shape for all three, because all three answer the same question -- what happened
        // to this traversal, and what a reader must not conclude from it. The outcome vocabulary
        // differs per operation and comes from each result type's own enum, never from this switch.
        String body = switch (operation) {
            case "pause" -> {
                var result = authorizedApplication.pauseExecution(context, traversalId);
                yield controlResultJson(result.outcome().name(), result.traversalId(), result.note());
            }
            case "resume" -> {
                var result = authorizedApplication.resumeExecution(context, traversalId);
                yield controlResultJson(result.outcome().name(), result.traversalId(), result.note());
            }
            default -> {
                var result = authorizedApplication.cancelExecution(context, traversalId);
                yield controlResultJson(result.outcome().name(), result.traversalId(), result.note());
            }
        };
        json(exchange, 200, body);
    }

    private static String controlResultJson(String outcome, java.util.UUID traversalId, String note) {
        return "{\"outcome\":\"" + outcome + "\""
                + ",\"traversalId\":\"" + traversalId + "\""
                + ",\"note\":\"" + escape(note) + "\"}";
    }

    /**
     * API-02 exposes ADR 0012's engine-wide drain as an operator command. 200 with
     * {@code "outcome":"DRAINED"} when every node terminated within {@link #drainBound}; 202 with
     * {@code "outcome":"TIMED_OUT"} otherwise -- 202 because the request was accepted and acted on (the
     * engine is genuinely draining) even though the bound elapsed before it finished, which is a
     * materially different situation from any 4xx/5xx in this adapter's vocabulary.
     */
    private void drainServer(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) {
            return;
        }
        try {
            var result = authorizedApplication.drain(
                    AuthenticatedPrincipalAttribute.requestContext(exchange), drainBound);
            int status = result.outcome() == ai.ravenroot.api.application.DrainResult.Outcome.DRAINED ? 200 : 202;
            json(exchange, status, "{\"outcome\":\"" + result.outcome() + "\"}");
        } catch (IllegalStateException engineFailure) {
            // DefaultRavenrootApplication.drain wraps a genuine engine-side failure (the drain stage
            // itself threw, or the wait was interrupted) as IllegalStateException -- a platform fault,
            // not a request defect, so this is the one case in this handler that is INTERNAL_ERROR
            // rather than ACCESS_DENIED (already handled by protectedRequest) or a 4xx.
            fail(exchange, ErrorCode.INTERNAL_ERROR);
        }
    }

    /** Reads a structured submission body, answering 413 itself when the document is over budget. */
    private StructuredSubmission readStructuredSubmission(HttpExchange exchange) throws IOException {
        int budget = StructuredSubmission.envelopeLimits(payloadLimits, MAX_GRAPH_BYTES).maxEncodedBytes();
        byte[] body;
        try (var input = exchange.getRequestBody()) {
            body = input.readNBytes(budget + 1);
        }
        if (body.length > budget) {
            fail(exchange, ErrorCode.SUBMISSION_DOCUMENT_TOO_LARGE);
            return null;
        }
        return StructuredSubmission.read(body, payloadLimits, MAX_GRAPH_BYTES);
    }

    private static byte[] readGraphMlRequest(HttpExchange exchange, java.io.InputStream input) throws IOException {
        byte[] graph = input.readNBytes(MAX_GRAPH_BYTES + 1);
        if (graph.length <= MAX_GRAPH_BYTES) {
            return graph;
        }
        fail(exchange, ErrorCode.GRAPHML_DOCUMENT_TOO_LARGE);
        return null;
    }

    /**
     * One rejection response for both GraphML layers (FIX-03).
     *
     * <p>The security layer and the compatibility layer share one response path. Separate branches
     * would let one produce a classified {@code GRAPHML_*} code while the other fell through to a
     * generic {@code IllegalArgumentException} handler that echoed a message containing the submitted
     * document. The shared path ensures a caller cannot tell which layer refused the document and
     * cannot obtain different amounts of information depending on which one did.</p>
     *
     * <p>The message is already sanitised by the time it gets here — {@code GraphMlRejection} is the
     * only thing that can build it. The document-derived detail is recorded through
     * {@link #graphMlRejections} (the durable audit
     * trail in production, stdout only for embedders that never configured one), keyed by the same
     * {@code incidentId} that goes back in the response. {@code requestId} is always minted here
     * ({@link AuthenticatedPrincipalAttribute#requestId}, never the authentication-dependent overload)
     * so the audit record's correlation id is never absent -- {@code AuditEnvelope} requires one.</p>
     */
    private void graphMlError(HttpExchange exchange, GraphMlRejectionDetail error) throws IOException {
        String requestId = AuthenticatedPrincipalAttribute.requestId(exchange);
        AuthenticatedPrincipal caller = callerOrNull(exchange);
        graphMlRejections.record(new GraphMlRejectionAuditEvent(clock.instant(), requestId,
                caller == null ? GraphMlRejectionAuditEvent.UNKNOWN : caller.tenantId(),
                caller == null ? GraphMlRejectionAuditEvent.UNKNOWN : caller.subject(), error));
        int status = error.reason() == GraphMlParseException.Reason.DOCUMENT_TOO_LARGE
                || error.reason() == GraphMlParseException.Reason.RESOURCE_LIMIT ? 413 : 400;
        fail(exchange, status, ErrorEnvelope.of(graphMlCode(error.reason()), requestId)
                .withIncident(error.incidentId()));
    }

    /**
     * The authenticated caller, or {@code null} before authentication has resolved an identity for
     * this exchange. GraphML/payload rejection and the widest constructor's stdout defaults
     * above are the only callers; {@link GraphMlRejectionAuditEvent}/{@link PayloadRejectionAuditEvent}
     * turn a {@code null} here into their own {@code UNKNOWN} placeholder, the same honest "the server
     * genuinely does not know who is calling" answer {@link RateLimitAuditEvent} already establishes.
     */
    private static AuthenticatedPrincipal callerOrNull(HttpExchange exchange) {
        try {
            return AuthenticatedPrincipalAttribute.require(exchange);
        } catch (RuntimeException absent) {
            return null;
        }
    }

    /**
     * The maximum number of events {@code /v1/events/recent} will return in one answer.
     *
     * <p>Sized to the in-memory ring's own {@code HISTORY_LIMIT} so a caller can drain the entire
     * retained window in one request and never be forced to page through a buffer that may evict
     * underneath it mid-page — paging a ring is how a reader ends up with a list that is short for a
     * reason nobody recorded.</p>
     */
    static final int RECENT_EVENTS_MAX_LIMIT = 2_048;

    /** The number of events returned when the caller states no {@code limit}. */
    static final int RECENT_EVENTS_DEFAULT_LIMIT = 100;

    /**
     * The one recognised {@code include} selector: in-process instrumentation and author diagnostics —
     * {@code activeInstances}, {@code inFlightArrivals}, {@code fallback}, {@code processingDuration},
     * bounded failure messages and trusted built-in-log output.
     *
     * <p>Named for the content the caller wants, never for the source that holds it. A caller asking
     * for instantaneous runtime measurements should not have to know that this deployment has a journal,
     * or what a journal is; the deployment concept stays out of their vocabulary and the server maps
     * content to source. This is also what lets convergence land without a breaking change: once both sources
     * carry the same fields, {@code include} degrades into an ordinary projection filter and every
     * existing request keeps its meaning.</p>
     */
    static final String INCLUDE_DIAGNOSTICS = "diagnostics";

    /**
     * {@code GET /v1/events/recent} — the bounded, request/response counterpart of {@link #executionEvents}.
     *
     * <h2>Why this is not the SSE stream with a timeout</h2>
     * <p>A poll built by consuming SSE for a while spends a rate-limited stream slot on a read and
     * answers over a window that varies with a deployment fact the caller cannot see. This endpoint
     * makes both explicit instead: a declared bound, and a declared source.</p>
     *
     * <h2>The three honesty properties</h2>
     * <ul>
     *   <li><b>Source is declared.</b> Durable projection when one is available, the in-memory ring
     *       otherwise — a deterministic preference, reported in {@code source} so the same query
     *       against two deployments gives distinguishable answers rather than silently different ones.
     *       <strong>The cursor axes are not interchangeable:</strong> the ring's cursor is
     *       {@code ExecutionEvent.sequence()} (in-process, reset by a restart) and the durable one is
     *       {@code journalOffset} (per-tenant, store-assigned). A cursor is only meaningful against the
     *       same {@code source} that issued it, which is why the source travels with every answer.</li>
     *   <li><b>The limit is refused, never clamped.</b> A clamped request answers a question the caller
     *       did not ask, and it does so invisibly.</li>
     *   <li><b>An aged-out cursor is named.</b> {@code continuity} is {@code CONTINUOUS} only when the
     *       server can establish that nothing between the caller's cursor and the returned events was
     *       dropped. Absence-because-none and absence-despite-existence are different answers and this
     *       endpoint never merges them.</li>
     * </ul>
     *
     * <h2>{@code continuity} describes retention, never visibility — and must not</h2>
     * <p>Events are also removed from this answer by authorization:
     * {@code AuthorizedRavenrootApplication} resolves each event's owner and <em>fails closed</em> when
     * ownership is unknown. That filtering is deliberately <strong>not</strong> reported as a gap, and
     * the reason is stronger than tidiness: <strong>continuity metadata that moved when an event was
     * filtered would be an existence-disclosure channel.</strong> A caller could vary its cursor, watch
     * the marker change, and learn that events it may not read nevertheless exist — inferring the
     * presence and volume of another tenant's activity from a field designed to describe a buffer.
     * A gap marker that is honest about retention and silent about visibility is the only shape that
     * does not leak; an event the caller may not observe is simply absent, with nothing said about
     * whether it was ever there.</p>
     *
     * <p>The practical consequence, worth stating rather than discovering: the ownership registry is
     * bounded and evictable, so an event can still be in the ring while its ownership entry has been
     * evicted, and it will then be absent from a window reported {@code CONTINUOUS}. That is correct
     * under this contract. A reader needing "did I see everything I was entitled to" is asking a second
     * question, and this response deliberately does not answer it.</p>
     *
     * <p><strong>{@code oldestAvailable} carries the inverse of that tension, recorded here while it is
     * still theoretical.</strong> The ring floor is deliberately global rather than visibility-narrowed:
     * a floor computed only from events this caller may observe would sit above the true eviction point
     * and would under-report gaps, which is the failure direction that looks healthy. The cost is that
     * the value is a property of shared state. In a genuinely multi-tenant deployment a caller polling
     * it can watch it advance and infer <em>global event volume</em> — not whose, not what, but how
     * much. Single-owner local use, which is what this endpoint was built for, has no such exposure.
     * A multi-tenant deployment should treat this as a decision to revisit rather than a property to
     * discover: the honest options are per-tenant floors carried by the source, or withholding the
     * field, and both are worse for gap detection than what is here.</p>
     *
     * <h2>{@code include}: the caller names content, never a source</h2>
     * <p>The two sources are not the same data with different windows. The journal records what
     * durably happened; the ring additionally carries in-process instrumentation —
     * {@code activeInstances}, {@code inFlightArrivals}, {@code fallback},
     * {@code processingDuration} — that the journal never captured. The public
     * {@code description} is not one of those fields: both sources derive it from source-authored
     * text, and the raw {@link ExecutionEvent#detail()} is never serialized. The ring additionally
     * carries {@code publicReason} — the journal never captured the routed outcome — so a
     * replayed {@code NODE_COMPLETED} gets the weaker sentence that does not claim success, rather
     * than the confident one it cannot substantiate.</p>
     *
     * <p>So the selection is keyed on content and is a pure function of the request:</p>
     * <ul>
     *   <li>{@code include=diagnostics} — served from the ring, the only source that has these fields.
     *       The ring's window is shorter, and that is exactly why the gap contract matters here: when
     *       the diagnostics have aged out the caller is told they are <em>gone</em>
     *       ({@code GAP_DETECTED}) rather than handed events whose fields are quietly missing.
     *       <strong>Absent-because-aged-out and absent-because-this-source-never-had-them must not look
     *       alike</strong>, which is the whole reason the parameter exists.</li>
     *   <li>no {@code include} — durable journal when one is available, ring otherwise.</li>
     * </ul>
     *
     * <p>The response still declares the serving source. Deciding inside the server without the caller
     * knowing was rejected: a source label that depends on something the caller did not knowingly ask
     * for converts an honest label into an alibi.</p>
     *
     * <h2>Convergence is the target state</h2>
     * <p>This parameter exists because the sources differ. It is not the destination, but convergence
     * must not be manufactured by persisting either unsafe diagnostics or ephemeral measurements.</p>
     * <ul>
     *   <li><b>{@code detail} remains internal and non-durable.</b> It is bounded but may be the deepest
     *       cause's message verbatim. The public description is built from source-authored text and a
     *       character-restricted classifier; neither this endpoint nor SSE serializes the
     *       diagnostic.</li>
     *   <li><b>{@code activeInstances} is not durable.</b> It is the count of live instances of the
     *       node's actor — 1 for a resident nature however much traffic crosses it, and the number of
     *       live worker instances otherwise. The arrival count is {@code inFlightArrivals}. Neither
     *       field was written to the journal under another meaning, so persisted events require no
     *       version-dependent interpretation.
     *       <p>Both numbers are
     *       <em>instantaneous</em> readings of in-process runtime state, true at the moment the event was
     *       published and meaningless afterwards, so persisting them would record a measurement that
     *       cannot be checked against anything and that a replay would present as current. Making either
     *       durable would change what the journal represents.</p></li>
     * </ul>
     * <p>When convergence lands, {@code include} degrades into an ordinary projection filter, so
     * nothing built against this contract breaks.</p>
     *
     * <h2>{@code after} absent means "the most recent window"</h2>
     * <p>Cursor semantics are ascending and strictly-greater-than, always. But a caller asking for
     * <em>recent</em> events with no cursor wants the tail, and defaulting {@code after} to zero would
     * hand back the oldest {@code limit} events in the ring — the least useful answer to the question
     * the endpoint is named for. The effective cursor is therefore derived from the tail and
     * <em>echoed</em> in {@code after}, so what would otherwise be an invisible default becomes a value
     * the caller can see and resume from.</p>
     */
    private void recentExecutionEvents(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        var requestContext = AuthenticatedPrincipalAttribute.requestContext(exchange);
        var parameters = query(exchange);

        int limit = RECENT_EVENTS_DEFAULT_LIMIT;
        String rawLimit = parameters.get("limit");
        if (rawLimit != null && !rawLimit.isBlank()) {
            try {
                limit = Integer.parseInt(rawLimit.trim());
            } catch (NumberFormatException notANumber) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
            if (limit < 1) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
            if (limit > RECENT_EVENTS_MAX_LIMIT) {
                // Named, not clamped: the caller must be able to learn the cap it exceeded, which is
                // why this has its own code carrying the bound rather than a generic invalid-request.
                fail(exchange, ErrorCode.EVENT_LIMIT_ABOVE_MAXIMUM);
                return;
            }
        }

        Long requestedAfter = null;
        String rawAfter = parameters.get("after");
        if (rawAfter != null && !rawAfter.isBlank()) {
            try {
                requestedAfter = Long.parseLong(rawAfter.trim());
            } catch (NumberFormatException notANumber) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
            if (requestedAfter < 0) {
                fail(exchange, ErrorCode.INVALID_REQUEST);
                return;
            }
        }
        boolean diagnostics = false;
        String rawInclude = parameters.get("include");
        if (rawInclude != null && !rawInclude.isBlank()) {
            for (String requested : rawInclude.split(",")) {
                if (INCLUDE_DIAGNOSTICS.equals(requested.trim())) {
                    diagnostics = true;
                } else {
                    // An unrecognised content selector is refused rather than ignored: silently
                    // dropping it would answer a narrower question than the caller asked, which is the
                    // same failure as clamping a limit.
                    fail(exchange, ErrorCode.INVALID_REQUEST);
                    return;
                }
            }
        }

        // The selection rule is a pure function of the request, keyed on CONTENT rather than on
        // retention. Diagnostics exist only in the in-process ring, so asking for them selects it;
        // asking for nothing extra prefers the durable journal. A caller never names a source.
        boolean useDurable = !diagnostics && authorizedApplication.durableEventJournalAvailable();
        if (!useDurable) {
            json(exchange, 200, recentRingEvents(requestContext, requestedAfter, limit, diagnostics));
            return;
        }
        try {
            json(exchange, 200, recentDurableEvents(requestContext, requestedAfter, limit));
        } catch (ai.ravenroot.api.persistence.ExecutionStoreException storeFailure) {
            // A store failure that is not truncation is an operational event, not a retention answer.
            // Logged here because this is the only place that knows the read was a journal poll:
            // swallowed, a failing disk would be indistinguishable from events ageing out and no
            // operator would ever see it.
            System.err.println("{\"event\":\"recent-events-journal-read-failed\",\"failure\":\""
                    + escape(storeFailure.failure().getClass().getSimpleName())
                    + "\",\"retryability\":\"" + escape(String.valueOf(storeFailure.retryability())) + "\"}");
            fail(exchange, ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * The in-memory ring branch. Continuity is established by comparing the caller's cursor against the
     * ring's retention floor, which is the only thing that can distinguish a quiet server from one that
     * evicted the events the caller asked for.
     */
    private String recentRingEvents(ai.ravenroot.api.security.RequestContext requestContext,
                                    Long requestedAfter, int limit, boolean diagnostics) {
        var floor = authorizedApplication.oldestRetainedEventSequence(requestContext);
        var all = authorizedApplication.executionEventsAfter(requestContext, requestedAfter == null ? -1L
                : requestedAfter);

        long effectiveAfter;
        List<ai.ravenroot.api.application.ExecutionEvent> page;
        if (requestedAfter == null) {
            // Tail window: take the last `limit` events and derive the cursor that precedes them.
            int from = Math.max(0, all.size() - limit);
            page = all.subList(from, all.size());
            effectiveAfter = page.isEmpty()
                    ? (all.isEmpty() ? (floor.isPresent() ? floor.getAsLong() : 0L) : all.getLast().sequence())
                    : page.getFirst().sequence() - 1;
        } else {
            effectiveAfter = requestedAfter;
            page = all.subList(0, Math.min(limit, all.size()));
        }

        String continuity;
        if (floor.isEmpty()) {
            continuity = "UNKNOWN";
        } else if (effectiveAfter + 1 < floor.getAsLong()) {
            // Events with a sequence in (effectiveAfter, floor) existed and have been evicted.
            continuity = "GAP_DETECTED";
        } else {
            continuity = "CONTINUOUS";
        }

        long lastSequence = page.isEmpty() ? effectiveAfter : page.getLast().sequence();
        var body = new StringBuilder(256);
        body.append("{\"source\":\"RING\"")
                .append(",\"include\":[").append(diagnostics ? "\"" + INCLUDE_DIAGNOSTICS + "\"" : "")
                .append(']')
                .append(",\"after\":").append(effectiveAfter)
                .append(",\"limit\":").append(limit)
                .append(",\"lastSequence\":").append(lastSequence)
                .append(",\"oldestAvailable\":")
                .append(floor.isPresent() ? Long.toString(floor.getAsLong()) : "null")
                .append(",\"continuity\":\"").append(continuity).append('"')
                .append(",\"events\":[");
        for (int index = 0; index < page.size(); index++) {
            if (index > 0) {
                body.append(',');
            }
            body.append(executionEventJson(page.get(index)));
        }
        return body.append("]}").toString();
    }

    /**
     * The durable-projection branch. The aged-out cursor is not computed here: the store already refuses
     * to answer a truncated read, so this translates that declared failure into the same
     * {@code GAP_DETECTED} the ring branch derives, rather than letting it surface as a 500 or — worse —
     * catching it and returning the short list the store deliberately refused to give.
     */
    private String recentDurableEvents(ai.ravenroot.api.security.RequestContext requestContext,
                                       Long requestedAfter, int limit) {
        long effectiveAfter = requestedAfter == null ? 0L : requestedAfter;
        List<ai.ravenroot.api.application.DurableExecutionEvent> page;
        String continuity = "CONTINUOUS";
        Long retainedFrom = null;
        try {
            page = authorizedApplication.durableEventsAfter(requestContext, effectiveAfter, limit);
        } catch (ai.ravenroot.api.persistence.ExecutionStoreException storeFailure) {
            // Only truncation is a gap. Every other store failure -- unavailable, corrupted, a disk
            // error -- is rethrown, because reporting it as GAP_DETECTED would tell the operator "your
            // events aged out" when the truth is "your store is broken", and nothing anywhere would
            // record the difference. Same shape drainJournalPages already uses for the SSE path.
            if (!(storeFailure.failure()
                    instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated truncated)) {
                throw storeFailure;
            }
            page = List.of();
            continuity = "GAP_DETECTED";
            // The durable side does carry a floor after all: JournalTruncated names the offset it still
            // retains, so this branch reports oldestAvailable exactly when the caller most needs it --
            // the moment it has been told there is a gap.
            retainedFrom = truncated.retainedFrom();
        }
        long lastOffset = page.isEmpty() ? effectiveAfter : page.getLast().journalOffset();
        var body = new StringBuilder(256);
        body.append("{\"source\":\"DURABLE\"")
                // Reached only when diagnostics were not requested, so the selection is always empty.
                .append(",\"include\":[]")
                .append(",\"after\":").append(effectiveAfter)
                .append(",\"limit\":").append(limit)
                .append(",\"lastSequence\":").append(lastOffset)
                // Null on a successful read: the store states its floor only when refusing a truncated
                // one, so claiming a value on the happy path would be inventing it.
                .append(",\"oldestAvailable\":")
                .append(retainedFrom == null ? "null" : Long.toString(retainedFrom))
                .append(",\"continuity\":\"").append(continuity).append('"')
                .append(",\"events\":[");
        for (int index = 0; index < page.size(); index++) {
            if (index > 0) {
                body.append(',');
            }
            body.append(durableRecentEventJson(page.get(index)));
        }
        return body.append("]}").toString();
    }

    /**
     * One durable row of {@code /v1/events/recent}, exposed package-locally so its field set can be
     * asserted directly.
     *
     * <p>Deliberately narrower than {@link #durableExecutionEventFrame}: this is the polling
     * counterpart, and it has always carried the coarse position and identity rather than the full
     * causal chain. {@code handlerId} is here for the same reason it is on the stream — a poller that
     * saw {@code HANDLER_RESOLVED} and could not tell <em>which</em> handler resolved would have to go
     * and ask, which is the question this projection exists to answer — and it is null on every other
     * event type and on rows written before PERS-05.</p>
     * @param event durable event to serialize.
     * @return one JSON object, with no surrounding array or separator.
     */
    static String durableRecentEventJson(ai.ravenroot.api.application.DurableExecutionEvent event) {
        String description = PublicExecutionDescription.forEventType(event.eventType());
        return "{\"journalOffset\":" + event.journalOffset()
                + ",\"streamSequence\":" + event.streamSequence()
                + ",\"occurredAt\":\"" + event.occurredAt() + "\""
                + ",\"type\":\"" + escape(event.eventType()) + "\""
                + ",\"description\":\"" + escape(description) + "\""
                + ",\"processInstanceId\":\"" + event.processInstanceId() + "\""
                + ",\"traversalId\":\"" + event.traversalId() + "\""
                + ",\"handlerId\":" + (event.handlerId() == null ? "null"
                        : "\"" + event.handlerId() + "\"")
                + "}";
    }

    private void executionEvents(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        var initialPrincipal = AuthenticatedPrincipalAttribute.require(exchange);
        if (!clock.instant().isBefore(initialPrincipal.expiresAt())) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, ErrorCode.AUTHENTICATION_REQUIRED);
            return;
        }
        boolean diagnostics = false;
        String rawInclude = query(exchange).get("include");
        if (rawInclude != null && !rawInclude.isBlank()) {
            for (String requested : rawInclude.split(",")) {
                if (INCLUDE_DIAGNOSTICS.equals(requested.trim())) {
                    diagnostics = true;
                } else {
                    fail(exchange, ErrorCode.INVALID_REQUEST);
                    return;
                }
            }
        }
        // A stream occupies a connection, a subscription and a buffer for as long as it is open, so the
        // limit that matters here is a count of simultaneous streams rather than a rate. Both slots are
        // released together by closing the lease, on every exit path including an abrupt disconnect.
        try (var slot = rateLimiter.acquireStreamSlot(initialPrincipal.tenantId(), initialPrincipal.subject())) {
            if (!slot.granted()) {
                refuse(exchange, slot.refusal());
                return;
            }
            streamExecutionEvents(exchange, initialPrincipal, diagnostics);
        }
    }

    /**
     * Dispatches to the durable projection when one is available, and to the pre-API-03 in-memory
     * stream otherwise (API-03, PERS-07).
     *
     * <p>This is a declared degradation, not a silent one: an embedder that composed no durable,
     * journal-capable {@code ExecutionStore} — {@link RavenrootApplication#durableEventJournalAvailable()}
     * says so up front — keeps exactly the behaviour this endpoint always had, including its one real
     * gap, that a process restart resets {@code ExecutionMonitor}'s in-memory cursor and therefore
     * loses everything a reconnecting client could otherwise have resumed. Choosing between the two
     * here, once, is what lets {@link #streamDurableExecutionEvents} not have to re-litigate that
     * choice on every wakeup.</p>
     */
    private void streamExecutionEvents(HttpExchange exchange, AuthenticatedPrincipal initialPrincipal,
                                       boolean diagnostics)
            throws IOException {
        var requestContext = AuthenticatedPrincipalAttribute.requestContext(exchange);
        if (!diagnostics && authorizedApplication.durableEventJournalAvailable()) {
            streamDurableExecutionEvents(exchange, initialPrincipal, requestContext);
        } else {
            streamInMemoryExecutionEvents(exchange, initialPrincipal, requestContext);
        }
    }

    /**
     * The process-local stream: {@code Last-Event-ID} is {@code ExecutionMonitor}'s own in-process
     * sequence counter, replay comes from its 2048-entry in-memory ring, and live delivery is the
     * listener's own push. Unchanged by API-03 — this is exactly what an embedder with no durable
     * store, or a store not declaring {@code StoreCapability.EVENT_JOURNAL}, still gets, and it is
     * still what every existing test of this shape exercises. It is also the only stream that can
     * carry bounded author diagnostics; the durable journal deliberately never stores them.
     */
    private void streamInMemoryExecutionEvents(HttpExchange exchange, AuthenticatedPrincipal initialPrincipal,
                                               ai.ravenroot.api.security.RequestContext requestContext)
            throws IOException {
        long requestedSequence = parseSequence(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        var queue = new BoundedEventQueue(rateLimiter.configuration().streamQueueCapacity());
        AutoCloseable subscription = authorizedApplication.subscribeToExecutionEvents(requestContext, queue::offer);
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Ravenroot-Event-Source", "RING");
            exchange.getResponseHeaders().set("X-Ravenroot-Event-Continuity", "PROCESS_LOCAL");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                long sentSequence = requestedSequence;
                Instant revalidateAt = nextRevalidation(initialPrincipal);
                for (var event : authorizedApplication.executionEventsAfter(requestContext, requestedSequence)) {
                    revalidateAt = revalidateEventStreamLease(exchange, initialPrincipal, revalidateAt);
                    writeEvent(output, event);
                    sentSequence = Math.max(sentSequence, event.sequence());
                }
                while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                    if (queue.overrun()) {
                        // This consumer fell far enough behind that its buffer overflowed. Its stream is
                        // no longer complete, so it is ended with a terminal frame naming the last id it
                        // definitely received. Reconnecting with Last-Event-ID replays the gap, which is
                        // why dropping the consumer costs it nothing but costs the server nothing either.
                        writeOverrun(output, sentSequence);
                        break;
                    }
                    long waitMillis = Math.max(1, Math.min(1_000,
                            Duration.between(clock.instant(), revalidateAt).toMillis()));
                    var event = queue.poll(waitMillis);
                    revalidateAt = revalidateEventStreamLease(exchange, initialPrincipal, revalidateAt);
                    if (event == null) {
                        output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    } else if (event.sequence() > sentSequence) {
                        writeEvent(output, event);
                        sentSequence = event.sequence();
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (AuthenticationException | ai.ravenroot.api.security.AuthorizationDeniedException leaseEnded) {
            // The long-lived authorization lease ended; the client must obtain a current token and reconnect.
        } catch (IOException disconnected) {
            // A browser closing EventSource is a normal stream termination.
        } finally {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // Listener removal is best-effort during connection teardown.
            } finally {
                // Unsubscribe first, then drain: the listener can no longer be handed new events, so
                // nothing buffered survives this connection.
                queue.clear();
                exchange.close();
            }
        }
    }

    /**
     * The durable projection (API-03, PERS-07): {@code Last-Event-ID} is
     * {@code JournalRecord.journalOffset()} — per tenant, strictly increasing, durable across a
     * restart — and every frame this method ever writes, backlog or live, is read back from
     * {@code ExecutionStore.readJournal} through the exact same call. There is deliberately no second
     * code path for "live" delivery with its own idea of what a frame looks like: a client cannot
     * observe a gap or a duplicate at the seam between backlog and live, because there is no seam —
     * only one source, asked again.
     *
     * <h2>Why the in-memory listener still exists here, and what it is no longer trusted for</h2>
     * <p>{@code ExecutionMonitor}'s live push is subscribed <strong>first</strong>, before anything
     * else, purely as a wakeup: a notification means "something was probably committed, go re-read
     * the journal", never "here is the event to emit". Nothing about its content, its ordering or
     * even its arrival is relied on for correctness — the periodic keepalive/revalidation tick
     * (already needed for lease revalidation) re-polls the journal regardless of whether a wakeup
     * ever fires, so a dropped or coalesced notification costs latency bounded by that tick, never
     * a missed event.</p>
     *
     * <p><strong>And on this path the notification is frequently not delivered at all — measured,
     * not assumed.</strong> {@code AuthorizedRavenrootApplication.subscribeToExecutionEvents} wraps
     * the listener in {@code canObserve}, which resolves ownership through the in-process
     * {@code executionOwners} map; that map holds only executions <em>this</em> process started, so
     * for exactly the executions durable replay exists to serve — another process's, or this
     * process's own from before a restart — every notification is filtered out before it reaches
     * this method. The subscription is kept regardless, because it is a genuine latency win for the
     * common case of a browser watching a graph this process just launched, and because narrowing it
     * would mean subscribing unfiltered and letting one tenant's activity time another tenant's
     * wakeups. What it must never become is a correctness dependency, and it is not one: with zero
     * notifications this stream still delivers every row, on the tick.</p>
     *
     * <h2>Slow-client isolation is structural here, not a heuristic</h2>
     * <p>This path deliberately does <strong>not</strong> use {@link BoundedEventQueue}, and does not
     * disconnect a consumer on overrun. That control is right for
     * {@link #streamInMemoryExecutionEvents}, where a dropped entry <em>is</em> a lost event and the
     * only honest response is to end the stream and let the client resume. It would be a false
     * instrument here, for a reason worth stating rather than discovering later: with a buffer of
     * wakeups, "the buffer overflowed" does not mean "this client is slow". Any synchronous burst
     * larger than the buffer overflows it — the publisher fills it faster than any consumer, however
     * fast, can be scheduled to drain it — so an overrun-based disconnect fires on a busy tenant and
     * drops healthy clients. A control that cannot distinguish the condition it names is worse than
     * no control.</p>
     *
     * <p>{@link DurableStreamWakeup} therefore holds <strong>one</strong> pending signal and drops
     * every redundant one, which is lossless by construction: the reader answers a signal by draining
     * the journal to exhaustion ({@link #drainJournalPages}), so N coalesced signals and one signal
     * produce byte-identical output. The required property — that backpressure,
     * a slow client and a disconnection never block the runtime — then holds without any heuristic:
     * the publisher's notification is a non-blocking offer into a one-slot buffer that can neither
     * block nor grow, and this connection's memory is bounded by one page of {@code pageSize}
     * events. As in API-03, a client that stops reading entirely blocks only its own connection thread
     * inside {@code output.write}; it cannot reach the publisher.</p>
     *
     * <h2>Retention past the horizon is a declared failure, not a short answer</h2>
     * <p>{@code ExecutionStoreFailure.JournalTruncated} — {@code readJournal} asking for an offset
     * older than the tenant's retained floor — ends the stream with
     * {@link #writeJournalTruncated}, a terminal frame naming the retained floor, rather than either
     * silently starting from whatever survived (a stream with a hole in it indistinguishable from a
     * complete one) or a bare 5xx a client has no way to distinguish from a transient failure worth
     * retrying blindly against.</p>
     *
     * <h2>Two things this stream declares it does not carry</h2>
     * <p><strong>No traversal-terminal frame.</strong> The journal has no execution-completed or
     * execution-failed record. Synthesizing one would be unsound: completion is
     * triggered by a journalled event, but failure can be triggered by a join timeout, an abandoned
     * branch or an aggregate rejection, none of which are journalled — so a single type would have
     * had to declare its own cause external on every run of the second kind, permanently and falsely,
     * in an append-only log that cannot retract it. This stream does not paper over that by
     * synthesising a frame from the aggregate: a frame with no journal offset cannot carry an
     * {@code id:}, and giving it a borrowed one would corrupt the single cursor that makes
     * reconnection gapless. <strong>A client determines that a traversal has ended by observing the
     * aggregate</strong> — {@code /v1/runtime}'s active-execution count — not from this stream.</p>
     *
     * <p><strong>A branch outliving its terminal traversal is not replayable.</strong> Such a branch
     * journals nothing — neither transitions nor events — so where a durable store is composed this
     * endpoint serves a projection that is, by exactly that much, <em>less</em> complete than the
     * in-memory stream, which still shows those events. That is a real coverage gap. It is not
     * mitigated by mixing the two sources:
     * interleaving in-memory events into this stream would mean emitting frames with no journal
     * offset, which is the same cursor corruption as above, and would trade a gap a client can be
     * told about for one it could not detect.</p>
     */
    private void streamDurableExecutionEvents(HttpExchange exchange, AuthenticatedPrincipal initialPrincipal,
                                              ai.ravenroot.api.security.RequestContext requestContext)
            throws IOException {
        long requestedOffset = parseSequence(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        int pageSize = rateLimiter.configuration().streamQueueCapacity();
        var wakeup = new DurableStreamWakeup();
        AutoCloseable subscription = authorizedApplication.subscribeToExecutionEvents(requestContext, wakeup::signal);
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Ravenroot-Event-Source", "DURABLE");
            exchange.getResponseHeaders().set("X-Ravenroot-Event-Continuity", "DURABLE");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                long sentOffset = requestedOffset;
                Instant revalidateAt = nextRevalidation(initialPrincipal);
                try {
                    sentOffset = drainJournalPages(output, requestContext, sentOffset, pageSize);
                    while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                        long waitMillis = Math.max(1, Math.min(1_000,
                                Duration.between(clock.instant(), revalidateAt).toMillis()));
                        // The return value is deliberately discarded. Every iteration re-reads the
                        // journal, woken or not: that is what makes the wakeup a pure latency
                        // optimisation and not a correctness dependency, and it is not optional here
                        // because the wakeup is filtered out entirely for the executions this path
                        // exists to serve (see this method's Javadoc). Draining only when woken would
                        // leave such a stream permanently silent while looking perfectly healthy --
                        // emitting keepalives forever over a journal it never re-read.
                        wakeup.await(waitMillis);
                        revalidateAt = revalidateEventStreamLease(exchange, initialPrincipal, revalidateAt);
                        long beforeOffset = sentOffset;
                        sentOffset = drainJournalPages(output, requestContext, sentOffset, pageSize);
                        if (sentOffset == beforeOffset) {
                            // A keepalive means exactly "this connection re-read the journal and it
                            // held nothing new", which is what lets a reader treat it as quiescence.
                            output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                            output.flush();
                        }
                    }
                } catch (JournalTruncatedSignal truncated) {
                    // Caught here, still inside the try-with-resources that owns `output`: the
                    // terminal frame must be written before the response body closes, not after --
                    // exchange.getResponseBody() past that point no longer reaches the client.
                    writeJournalTruncated(output, truncated.failure());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (AuthenticationException | ai.ravenroot.api.security.AuthorizationDeniedException leaseEnded) {
            // The long-lived authorization lease ended; the client must obtain a current token and reconnect.
        } catch (IOException disconnected) {
            // A browser closing EventSource is a normal stream termination.
        } finally {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // Listener removal is best-effort during connection teardown.
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * A one-slot, coalescing, never-blocking wakeup for {@link #streamDurableExecutionEvents} — the
     * durable path's replacement for {@link BoundedEventQueue}, and deliberately not a buffer.
     *
     * <p>It holds at most one pending signal and silently drops every redundant one. That is lossless
     * rather than merely tolerable, because the reader answers a signal by draining the journal to
     * exhaustion: N signals coalesced into one and one signal produce identical output. The capacity
     * is one for the same reason it is not larger — a larger buffer would only create an overflow
     * condition that carries no information, which is exactly the false slow-client signal
     * {@link #streamDurableExecutionEvents}'s Javadoc rejects.</p>
     *
     * <p>The publisher's side is {@link ArrayBlockingQueue#offer(Object)}, which never blocks and
     * never grows; the shared execution-event publisher therefore cannot be slowed, stalled or
     * expanded by any consumer's behaviour, which is the whole of what this endpoint owes the
     * runtime.</p>
     *
     * <h2>The race, and why re-reading closes it</h2>
     * <p>The reader consumes the pending signal <em>before</em> reading the journal. Any commit whose
     * notification arrives during that read finds the slot empty and fills it, so the next
     * {@link #await} returns immediately and re-reads; any notification that arrives before the
     * signal is consumed has, by ordering, already committed, so the read that follows sees its row.
     * No interleaving leaves a committed row unread with the slot empty.</p>
     */
    // Package-private, not private, solely so DurableStreamWakeupTest can mutation-prove the two
    // properties this class exists for -- that signalling never blocks and that signals coalesce.
    // Neither is reachable from an HTTP-level test: the live subscription is wrapped in canObserve,
    // which filters every event out before it can reach a signal (see
    // streamDurableExecutionEvents' Javadoc), so an integration test that flooded the publisher
    // would pass identically against a blocking implementation and prove nothing about this class.
    static final class DurableStreamWakeup {
        private static final Object SIGNAL = new Object();

        private final ArrayBlockingQueue<Object> pending = new ArrayBlockingQueue<>(1);

        /**
         * Records that something was probably committed. The event itself is ignored: on this path
         * only arrival is information, never content — see {@link #streamDurableExecutionEvents}.
         */
        void signal(ai.ravenroot.api.application.ExecutionEvent ignored) {
            pending.offer(SIGNAL);
        }

        /** True when a signal was pending or arrived within the window; false on a plain timeout. */
        boolean await(long timeoutMillis) throws InterruptedException {
            return pending.poll(timeoutMillis, TimeUnit.MILLISECONDS) != null;
        }
    }

    /**
     * Reads and writes whole pages until the journal has nothing left after {@code afterOffset},
     * returning the new cursor. Used identically for the initial backlog and every live re-poll — see
     * {@link #streamDurableExecutionEvents}'s own Javadoc for why that unification is the point.
     *
     * <p><strong>The paging loop is a latency optimisation, not a correctness control, and this is
     * measured.</strong> Mutating it to read a single page per call leaves every test in
     * {@code DurableSseReplayIntegrationTest} green, because {@link #streamDurableExecutionEvents}
     * re-polls unconditionally on every tick and simply delivers the next page a tick later. What the
     * loop buys is that a client resuming from far behind catches up in one pass instead of one page
     * per second; what it does not buy is the absence of gaps, which comes from the cursor and the
     * unconditional re-poll. Recorded here because a reader would otherwise reasonably assume the
     * loop was load-bearing and be reluctant to touch it.</p>
     *
     * @throws JournalTruncatedSignal wrapping {@code ExecutionStoreFailure.JournalTruncated} when
     *                                {@code afterOffset} is older than the tenant's retained floor
     */
    private long drainJournalPages(OutputStream output, ai.ravenroot.api.security.RequestContext requestContext,
                                   long afterOffset, int pageSize) throws IOException {
        long cursor = afterOffset;
        List<ai.ravenroot.api.application.DurableExecutionEvent> page;
        do {
            try {
                page = authorizedApplication.durableEventsAfter(requestContext, cursor, pageSize);
            } catch (ai.ravenroot.api.persistence.ExecutionStoreException failure) {
                if (failure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated truncated) {
                    throw new JournalTruncatedSignal(truncated);
                }
                throw failure;
            }
            for (var event : page) {
                writeDurableEvent(output, event);
                cursor = Math.max(cursor, event.journalOffset());
            }
        } while (page.size() == pageSize);
        return cursor;
    }

    /** Carries a declared retention failure out of {@link #drainJournalPages} without disguising it as I/O. */
    private static final class JournalTruncatedSignal extends RuntimeException {
        private final ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated failure;

        private JournalTruncatedSignal(ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated failure) {
            super(null, null, false, false);
            this.failure = failure;
        }

        private ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated failure() {
            return failure;
        }
    }

    /**
     * Terminal frame for a client whose {@code Last-Event-ID} is older than
     * {@link ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated#retainedFrom()} — the
     * declared alternative to silently starting the replay from whatever survived (see
     * {@link #streamDurableExecutionEvents}'s own Javadoc). {@code resumeFrom} is one less than the
     * retained floor, so a client that naively reconnects with this value as its next
     * {@code Last-Event-ID} resumes exactly at the earliest offset the journal can still serve, per
     * {@code readJournal}'s own "strictly after" contract, rather than being told a number that would
     * immediately truncate again.
     */
    private static void writeJournalTruncated(OutputStream output,
                                              ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated failure)
            throws IOException {
        long resumeFrom = failure.retainedFrom() - 1;
        String frame = "event: stream-truncated\ndata: "
                + "{\"code\":\"STREAM_RETENTION_EXCEEDED\",\"retainedFrom\":" + failure.retainedFrom()
                + ",\"resumeFrom\":" + resumeFrom + "}\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /** Terminal frame telling a dropped consumer where to resume from. */
    private static void writeOverrun(OutputStream output, long lastSequence) throws IOException {
        String frame = "id: " + lastSequence + "\nevent: stream-overrun\ndata: "
                + "{\"code\":\"STREAM_CONSUMER_TOO_SLOW\",\"resumeAfter\":" + lastSequence + "}\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * One durable frame. Deliberately a narrower field set than {@link #writeEvent}: everything here
     * is either on {@link ai.ravenroot.api.application.DurableExecutionEvent} directly or resolved
     * from durable structure ({@code nodeId}); nothing is fabricated to fill a wider shape the journal
     * never promised (see that type's own Javadoc on why it is a distinct type from
     * {@code ExecutionEvent} rather than a second constructor on it).
     */
    private static void writeDurableEvent(OutputStream output,
                                          ai.ravenroot.api.application.DurableExecutionEvent event)
            throws IOException {
        output.write(durableExecutionEventFrame(event));
        output.flush();
    }

    /** Complete UTF-8 durable frame, exposed package-locally for the shared transport-size proof. */
    static byte[] durableExecutionEventFrame(ai.ravenroot.api.application.DurableExecutionEvent event) {
        String description = PublicExecutionDescription.forEventType(event.eventType());
        String body = "{\"journalOffset\":" + event.journalOffset()
                + ",\"streamSequence\":" + event.streamSequence()
                + ",\"occurredAt\":\"" + event.occurredAt() + "\""
                + ",\"eventType\":\"" + escape(event.eventType()) + "\""
                + ",\"description\":\"" + escape(description) + "\""
                + ",\"graphVersion\":\"" + escape(event.graphVersion()) + "\""
                + ",\"processInstanceId\":\"" + event.processInstanceId() + "\""
                + ",\"traversalId\":\"" + event.traversalId() + "\""
                + ",\"invocationId\":" + (event.invocationId() == null ? "null" : "\"" + event.invocationId() + "\"")
                + ",\"attemptId\":" + (event.attemptId() == null ? "null" : "\"" + event.attemptId() + "\"")
                + ",\"causationId\":" + (event.causationId() == null ? "null" : "\"" + event.causationId() + "\"")
                + ",\"nodeId\":" + (event.nodeId() == null ? "null" : "\"" + escape(event.nodeId()) + "\"")
                + ",\"edgeId\":" + (event.edgeId() == null ? "null"
                        : "\"" + escape(StableEdgeId.requireValid(event.edgeId())) + "\"")
                // The fourth identity, beside the process, the traversal and the invocation, so a
                // client can tell a handler event apart from a node event that shares all three
                // instead of parsing the sentence. A UUID, so it needs no escaping and costs a fixed
                // 36 bytes inside the projection's own reserve.
                + ",\"handlerId\":" + (event.handlerId() == null ? "null"
                        : "\"" + event.handlerId() + "\"")
                + "}";
        String frame = "id: " + event.journalOffset() + "\nevent: execution\ndata: " + body + "\n\n";
        return frame.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeEvent(OutputStream output, ai.ravenroot.api.application.ExecutionEvent event)
            throws IOException {
        output.write(executionEventFrame(event));
        output.flush();
    }

    /** Complete UTF-8 live frame, exposed package-locally so the client-size contract is tested exactly. */
    static byte[] executionEventFrame(ai.ravenroot.api.application.ExecutionEvent event) {
        String frame = "id: " + event.sequence() + "\nevent: execution\ndata: " + executionEventJson(event) + "\n\n";
        return frame.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The single serialization of an {@link ai.ravenroot.api.application.ExecutionEvent} for the wire,
     * shared by the SSE stream and by {@code /v1/events/recent}.
     *
     * <p>Shared deliberately. {@code description} is a source-authored sentence;
     * {@link ExecutionEvent#detail()} is never serialized because it can contain a raw exception
     * message or graph-authored value. Two serializers would let either safety rule silently miss the
     * polling path.</p>
     *
     * <h2>The sentence depends on the classifier, and the {@code detail} alias is absent</h2>
     * <p>{@code description} is selected from {@link ExecutionEvent#publicReason()} as well as the
     * type, so a completed node that routed a non-default outcome no longer reports success. The
     * classifier itself is emitted under its own name: a client that wants to branch on the outcome —
     * colour a row, filter a view — should read a token, not parse an English sentence that this
     * server is free to rewrite.</p>
     *
     * <p>The {@code detail} key is <b>removed</b>. It was introduced as a compatibility alias holding
     * the public sentence, which made a field named for the diagnostic contain something that was not
     * the diagnostic — a second untrue statement on the same wire, aimed at whoever reads this API
     * rather than at whoever reads the panel. Removing it is safe against the published contract:
     * {@code docs/api/openapi.json} never declared it, so nothing was promised it, and the one client
     * in this repository reads {@code description} and ignores it.</p>
     */
    static String executionEventJson(ai.ravenroot.api.application.ExecutionEvent event) {
        String description = PublicExecutionDescription.forType(event.type(), event.publicReason());
        RuntimeActivityData.TextProjection message = event.authorMessage();
        return "{\"sequence\":" + event.sequence()
                + ",\"occurredAt\":\"" + event.occurredAt() + "\""
                + ",\"engineId\":\"" + escape(event.engineId()) + "\""
                + ",\"graphVersion\":\"" + escape(event.graphVersion()) + "\""
                + ",\"processInstanceId\":\"" + event.processInstanceId() + "\""
                + ",\"traversalId\":\"" + event.traversalId() + "\""
                + ",\"executionId\":\"" + event.executionId() + "\""
                + ",\"invocationId\":" + (event.invocationId() == null ? "null" : "\"" + event.invocationId() + "\"")
                + ",\"attemptId\":" + (event.attemptId() == null ? "null" : "\"" + event.attemptId() + "\"")
                + ",\"type\":\"" + event.type() + "\""
                + ",\"nodeId\":" + (event.nodeId() == null ? "null" : "\"" + escape(event.nodeId()) + "\"")
                + ",\"edgeId\":" + (event.edgeId() == null ? "null"
                        : "\"" + escape(StableEdgeId.requireValid(event.edgeId())) + "\"")
                + ",\"activeInstances\":" + event.activeInstances()
                // The second number, under a name that cannot be mistaken for the first. Both are
                // emitted because they answer different questions -- how much work this node's role is
                // carrying, and how deep the queue at it is -- and a client given only one of them
                // cannot derive the other.
                + ",\"inFlightArrivals\":" + event.inFlightArrivals()
                + ",\"fallback\":" + event.fallback()
                + ",\"description\":\"" + escape(description) + "\""
                // The bare classifier beside the sentence built from it, so a client branches on
                // a token instead of matching prose. Null stays null: absent means this event type
                // carries no classifier, and "" would be a token no reader could look up.
                + ",\"publicReason\":" + (event.publicReason() == null ? "null"
                        : "\"" + escape(event.publicReason()) + "\"")
                + ",\"message\":" + (message == null ? "null" : "\"" + escape(message.value()) + "\"")
                + ",\"messageRedacted\":" + (message != null && message.redacted())
                + ",\"messageTruncated\":" + (message != null && message.truncated())
                + (event.authorOutput() == null ? ""
                        : ",\"output\":" + PayloadJson.write(event.authorOutput().value())
                                + ",\"outputRedacted\":" + event.authorOutput().redacted()
                                + ",\"outputTruncated\":" + event.authorOutput().truncated())
                + ",\"processingDuration\":" + (event.processingDuration() == null ? "null"
                        : event.processingDuration().toNanos() / 1_000_000_000.0)
                + "}";
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", expected + ", OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (expected.equals(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", expected);
        fail(exchange, ErrorCode.METHOD_NOT_ALLOWED);
        return false;
    }

    /**
     * The only way this adapter answers with an error (API-01).
     *
     * <p>No signature on this class accepts error text: a call site picks an {@link ErrorCode} and
     * the message is the code's. What can no longer be expressed cannot be forgotten.</p>
     */
    private static void fail(HttpExchange exchange, ErrorCode code) throws IOException {
        fail(exchange, code.status(), ErrorEnvelope.of(code, AuthenticatedPrincipalAttribute.requestId(exchange)));
    }

    private static void fail(HttpExchange exchange, int status, ErrorEnvelope envelope) throws IOException {
        json(exchange, status, envelope.toJson());
    }

    /** Answers a classified payload rejection and records its payload-derived detail server-side. */
    private void failPayload(HttpExchange exchange, PayloadException rejection) throws IOException {
        String correlationId = AuthenticatedPrincipalAttribute.requestId(exchange);
        AuthenticatedPrincipal caller = callerOrNull(exchange);
        payloadRejections.record(new PayloadRejectionAuditEvent(clock.instant(), correlationId,
                caller == null ? PayloadRejectionAuditEvent.UNKNOWN : caller.tenantId(),
                caller == null ? PayloadRejectionAuditEvent.UNKNOWN : caller.subject(), rejection));
        fail(exchange, rejection.reason().recommendedStatus(), ErrorEnvelope.of(rejection, correlationId));
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
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

    private Instant nextRevalidation(ai.ravenroot.server.security.AuthenticatedPrincipal principal) {
        Instant interval = clock.instant().plus(httpSecurity.sseAuthenticationRevalidation());
        return principal.expiresAt().isBefore(interval) ? principal.expiresAt() : interval;
    }

    private Instant revalidateEventStreamLease(
            HttpExchange exchange,
            ai.ravenroot.server.security.AuthenticatedPrincipal initialPrincipal,
            Instant revalidateAt) throws AuthenticationException {
        Instant now = clock.instant();
        if (!now.isBefore(initialPrincipal.expiresAt())) {
            throw new AuthenticationException("SSE credential has expired");
        }
        if (now.isBefore(revalidateAt)) {
            return revalidateAt;
        }
        var currentPrincipal = authenticator.revalidate(exchange.getRequestHeaders());
        if (!sameSecurityIdentity(initialPrincipal, currentPrincipal)
                || !clock.instant().isBefore(currentPrincipal.expiresAt())) {
            throw new AuthenticationException("SSE security identity is no longer current");
        }
        // Same exchange, therefore same correlation id: a revalidated credential is the same request
        // continuing, not a new one. Minting a fresh id here made every SSE connection's audit records
        // mutually unjoinable (SEC-07).
        authorizedApplication.authorizeExecutionEvents(
                AuthenticatedPrincipalAttribute.requestContext(exchange, currentPrincipal));
        return nextRevalidation(currentPrincipal);
    }

    private static boolean sameSecurityIdentity(ai.ravenroot.server.security.AuthenticatedPrincipal expected,
                                                ai.ravenroot.server.security.AuthenticatedPrincipal actual) {
        return expected.subject().equals(actual.subject())
                && expected.type() == actual.type()
                && expected.issuer().equals(actual.issuer())
                && expected.tenantId().equals(actual.tenantId())
                && expected.roles().equals(actual.roles())
                && expected.scopes().equals(actual.scopes());
    }

    private static HttpSecurityConfiguration defaultHttpSecurity(int port) {
        return new HttpSecurityConfiguration(
                ai.ravenroot.server.security.BrowserOriginPolicy.fromEnvironment(Map.of(), port),
                new ai.ravenroot.server.security.SecurityHeadersPolicy(false), Duration.ofSeconds(30));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        var result = new LinkedHashMap<String, String>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Stop(0) -- the previous, hardcoded value -- closes the
        // listening socket and gives every in-flight exchange no time at all; it is what made
        // draining unobservable to a client that had already been accepted. httpStopDelay is
        // whichever bound the composition root configured (default 10s, see
        // ReadinessConfiguration#DEFAULT_HTTP_STOP_DELAY's Javadoc for why that value).
        if (managedIngress != null) {
            managedIngress.close();
        }
        server.stop((int) httpStopDelay.toSeconds());
        executor.close();
        try {
            auditSubscription.close();
        } catch (Exception ignored) {
            // Subscription removal is best-effort during shutdown.
        }
        try {
            executionAccounting.close();
        } catch (Exception ignored) {
            // Subscription removal is best-effort during shutdown.
        }
        try {
            decisionalEventSubscription.close();
        } catch (Exception ignored) {
            // Subscription removal is best-effort during shutdown.
        }
        // Limiter state is per-server, so it must not survive the server. Buckets, gates and stream
        // counters are all released here rather than left to a GC root held by an embedding process.
        rateLimiter.close();
        readinessGate.close();
        application.close();
    }
}
