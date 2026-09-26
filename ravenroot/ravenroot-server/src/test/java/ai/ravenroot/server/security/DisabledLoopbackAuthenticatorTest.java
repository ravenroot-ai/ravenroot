package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisabledLoopbackAuthenticatorTest {
    @Test
    void localEmbedHostCanSelectWorkloadIdentityWithoutChangingOtherLocalRequests() {
        var authenticator = new DisabledLoopbackAuthenticator();
        var ordinary = new Headers();
        assertEquals(AuthenticatedPrincipal.Type.USER, authenticator.authenticate(ordinary).type());
        ordinary.set(DisabledLoopbackAuthenticator.WORKLOAD_HEADER, "embed");
        var embed = authenticator.authenticate(ordinary);
        assertEquals(AuthenticatedPrincipal.Type.WORKLOAD, embed.type());
        assertEquals("local", embed.tenantId());
        ordinary.set(DisabledLoopbackAuthenticator.WORKLOAD_HEADER, "anything-else");
        assertEquals(AuthenticatedPrincipal.Type.USER, authenticator.authenticate(ordinary).type());
    }
}
