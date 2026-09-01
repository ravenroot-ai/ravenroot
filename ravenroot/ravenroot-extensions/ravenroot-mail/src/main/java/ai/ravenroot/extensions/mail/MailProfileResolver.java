package ai.ravenroot.extensions.mail;

import java.util.Optional;

/** Operator-owned lookup for the complete SMTP policy bound to a tenant and profile name. */
@FunctionalInterface
public interface MailProfileResolver {
    Optional<MailProfile> resolve(String tenant, String profile);
}
