package ai.ravenroot.core.plugin.fixture;

/**
 * Test fixture only (PLAT-12): compiled normally as part of this module's own test
 * sources, so it is visible to whatever classloader ends up as {@code PluginClassLoaderTest}'s
 * PARENT -- simulating "the host already provides a real implementation under a reserved namespace".
 *
 * <p>{@link #origin()} distinguishes this HOST version from a differently-compiled BUNDLE version of
 * the exact same fully-qualified name that {@code PluginClassLoaderTest} compiles separately, at test
 * time, from {@code src/test/resources/classloader-fixture/} -- never on this module's own compile
 * classpath, so the two {@code .class} files with the same name can coexist without a {@code javac}
 * collision, and packs into a fixture bundle jar. Loading {@code ai.ravenroot.core.plugin.fixture.
 * ReservedShadowFixture} through a real {@link ai.ravenroot.plugin.bundle.PluginClassLoader} (parent =
 * this class's own classloader, bundle jar = the compiled-at-test-time fake) and asserting
 * {@code origin()} still returns {@code "host"} is the runtime-layer proof that parent-first delegation
 * actually prevents a bundle from shadowing a reserved-namespace class -- on top of, not instead of,
 * the build-time refusal (this fixture's sibling, {@link ReservedFixtureBehavior}, is what
 * that build-time check is proven against).</p>
 */
public final class ReservedShadowFixture {
    private ReservedShadowFixture() {
    }

    public static String origin() {
        return "host";
    }
}
