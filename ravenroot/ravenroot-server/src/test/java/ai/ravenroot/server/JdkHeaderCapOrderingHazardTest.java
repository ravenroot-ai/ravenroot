package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates, in a fresh out-of-process JVM, the two claims
 * {@link RavenrootServer}'s static initializer makes about itself -- see its "Set here, verified at
 * {@code start()}" Javadoc -- instead of assuming either one holds inside this module's own Surefire
 * run, which cannot settle either question by construction (it runs every test class in one already-
 * forked, shared JVM; see {@link JdkHeaderCapOrderingHazardBoundary}'s Javadoc for why that makes
 * "first" mean "whichever class Surefire happens to schedule first" rather than the thing under test).
 *
 * <p>Same child-process pattern as {@code qa03.DeploymentIngressKillTest}: this JVM's own
 * {@code java.class.path}, a plain {@code java} invocation, the boundary class's marker lines on stdout.
 */
class JdkHeaderCapOrderingHazardTest {

    /**
     * Reproduces the hazard directly, then confirms {@link RavenrootServer#start()} catches it instead
     * of leaving it silent: a foreign {@code com.sun.net.httpserver.HttpServer}, created before anything
     * touches {@code RavenrootServer}, permanently locks {@code sun.net.httpserver.ServerConfig} onto
     * the JDK's own 380 KiB default for the rest of that JVM -- {@code System.setProperty} still
     * "succeeds" (later reads of the property return the intended 2 MiB), but the JDK's own request
     * parser never sees it. Without the startup probe, the property looks applied while the live cap
     * remains unchanged. This is the failure {@code security/TestOidcProvider.java} would reproduce if
     * it created its plain server first.
     */
    @Test
    void aForeignHttpServerCreatedFirstIsCaughtNotSilentlyInert() throws Exception {
        Result result = run("foreign-first", List.of());

        assertTrue(result.stdout().contains(JdkHeaderCapOrderingHazardBoundary.FOREIGN_SERVER_UP),
                "the boundary process never reported creating the foreign HttpServer, so this run did "
                        + "not exercise the ordering it claims to; transcript: " + result.stdout());
        // Not just the HAZARD_DETECTED prefix: verifyRequestHeaderCapTookEffect() can throw
        // IllegalStateException for two different reasons -- the cap diagnosis and a "could not verify"
        // wrapper for an inconclusive probe that the boundary reports with the same marker. A transient timeout on the
        // loopback probe could make this assertion pass for the wrong reason -- the exact class of
        // false-positive class this test eliminates -- unless the message itself, not
        // just which exception type was thrown, is checked.
        assertTrue(result.stdout().stream().anyMatch(line -> line.startsWith(JdkHeaderCapOrderingHazardBoundary.HAZARD_DETECTED)
                        && line.contains("maxReqHeaderSize did not take effect")),
                "RavenrootServer#start() did not fail fast, specifically diagnosing that "
                        + "sun.net.httpserver.maxReqHeaderSize did not take effect, when "
                        + "sun.net.httpserver.ServerConfig was already locked onto the JDK's 380 KiB "
                        + "default by a foreign HttpServer -- either the configured cap is silently inert in exactly "
                        + "the ordering scenario under test, or the probe failed for an unrelated, inconclusive "
                        + "reason that proves nothing; transcript: " + result.stdout());
        assertEquals(0, result.exitCode(), "the boundary process's own exit code disagreed with its "
                + "stdout marker; transcript: " + result.stdout());
    }

    /**
     * The other half of the same claim: an operator's own {@code -D} must survive RavenrootServer's
     * static initializer untouched. This is tested directly with a value distinguishable from both the JDK default (380 KiB)
     * and this class's own intended default (2 MiB), so a static initializer that quietly overwrote it
     * either way would be caught.
     */
    @Test
    void anOperatorsExplicitPropertyIsReadNotOverwritten() throws Exception {
        String operatorValue = "999999";
        Result result = run("operator-override",
                List.of("-Dsun.net.httpserver.maxReqHeaderSize=" + operatorValue));

        assertEquals(0, result.exitCode(), "transcript: " + result.stdout());
        assertTrue(result.stdout().contains(JdkHeaderCapOrderingHazardBoundary.PROPERTY_AFTER_STATIC_INIT_PREFIX + operatorValue),
                "RavenrootServer's static initializer must read an already-set "
                        + "sun.net.httpserver.maxReqHeaderSize, not overwrite it with its own default; "
                        + "expected " + JdkHeaderCapOrderingHazardBoundary.PROPERTY_AFTER_STATIC_INIT_PREFIX + operatorValue
                        + " on stdout; transcript: " + result.stdout());
    }

    /**
     * An operator's override above {@code Integer.MAX_VALUE} must not kill startup. This class
     * reads the property with {@code Long.parseLong}, which accepts it fine; {@code 3000000000} is the
     * exact boundary value under test; an {@code (int)} cast wraps it to
     * {@code -1294975488}, which {@code String.repeat} then rejected with
     * {@code IllegalArgumentException: count is negative: -1294975488} -- killing {@link
     * RavenrootServer#start()} entirely, for a value {@code sun.net.httpserver.ServerConfig} itself
     * would have silently ignored via {@code Integer.getInteger} anyway. Asserts more than "did not
     * throw": the two probe markers are direct proof, sent against the real running server, that the
     * live cap ends up exactly where the JDK's own default would put it -- not a generic "startup
     * succeeded" that could pass for an unrelated reason.
     */
    @Test
    void anIntOverflowingPropertyIsIgnoredNotFatal() throws Exception {
        String overflowingValue = "3000000000";
        Result result = run("overflow-override",
                List.of("-Dsun.net.httpserver.maxReqHeaderSize=" + overflowingValue));

        assertEquals(0, result.exitCode(), "RavenrootServer#start() must not die on an int-overflowing "
                + "sun.net.httpserver.maxReqHeaderSize override -- sun.net.httpserver.ServerConfig itself "
                + "reads this property with Integer.getInteger and silently falls back to its own default "
                + "for exactly this value; transcript: " + result.stdout());
        assertTrue(result.stdout().contains(JdkHeaderCapOrderingHazardBoundary.OVERFLOW_SMALL_PROBE_OK),
                "a header comfortably under the JDK's own 380 KiB default must still be accepted; "
                        + "transcript: " + result.stdout());
        assertTrue(result.stdout().contains(JdkHeaderCapOrderingHazardBoundary.OVERFLOW_LARGE_PROBE_REJECTED),
                "a header over the JDK's own 380 KiB default must still be rejected -- proving the live "
                        + "cap is the JDK's own default, not the operator's out-of-range override, exactly "
                        + "as if the property had never been set; transcript: " + result.stdout());
    }

    private record Result(int exitCode, List<String> stdout) {
    }

    private static Result run(String scenario, List<String> extraJvmArgs) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        var command = new ArrayList<String>();
        command.add(java.toString());
        command.addAll(extraJvmArgs);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(JdkHeaderCapOrderingHazardBoundary.class.getName());
        command.add(scenario);

        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        var lines = new ArrayList<String>();
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                lines.add(line);
            }
        }
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "boundary process (" + scenario + ") never exited; "
                + "transcript so far: " + lines);
        return new Result(child.exitValue(), lines);
    }
}
