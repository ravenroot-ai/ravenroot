package ai.ravenroot.api.persistence;

/**
 * Per-tenant admission bounds for non-terminal saga application commands.
 *
 * @param maximumOutstandingCommands maximum PENDING, CLAIMED or BROKER_ACCEPTED rows
 * @param maximumOutstandingBytes maximum encoded immutable intent bytes across those rows
 */
public record SagaOutboxCapacity(int maximumOutstandingCommands, long maximumOutstandingBytes) {
    /** JVM property overriding the shipped command-count bound. */
    public static final String COMMANDS_PROPERTY = "ravenroot.saga.outbox.maxOutstandingCommands";
    /** JVM property overriding the shipped encoded-byte bound. */
    public static final String BYTES_PROPERTY = "ravenroot.saga.outbox.maxOutstandingBytes";
    /** Shipped bounds used when neither JVM property is present. */
    public static final SagaOutboxCapacity DEFAULTS = new SagaOutboxCapacity(128, 16L * 1024 * 1024);

    /** Validates positive, operationally bounded capacity values. */
    public SagaOutboxCapacity {
        if (maximumOutstandingCommands < 1 || maximumOutstandingCommands > 1_000_000) {
            throw new IllegalArgumentException("maximumOutstandingCommands out of range");
        }
        if (maximumOutstandingBytes < 1024 || maximumOutstandingBytes > 16L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("maximumOutstandingBytes out of range");
        }
    }

    /**
     * Resolves one immutable process configuration from the documented JVM properties.
     *
     * @return configured capacity, or {@link #DEFAULTS} when neither property is set
     */
    public static SagaOutboxCapacity configured() {
        String commands = System.getProperty(COMMANDS_PROPERTY);
        String bytes = System.getProperty(BYTES_PROPERTY);
        try {
            return new SagaOutboxCapacity(commands == null ? DEFAULTS.maximumOutstandingCommands
                    : Integer.parseInt(commands), bytes == null ? DEFAULTS.maximumOutstandingBytes
                    : Long.parseLong(bytes));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid saga outbox capacity property", invalid);
        }
    }
}
