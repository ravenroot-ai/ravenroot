package javax.ravenroottest.fixture;

/**
 * Test fixture only (PLAT-12): a HOST-side class under {@code javax.}, compiled
 * normally as part of this module's own test sources so it is visible via {@code
 * PluginClassLoaderTest}'s PARENT classloader -- simulating "the host already provides a real
 * implementation under javax." the way the real JDK provides {@code javax.crypto}/{@code javax.net}/
 * {@code javax.sql}. This package ({@code javax.ravenroottest.fixture}) is not owned by any JDK
 * module, so defining a class here from an unnamed-module classpath (a plain {@code URLClassLoader})
 * is unproblematic -- unlike trying to reuse an actual JDK-owned {@code javax.*} package, which would
 * make the test depend on module-system internals it has no need to depend on.
 *
 * <p>{@link #origin()} distinguishes this HOST version from a differently-compiled BUNDLE version of
 * the same fully-qualified name, compiled separately at test time from {@code
 * src/test/resources/classloader-fixture/}. This is the direct runtime proof for the current rule:
 * previously, {@code javax.} was child-first (same as any ordinary bundle-private package), so the
 * bundle's own version would have won; now, {@code
 * ReservedPluginPackages.isParentFirst} includes {@code javax.}, so this HOST version must win
 * instead.</p>
 */
public final class ParentFirstShadowFixture {
    private ParentFirstShadowFixture() {
    }

    public static String origin() {
        return "host";
    }
}
