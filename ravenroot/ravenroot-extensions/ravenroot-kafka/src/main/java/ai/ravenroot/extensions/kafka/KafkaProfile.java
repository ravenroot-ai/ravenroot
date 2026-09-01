package ai.ravenroot.extensions.kafka;

import java.util.List;
import java.util.Set;

/** Immutable tenant-scoped operator authority for one Kafka cluster profile. */
public record KafkaProfile(
        String tenant, String name, List<String> bootstrapServers, String clientDnsLookup, boolean tls,
        String saslMechanism, String username, String credentialRef, String clientId,
        String defaultTopic, Set<String> topics, Set<String> headers,
        boolean allowPartition, int maxPartition, boolean allowTimestamp, String compression,
        String acks, boolean idempotence, int retries, int maxInFlight,
        boolean allowAutoCreate, int maxConcurrency, int maxPerSecond, int timeoutMs,
        int maxRecordBytes, long bufferMemoryBytes) {

    private static final Set<String> DNS = Set.of("use_all_dns_ips", "resolve_canonical_bootstrap_servers_only");
    private static final Set<String> SASL = Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");
    private static final Set<String> COMPRESSION = Set.of("none", "gzip", "snappy", "lz4", "zstd");

    public KafkaProfile {
        bootstrapServers = List.copyOf(bootstrapServers == null ? List.of() : bootstrapServers);
        topics = Set.copyOf(topics == null ? Set.of() : topics);
        headers = Set.copyOf(headers == null ? Set.of() : headers);
        if (!identifier(tenant) || !identifier(name) || bootstrapServers.isEmpty() || bootstrapServers.size() > 16
                || bootstrapServers.stream().anyMatch(server -> !bootstrap(server))
                || !DNS.contains(clientDnsLookup) || !SASL.contains(saslMechanism)
                || username == null || username.isBlank() || username.length() > 256
                || credentialRef == null || credentialRef.isBlank() || credentialRef.length() > 256
                || clientId == null || clientId.isBlank() || clientId.length() > 128
                || !topic(defaultTopic) || topics.size() > 64 || topics.stream().anyMatch(value -> !topic(value))
                || headers.size() > 32 || headers.stream().anyMatch(value -> !header(value))
                || maxPartition < 0 || maxPartition > 100_000 || !COMPRESSION.contains(compression)
                || !"all".equals(acks) || !idempotence || retries < 1 || retries > 1_000_000
                || maxInFlight < 1 || maxInFlight > 5 || maxConcurrency < 1 || maxConcurrency > 16
                || maxPerSecond < 1 || maxPerSecond > 1_000 || timeoutMs < 100 || timeoutMs > 30_000
                || maxRecordBytes < 1 || maxRecordBytes > 1_048_576
                || bufferMemoryBytes < maxRecordBytes || bufferMemoryBytes > 16_777_216L
                || !tls && bootstrapServers.stream().anyMatch(server -> !loopback(server))) {
            throw new IllegalArgumentException("invalid Kafka operator profile");
        }
    }

    boolean allowsTopic(String value) { return defaultTopic.equals(value) || topics.contains(value); }
    boolean allowsHeader(String value) { return headers.contains(value); }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static boolean topic(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}")
                && !".".equals(value) && !"..".equals(value);
    }

    private static boolean header(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,126}");
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
