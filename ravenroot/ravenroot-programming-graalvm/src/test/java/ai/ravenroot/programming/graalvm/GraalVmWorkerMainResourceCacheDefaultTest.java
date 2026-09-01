package ai.ravenroot.programming.graalvm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Names {@link GraalVmWorkerMain#applyResourceCacheDefault(Path, String)} directly,
 * by method, so a future regression reads as a failure of THIS function rather than as an
 * unrelated symptom several layers downstream. Before this class existed, the only red/green
 * control on the {@code /opt/ravenroot} existence guard was mutation testing: mutating the guard
 * away turned {@code PythonSandboxLimitBreachTest} red with a message about {@code subprocess} --
 * true, but useless to whoever reads it next, because nothing in that failure names the function
 * that actually broke.
 *
 * <p>Every test clears the system property it touches in {@link #restoreProperty()}: these tests
 * share a JVM with the rest of this module's suite, and {@code polyglot.engine.userResourceCache}
 * leaking between tests would make later tests depend on execution order.
 */
class GraalVmWorkerMainResourceCacheDefaultTest {
    private static final String PROPERTY = "polyglot.engine.userResourceCache";

    @AfterEach
    void restoreProperty() {
        System.clearProperty(PROPERTY);
    }

    /**
     * The regression this class exists to name: an image root that does not exist (any development
     * machine or CI runner outside the shipped container) must leave the property untouched, so
     * GraalVM's own {@code $XDG_CACHE_HOME}/{@code $HOME/.cache} resolution keeps working exactly
     * as it did previously. Applying {@link GraalVmWorkerMain#DEFAULT_RESOURCE_CACHE_DIR}
     * unconditionally here is precisely what broke {@code PythonSandboxLimitBreachTest} on a
     * development machine before this guard existed.
     */
    @Test
    void aMissingImageRootLeavesThePropertyUntouched(@TempDir Path directory) {
        Path notTheImage = directory.resolve("not-opt-ravenroot");

        GraalVmWorkerMain.applyResourceCacheDefault(notTheImage, null);

        assertNull(System.getProperty(PROPERTY),
                "outside the shipped image, this method must not set anything, or GraalVM's own "
                        + "default resolution -- the previously working behaviour -- regresses");
    }

    /** The shipped image's own filesystem layout: {@code /opt/ravenroot} genuinely exists. */
    @Test
    void anExistingImageRootAppliesTheDefault(@TempDir Path directory) {
        GraalVmWorkerMain.applyResourceCacheDefault(directory, null);

        assertEquals(GraalVmWorkerMain.DEFAULT_RESOURCE_CACHE_DIR, System.getProperty(PROPERTY));
    }

    /**
     * {@code RAVENROOT_GRAAL_RESOURCE_CACHE_DIR}, when it reaches this process's own environment,
     * overrides the default regardless of whether the image root exists -- an integrator's own
     * supervisor may run this worker outside {@code /opt/ravenroot} entirely.
     */
    @Test
    void anEnvironmentOverrideWinsOverTheDefault(@TempDir Path directory) {
        GraalVmWorkerMain.applyResourceCacheDefault(directory, "/custom/cache/path");

        assertEquals("/custom/cache/path", System.getProperty(PROPERTY));
    }

    /**
     * A blank override is treated as absent, not as a literal empty path -- an operator who sets
     * the variable to the empty string almost certainly means "unset", and {@code Path.of("")}
     * would resolve to the current working directory, which is never what was intended.
     */
    @Test
    void aBlankEnvironmentOverrideFallsThroughToTheDefault(@TempDir Path directory) {
        GraalVmWorkerMain.applyResourceCacheDefault(directory, "   ");

        assertEquals(GraalVmWorkerMain.DEFAULT_RESOURCE_CACHE_DIR, System.getProperty(PROPERTY));
    }

    /**
     * The case that decides which of two mechanisms actually made {@code import json} work under
     * {@code deploy/dev/sandbox-supervisor.sh}, the one supervisor this repository ships: it
     * already passes {@code -Dpolyglot.engine.userResourceCache=...} on the worker's own command
     * line, so by the time {@code main()} runs, the property is already set -- and this method must
     * never override an already-set property, from any source, including its own default and any
     * environment override.
     */
    @Test
    void anAlreadySetPropertyIsNeverOverridden(@TempDir Path directory) {
        System.setProperty(PROPERTY, "/set/by/something/else");

        GraalVmWorkerMain.applyResourceCacheDefault(directory, "/would/be/the/override");

        assertEquals("/set/by/something/else", System.getProperty(PROPERTY));
    }
}
