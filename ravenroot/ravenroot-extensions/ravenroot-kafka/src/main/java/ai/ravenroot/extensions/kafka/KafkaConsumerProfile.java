package ai.ravenroot.extensions.kafka;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Immutable tenant-scoped operator authority for one long-lived Kafka consumer group. */
public record KafkaConsumerProfile(
        String tenant, String name, List<String> bootstrapServers, String clientDnsLookup, boolean tls,
        String saslMechanism, String username, String credentialRef, String clientId,
        String groupLogicalName, String groupId, String staticMemberId,
        Set<String> topics, String topicPattern, Set<String> headers,
        String assignmentStrategy, String autoOffsetReset, String isolationLevel,
        int startupTimeoutMs, int pollTimeoutMs, int maxPollIntervalMs, int sessionTimeoutMs,
        int heartbeatIntervalMs, int maxInFlight, int maxFetchBytes, int maxPartitionFetchBytes,
        int maxRecordBytes, int maxKeyBytes, int maxValueBytes, int maxHeaderBytes,
        int drainTimeoutMs, int retryBackoffMs, int maxRetryBackoffMs, int poisonAttempts,
        String poisonPolicy, String deadLetterTopic) {

    private static final Set<String> DNS = Set.of(
            "use_all_dns_ips", "resolve_canonical_bootstrap_servers_only");
    private static final Set<String> SASL = Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");
    private static final Set<String> ASSIGNMENT = Set.of("cooperative-sticky", "range", "round-robin");
    private static final Set<String> RESET = Set.of("earliest", "latest", "none");
    private static final Set<String> POISON = Set.of("halt", "dead-letter");

    public KafkaConsumerProfile {
        bootstrapServers = List.copyOf(bootstrapServers == null ? List.of() : bootstrapServers);
        topics = Set.copyOf(topics == null ? Set.of() : topics);
        headers = Set.copyOf(headers == null ? Set.of() : headers);
        staticMemberId = blankToNull(staticMemberId);
        topicPattern = blankToNull(topicPattern);
        deadLetterTopic = blankToNull(deadLetterTopic);
        assignmentStrategy = lower(assignmentStrategy);
        autoOffsetReset = lower(autoOffsetReset);
        isolationLevel = lower(isolationLevel);
        poisonPolicy = lower(poisonPolicy);
        boolean oneSubscription = !topics.isEmpty() ^ topicPattern != null;
        if (!identifier(tenant) || !identifier(name) || bootstrapServers.isEmpty() || bootstrapServers.size() > 16
                || bootstrapServers.stream().anyMatch(server -> !bootstrap(server))
                || !DNS.contains(clientDnsLookup) || !SASL.contains(saslMechanism)
                || username == null || username.isBlank() || username.length() > 256
                || credentialRef == null || credentialRef.isBlank() || credentialRef.length() > 256
                || !safeClientId(clientId) || !identifier(groupLogicalName) || !safeGroupId(groupId)
                || staticMemberId != null && !safeMemberId(staticMemberId)
                || !oneSubscription || topics.size() > 64 || topics.stream().anyMatch(value -> !topic(value))
                || topicPattern != null && !anchoredPattern(topicPattern)
                || headers.size() > 32 || headers.stream().anyMatch(value -> !header(value))
                || !ASSIGNMENT.contains(assignmentStrategy) || !RESET.contains(autoOffsetReset)
                || !"read_committed".equals(isolationLevel)
                || startupTimeoutMs < 100 || startupTimeoutMs > 120_000
                || pollTimeoutMs < 10 || pollTimeoutMs > 5_000
                || maxPollIntervalMs < 1_000 || maxPollIntervalMs > 1_800_000
                || sessionTimeoutMs < 1_000 || sessionTimeoutMs >= maxPollIntervalMs
                || heartbeatIntervalMs < 100 || heartbeatIntervalMs * 3L > sessionTimeoutMs
                || maxInFlight < 1 || maxInFlight > 10_000
                || maxFetchBytes < 1 || maxFetchBytes > 64 * 1_048_576
                || maxPartitionFetchBytes < 1 || maxPartitionFetchBytes > maxFetchBytes
                || maxRecordBytes < 1 || maxRecordBytes > maxPartitionFetchBytes
                || maxKeyBytes < 0 || maxKeyBytes > maxRecordBytes
                || maxValueBytes < 1 || maxValueBytes > maxRecordBytes
                || maxHeaderBytes < 0 || maxHeaderBytes > maxRecordBytes
                || drainTimeoutMs < 0 || drainTimeoutMs > 120_000
                || retryBackoffMs < 1 || retryBackoffMs > 60_000
                || maxRetryBackoffMs < retryBackoffMs || maxRetryBackoffMs > 300_000
                || poisonAttempts < 1 || poisonAttempts > 1_000
                || !POISON.contains(poisonPolicy)
                || "dead-letter".equals(poisonPolicy) && (deadLetterTopic == null || !topic(deadLetterTopic))
                || deadLetterTopic != null && topics.contains(deadLetterTopic)
                || !tls && bootstrapServers.stream().anyMatch(server -> !loopback(server))) {
            throw new IllegalArgumentException("invalid Kafka consumer operator profile");
        }
    }

    boolean patternSubscription() { return topicPattern != null; }
    boolean allowsTopic(String value) {
        return topics.contains(value) || topicPattern != null && Pattern.matches(topicPattern, value);
    }
    boolean allowsHeader(String value) { return headers.contains(value); }
    boolean deadLetters() { return "dead-letter".equals(poisonPolicy); }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }
    private static boolean safeClientId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }
    private static boolean safeGroupId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}");
    }
    private static boolean safeMemberId(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,248}");
    }
    private static boolean topic(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}")
                && !".".equals(value) && !"..".equals(value);
    }
    private static boolean header(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,126}");
    }
    private static boolean anchoredPattern(String value) {
        if (value.length() > 256 || !value.startsWith("^") || !value.endsWith("$")) return false;
        try { Pattern.compile(value); return true; }
        catch (PatternSyntaxException invalid) { return false; }
    }
    private static boolean bootstrap(String value) {
        if (value == null || value.length() > 263 || !(value.matches("[A-Za-z0-9.-]+:[1-9][0-9]{0,4}")
                || value.matches("\\[[0-9A-Fa-f:]+]:[1-9][0-9]{0,4}"))) return false;
        try { return Integer.parseInt(value.substring(value.lastIndexOf(':') + 1)) <= 65_535; }
        catch (NumberFormatException invalid) { return false; }
    }
    private static boolean loopback(String value) {
        return value.matches("(?i)localhost:[0-9]+|127\\.0\\.0\\.1:[0-9]+|\\[::1]:[0-9]+");
    }
}
