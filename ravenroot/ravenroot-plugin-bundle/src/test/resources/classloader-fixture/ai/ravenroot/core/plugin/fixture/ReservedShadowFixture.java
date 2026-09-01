package ai.ravenroot.core.plugin.fixture;

/**
 * BUNDLE-side fixture (PLAT-12). Deliberately NOT under src/test/java: it shares its
 * fully-qualified name with the HOST-side ai.ravenroot.core.plugin.fixture.ReservedShadowFixture, which
 * IS under src/test/java, so this file must never be on this module's own compile classpath or javac
 * would collide on the duplicate class name. PluginClassLoaderTest compiles this source file
 * separately, at test run time, into an isolated output directory and packs the result into a bundle
 * jar -- never onto this module's own classpath -- specifically to prove that PluginClassLoader,
 * loading this exact binary name, resolves the HOST's class (origin() == "host"), not this one, because
 * ai.ravenroot.core. is parent-first-preferred.
 */
public final class ReservedShadowFixture {
    private ReservedShadowFixture() {
    }

    public static String origin() {
        return "bundle-fake";
    }
}
