package ai.ravenroot.extensions.mail.imap;

import java.util.Optional;

/** Resolves operator authority for a long-lived IMAP consumer. */
@FunctionalInterface
interface ImapConsumerPolicyResolver {
    Optional<ImapConsumerPolicy> resolve(String tenant, String profile);
}
