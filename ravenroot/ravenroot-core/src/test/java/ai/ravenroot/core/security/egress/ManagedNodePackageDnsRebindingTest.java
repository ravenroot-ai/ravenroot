package ai.ravenroot.core.security.egress;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedNodePackageDnsRebindingTest {
    private static final AtomicInteger NAMES = new AtomicInteger();
    private EgressAddressGuard.NameSource upstream;
    private ReservedNetworkPolicy policy;

    @BeforeAll static void installProvider() throws Exception { assertNotNull(InetAddress.getByName("localhost")); }
    @BeforeEach void capture() { upstream = EgressAddressGuard.upstream(); policy = EgressAddressGuard.policy(); }
    @AfterEach void restore() {
        EgressAddressGuard.replaceUpstream(upstream);
        EgressAddressGuard.configure(policy);
    }

    @Test
    void packageAllowlistCannotBeReboundIntoReservedSpace() throws Exception {
        String host = "package-rebind-" + NAMES.incrementAndGet() + "-" + System.nanoTime() + ".invalid";
        EgressAddressGuard.configure(ReservedNetworkPolicy.denyAllReserved());
        EgressAddressGuard.replaceUpstream((ignored, lookupPolicy) ->
                List.of(InetAddress.getByName("127.0.0.1")));
        var egressPolicy = NodePackageEgressPolicy.builder().allowOrigin("http", host, 80)
                .allowHttpMethod("GET").maximumDeadline(Duration.ofSeconds(1)).build();
        var services = ManagedNodePackageServices.builder("test.rebinding", egressPolicy,
                        (packageId, tenant, reference) -> java.util.Optional.empty())
                .grant(NodePackageCapability.OUTBOUND_HTTP).build();

        var call = services.outboundHttp().execute(message(), new OutboundHttpRequest(
                URI.create("http://" + host + "/"), "GET", Map.of(), null,
                Duration.ofSeconds(1), null));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> call.completion().toCompletableFuture().join());
        assertEquals(NodePackageServiceException.Reason.RESOLUTION_REFUSED,
                ((NodePackageServiceException) failure.getCause()).reason());
    }

    private static NodeMessage message() {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", "tenant", "subject", PrincipalType.USER, "issuer"),
                id, id, id, id, Set.of(), "node", null, Map.of());
    }
}
