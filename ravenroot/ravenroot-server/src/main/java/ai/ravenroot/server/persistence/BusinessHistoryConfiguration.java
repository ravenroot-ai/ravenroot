package ai.ravenroot.server.persistence;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.payload.PayloadLimits;
import java.time.Duration;
import java.util.*;

/** Resolves shared history policy at normal server composition, before any listener or initial START. */
final class BusinessHistoryConfiguration {
    private BusinessHistoryConfiguration() { }
    static BusinessHistoryPolicy fromSources(Map<String, String> properties, Map<String, String> environment) {
        var d = BusinessHistoryPolicy.DEFAULTS; var selection = d.selection(); var response = d.response();
        return new BusinessHistoryPolicy(
                integer(properties, environment, "max-histories-per-scope", d.maxHistoriesPerScope()),
                integer(properties, environment, "max-open-joins", d.maxOpenJoinsPerHistory()),
                integer(properties, environment, "max-retained-joins", d.maxRetainedJoinsPerHistory()),
                integer(properties, environment, "max-contributions", d.maxContributionsPerJoin()),
                integer(properties, environment, "max-join-payload-bytes", Math.toIntExact(d.maxJoinPayloadBytes())),
                integer(properties, environment, "max-page-size", d.maxPageSize()),
                Duration.ofSeconds(integer(properties, environment, "retention-seconds", Math.toIntExact(d.historyRetention().toSeconds()))),
                Duration.ofSeconds(integer(properties, environment, "join-deadline-seconds", Math.toIntExact(d.joinDeadline().toSeconds()))),
                new CorrelationLimits(integer(properties, environment, "max-id-characters", selection.maxIdCharacters()),
                        integer(properties, environment, "max-selector-characters", selection.maxSelectorCharacters()),
                        integer(properties, environment, "max-selector-segments", selection.maxSelectorSegments()),
                        integer(properties, environment, "max-manifest-items", selection.maxManifestItems()),
                        ai.ravenroot.core.runtime.GraphExecutionLimits.fromEnvironment(environment).payload()),
                integer(properties, environment, "max-cas-attempts", d.maxJoinCasAttempts()),
                Duration.ofMillis(integer(properties, environment, "read-timeout-ms", Math.toIntExact(d.readTimeout().toMillis()))),
                new PayloadLimits(integer(properties, environment, "response-max-bytes", response.maxEncodedBytes()), response.maxDepth(),
                        response.maxCollectionSize(), integer(properties, environment, "response-max-values", response.maxValueCount()),
                        response.maxTextLength(), response.maxKeyLength()),
                integer(properties, environment, "max-pending-entries", d.maxPendingEntriesPerScope()),
                integer(properties, environment, "max-pending-entry-bytes", Math.toIntExact(d.maxPendingEntryBytesPerScope())));
    }
    private static int integer(Map<String, String> properties, Map<String, String> environment, String name, int fallback) {
        String property = "ravenroot.history." + name;
        String variable = "RAVENROOT_HISTORY_" + name.toUpperCase(Locale.ROOT).replace('-', '_');
        String value = properties.get(property);
        if (value == null || value.isBlank()) value = environment.get(variable);
        if (value == null || value.isBlank()) return fallback;
        try { int parsed = Integer.parseInt(value.trim()); if (parsed < 1) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(property + " / " + variable + " requires a bounded positive integer"); }
    }
}
