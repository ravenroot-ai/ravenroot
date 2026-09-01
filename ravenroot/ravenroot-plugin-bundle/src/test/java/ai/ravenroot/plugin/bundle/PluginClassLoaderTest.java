package ai.ravenroot.plugin.bundle;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runtime-layer proof for {@link PluginClassLoader}'s parent-first/child-first policy, on top of --
 * not instead of -- the build-time refusal (PLAT-12). Until this class
 * existed, {@code PluginClassLoader} was only ever exercised indirectly through
 * {@link PluginBundleLoaderTest}, which never constructs it by name or asserts anything about which
 * of two same-named classes actually resolves.
 *
 * <p>Every test here constructs a real bundle jar containing a class whose fully-qualified name
 * collides with a HOST-side fixture already on the test's own classpath (compiled normally as part of
 * this module's test sources -- see {@code ai.ravenroot.core.plugin.fixture.ReservedShadowFixture},
 * {@code javax.ravenroottest.fixture.ParentFirstShadowFixture},
 * {@code jakarta.ravenroottest.fixture.ChildFirstShadowFixture}), loads that name through a real
 * {@link PluginClassLoader} whose parent is this test class's own classloader, and asserts which
 * version actually resolves by calling a distinguishing {@code origin()} method. This deliberately
 * bypasses {@link PluginBundleValidator} entirely -- these tests are about what the classloader alone
 * guarantees, independent of whether a real bundle could ever reach this classloader with such a
 * class in the first place (for {@code ai.ravenroot.core.} it could not, per
 * {@link ClassFileOwnNameTest}; for {@code javax.} it legitimately could, since {@code isReserved}
 * is false for it).</p>
 */
class PluginClassLoaderTest {

    @Test
    void hostVersionShadowsBundleVersionForAnAlreadyReservedPrefix() throws Exception {
        URL bundleJar = compileToJar("ai/ravenroot/core/plugin/fixture/ReservedShadowFixture.java",
                "ai/ravenroot/core/plugin/fixture/ReservedShadowFixture.class");
        PluginClassLoader loader = new PluginClassLoader("test.reserved-shadow",
                new URL[] {bundleJar}, getClass().getClassLoader());

        Class<?> resolved = loader.loadClass("ai.ravenroot.core.plugin.fixture.ReservedShadowFixture");

        assertEquals("host", origin(resolved),
                "ai.ravenroot.core. was already parent-first -- this is a regression guard, not new "
                        + "behaviour, and defence in depth on top of the "
                        + "build-time refusal of this exact class");
    }

    /**
     * The current {@code javax.} rule, proven directly: previously, {@code javax.} was child-first
     * like any ordinary bundle-private package, so the BUNDLE's version would have won here. Now,
     * ReservedPluginPackages.isParentFirst includes javax., so the HOST's version must win instead --
     * exactly the property PluginClassLoader's own javadoc now documents as the only thing preventing
     * a bundle from shadowing a real JDK javax.* class, since isReserved (the build-time refusal set)
     * deliberately does NOT include javax..
     */
    @Test
    void hostVersionShadowsBundleVersionForJavaxSinceIncrement6() throws Exception {
        URL bundleJar = compileToJar("javax/ravenroottest/fixture/ParentFirstShadowFixture.java",
                "javax/ravenroottest/fixture/ParentFirstShadowFixture.class");
        PluginClassLoader loader = new PluginClassLoader("test.javax-shadow",
                new URL[] {bundleJar}, getClass().getClassLoader());

        Class<?> resolved = loader.loadClass("javax.ravenroottest.fixture.ParentFirstShadowFixture");

        assertEquals("host", origin(resolved));
    }

    /**
     * The regression guard: jakarta. must stay child-first. The dogfooding ravenroot-mail bundle ships
     * 194 real jakarta.* classes with no host-provided implementation to defer to (ReservedPluginPackages'
     * own javadoc) -- if this test ever started asserting "host" instead of "bundle-fake", it would mean
     * jakarta. had quietly become parent-first-preferred too, breaking that bundle's own dependency
     * resolution the same way the original, since-corrected javax./jakarta. reservation did.
     */
    @Test
    void bundleVersionWinsOverHostVersionForJakarta() throws Exception {
        URL bundleJar = compileToJar("jakarta/ravenroottest/fixture/ChildFirstShadowFixture.java",
                "jakarta/ravenroottest/fixture/ChildFirstShadowFixture.class");
        PluginClassLoader loader = new PluginClassLoader("test.jakarta-child-first",
                new URL[] {bundleJar}, getClass().getClassLoader());

        Class<?> resolved = loader.loadClass("jakarta.ravenroottest.fixture.ChildFirstShadowFixture");

        assertEquals("bundle-fake", origin(resolved));
    }

    /**
     * The "no capability lost" half of the javax. claim: a javax.* class the host does NOT provide --
     * no host-side fixture exists anywhere on the test classpath under this exact name -- must still
     * resolve from the bundle's own jar. Parent-first is a preference exercised only when a host
     * version actually exists (super.loadClass failing in the parent falls through to this loader's
     * own findClass, per PluginClassLoader's own javadoc); it is not a hard refusal of bundle-private
     * content merely for living under a parent-first-preferred prefix.
     */
    @Test
    void bundleOnlyJavaxClassWithNoHostImplementationStillResolves() throws Exception {
        URL bundleJar = compileToJar("javax/ravenroottest/fixtureonly/BundleOnlyFixture.java",
                "javax/ravenroottest/fixtureonly/BundleOnlyFixture.class");
        PluginClassLoader loader = new PluginClassLoader("test.javax-bundle-only",
                new URL[] {bundleJar}, getClass().getClassLoader());

        Class<?> resolved = loader.loadClass("javax.ravenroottest.fixtureonly.BundleOnlyFixture");

        assertEquals("bundle-only", origin(resolved));
    }

    private static String origin(Class<?> type) throws ReflectiveOperationException {
        return (String) type.getMethod("origin").invoke(null);
    }

    /**
     * Compiles one source file from src/test/resources/classloader-fixture/, at test run time, into an
     * isolated output directory, then packs the single resulting .class file into a fresh one-entry jar
     * -- never touching this module's own compile classpath, which already carries a differently
     * -compiled class of the exact same fully-qualified name (see this class's own javadoc). Mirrors
     * PluginBundleLoaderTest's compileMissingDepFixture/writeJar pattern rather than inventing a second
     * one.
     */
    private static URL compileToJar(String sourceRelativePath, String classRelativePath) throws IOException,
            URISyntaxException {
        Path sourceDir = Path.of(PluginClassLoaderTest.class.getResource("/classloader-fixture").toURI());
        Path outputDir = Files.createTempDirectory("classloader-fixture-classes");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null,
                "-d", outputDir.toString(),
                "-cp", System.getProperty("java.class.path"),
                sourceDir.resolve(sourceRelativePath).toString());
        if (result != 0) {
            throw new IllegalStateException("Dynamic fixture compilation failed, exit code " + result
                    + " for " + sourceRelativePath);
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(classRelativePath, Files.readAllBytes(outputDir.resolve(classRelativePath)));

        Path jarPath = Files.createTempFile("classloader-fixture", ".jar");
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                zipOut.putNextEntry(new ZipEntry(entry.getKey()));
                zipOut.write(entry.getValue());
                zipOut.closeEntry();
            }
        }
        return jarPath.toUri().toURL();
    }
}
