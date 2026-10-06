package ai.ravenroot.server.activity;

import ai.ravenroot.api.activity.ActivityCapturePolicy;
import ai.ravenroot.api.activity.ActivityContentKind;
import ai.ravenroot.api.activity.ActivityFailurePolicy;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Operator-owned configuration for the optional, separate activity-content archive. */
public record ActivityCaptureConfiguration(ActivityCapturePolicy policy, int maxPageSize) {
  public static final int DEFAULT_MAX_PAGE_SIZE = 100;
  private static final Set<String> DEFAULT_REDACT_KEYS =
      Set.of("password", "secret", "token", "authorization", "api_key", "apikey");

  public ActivityCaptureConfiguration {
    java.util.Objects.requireNonNull(policy, "policy");
    if (maxPageSize < 1 || maxPageSize > 1_000) {
      throw new IllegalArgumentException(
          "activity archive max page size must be between 1 and 1000");
    }
  }

  public static ActivityCaptureConfiguration fromSystem(
      Properties properties, Map<String, String> environment) {
    java.util.Objects.requireNonNull(properties, "properties");
    java.util.Objects.requireNonNull(environment, "environment");
    boolean enabled =
        bool(
            value(
                properties,
                environment,
                "ravenroot.activity-capture.enabled",
                "RAVENROOT_ACTIVITY_CAPTURE_ENABLED",
                "false"));
    int maxPage =
        integer(
            value(
                properties,
                environment,
                "ravenroot.activity-capture.max-page-size",
                "RAVENROOT_ACTIVITY_CAPTURE_MAX_PAGE_SIZE",
                "100"),
            "max page size");
    if (!enabled) return new ActivityCaptureConfiguration(ActivityCapturePolicy.DISABLED, maxPage);

    ActivityFailurePolicy failurePolicy =
        enumValue(
            ActivityFailurePolicy.class,
            value(
                properties,
                environment,
                "ravenroot.activity-capture.policy",
                "RAVENROOT_ACTIVITY_CAPTURE_POLICY",
                "BEST_EFFORT"));
    Set<String> nodes =
        csv(
            value(
                properties,
                environment,
                "ravenroot.activity-capture.nodes",
                "RAVENROOT_ACTIVITY_CAPTURE_NODES",
                ""),
            false);
    Set<ActivityContentKind> contents =
        enumCsv(
            ActivityContentKind.class,
            value(
                properties,
                environment,
                "ravenroot.activity-capture.contents",
                "RAVENROOT_ACTIVITY_CAPTURE_CONTENTS",
                "INPUT_PAYLOAD,OUTPUT_PAYLOAD"));
    int maxBytes =
        integer(
            value(
                properties,
                environment,
                "ravenroot.activity-capture.max-payload-bytes",
                "RAVENROOT_ACTIVITY_CAPTURE_MAX_PAYLOAD_BYTES",
                "65536"),
            "max payload bytes");
    int maxInFlight =
        integer(
            value(
                properties,
                environment,
                "ravenroot.activity-capture.max-in-flight",
                "RAVENROOT_ACTIVITY_CAPTURE_MAX_IN_FLIGHT",
                "64"),
            "max in-flight writes");
    long timeoutMillis =
        longValue(
            value(
                properties,
                environment,
                "ravenroot.activity-capture.write-timeout-millis",
                "RAVENROOT_ACTIVITY_CAPTURE_WRITE_TIMEOUT_MILLIS",
                "2000"),
            "write timeout");
    long retentionSeconds =
        longValue(
            value(
                properties,
                environment,
                "ravenroot.activity-capture.retention-seconds",
                "RAVENROOT_ACTIVITY_CAPTURE_RETENTION_SECONDS",
                "604800"),
            "retention");
    Set<String> redactKeys =
        csv(
            value(
                properties,
                environment,
                "ravenroot.activity-capture.redact-keys",
                "RAVENROOT_ACTIVITY_CAPTURE_REDACT_KEYS",
                String.join(",", DEFAULT_REDACT_KEYS)),
            true);
    var defaults = PayloadLimits.DEFAULTS;
    var limits =
        new PayloadLimits(
            maxBytes,
            defaults.maxDepth(),
            defaults.maxCollectionSize(),
            defaults.maxValueCount(),
            Math.min(defaults.maxTextLength(), maxBytes),
            defaults.maxKeyLength());
    var policy =
        new ActivityCapturePolicy(
            true,
            failurePolicy,
            nodes,
            contents,
            limits,
            maxInFlight,
            Duration.ofMillis(timeoutMillis),
            Duration.ofSeconds(retentionSeconds),
            new KeyRedactor(redactKeys, limits));
    return new ActivityCaptureConfiguration(policy, maxPage);
  }

