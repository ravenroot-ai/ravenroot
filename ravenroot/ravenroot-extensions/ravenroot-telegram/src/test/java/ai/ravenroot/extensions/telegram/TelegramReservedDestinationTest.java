package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramReservedDestinationTest {
    @Test
    void literalTransportOriginIsRefusedBeforeCredentialOrClientAndExactExceptionReachesBoth() {
        AtomicInteger credentials = new AtomicInteger();
        AtomicInteger clients = new AtomicInteger();
        var resolver = (ai.ravenroot.api.security.CredentialResolver) reference -> {
            credentials.incrementAndGet();
            return Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray()));
        };
        var factory = (TelegramBotApiClient.ClientFactory) timeout -> {
            clients.incrementAndGet();
            return null;
        };
        URI origin = URI.create("https://127.0.0.1");
        SecurityException refused = assertThrows(SecurityException.class,
                () -> new TelegramBotApiClient(resolver, factory, origin,
                        ReservedNetworkPolicy.denyAllReserved()));
        assertFalse(refused.getMessage().contains("127.0.0.1"));
        assertEquals(0, credentials.get());
        assertEquals(0, clients.get());

        TelegramBotApiClient allowed = new TelegramBotApiClient(resolver, factory, origin,
                ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.0.0.1:LOOPBACK"));
        allowed.call(TelegramTestSupport.profile("tenant-a", "telegram-bot", 0),
                100, 0, "sendMessage", new TelegramBotApiClient.Body(new byte[] {1}, "application/json"));
        assertEquals(1, credentials.get());
        assertEquals(1, clients.get());
    }
}
