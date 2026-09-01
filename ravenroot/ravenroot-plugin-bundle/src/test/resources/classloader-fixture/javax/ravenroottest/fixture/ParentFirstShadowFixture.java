package javax.ravenroottest.fixture;

/**
 * BUNDLE-side fixture (PLAT-12). Shares its fully-qualified name with the HOST-side
 * javax.ravenroottest.fixture.ParentFirstShadowFixture (under src/test/java); compiled separately, at
 * test run time, into a bundle jar to prove PluginClassLoader resolves the HOST's class (origin() ==
 * "host") rather than this one, because javax. is parent-first-preferred.
 */
public final class ParentFirstShadowFixture {
    private ParentFirstShadowFixture() {
    }

    public static String origin() {
        return "bundle-fake";
    }
}
