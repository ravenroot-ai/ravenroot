package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcDriverLoaderTest {
    private static final String INITIALIZED = "ravenroot.jdbc.fixture.initialized";

    @Test
    void verifiesIsolatedArtifactIdentityAndDigestBeforeDriverInitialization(@TempDir Path workspace)
            throws Exception {
        Path jar = compileDriver(workspace, "pinned-driver.jar");
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            System.clearProperty(INITIALIZED);
            JdbcProfile mismatched = profile("pinned-driver", "0".repeat(64));

            JdbcFailure refused = assertThrows(JdbcFailure.class,
                    () -> JdbcDriverLoader.verified(loader).load(mismatched));

            assertEquals(JdbcFailure.Code.DRIVER_REFUSED, refused.code());
            assertNull(System.getProperty(INITIALIZED), "a mismatched artifact must execute no static initializer");

            Driver accepted = JdbcDriverLoader.verified(loader).load(profile("pinned-driver", sha256(jar)));
            assertInstanceOf(Driver.class, accepted);
            assertEquals("true", System.getProperty(INITIALIZED));
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void refusesTamperedOrUnexpectedlyNamedDriverJars(@TempDir Path workspace) throws Exception {
        Path tampered = compileDriver(workspace.resolve("tampered"), "expected-driver.jar");
        String original = sha256(tampered);
        Files.writeString(tampered, "tamper", java.nio.file.StandardOpenOption.APPEND);
        try (var loader = new URLClassLoader(new java.net.URL[]{tampered.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            System.clearProperty(INITIALIZED);
            assertThrows(JdbcFailure.class,
                    () -> JdbcDriverLoader.verified(loader).load(profile("expected-driver", original)));
            assertNull(System.getProperty(INITIALIZED));
        }

        Path wrongName = compileDriver(workspace.resolve("wrong-name"), "actual-driver.jar");
        try (var loader = new URLClassLoader(new java.net.URL[]{wrongName.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            System.clearProperty(INITIALIZED);
            assertThrows(JdbcFailure.class,
                    () -> JdbcDriverLoader.verified(loader).load(profile("expected-driver", sha256(wrongName))));
            assertNull(System.getProperty(INITIALIZED));
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void replacementAfterVerificationCannotChangeTheBytesThatDefineTheDriver(@TempDir Path workspace)
            throws Exception {
        Path installed = compileDriver(workspace.resolve("installed"), "pinned-driver.jar", "original");
        Path alternate = compileDriver(workspace.resolve("alternate"), "alternate.jar", "alternate");
        String pinned = sha256(installed);
        try (var loader = new URLClassLoader(new java.net.URL[]{installed.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            System.clearProperty(INITIALIZED);
            JdbcDriverLoader verified = JdbcDriverLoader.verified(loader, () -> {
                try {
                    Files.copy(alternate, installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });

            Driver driver = verified.load(profile("pinned-driver", pinned));

            assertInstanceOf(Driver.class, driver);
            assertEquals("original", System.getProperty(INITIALIZED),
                    "class definition must use the verified private copy, not the replaced installation path");
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void rejectsEveryMultiReleaseFormBeforeInitializationWhileAcceptingFlatJava21Jar(@TempDir Path workspace)
            throws Exception {
        Path base = compileDriver(workspace.resolve("base"), "base.jar", "base");
        byte[] driverClass = jarEntry(base, "fixture/PinnedDriver.class");

        Path java21 = writeJar(workspace.resolve("java21/pinned-driver.jar"), true, Map.of(
                "fixture/PinnedDriver.class", driverClass,
                "META-INF/versions/21/fixture/PinnedDriver.class", driverClass));
        Path malformed = writeJar(workspace.resolve("malformed/pinned-driver.jar"), false, Map.of(
                "fixture/PinnedDriver.class", driverClass,
                "META-INF/versions/twenty-one/fixture/PinnedDriver.class", driverClass));
        Path versionConflict = writeJar(workspace.resolve("conflict/pinned-driver.jar"), true, Map.of(
                "fixture/PinnedDriver.class", driverClass,
                "META-INF/versions/9/fixture/PinnedDriver.class", driverClass,
                "META-INF/versions/21/fixture/PinnedDriver.class", driverClass));

        try {
            assertMultiReleaseRefused(java21);
            assertMultiReleaseRefused(malformed);
            assertMultiReleaseRefused(versionConflict);

            Path flat = workspace.resolve("flat/pinned-driver.jar");
            Files.createDirectories(flat.getParent());
            Files.copy(base, flat);
            try (var loader = new URLClassLoader(new java.net.URL[]{flat.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                Driver accepted = JdbcDriverLoader.verified(loader).load(profile("pinned-driver", sha256(flat)));
                assertInstanceOf(Driver.class, accepted);
                assertEquals("base", System.getProperty(INITIALIZED));
            }
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void privateLoaderTcclCoversDependenciesResourcesServicesAndConnectAndAlwaysRestores(@TempDir Path workspace)
            throws Exception {
        String marker = "one";
        Path jar = compileContextDriver(workspace, "pinned-driver.jar", marker);
        try (var discovery = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
             var sentinel = new URLClassLoader(new java.net.URL[0], ClassLoader.getPlatformClassLoader())) {
            Thread thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            thread.setContextClassLoader(sentinel);
            try {
                Driver driver = JdbcDriverLoader.verified(discovery)
                        .load(profile("pinned-driver", sha256(jar)));
                assertEquals(marker + ":dependency:service", System.getProperty(INITIALIZED));
                assertSame(sentinel, thread.getContextClassLoader(), "load/initialization must restore caller TCCL");

                JdbcDriverLoader.inContext(driver, () -> driver.connect("jdbc:fixture:test", new java.util.Properties()));
                assertEquals(marker + ":dependency:service", System.getProperty(INITIALIZED + ".connect"));
                assertSame(sentinel, thread.getContextClassLoader(), "driver calls must restore caller TCCL");

                assertThrows(IllegalStateException.class,
                        () -> JdbcDriverLoader.inContext(driver, () -> { throw new IllegalStateException("fixture"); }));
                assertSame(sentinel, thread.getContextClassLoader(), "exceptional driver calls must restore TCCL");
            } finally {
                thread.setContextClassLoader(original);
                System.clearProperty(INITIALIZED);
                System.clearProperty(INITIALIZED + ".connect");
            }
        }
    }

    @Test
    void concurrentPrivateDriverContextsRemainIsolated(@TempDir Path workspace) throws Exception {
        Path firstJar = compileContextDriver(workspace.resolve("first"), "first-driver.jar", "first");
        Path secondJar = compileContextDriver(workspace.resolve("second"), "second-driver.jar", "second");
        try (var firstDiscovery = new URLClassLoader(new java.net.URL[]{firstJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
             var secondDiscovery = new URLClassLoader(new java.net.URL[]{secondJar.toUri().toURL()},
                     ClassLoader.getPlatformClassLoader())) {
            Driver first = JdbcDriverLoader.verified(firstDiscovery)
                    .load(profile("first-driver", sha256(firstJar)));
            Driver second = JdbcDriverLoader.verified(secondDiscovery)
                    .load(profile("second-driver", sha256(secondJar)));

            CompletableFuture<String> one = CompletableFuture.supplyAsync(() -> contextMarker(first));
            CompletableFuture<String> two = CompletableFuture.supplyAsync(() -> contextMarker(second));
            assertEquals(List.of("first", "second"), List.of(one.join(), two.join()));
        } finally {
            System.clearProperty(INITIALIZED);
            System.clearProperty(INITIALIZED + ".connect");
        }
    }

    @Test
    void exactDriverIdSelectsAmongSameNamedClassesWithoutClasspathOrderOrCrossVisibility(@TempDir Path workspace)
            throws Exception {
        Path postgresql = compileDriver(workspace.resolve("postgresql"), "postgresql-42.7.7.jar", "postgresql");
        Path mysql = compileDriver(workspace.resolve("mysql"), "mysql-connector-j-9.5.0.jar", "mysql");
        try (var bundle = new URLClassLoader(new java.net.URL[]{mysql.toUri().toURL(), postgresql.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Driver selectedPostgresql = JdbcDriverLoader.verified(bundle)
                    .load(profile("postgresql-42.7.7", sha256(postgresql)));
            assertEquals("postgresql", System.getProperty(INITIALIZED));

            Driver selectedMysql = JdbcDriverLoader.verified(bundle)
                    .load(profile("mysql-connector-j-9.5.0", sha256(mysql)));
            assertEquals("mysql", System.getProperty(INITIALIZED));
            org.junit.jupiter.api.Assertions.assertNotSame(
                    selectedPostgresql.getClass().getClassLoader(), selectedMysql.getClass().getClassLoader());
            assertEquals(selectedPostgresql.getClass().getName(), selectedMysql.getClass().getName(),
                    "same driverClass name in two jars must remain isolated by exact driverId");
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    private static JdbcProfile profile(String driverId, String digest) {
        JdbcProfile base = JdbcTestSupport.profile(JdbcTestSupport.query("SELECT id FROM users"));
        return new JdbcProfile(base.tenant(), base.name(), driverId, "fixture.PinnedDriver", digest,
                base.url(), base.username(), base.credentialRef(), base.isolation(), base.deadlineMs(),
                base.maxConcurrency(), base.maxParameters(), base.maxParameterBytes(), base.maxRows(),
                base.maxColumns(), base.maxCellBytes(), base.maxTotalBytes(), base.maxGeneratedKeyRows(),
                base.statements());
    }

    private static Path compileDriver(Path workspace, String jarName) throws Exception {
        return compileDriver(workspace, jarName, "true");
    }

    private static Path compileDriver(Path workspace, String jarName, String initializationMarker) throws Exception {
        Files.createDirectories(workspace);
        Path sources = Files.createDirectories(workspace.resolve("src/fixture"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Path source = sources.resolve("PinnedDriver.java");
        Files.writeString(source, """
                package fixture;
                public final class PinnedDriver implements java.sql.Driver {
                    static { System.setProperty("%s", "%s"); }
                    public java.sql.Connection connect(String u, java.util.Properties p) { return null; }
                    public boolean acceptsURL(String u) { return false; }
                    public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {
                        return new java.sql.DriverPropertyInfo[0];
                    }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
                }
                """.formatted(INITIALIZED, initializationMarker));
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-d", classes.toString(), source.toString());
        assertEquals(0, compiled);
        Path jar = workspace.resolve(jarName);
        try (OutputStream file = Files.newOutputStream(jar); var zip = new JarOutputStream(file);
             InputStream bytecode = Files.newInputStream(classes.resolve("fixture/PinnedDriver.class"))) {
            zip.putNextEntry(new JarEntry("fixture/PinnedDriver.class"));
            bytecode.transferTo(zip);
            zip.closeEntry();
        }
        return jar;
    }

    private static Path compileContextDriver(Path workspace, String jarName, String marker) throws Exception {
        Files.createDirectories(workspace);
        Path sources = Files.createDirectories(workspace.resolve("src/fixture"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Files.writeString(sources.resolve("Marker.java"),
                "package fixture; public interface Marker { String value(); }");
        Files.writeString(sources.resolve("MarkerImpl.java"),
                "package fixture; public final class MarkerImpl implements Marker { public String value() { return \"service\"; } }");
        Files.writeString(sources.resolve("Dependency.java"),
                "package fixture; final class Dependency { static String value() { return \"dependency\"; } }");
        Files.writeString(sources.resolve("PinnedDriver.java"), """
                package fixture;
                public final class PinnedDriver implements java.sql.Driver {
                    static { observe("%s"); }
                    private static void observe(String property) {
                        try {
                            ClassLoader loader = Thread.currentThread().getContextClassLoader();
                            String resource;
                            try (java.io.InputStream input = loader.getResourceAsStream("fixture/marker.txt")) {
                                resource = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            }
                            String service = java.util.ServiceLoader.load(Marker.class, loader).findFirst().orElseThrow().value();
                            System.setProperty(property, resource + ":" + Dependency.value() + ":" + service);
                        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
                    }
                    public java.sql.Connection connect(String u, java.util.Properties p) {
                        observe("%s.connect"); return null;
                    }
                    public boolean acceptsURL(String u) { return false; }
                    public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {
                        return new java.sql.DriverPropertyInfo[0];
                    }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
                }
                """.formatted(INITIALIZED, INITIALIZED));
        List<String> arguments = new ArrayList<>(List.of("--release", "21", "-d", classes.toString()));
        try (var files = Files.list(sources)) {
            files.map(Path::toString).sorted().forEach(arguments::add);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(String[]::new)));

        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (var files = Files.walk(classes)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try { entries.put(classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path)); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        }
        entries.put("fixture/marker.txt", marker.getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/fixture.Marker", "fixture.MarkerImpl\n".getBytes(StandardCharsets.UTF_8));
        return writeJar(workspace.resolve(jarName), false, entries);
    }

    private static Path writeJar(Path jar, boolean multiRelease, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        try (OutputStream file = Files.newOutputStream(jar); var zip = new JarOutputStream(file, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new JarEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] jarEntry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile());
             InputStream input = archive.getInputStream(archive.getJarEntry(name))) {
            return input.readAllBytes();
        }
    }

    private static void assertMultiReleaseRefused(Path jar) throws Exception {
        System.clearProperty(INITIALIZED);
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            JdbcFailure refused = assertThrows(JdbcFailure.class,
                    () -> JdbcDriverLoader.verified(loader).load(profile("pinned-driver", sha256(jar))));
            assertEquals(JdbcFailure.Code.DRIVER_REFUSED, refused.code());
            assertNull(System.getProperty(INITIALIZED), "an MR image must execute no initializer");
        }
    }

    private static String contextMarker(Driver driver) {
        try {
            return JdbcDriverLoader.inContext(driver, () -> {
                ClassLoader before = Thread.currentThread().getContextClassLoader();
                try (InputStream input = before.getResourceAsStream("fixture/marker.txt")) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            });
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
