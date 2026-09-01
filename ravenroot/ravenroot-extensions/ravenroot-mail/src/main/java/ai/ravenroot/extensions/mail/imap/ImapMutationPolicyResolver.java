package ai.ravenroot.extensions.mail.imap;

import java.util.Optional;

/** Resolves mutation authority for the same opaque tenant/profile identity as {@link ImapProfile}. */
public interface ImapMutationPolicyResolver {
    Optional<ImapMutationPolicy> resolve(String tenant, String profileId);
}
