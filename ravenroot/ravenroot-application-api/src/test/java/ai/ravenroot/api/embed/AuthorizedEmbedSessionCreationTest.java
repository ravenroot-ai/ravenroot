package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorizedEmbedSessionCreationTest {

    @Test
    void onlyWorkloadsWithTheDedicatedScopeReachTheRegistrationAuthority() {
        var called = new AtomicBoolean();
        var creation = new AuthorizedEmbedSessionCreation(
                new DefaultAuthorizationService(event -> { }), recording(called));

        assertThrows(AuthorizationDeniedException.class,
                () -> creation.resolve(context(PrincipalType.USER,
                        Set.of("ravenroot.embed.session.create")), "reg"));
        assertThrows(AuthorizationDeniedException.class,
                () -> creation.resolve(context(PrincipalType.WORKLOAD, Set.of("ravenroot.read")), "reg"));
        assertFalse(called.get());

        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                creation.resolve(context(PrincipalType.WORKLOAD,
                        Set.of("ravenroot.embed.session.create")), "reg"));
    }

    @Test
    void authorizationAuditFailureDeniesBeforeRegistrationResolution() {
        var called = new AtomicBoolean();
        var creation = new AuthorizedEmbedSessionCreation(
                new DefaultAuthorizationService(event -> {
                    throw new IllegalStateException("audit unavailable");
                }), recording(called));

        assertThrows(AuthorizationDeniedException.class,
                () -> creation.resolve(context(PrincipalType.WORKLOAD,
                        Set.of("ravenroot.embed.session.create")), "reg"));
        assertFalse(called.get());
    }

    /** A store outage must not be reported as «this registration does not exist», which is permanent. */
    @Test
    void storeFailureIsTemporaryRatherThanAPermanentDenial() {
        var creation = new AuthorizedEmbedSessionCreation(
                new DefaultAuthorizationService(event -> { }), new StubAuthority() {
                    @Override
                    public EmbedRegistrationResolution resolveCurrent(RequestContext workload,
                                                                      String registrationId) {
                        throw new IllegalStateException("/var/lib/ravenroot/embed.db is unreadable");
                    }
                });

        assertInstanceOf(EmbedRegistrationResolution.Temporary.class,
                creation.resolve(context(PrincipalType.WORKLOAD,
                        Set.of("ravenroot.embed.session.create")), "reg"));
    }

    private static EmbedRegistrationAuthority recording(AtomicBoolean called) {
        return new StubAuthority() {
            @Override
            public EmbedRegistrationResolution resolveCurrent(RequestContext workload,
                                                              String registrationId) {
                called.set(true);
                return EmbedRegistrationResolution.Unavailable.INSTANCE;
            }
        };
    }

    private static RequestContext context(PrincipalType type, Set<String> scopes) {
        return new RequestContext("request", "subject", type, "issuer", "tenant",
                Set.of(Role.VIEWER), scopes);
    }

    /** Refuses everything; each test overrides only the one method it is about. */
    private abstract static class StubAuthority implements EmbedRegistrationAuthority {
        @Override
        public EmbedProvisionOutcome provision(EmbedProvisionCommand command) {
            return EmbedProvisionOutcome.Unavailable.INSTANCE;
        }

        @Override
        public EmbedRevokeOutcome revoke(EmbedRevokeCommand command) {
            return EmbedRevokeOutcome.Unavailable.INSTANCE;
        }

        @Override
        public boolean isCurrent(EmbedRegistrationAggregate captured) {
            return false;
        }
    }
}
