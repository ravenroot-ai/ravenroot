package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Driver;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the multi-release jar the PostgreSQL project publishes, exactly as operators receive it.
 * PostgreSQL JDBC 42.7.12 carries a Java 11 variant of {@code org.postgresql.util.LazyCleanerImpl}
 * built on {@code java.lang.ref.Cleaner}, next to a Java 8 base variant that runs its own thread.
 *
 * <p>The jar is the test-scoped operator-driver fixture the module already declares, taken from
 * the build's own resolution of it. Nothing copies or re-downloads it, and the digest below pins
 * the exact bytes, so moving {@code postgresql-jdbc.version} to a driver of another shape fails
 * here rather than quietly weakening what this test proves.
 */
class JdbcMultiReleaseVendorDriverTest {
    private static final String DRIVER_ID = "postgresql-42.7.12";
    /** Equal to the digest GitHub records for the jar asset of the vendor's REL42.7.12 release. */
    private static final String DRIVER_SHA256 =
            "31fbf6f06b2217fb51d5100cee51b22625cc81640da0679b47914e54c1e6377c";
    private static final String CLEANER = "org/postgresql/util/LazyCleanerImpl.class";
    private static final String CLEANER_JAVA_11 = "META-INF/versions/11/" + CLEANER;

    @Test
    void vendorMultiReleaseDriverIsDefinedFromTheImageResolvedForTheTargetRelease() throws Exception {
        Path driverJar = resolvedDriverJar();
        assertEquals(JdbcDriverArtifactName.fileName(DRIVER_ID), driverJar.getFileName().toString(),
                "the operator-driver fixture is the multi-release vendor jar this test pins");
        assertEquals(DRIVER_SHA256, sha256(driverJar), "the vendor jar is pinned to exact bytes");
        byte[] baseCleaner;
        byte[] java11Cleaner;
        try (JarFile vendor = new JarFile(driverJar.toFile())) {
            assertEquals("true", vendor.getManifest().getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE));
            baseCleaner = vendor.getInputStream(vendor.getJarEntry(CLEANER)).readAllBytes();
            java11Cleaner = vendor.getInputStream(vendor.getJarEntry(CLEANER_JAVA_11)).readAllBytes();
        }
        assertFalse(Arrays.equals(baseCleaner, java11Cleaner), "the fixture must carry two real variants");

        Driver driver = null;
        Thread thread = Thread.currentThread();
        ClassLoader caller = thread.getContextClassLoader();
        try (var bundle = new URLClassLoader(new java.net.URL[]{driverJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            driver = JdbcDriverLoader.verified(bundle).load(profile());
            assertSame(caller, thread.getContextClassLoader(), "loading restores the caller context");

            ClassLoader image = driver.getClass().getClassLoader();
            assertEquals("org.postgresql.Driver", driver.getClass().getName());
            assertInstanceOf(JdbcDriverLoader.PrivateDriverClassLoader.class, image);

            // The image holds the Java 11 variant under its plain name, and only that variant.
            try (InputStream resolved = image.getResourceAsStream(CLEANER)) {
                assertArrayEquals(java11Cleaner, resolved.readAllBytes());
            }
            assertNull(image.getResource(CLEANER_JAVA_11), "the versioned namespace is resolved, not exposed");
            assertNull(image.getResourceAsStream(CLEANER_JAVA_11),
                    "nor is it reachable through the stream accessor a driver would use");
            // A versioned resource that is not a class is resolved the same way: the Java 11
            // OSGi metadata this jar carries is in the image under its plain name only.
            assertNotNull(image.getResourceAsStream("OSGI-INF/MANIFEST.MF"));
            assertNull(image.getResource("META-INF/versions/11/OSGI-INF/MANIFEST.MF"));

            // The class actually defined is that variant: its fields are the Cleaner-based ones.
            Class<?> cleaner = Class.forName("org.postgresql.util.LazyCleanerImpl", false, image);
            assertSame(image, cleaner.getClassLoader());
            Field field = cleaner.getDeclaredField("cleaner");
            assertEquals(java.lang.ref.Cleaner.class, field.getType());
            assertFalse(Arrays.stream(cleaner.getDeclaredFields()).anyMatch(f -> f.getName().equals("queue")),
                    "the Java 8 base variant must not be the one defined");
            // A class that exists only in the versioned namespace is part of the image too.
            assertSame(image, Class.forName("org.postgresql.util.LazyCleanerImpl$CleanableWrapper", false, image)
                    .getClassLoader());
        } finally {
            // pgjdbc self-registers on initialization; its own method deregisters from its loader.
            if (driver != null) driver.getClass().getMethod("deregister").invoke(null);
        }
    }

    @Test
    void targetReleaseIsTheReleaseThisExtensionIsCompiledFor() throws Exception {
        try (var input = new DataInputStream(
                JdbcDriverLoader.class.getResourceAsStream("JdbcDriverLoader.class"))) {
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            int release = input.readUnsignedShort() - 44;
            assertEquals(release, JdbcDriverLoader.PrivateDriverClassLoader.TARGET_RELEASE,
                    "multi-release jars resolve against the product's Java release");
        }
    }

    /**
     * The test-scoped operator-driver fixture, located through the build's own resolution of it
     * rather than through a copy of its own: the same mechanism {@link JdbcDriverBundleEndToEndTest}
     * uses, so the driver version is declared in exactly one place.
     */
    private static Path resolvedDriverJar() throws Exception {
        var resource = ClassLoader.getSystemResource("org/postgresql/Driver.class");
        assertTrue(resource != null && "jar".equals(resource.getProtocol()),
                "the PostgreSQL driver is a test-scoped jar on the class path");
        var connection = (java.net.JarURLConnection) resource.openConnection();
        connection.setUseCaches(false);
        return Path.of(connection.getJarFileURL().toURI()).toAbsolutePath().normalize();
    }

    private static JdbcProfile profile() {
        JdbcProfile base = JdbcTestSupport.profile(JdbcTestSupport.query("SELECT id FROM users"));
        return new JdbcProfile(base.tenant(), base.name(), DRIVER_ID, "org.postgresql.Driver", DRIVER_SHA256,
                base.url(), base.username(), base.credentialRef(), base.isolation(), base.deadlineMs(),
                base.maxConcurrency(), base.maxParameters(), base.maxParameterBytes(), base.maxRows(),
                base.maxColumns(), base.maxCellBytes(), base.maxTotalBytes(), base.maxGeneratedKeyRows(),
                base.statements());
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
