package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads an explicit mutation-policy companion for an opaque IMAP profile.
 *
 * <p>The value has exactly three semicolon-delimited fields:
 * {@code operations;destinationFolders;trashFolder}. Operations are comma-separated members of
 * {@code MOVE}, {@code TRASH}, and {@code HARD_DELETE}. Destination folders are comma-separated.
 * An absent variable grants no mutation authority. Rejections name only the failed constraint.
 */
public final class EnvironmentImapMutationPolicyResolver implements ImapMutationPolicyResolver {
    enum Rejection {
        FIELD_COUNT, OPERATIONS_EMPTY, UNKNOWN_OPERATION, DUPLICATE_OPERATION,
        DUPLICATE_DESTINATION, DESTINATION_INVALID, MOVE_DESTINATION_REQUIRED,
        TRASH_FOLDER_REQUIRED, TRASH_FOLDER_NOT_ALLOWED, UNUSED_TRASH_FOLDER, RECORD_POLICY
    }

    private static final System.Logger LOGGER =
            System.getLogger("ai.ravenroot.mail.imap.mutation-policy.rejected");
    private final Map<String, String> env;

    public EnvironmentImapMutationPolicyResolver() {
        this(System.getenv());
    }

    EnvironmentImapMutationPolicyResolver(Map<String, String> env) {
        this.env = Map.copyOf(env);
    }

    @Override public Optional<ImapMutationPolicy> resolve(String tenant, String profileId) {
        if (!safe(tenant) || !safe(profileId)) return Optional.empty();
        String raw = env.get(environmentVariableName(tenant, profileId));
        if (raw == null) return Optional.empty();
        String[] fields = raw.split(";", -1);
        if (fields.length != 3) return rejected(tenant, profileId, Rejection.FIELD_COUNT);

        ParseOperations operations = operations(fields[0]);
        if (operations.rejection() != null) return rejected(tenant, profileId, operations.rejection());
        ParseFolders destinations = folders(fields[1]);
        if (destinations.rejection() != null) return rejected(tenant, profileId, destinations.rejection());
        String trash = fields[2].strip();
        if (!trash.isEmpty() && !validFolder(trash))
            return rejected(tenant, profileId, Rejection.DESTINATION_INVALID);
        if (operations.values().contains(ImapMutationOperation.MOVE) && destinations.values().isEmpty())
            return rejected(tenant, profileId, Rejection.MOVE_DESTINATION_REQUIRED);
        if (operations.values().contains(ImapMutationOperation.TRASH) && trash.isEmpty())
            return rejected(tenant, profileId, Rejection.TRASH_FOLDER_REQUIRED);
        if (operations.values().contains(ImapMutationOperation.TRASH) && !destinations.values().contains(trash))
            return rejected(tenant, profileId, Rejection.TRASH_FOLDER_NOT_ALLOWED);
        if (!operations.values().contains(ImapMutationOperation.TRASH) && !trash.isEmpty())
            return rejected(tenant, profileId, Rejection.UNUSED_TRASH_FOLDER);
        try {
            return Optional.of(new ImapMutationPolicy(tenant, profileId, operations.values(),
                    destinations.values(), trash));
        } catch (RuntimeException invalid) {
            return rejected(tenant, profileId, Rejection.RECORD_POLICY);
        }
    }

    static String environmentVariableName(String tenant, String profileId) {
        return "RAVENROOT_IMAP_MUTATION_POLICY_" + EnvironmentKeyCodec.hex(tenant)
                + "_" + EnvironmentKeyCodec.hex(profileId);
    }

    private static ParseOperations operations(String value) {
        if (value.isBlank()) return new ParseOperations(Set.of(), Rejection.OPERATIONS_EMPTY);
        LinkedHashSet<ImapMutationOperation> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            final ImapMutationOperation operation;
            try { operation = ImapMutationOperation.valueOf(token); }
            catch (RuntimeException invalid) {
                return new ParseOperations(Set.of(), Rejection.UNKNOWN_OPERATION);
            }
            if (!result.add(operation)) return new ParseOperations(Set.of(), Rejection.DUPLICATE_OPERATION);
        }
        return new ParseOperations(Set.copyOf(result), null);
    }

    private static ParseFolders folders(String value) {
        if (value.isBlank()) return new ParseFolders(Set.of(), null);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : value.split(",", -1)) {
            String folder = raw.strip();
            if (!validFolder(folder)) return new ParseFolders(Set.of(), Rejection.DESTINATION_INVALID);
            if (!result.add(folder)) return new ParseFolders(Set.of(), Rejection.DUPLICATE_DESTINATION);
        }
        return new ParseFolders(Set.copyOf(result), null);
    }

    private static boolean validFolder(String value) {
        return !value.isEmpty() && value.length() <= 256 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\0') < 0;
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static Optional<ImapMutationPolicy> rejected(String tenant, String profile,
                                                          Rejection rejection) {
        LOGGER.log(System.Logger.Level.WARNING,
                "ravenroot_imap_mutation_policy_rejected tenant={0} profile={1} constraint={2}",
                tenant, profile, rejection);
        return Optional.empty();
    }

    private record ParseOperations(Set<ImapMutationOperation> values, Rejection rejection) { }
    private record ParseFolders(Set<String> values, Rejection rejection) { }
}
