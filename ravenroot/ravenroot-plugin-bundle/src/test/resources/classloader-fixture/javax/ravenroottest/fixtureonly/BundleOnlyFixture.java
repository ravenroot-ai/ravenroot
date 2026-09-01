package javax.ravenroottest.fixtureonly;

/**
 * BUNDLE-ONLY fixture (PLAT-12): no HOST-side counterpart exists anywhere on the
 * test classpath for this exact fully-qualified name. Proves the second half of the "no capability
 * lost" claim in ReservedPluginPackages' own javadoc: a genuinely bundle-private class under a
 * parent-first-preferred prefix (javax.) the host does NOT provide still resolves successfully,
 * through PluginClassLoader's own fallthrough (super.loadClass failing in the parent, then this
 * loader's own findClass reaching the bundle's jar) -- parent-first is a preference when a host
 * version exists, never a hard refusal of bundle-private content under the same prefix.
 */
public final class BundleOnlyFixture {
    private BundleOnlyFixture() {
    }

    public static String origin() {
        return "bundle-only";
    }
}
