package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.plugin.bundle.PluginArtifact;
import ai.ravenroot.plugin.bundle.PluginBundleValidator;
import ai.ravenroot.plugin.bundle.PluginCli;
import ai.ravenroot.plugin.bundle.PluginManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Driver;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDriverBundleEndToEndTest {
    private static final String POSTGRESQL_ID = "postgresql-42.7.7";
    private static final String POSTGRESQL_SHA256 =
            "157963d60ae66d607e09466e8c0cdf8087e9cb20d0159899ffca96bca2528460";
    private static final String MYSQL_ID = "mysql-connector-j-9.5.0";
    private static final String MYSQL_SHA256 =
            "f2ca3dfaf00d4aa311470db7ea3051962944ba0cb60005a2f75467549c39f425";

    @Test
    void multiDriverBundleCrossesToolingManifestProfilesAndPrivateLoaders(@TempDir Path workspace)
            throws Exception {
        Path postgresqlJar = driverJar("org/postgresql/Driver.class");
        Path mysqlJar = driverJar("com/mysql/cj/jdbc/Driver.class");
        assertEquals(JdbcDriverArtifactName.fileName(POSTGRESQL_ID), postgresqlJar.getFileName().toString());
        assertEquals(JdbcDriverArtifactName.fileName(MYSQL_ID), mysqlJar.getFileName().toString());
        JdbcDriverArtifactName.main(new String[]{postgresqlJar.getFileName().toString()});
        JdbcDriverArtifactName.main(new String[]{mysqlJar.getFileName().toString()});
        assertEquals(POSTGRESQL_SHA256, sha256(postgresqlJar),
                "the executable PostgreSQL example is pinned to exact driver bytes");
        String mysqlSha256 = sha256(mysqlJar);
        assertEquals(MYSQL_SHA256, mysqlSha256, "the executable MySQL example is pinned to exact driver bytes");

        Path extensionJar = jarModuleClasses(workspace.resolve("ravenroot-jdbc-e2e.jar"));
        Path bundle = workspace.resolve("bundle");
        assertEquals(0, generateManifest(bundle, extensionJar, postgresqlJar, POSTGRESQL_SHA256,
                mysqlJar, mysqlSha256));

        PluginManifest manifest = PluginBundleValidator.validate(bundle);
        assertEquals(List.of(POSTGRESQL_ID + ".jar", MYSQL_ID + ".jar"),
                manifest.dependencyArtifacts().stream().map(PluginArtifact::fileName).toList());
        PluginArtifact installedPostgresql = manifest.dependencyArtifacts().get(0);
        PluginArtifact installedMysql = manifest.dependencyArtifacts().get(1);
        assertEquals(POSTGRESQL_SHA256, installedPostgresql.sha256Hex());
        assertEquals(mysqlSha256, installedMysql.sha256Hex());

        String pgKey = EnvironmentJdbcProfileResolver.variable("tenant-a", "orders-pg");
        String mysqlKey = EnvironmentJdbcProfileResolver.variable("tenant-a", "crm-mysql");
        JdbcProfile pgProfile = new EnvironmentJdbcProfileResolver(Map.of(
                pgKey, encodedProfile(installedPostgresql, "org.postgresql.Driver",
                        "jdbc:postgresql://pg.internal:5432/orders", "accounting", "orders-db-password"),
                mysqlKey, encodedProfile(installedMysql, "com.mysql.cj.jdbc.Driver",
                        "jdbc:mysql://mysql.internal:3306/crm", null, "crm-db-password")))
                .resolve("tenant-a", "orders-pg").orElseThrow();
        JdbcProfile mysqlProfile = new EnvironmentJdbcProfileResolver(Map.of(
                mysqlKey, encodedProfile(installedMysql, "com.mysql.cj.jdbc.Driver",
                        "jdbc:mysql://mysql.internal:3306/crm", null, "crm-db-password")))
                .resolve("tenant-a", "crm-mysql").orElseThrow();
        assertEquals(POSTGRESQL_ID, pgProfile.driverId());
        assertEquals(MYSQL_ID, mysqlProfile.driverId());
        assertEquals("accounting", pgProfile.schema());

        Driver postgresql = null;
        try (var discovery = new URLClassLoader(
                new java.net.URL[]{bundle.resolve(installedMysql.fileName()).toUri().toURL(),
                        bundle.resolve(installedPostgresql.fileName()).toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            postgresql = JdbcDriverLoader.verified(discovery).load(pgProfile);
            Driver mysql = JdbcDriverLoader.verified(discovery).load(mysqlProfile);
            assertEquals("org.postgresql.Driver", postgresql.getClass().getName());
            assertEquals("com.mysql.cj.jdbc.Driver", mysql.getClass().getName());
            assertNotSame(discovery, postgresql.getClass().getClassLoader());
            assertNotSame(postgresql.getClass().getClassLoader(), mysql.getClass().getClassLoader());
        } finally {
            // pgjdbc self-registers on initialization. Its own method performs deregistration from
            // the correct defining loader; no connection is opened by this regression.
            if (postgresql != null) postgresql.getClass().getMethod("deregister").invoke(null);
        }
    }

    private static int generateManifest(Path bundle, Path extensionJar, Path postgresqlJar,
                                        String postgresqlDigest, Path mysqlJar, String mysqlDigest)
            throws Exception {
        Method method = PluginCli.class.getDeclaredMethod("generateManifest", String[].class);
        method.setAccessible(true);
        return (int) method.invoke(null, (Object) new String[]{"generate-manifest", bundle.toString(),
                extensionJar.toString(), JdbcNodePackage.class.getName(),
                "--pinned-dependency", postgresqlJar.toString(), postgresqlDigest,
                "--pinned-dependency", mysqlJar.toString(), mysqlDigest});
    }

    private static Path driverJar(String driverClassResource) throws Exception {
        var resource = ClassLoader.getSystemResource(driverClassResource);
        assertTrue(resource != null && "jar".equals(resource.getProtocol()));
        var connection = (JarURLConnection) resource.openConnection();
        connection.setUseCaches(false);
        return Path.of(connection.getJarFileURL().toURI()).toAbsolutePath().normalize();
    }

    private static Path jarModuleClasses(Path jar) throws Exception {
        Path classes = Path.of("target/classes");
        assertTrue(Files.isDirectory(classes));
        try (OutputStream output = Files.newOutputStream(jar); var archive = new JarOutputStream(output);
             var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String entryName = classes.relativize(file).toString().replace('\\', '/');
                archive.putNextEntry(new JarEntry(entryName));
                Files.copy(file, archive);
                archive.closeEntry();
            }
        }
        return jar;
    }

    private static String encodedProfile(PluginArtifact driver, String driverClass, String url,
                                         String schema, String credentialRef) {
        String schemaField = schema == null ? "" : ",\"schema\":\"" + schema + "\"";
        String json = """
                {"driverId":"%s","driverClass":"%s","driverSha256":"%s",
                "url":"%s","username":"app","credentialRef":"%s"%s,"isolation":"READ_COMMITTED",
                "deadlineMs":1000,"maxConcurrency":2,"maxParameters":16,"maxParameterBytes":4096,"maxRows":10,"maxColumns":8,
                "maxCellBytes":1024,"maxTotalBytes":16384,"maxGeneratedKeyRows":4,
                "statements":{"find":{"kind":"QUERY","sql":"SELECT id FROM users WHERE id=:id","generatedKeys":[]}}}
                """.formatted(driver.fileName().substring(0, driver.fileName().length() - ".jar".length()),
                driverClass, driver.sha256Hex(), url, credentialRef, schemaField);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
