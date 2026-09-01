package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDependencyIsolationTest {
    @Test void noDatabaseDriverIsBundledAndStandardDistributionDoesNotIncludeJdbc() throws Exception {
        Path module = Path.of("pom.xml");
        String pom = Files.readString(module);
        assertFalse(pom.contains("h2") || pom.contains("sqlite"));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(module.toFile());
        var dependencies = document.getElementsByTagName("dependency");
        boolean pinnedPostgresqlFixture = false;
        boolean pinnedMysqlFixture = false;
        for (int index = 0; index < dependencies.getLength(); index++) {
            var dependency = dependencies.item(index).getChildNodes();
            String artifact = null;
            String scope = null;
            for (int child = 0; child < dependency.getLength(); child++) {
                if ("artifactId".equals(dependency.item(child).getNodeName())) artifact = dependency.item(child).getTextContent().trim();
                if ("scope".equals(dependency.item(child).getNodeName())) scope = dependency.item(child).getTextContent().trim();
            }
            if ("postgresql".equals(artifact)) {
                assertTrue("test".equals(scope), "the real PostgreSQL fixture must never become a runtime dependency");
                pinnedPostgresqlFixture = true;
            }
            if ("mysql-connector-j".equals(artifact)) {
                assertTrue("test".equals(scope), "the real MySQL fixture must never become a runtime dependency");
                pinnedMysqlFixture = true;
            }
        }
        assertTrue(pinnedPostgresqlFixture);
        assertTrue(pinnedMysqlFixture);
        assertTrue(pom.contains("ravenroot-application-api"));
        Path distribution = Path.of("../../ravenroot-distribution/pom.xml").normalize();
        if (Files.exists(distribution)) assertFalse(Files.readString(distribution).contains("ravenroot-jdbc"));
    }
}
