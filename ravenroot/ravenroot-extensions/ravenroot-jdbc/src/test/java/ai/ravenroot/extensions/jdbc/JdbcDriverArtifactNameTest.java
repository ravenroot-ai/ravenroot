package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcDriverArtifactNameTest {
    @Test
    void boundaryTableAllowsSafeDottedBasenamesAndRejectsEveryUnsafeEdge() {
        assertEquals(false, JdbcDriverArtifactName.validDriverId(null));
        var cases = new LinkedHashMap<String, Boolean>();
        cases.put("a", true);
        cases.put("postgresql-42.7.7", true);
        cases.put("A_1.2-b", true);
        cases.put("a" + "b".repeat(62) + "z", true);
        cases.put("", false);
        cases.put(".", false);
        cases.put("..", false);
        cases.put(".postgresql", false);
        cases.put("-postgresql", false);
        cases.put("_postgresql", false);
        cases.put("postgresql.", false);
        cases.put("postgresql-", false);
        cases.put("postgresql_", false);
        cases.put("postgresql..42", false);
        cases.put("postgresql/42", false);
        cases.put("postgresql\\42", false);
        cases.put("postgresql\n42", false);
        cases.put("postgresql 42", false);
        cases.put("postgresql-é42", false);
        cases.put("a" + "b".repeat(64), false);

        for (Map.Entry<String, Boolean> boundary : cases.entrySet()) {
            assertEquals(boundary.getValue(), JdbcDriverArtifactName.validDriverId(boundary.getKey()),
                    () -> "driverId boundary: " + boundary.getKey().replace("\n", "\\n"));
        }
        assertEquals("postgresql-42.7.7.jar", JdbcDriverArtifactName.fileName("postgresql-42.7.7"));
    }
}
