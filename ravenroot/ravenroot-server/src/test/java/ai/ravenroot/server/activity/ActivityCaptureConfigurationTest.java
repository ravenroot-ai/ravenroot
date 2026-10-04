package ai.ravenroot.server.activity;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.activity.ActivityContentKind;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadValue;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ActivityCaptureConfigurationTest {
  @Test
  void disabledByDefaultAndEnabledDefaultsToBestEffort() {
    assertFalse(
        ActivityCaptureConfiguration.fromSystem(new Properties(), Map.of()).policy().enabled());
    var enabled =
        ActivityCaptureConfiguration.fromSystem(
            new Properties(), Map.of("RAVENROOT_ACTIVITY_CAPTURE_ENABLED", "true"));
    assertTrue(enabled.policy().enabled());
    assertEquals(
        ai.ravenroot.api.activity.ActivityFailurePolicy.BEST_EFFORT,
        enabled.policy().failurePolicy());
  }

  @Test
  void redactsNestedMapAndPayloadValueBeforeCanonicalEncoding() {
    var configured =
        ActivityCaptureConfiguration.fromSystem(
            new Properties(),
            Map.of(
                "RAVENROOT_ACTIVITY_CAPTURE_ENABLED", "true",
                "RAVENROOT_ACTIVITY_CAPTURE_REDACT_KEYS", "secret,token"));
    Object ordinary =
        configured
            .policy()
            .redactor()
            .redact(
                ActivityContentKind.INPUT_PAYLOAD,
                Map.of("nested", Map.of("secret", "clear"), "safe", "ok"));
    assertEquals(
        "{\"nested\":{\"secret\":\"[REDACTED]\"},\"safe\":\"ok\"}", encoded(ordinary, configured));

    Object typed =
        configured
            .policy()
            .redactor()
            .redact(
                ActivityContentKind.INPUT_PAYLOAD,
                PayloadValue.map(
                    Map.of("nested", PayloadValue.map(Map.of("token", PayloadValue.of("clear"))))));
    assertEquals("{\"nested\":{\"token\":\"[REDACTED]\"}}", encoded(typed, configured));
  }

  @Test
  void refusesConfigurationThatWouldRemoveCaptureSafetyCeilings() {
    for (Map<String, String> widened :
        java.util.List.of(
            Map.of("RAVENROOT_ACTIVITY_CAPTURE_MAX_PAYLOAD_BYTES", "67108865"),
            Map.of("RAVENROOT_ACTIVITY_CAPTURE_MAX_IN_FLIGHT", "10001"),
            Map.of("RAVENROOT_ACTIVITY_CAPTURE_WRITE_TIMEOUT_MILLIS", "300001"),
            Map.of("RAVENROOT_ACTIVITY_CAPTURE_RETENTION_SECONDS", "315360001"),
            Map.of("RAVENROOT_ACTIVITY_CAPTURE_MAX_PAGE_SIZE", "1001"))) {
      var environment = new java.util.HashMap<String, String>();
      environment.put("RAVENROOT_ACTIVITY_CAPTURE_ENABLED", "true");
      environment.putAll(widened);
      assertThrows(
          IllegalArgumentException.class,
          () -> ActivityCaptureConfiguration.fromSystem(new Properties(), environment));
    }
  }

  private static String encoded(Object value, ActivityCaptureConfiguration configured) {
    return new String(
        PayloadJson.writeJava(value, configured.policy().payloadLimits()), StandardCharsets.UTF_8);
  }
}
