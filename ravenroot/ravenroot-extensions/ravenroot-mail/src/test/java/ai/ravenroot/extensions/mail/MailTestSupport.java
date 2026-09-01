package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class MailTestSupport {
    static final String PROFILE = "test-profile";
    static final String FROM = "from@example.test";

    private MailTestSupport() { }

    static MailProfile profile(String tenant, String name, String host, int port, String mode,
                               String username, String credentialRef, int retries) {
        return profile(tenant, name, host, port, mode, username, credentialRef, retries, 16);
    }

    static MailProfile profile(String tenant, String name, String host, int port, String mode,
                               String username, String credentialRef, int retries, int maxConcurrency) {
        return profile(tenant, name, host, port, mode, username, credentialRef, retries, maxConcurrency, 2_000);
    }

    /**
     * The same profile, with connect/read/write timeouts all set to {@code timeoutMs} instead
     * of the fixed 2000ms every other overload uses. A test whose property genuinely requires a real
     * (even if loopback, in-process) SMTP round trip -- as opposed to one asserting a purely
     * synchronous rejection before any socket opens -- needs a budget wide enough to absorb ordinary
     * scheduling or GC jitter on a loaded machine; 2000ms is tight enough that Jakarta Mail's own
     * connect/read timeout can fire on a slow reactor run even though the connection is entirely
     * local. Everything else about the profile, including every assertion a caller makes against it,
     * is unchanged.
     */
    static MailProfile profile(String tenant, String name, String host, int port, String mode,
                               String username, String credentialRef, int retries, int maxConcurrency,
                               int timeoutMs) {
        return new MailProfile(tenant, name, host, port, mode, mode.equals("SMTP") && username.isBlank() && credentialRef.isBlank(), username, credentialRef, FROM,
                Set.of(FROM), Set.of("reply@example.test"), Set.of("*"), Set.of("x-correlation", "x-trace"),
                100, 40, 8192, 1_048_576, 10, 5_242_880, 10_485_760, 13_981_016,
                timeoutMs, timeoutMs, timeoutMs, retries, maxConcurrency);
    }

    static NodeAction action(CredentialResolver credentials, String host, int port, String mode, int retries) {
        MailProfileResolver profiles = (tenant, name) -> Optional.of(profile(tenant, name, host, port, mode, "", "", retries));
        return new MailSendNodeBehavior(credentials, profiles).create(configuration(Map.of()));
    }

    /** {@link #action(CredentialResolver, String, int, String, int)} with an explicit timeout. */
    static NodeAction action(CredentialResolver credentials, String host, int port, String mode, int retries,
                             int timeoutMs) {
        MailProfileResolver profiles = (tenant, name) ->
                Optional.of(profile(tenant, name, host, port, mode, "", "", retries, 16, timeoutMs));
        return new MailSendNodeBehavior(credentials, profiles).create(configuration(Map.of()));
    }

    static NodeAction authenticatedAction(CredentialResolver credentials, String host, int port, String mode) {
        MailProfileResolver profiles = (tenant, name) -> Optional.of(profile(tenant, name, host, port, mode,
                "smtp-user", "mail-primary", 0));
        return new MailSendNodeBehavior(credentials, profiles).create(configuration(Map.of()));
    }

    static NodeConfiguration configuration(Map<String, ?> extra) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mailProfile", PROFILE);
        values.putAll(extra);
        return new NodeConfiguration("mail", "mail.send", values);
    }
}
