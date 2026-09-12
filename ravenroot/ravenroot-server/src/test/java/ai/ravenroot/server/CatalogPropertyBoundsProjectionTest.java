package ai.ravenroot.server;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPropertyBoundsProjectionTest {
    @Test
    void catalogJsonPublishesNumericUtf8AndTokenBoundsWithoutUiConstants() throws Exception {
        var property = NodePropertyDescriptor.boundedText("authorizedRoles", "Authorized roles",
                NodePropertyType.TEXT, false, "roles", "", 8192, 37, 513);
        Method encoder = RavenrootServer.class.getDeclaredMethod("nodePropertyJson",
                NodePropertyDescriptor.class);
        encoder.setAccessible(true);
        String json = (String) encoder.invoke(null, property);
        assertTrue(json.contains("\"maximumUtf8Bytes\":8192"), json);
        assertTrue(json.contains("\"maximumItems\":37"), json);
        assertTrue(json.contains("\"maximumItemUtf8Bytes\":513"), json);

        var number = NodePropertyDescriptor.optionalBounded("expiresAfterSeconds", "Expiry",
                NodePropertyType.INTEGER, "expiry", "604800", 1, 5_184_000);
        json = (String) encoder.invoke(null, number);
        assertTrue(json.contains("\"minimumValue\":\"1\""), json);
        assertTrue(json.contains("\"maximumValue\":\"5184000\""), json);
    }
}
