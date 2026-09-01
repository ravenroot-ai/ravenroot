package jakarta.ravenroottest.fixture;

/**
 * BUNDLE-side fixture (PLAT-12). Shares its fully-qualified name with the HOST-side
 * jakarta.ravenroottest.fixture.ChildFirstShadowFixture (under src/test/java); compiled separately, at
 * test run time, into a bundle jar to prove PluginClassLoader resolves THIS class (origin() ==
 * "bundle-fake"), not the host's, because jakarta. remains child-first -- the regression guard proving
 * the javax.-only addition did not also make jakarta. parent-first.
 */
public final class ChildFirstShadowFixture {
    private ChildFirstShadowFixture() {
    }

    public static String origin() {
        return "bundle-fake";
    }
}