  private static String value(
      Properties properties,
      Map<String, String> environment,
      String property,
      String variable,
      String fallback) {
    String configured = properties.getProperty(property);
    if (configured == null) configured = environment.get(variable);
    return configured == null ? fallback : configured.trim();
  }

  private static boolean bool(String value) {
    if ("true".equalsIgnoreCase(value)) return true;
    if ("false".equalsIgnoreCase(value)) return false;
    throw new IllegalArgumentException("activity capture enabled must be true or false");
  }

  private static int integer(String value, String name) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 1) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException(
          "activity capture " + name + " must be a positive whole number");
    }
  }

  private static long longValue(String value, String name) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed < 1) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException(
          "activity capture " + name + " must be a positive whole number");
    }
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
    try {
      return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException(
          "unsupported activity capture value for " + type.getSimpleName());
    }
  }

  private static <E extends Enum<E>> Set<E> enumCsv(Class<E> type, String value) {
    if (value.isBlank()) return Set.of();
    var result = java.util.EnumSet.noneOf(type);
    for (String part : value.split(",")) result.add(enumValue(type, part.trim()));
    return Set.copyOf(result);
  }

  private static Set<String> csv(String value, boolean lowerCase) {
    if (value.isBlank()) return Set.of();
    var result = new TreeSet<String>();
    Arrays.stream(value.split(","))
        .map(String::trim)
        .forEach(
            item -> {
              if (item.isEmpty())
                throw new IllegalArgumentException("activity capture list contains a blank item");
              result.add(lowerCase ? item.toLowerCase(Locale.ROOT) : item);
            });
    return Set.copyOf(result);
  }

  /** Copies only within the same structural limits later used by canonical encoding. */
  private record KeyRedactor(Set<String> keys, PayloadLimits limits)
      implements ai.ravenroot.api.activity.ActivityRedactor {
    @Override
    public Object redact(ActivityContentKind kind, Object value) {
      return copy(value, 1, new int[] {0});
    }

    private Object copy(Object value, int depth, int[] count) {
      if (depth > limits.maxDepth() || ++count[0] > limits.maxValueCount()) {
        throw new IllegalArgumentException("activity content exceeds redaction bounds");
      }
      if (value instanceof PayloadValue payload) {
        return switch (payload) {
          case PayloadValue.MapValue map -> copy(map.entries(), depth, count);
          case PayloadValue.ListValue list -> copy(list.values(), depth, count);
          default -> copy(payload.toJava(), depth, count);
        };
      }
      if (value instanceof Map<?, ?> map) {
        if (map.size() > limits.maxCollectionSize()) {
          throw new IllegalArgumentException("activity content exceeds redaction bounds");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key) || key.length() > limits.maxKeyLength()) {
            throw new IllegalArgumentException("activity content cannot be redacted safely");
          }
          result.put(
              key,
              keys.contains(key.toLowerCase(Locale.ROOT))
                  ? "[REDACTED]"
                  : copy(entry.getValue(), depth + 1, count));
        }
        return result;
      }
      if (value instanceof Object[] array) return copyIterable(List.of(array), depth, count);
      if (value instanceof Iterable<?> iterable) return copyIterable(iterable, depth, count);
      return value;
    }

    private List<Object> copyIterable(Iterable<?> values, int depth, int[] count) {
      var result = new java.util.ArrayList<Object>();
      for (Object value : values) {
        if (result.size() >= limits.maxCollectionSize()) {
          throw new IllegalArgumentException("activity content exceeds redaction bounds");
        }
        result.add(copy(value, depth + 1, count));
      }
      return result;
    }
  }
}
