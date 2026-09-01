package ai.ravenroot.extensions.mail.imap;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mutation authority attached to one opaque IMAP profile.
 *
 * <p>The connection profile owns source-folder authority. This separate, same-id policy owns the
 * strictly smaller mutation authority: which operations may run, which destination folders may be
 * selected, and which one is the trash folder. Keeping it separate makes every legacy eleven-field
 * IMAP profile query-only until an operator explicitly adds a mutation policy.
 */
public record ImapMutationPolicy(String tenant, String profileId,
                                 Set<ImapMutationOperation> allowedOperations,
                                 Set<String> destinationFolders,
                                 String trashFolder) {
    public ImapMutationPolicy {
        if (!safeId(tenant) || !safeId(profileId) || allowedOperations == null
                || destinationFolders == null || trashFolder == null) {
            throw new IllegalArgumentException("Invalid IMAP mutation policy");
        }
        allowedOperations = Set.copyOf(allowedOperations);
        destinationFolders = destinationFolders.stream().map(String::strip)
                .peek(ImapMutationPolicy::requireFolder)
                .collect(Collectors.toUnmodifiableSet());
        trashFolder = trashFolder.strip();
        if (allowedOperations.isEmpty()
                || allowedOperations.contains(ImapMutationOperation.MOVE) && destinationFolders.isEmpty()
                || allowedOperations.contains(ImapMutationOperation.TRASH)
                        && (trashFolder.isEmpty() || !destinationFolders.contains(trashFolder))
                || !allowedOperations.contains(ImapMutationOperation.TRASH) && !trashFolder.isEmpty()) {
            throw new IllegalArgumentException("Invalid IMAP mutation policy");
        }
    }

    public boolean allows(ImapMutationOperation operation) {
        return allowedOperations.contains(operation);
    }

    public boolean allowsDestination(String folder) {
        return folder != null && destinationFolders.contains(folder);
    }

    private static boolean safeId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static void requireFolder(String value) {
        if (value.isEmpty() || value.length() > 256 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid IMAP mutation policy");
        }
    }
}
