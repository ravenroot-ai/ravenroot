package ai.ravenroot.plugin.bundle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct coverage for the boundary {@code ReservedPluginPackages.isReserved} actually has to draw;
 * an earlier boundary refused the dogfooding bundle that {@code plugin.sh build mail} produces.
 */
class ReservedPluginPackagesTest {

    @Test
    void trueJdkOwnedPrefixesAreStillReserved() {
        assertTrue(ReservedPluginPackages.isReserved("java.lang.String"));
        assertTrue(ReservedPluginPackages.isReserved("sun.misc.Unsafe"));
        assertTrue(ReservedPluginPackages.isReserved("jdk.internal.misc.Unsafe"));
        assertTrue(ReservedPluginPackages.isReserved("com.sun.tools.javac.Main"));
    }

    @Test
    void theHostRuntimeRootsRemainReserved() {
        assertTrue(ReservedPluginPackages.isReserved("ai.ravenroot.api.node.NodePackage"));
        assertTrue(ReservedPluginPackages.isReserved("ai.ravenroot.core.runtime.NodePackages"));
        assertTrue(ReservedPluginPackages.isReserved("ai.ravenroot.server.RavenrootServerMain"));
        assertTrue(ReservedPluginPackages.isReserved("ai.ravenroot.cli.RavenrootCli"));
        assertTrue(ReservedPluginPackages.isReserved("ai.ravenroot.distribution.Anything"));
    }

    @Test
    void ravenrootExtensionsRemainsDeliberatelyOpen() {
        assertFalse(ReservedPluginPackages.isReserved("ai.ravenroot.extensions.mail.MailNodePackage"));
    }

    /**
     * The finding: {@code javax.} and {@code jakarta.} are standard API surfaces real third-party
     * libraries ship implementation classes under -- not exclusively JDK-owned the way
     * {@code java.}/{@code sun.}/{@code jdk.}/{@code com.sun.} are. Angus Mail (jakarta.mail) and its
     * own transitive dependency Jakarta Activation (jakarta.activation) are exactly this case, and
     * neither has a host-provided implementation to defer to -- ravenroot-core and
     * ravenroot-application-api depend on neither. Reserving the namespace anyway refused the
     * dogfooding bundle the first time this was actually built, not a hypothetical. Both remain
     * NOT build-time-refused today -- this method, {@code isReserved}, never changed for either
     * prefix. What DID change is {@code isParentFirst}: see the tests below.
     */
    @Test
    void jakartaAndJavaxAreNotBuildTimeRefusedBecauseRealDependenciesLiveThere() {
        assertFalse(ReservedPluginPackages.isReserved("jakarta.mail.Message"));
        assertFalse(ReservedPluginPackages.isReserved("jakarta.activation.DataSource"));
        assertFalse(ReservedPluginPackages.isReserved("javax.mail.Message"));
    }

    @Test
    void matchingIsCaseInsensitiveAndWhitespaceTolerant() {
        assertTrue(ReservedPluginPackages.isReserved("  Java.Lang.String  "));
        assertTrue(ReservedPluginPackages.isReserved("AI.RAVENROOT.CORE.Foo"));
    }

    @Test
    void nullIsNotReserved() {
        assertFalse(ReservedPluginPackages.isReserved(null));
    }

    // ---- isParentFirst (PLAT-12): the classloader's runtime-preference set -----------------------

    /**
     * The current boundary: {@code javax.} is preferred parent-first at the
     * classloader layer (the JDK really does ship implementations under it -- javax.crypto, javax.net,
     * javax.sql) even though {@code isReserved} above still returns false for it -- a bundle CAN
     * legitimately declare a class there and pass build-time validation; the classloader is the only
     * layer that prevents it from shadowing a real JDK class when one exists.
     */
    @Test
    void javaxIsParentFirstButNotBuildTimeRefused() {
        assertTrue(ReservedPluginPackages.isParentFirst("javax.crypto.Cipher"));
        assertTrue(ReservedPluginPackages.isParentFirst("javax.ravenroottest.fixture.AnythingUnderJavax"));
        assertFalse(ReservedPluginPackages.isReserved("javax.crypto.Cipher"));
    }

    /**
     * The regression this test guards: {@code jakarta.} must stay off BOTH sets. The mail bundle's
     * 194 real {@code jakarta.*} classes prove it does not belong on {@code isReserved}; this proves
     * the runtime-preference set did not quietly re-add it
     * through the back door the way the original javax./jakarta. reservation lumped both together.
     */
    @Test
    void jakartaIsNeitherParentFirstNorBuildTimeRefused() {
        assertFalse(ReservedPluginPackages.isParentFirst("jakarta.mail.Message"));
        assertFalse(ReservedPluginPackages.isParentFirst("jakarta.activation.DataSource"));
    }

    /**
     * isParentFirst is defined as a superset of isReserved, not an independently maintained list --
     * this proves that relationship holds for every isReserved prefix, not just javax., so a future
     * addition to RESERVED_PREFIXES can never silently fail to also become parent-first-preferred.
     */
    @Test
    void everyBuildTimeRefusedPrefixIsAlsoParentFirst() {
        assertTrue(ReservedPluginPackages.isParentFirst("java.lang.String"));
        assertTrue(ReservedPluginPackages.isParentFirst("sun.misc.Unsafe"));
        assertTrue(ReservedPluginPackages.isParentFirst("jdk.internal.misc.Unsafe"));
        assertTrue(ReservedPluginPackages.isParentFirst("com.sun.tools.javac.Main"));
        assertTrue(ReservedPluginPackages.isParentFirst("ai.ravenroot.api.node.NodePackage"));
        assertTrue(ReservedPluginPackages.isParentFirst("ai.ravenroot.core.runtime.NodePackages"));
        assertTrue(ReservedPluginPackages.isParentFirst("ai.ravenroot.server.RavenrootServerMain"));
        assertTrue(ReservedPluginPackages.isParentFirst("ai.ravenroot.cli.RavenrootCli"));
        assertTrue(ReservedPluginPackages.isParentFirst("ai.ravenroot.distribution.Anything"));
    }

    @Test
    void ravenrootExtensionsIsNeitherParentFirstNorBuildTimeRefused() {
        assertFalse(ReservedPluginPackages.isParentFirst("ai.ravenroot.extensions.mail.MailNodePackage"));
    }

    @Test
    void isParentFirstMatchingIsCaseInsensitiveAndWhitespaceTolerant() {
        assertTrue(ReservedPluginPackages.isParentFirst("  Javax.Crypto.Cipher  "));
    }

    @Test
    void nullIsNotParentFirst() {
        assertFalse(ReservedPluginPackages.isParentFirst(null));
    }
}
