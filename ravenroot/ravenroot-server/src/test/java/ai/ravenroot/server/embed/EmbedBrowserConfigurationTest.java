package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.AuthorizedEmbedGraphProjection;
import ai.ravenroot.api.embed.AuthorizedEmbedSessionCreation;
import ai.ravenroot.api.embed.InMemoryEmbedRegistrationAuthority;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.server.ReplicaCount;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedBrowserConfigurationTest {

    private final InMemoryEmbedRegistrationAuthority registrations =
            new InMemoryEmbedRegistrationAuthority();
    private final DefaultAuthorizationService authorization = new DefaultAuthorizationService(event -> { });

    @Test
    void absentFlagIsDisabledWithoutRequiringAnyCollaborator() {
        assertFalse(EmbedBrowserConfiguration.fromEnvironment(Map.of(), null, null, null, null, null)
                .active());
    }

    @Test
    void enablementRequiresExactOriginSingleProcessAcknowledgementAndOneReplica() {
        var base = Map.of(
                "RAVENROOT_EMBED_ENABLED", "true",
                "RAVENROOT_EMBED_VIEWER_ORIGIN", "https://viewer.example",
                "RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED", "true");
        assertTrue(configuration(base).active());
        assertThrows(IllegalArgumentException.class,
                () -> configuration(with(base, "RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED", "false")));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(with(base, "RAVENROOT_EMBED_VIEWER_ORIGIN",
                        "https://viewer.example/path")));
    }

    /**
     * This guard must read the deployed replica-count variable, not {@code RAVENROOT_REPLICA_COUNT}, which no deployment
     * sets, so it always saw its own default of one and was inert in production.
     *
     * <p>The environment below is the shape {@code compose.yaml},
     * {@code deploy/kubernetes/ravenroot.yaml} and the Helm deployment template actually produce —
     * {@code RAVENROOT_REPLICAS} and nothing else. The previous test set the variable the guard read
     * rather than the one a deployment sets, which is why it stayed green over a control that never
     * fired. The final assertion pins that: setting the dead name must no longer refuse anything, or
     * the guard has simply moved rather than been fixed.</p>
     */
    @Test
    void theReplicaGuardReadsTheVariableDeploymentsActuallySet() {
        var base = Map.of(
                "RAVENROOT_EMBED_ENABLED", "true",
                "RAVENROOT_EMBED_VIEWER_ORIGIN", "https://viewer.example",
                "RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED", "true");
        assertEquals("RAVENROOT_REPLICAS", ReplicaCount.VARIABLE);
        assertEquals("RAVENROOT_REPLICAS", EmbedBrowserConfiguration.REPLICAS_VARIABLE);

        assertThrows(IllegalArgumentException.class,
                () -> configuration(with(base, "RAVENROOT_REPLICAS", "3")),
                "three replicas are three registration authorities that cannot see each other");
        assertThrows(IllegalArgumentException.class,
                () -> configuration(with(base, "RAVENROOT_REPLICAS", "2")));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(with(base, "RAVENROOT_REPLICAS", "nope")),
                "a malformed replica count must fail closed rather than assume one");
        assertThrows(IllegalArgumentException.class,
                () -> configuration(with(base, "RAVENROOT_REPLICAS", "0")));

        assertTrue(configuration(with(base, "RAVENROOT_REPLICAS", "1")).active());
        assertTrue(configuration(with(base, "RAVENROOT_REPLICA_COUNT", "5")).active(),
                "RAVENROOT_REPLICA_COUNT is not a Ravenroot setting and must not be read");
    }

    @Test
    void invalidBooleanCapacityAndTtlFailAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration(Map.of("RAVENROOT_EMBED_ENABLED", "yes")));
        var enabled = Map.of(
                "RAVENROOT_EMBED_ENABLED", "true",
                "RAVENROOT_EMBED_VIEWER_ORIGIN", "https://viewer.example",
                "RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED", "true");
        assertThrows(IllegalArgumentException.class,
                () -> configuration(with(enabled, "RAVENROOT_EMBED_TICKET_CAPACITY", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(with(enabled, "RAVENROOT_EMBED_TICKET_TTL_SECONDS", "0")));
    }

    private EmbedBrowserConfiguration configuration(Map<String, String> environment) {
        return EmbedBrowserConfiguration.fromEnvironment(environment,
                new AuthorizedEmbedSessionCreation(authorization, registrations), registrations,
                new AuthorizedEmbedGraphProjection(authorization, registrations),
                event -> { }, Clock.systemUTC());
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        var copy = new java.util.HashMap<>(base);
        copy.put(key, value);
        return Map.copyOf(copy);
    }
}
