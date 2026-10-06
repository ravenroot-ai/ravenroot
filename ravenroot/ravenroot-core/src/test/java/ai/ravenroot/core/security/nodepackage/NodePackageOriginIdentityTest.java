package ai.ravenroot.core.security.nodepackage;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodePackageOriginIdentityTest {
    @Test void exactNamedZoneBindsHttpWebSocketCredentialsAndSigningIndependently() {
        for (String zone : List.of("ETH0", "eth0", "25ETH0", "2", "252")) {
            for (String scheme : List.of("http", "https", "ws", "wss")) {
                int port = ("https".equals(scheme) || "wss".equals(scheme)) ? 443 : 80;
                boolean webSocket = scheme.startsWith("ws");
                var origin = new NodePackageEgressPolicy.Origin(scheme, "[FE80::A%" + zone + "]", port);
                var builder = NodePackageEgressPolicy.builder().allowOrigin(scheme, origin.host(), port)
                        .bindCredential("api", origin, "Authorization", "Bearer ");
                if (!webSocket) builder.bindAwsSigV4("s3", origin, "credential", "eu-west-1", "s3");
                var policy = builder.build();
                URI exact = URI.create(scheme + "://[fe80::a%" + zone + "]/object");
                assertEquals("[fe80::a%" + zone + "]", origin.host());
                assertDoesNotThrow(() -> policy.requireAllowed(exact, webSocket));
                assertEquals(origin, policy.requireCredentialPlacement("api", exact).origin());
                if (!webSocket) assertEquals(origin, policy.requireAwsSigV4SigningGrant("s3", exact).origin());
                for (String different : List.of("ETH0", "eth0", "25ETH0", "2", "252")) {
                    if (different.equals(zone)) continue;
                    URI wrong = URI.create(scheme + "://[fe80::a%" + different + "]/object");
                    assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed(wrong, webSocket));
                    assertThrows(IllegalArgumentException.class, () -> policy.requireCredentialPlacement("api", wrong));
                    if (!webSocket) assertThrows(IllegalArgumentException.class,
                            () -> policy.requireAwsSigV4SigningGrant("s3", wrong));
                }
                assertThrows(IllegalArgumentException.class,
                        () -> policy.requireAllowed(URI.create(scheme + "://[fe80::a%" + zone + "]:1234/"), webSocket));
            }
        }
    }

    @Test void dnsCaseDefaultPortsAndExistingHostValidationRemainIntact() {
        var policy = NodePackageEgressPolicy.builder().allowOrigin("HTTPS", " EXAMPLE.TEST ", 443).build();
        assertDoesNotThrow(() -> policy.requireAllowed(URI.create("https://example.test/"), false));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireAllowed(URI.create("https://different.test/"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new NodePackageEgressPolicy.Origin("https", "example.test\r\nother", 443));
        assertThrows(IllegalArgumentException.class,
                () -> NodePackageEgressPolicy.builder().build().requireAllowed(URI.create("https://example.test/"), false));
    }
}
