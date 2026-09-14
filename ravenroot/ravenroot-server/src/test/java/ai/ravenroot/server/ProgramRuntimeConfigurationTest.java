package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgramRuntimeConfigurationTest {
    @Test void propertyPresencePrecedesEnvironmentAndDefault() {
        assertEquals(ProgramRuntimeConfiguration.Runtime.GRAALVM,
                ProgramRuntimeConfiguration.resolve(new Properties(), Map.of()).runtime());
        var properties = new Properties();
        properties.setProperty(ProgramRuntimeConfiguration.PROPERTY, "disabled");
        assertEquals(ProgramRuntimeConfiguration.Runtime.DISABLED,
                ProgramRuntimeConfiguration.resolve(properties,
                        Map.of(ProgramRuntimeConfiguration.ENVIRONMENT, "graalvm")).runtime());
    }

    @Test void blankUnknownAndNonStringSelectedPropertiesRefuseWithoutEnvironmentFallback() {
        for (String invalid : new String[]{"", " ", "other"}) {
            var properties = new Properties();
            properties.setProperty(ProgramRuntimeConfiguration.PROPERTY, invalid);
            assertThrows(IllegalArgumentException.class, () -> ProgramRuntimeConfiguration.resolve(properties,
                    Map.of(ProgramRuntimeConfiguration.ENVIRONMENT, "graalvm")));
        }
        var properties = new Properties();
        properties.put(ProgramRuntimeConfiguration.PROPERTY, new Object());
        assertThrows(IllegalArgumentException.class, () -> ProgramRuntimeConfiguration.resolve(properties,
                Map.of(ProgramRuntimeConfiguration.ENVIRONMENT, "graalvm")));
    }
}
