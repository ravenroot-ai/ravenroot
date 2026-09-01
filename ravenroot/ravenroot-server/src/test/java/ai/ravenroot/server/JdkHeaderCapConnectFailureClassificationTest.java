package ai.ravenroot.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins probe-failure misclassification directly against a real connect-refused port.
 * {@link java.net.ConnectException} is itself a
 * {@link java.net.SocketException}, so a refused connect -- exactly what a sandboxed network denying the
 * loopback hop, "Operation not permitted", "Network is unreachable", or ephemeral-port exhaustion would
 * produce during {@link RavenrootServer#verifyRequestHeaderCapTookEffect()}'s own startup probe -- used
 * to fall into the same {@code catch} as an established connection actively torn down mid-write, and be
 * misdiagnosed as direct proof {@code sun.net.httpserver.maxReqHeaderSize} did not take effect. See
 * {@link RavenrootServer#probeHeaderCap}'s Javadoc for the classification ({@code connect()} in its own
 * {@code try}/{@code catch}) and {@link RavenrootServer#verifyRequestHeaderCapTookEffect()}'s "Startup
 * posture" Javadoc for the full story of the two outcomes and this method's fail-fast-only-on-direct-proof
 * posture. Why an in-process test like this one suffices here -- rather than a fresh-JVM reproduction like
 * {@code JdkHeaderCapOrderingHazardTest} -- is not part of that "Startup posture" story; it is stated here
 * instead: this defect is a property of {@link RavenrootServer#probeHeaderCap}'s own classification logic,
 * not of JVM-wide static-initializer ordering, so it needs no process boundary to demonstrate.
 *
 * <p>{@code anAnsweredProbeAgainstARealServerIsConfirmedOk} below creates its own plain
 * {@code com.sun.net.httpserver.HttpServer} -- exactly the "foreign server" {@code
 * JdkHeaderCapOrderingHazardTest} demonstrates is dangerous in a fresh JVM, and exactly what {@code
 * security/TestOidcProvider.java} already guards against for this module's own shared Surefire JVM (see
 * its Javadoc). This class had no such guard: run in isolation, or ahead of any test that constructs a
 * real {@link RavenrootServer}, its nude {@code HttpServer.create()} used to be the first thing in the
 * whole process to touch {@code sun.net.httpserver}, permanently locking {@code
 * sun.net.httpserver.ServerConfig} onto the JDK's 380 KiB default before {@code RavenrootServer}'s own
 * static initializer runs. A later test in the same JVM that starts a real {@code
 * RavenrootServer} -- {@code DrainSequencingTest} is one case, but any of the seventeen other test
 * classes in this module that construct one would do -- failed with the cap-initialization diagnostic,
 * accusing an inert property even though it was set correctly by the class that lost
 * a race this class's own test fixture created. Nor did this class protect itself even partially: JUnit 5
 * declares no stable method order absent an explicit {@code @TestMethodOrderer}, and this class has none
 * so a clean reactor build cannot depend on which class the filesystem hands Surefire first. The
 * static initializer below uses the same guard as {@code TestOidcProvider}, so this class's own probe
 * server is not the first thing in the JVM to touch
 * {@code sun.net.httpserver}. See {@code docs/qa/what-the-testkits-do-not-cover.md}
 * ("An unguarded HttpServer.create() can make a filtered run accuse the cap initialization of a defect
 * belonging to another class") for the full mechanism and measurement.
 */
class JdkHeaderCapConnectFailureClassificationTest {

    /**
     * Forces {@link RavenrootServer}'s static initializer -- which sets {@code
     * sun.net.httpserver.maxReqHeaderSize} -- to run before this class's own {@code
     * anAnsweredProbeAgainstARealServerIsConfirmedOk} creates a plain {@code HttpServer} below. Same
     * mechanism and same rationale as {@code security/TestOidcProvider}'s identical static block: {@code
     * Class.forName(Class#getName())} is specified (JLS 12.4.1) to initialize the named class, unlike a
     * plain {@code .class} literal, which would resolve the {@code Class} object without running it.
     */
    static {
        try {
            Class.forName(RavenrootServer.class.getName());
        } catch (ClassNotFoundException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    /**
     * Binding a {@code ServerSocket(0)}, reading its ephemeral port, closing it and then connecting to
     * that same port is racy: the just-released port can be reassigned to another
     * listener before this test's own {@code connect()} ran, the probe actually connected and got
     * answered, and the failure read {@code CAP_CONFIRMED_OK -- cause: null} -- {@link
     * RavenrootServer#probeHeaderCap} accused of a misclassification it never made. Reproduced
     * deliberately by racing a real listener onto the just-released port, confirming the mechanism.
     *
     * <p>The test controls both axes independently. First, the precondition is no longer a
     * released ephemeral port at all, so there is nothing left to race: TCP port 1 on loopback sits in
     * the privileged range ({@code 0}-{@code 1023}), which an ordinary non-root process cannot
     * {@code bind()} -- true of every process this reactor itself starts, and of most dev/CI hosts.
     * That is <em>not</em> a universal guarantee, though: on Linux, {@code
     * net.ipv4.ip_unprivileged_port_start} can lower or remove the privileged floor, and container
     * images commonly do exactly that (some also run their build step as root outright) -- which is
     * this repository's own declared CI platform. Neither makes it likely that something is actually
     * listening on port 1, but it does mean the premise is probable, not provable by construction, which
     * is exactly why the independent premise check below is load-bearing rather than decorative.
     * (Rejected: binding a port and connecting to a neighboring one has the identical released-port
     * defect; a full-backlog {@code ServerSocket} trades one
     * platform-dependent race for another, since whether a full backlog refuses or blocks is itself
     * OS-dependent; injecting the {@code connect()} failure directly, bypassing the network, would need
     * a production-code seam in {@link RavenrootServer#probeHeaderCap} that exists purely for this
     * test's benefit. What that seam would actually buy -- determinism in place of the host-dependent
     * skip this test still has to report when the premise below does not hold (something really is
     * listening on port 1, or a firewall drops instead of refuses, see the timeout note further down) --
     * is not worth a test-only hook in production code. It would not buy the same guarantee the second
     * independent premise check gets: bypassing the network cannot produce a real network refusal, so
     * injecting one is not equivalent to probing for one. The premise must be established independently
     * of {@code probeHeaderCap}'s own output.)
     * Second, belt-and-suspenders regardless of the above -- and this is the axis that actually carries
     * the guarantee: the premise that loopback port 1 refuses a connection is established
     * with a plain, bare {@link java.net.Socket#connect} <strong>before</strong> and <strong>outside</strong>
     * {@link RavenrootServer#probeHeaderCap} is ever called, so whether the premise holds is a fact about
     * this host's network, never a fact about how {@code probeHeaderCap} classified the result. Deriving
     * deriving the premise from {@code probeHeaderCap}'s own output would silently
     * turned every misclassification the class exists to catch into a skip instead of a failure (an
     * reintroducing the exact misclassification turns this test from {@code BUILD FAILURE} into a green
     * skip). If the plain-socket premise does not
     * hold -- something really is listening on loopback port 1, or a sandboxed network times the connect
     * out instead of refusing it outright -- the test reports its premise as not met ({@link
     * org.opentest4j.TestAbortedException} via {@code assumeTrue}, surfaced as skipped, never as a
     * failure) using a timeout well under {@code probeHeaderCap}'s own 5-second connect timeout, so a
     * firewall that drops rather than refuses degrades this test to a fast skip, not a slow one.
     */
    @Test
    void aRefusedConnectIsInconclusiveNotDirectProof() throws Exception {
        // Port 1 (tcpmux) is in the privileged range: binding it needs root (or a relaxed
        // ip_unprivileged_port_start -- see this method's own Javadoc above, not the class one, for the
        // container-image caveat). Unlike an ephemeral port obtained with
        // ServerSocket(0) and released, nobody can race us onto it between deciding to use it and
        // connecting, because there is no window in which *we* held and released it: we never bind it
        // at all. Measured on this repository's own dev host: connecting here returns
        // java.net.ConnectException in ~12ms cold, sub-millisecond once the JIT and OS routing cache are
        // warm.
        var refusedTarget = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);

        // The premise is a fact about the network, established with a plain socket, independently of
        // and before RavenrootServer#probeHeaderCap: "was the connection actually refused?" must never
        // be answered by asking probeHeaderCap how it classified its own attempt, or every wrong
        // classification -- the very thing this test exists to catch -- reads back as "premise not
        // met" instead of a failure. The 2-second timeout here is deliberately shorter than
        // probeHeaderCap's own 5-second connect timeout, so an environment that drops instead of
        // refusing (a firewall in DROP mode) degrades this test to a quick skip rather than a slow one.
        Exception premise = null;
        try (var rawProbe = new java.net.Socket()) {
            rawProbe.connect(refusedTarget, 2_000);
        } catch (Exception refusedOrNot) {
            premise = refusedOrNot;
        }
        assumeTrue(premise instanceof ConnectException,
                "premise not met: a plain connect() to loopback port 1 was not refused (" + premise
                        + "). Something is listening there, or this environment intercepts "
                        + "privileged-port connects unusually; either way this run proves nothing about "
                        + "RavenrootServer#probeHeaderCap's classification, so the premise is reported as "
                        + "skipped, not as a defect.");

        var result = RavenrootServer.probeHeaderCap(refusedTarget, 500_000);

        assertEquals(RavenrootServer.HeaderCapProbeOutcome.INCONCLUSIVE, result.outcome(),
                "the premise -- a plain connect() to loopback port 1 was refused -- just held, so "
                        + "probeHeaderCap must classify the same refusal as INCONCLUSIVE too; cause: "
                        + result.cause());
        assertInstanceOf(ConnectException.class, result.cause(),
                "probeHeaderCap's own connect() should fail the same way the premise check's did");
        assertEquals("could not connect to the self-check probe server", result.inconclusiveReason());
    }

    /**
     * The companion case: once {@code connect()} itself succeeds, an ordinary answered probe (here,
     * against a real, listening, context-less {@code HttpServer} exactly like
     * {@link RavenrootServer#verifyRequestHeaderCapTookEffect()} creates) is neither of the failure
     * outcomes above -- it is confirmation the cap in this JVM comfortably covers a modest probe size.
     */
    @Test
    void anAnsweredProbeAgainstARealServerIsConfirmedOk() throws Exception {
        HttpServer probeServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        probeServer.start();
        try {
            var result = RavenrootServer.probeHeaderCap(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), probeServer.getAddress().getPort()),
                    10_000);

            assertEquals(RavenrootServer.HeaderCapProbeOutcome.CAP_CONFIRMED_OK, result.outcome(),
                    "a small, ordinary probe against a live server should be answered (a 404, since this "
                            + "server has no registered context), not classified as inconclusive or as "
                            + "proof the cap is wrong; cause: " + result.cause());
        } finally {
            probeServer.stop(0);
        }
    }
}
