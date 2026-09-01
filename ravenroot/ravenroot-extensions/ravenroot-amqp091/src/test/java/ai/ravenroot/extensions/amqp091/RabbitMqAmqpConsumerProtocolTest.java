package ai.ravenroot.extensions.amqp091;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.PossibleAuthenticationFailureException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitMqAmqpConsumerProtocolTest {
    @Test
    void configuresTlsHostnameChecksDeadlinesAndDisablesClientRecovery() {
        var factory = new RefusingFactory(new IOException("offline"));
        var protocol = new RabbitMqAmqpConsumerProtocol(() -> factory);

        var failure = assertThrows(AmqpConsumerProtocol.Failure.class, () -> protocol.open(
                AmqpTestSupport.profile(), AmqpConsumerTestSupport.policy(),
                AmqpTestSupport.SECRET.toCharArray(), 3));

        assertFalse(failure.permanent());
        assertEquals("amqp-consumer-unavailable", failure.getMessage());
        assertEquals("broker.example.test", factory.getHost());
        assertEquals(5671, factory.getPort());
        assertEquals("/tenant-a", factory.getVirtualHost());
        assertEquals(AmqpTestSupport.profile().timeoutMs(), factory.getConnectionTimeout());
        assertEquals(AmqpTestSupport.profile().timeoutMs(), factory.getHandshakeTimeout());
        assertEquals(AmqpTestSupport.profile().timeoutMs(), factory.getChannelRpcTimeout());
        assertEquals(AmqpTestSupport.profile().timeoutMs(), factory.getShutdownTimeout());
        assertTrue(factory.sslConfigured);
        assertTrue(factory.hostnameVerification);
        assertFalse(factory.isAutomaticRecoveryEnabled());
        assertFalse(factory.isTopologyRecoveryEnabled());
    }

    @Test
    void authenticationAndTlsHandshakeFailuresArePermanentAndSanitized() {
        for (IOException cause : java.util.List.of(
                new PossibleAuthenticationFailureException("password=" + AmqpTestSupport.SECRET),
                new javax.net.ssl.SSLHandshakeException("certificate=" + AmqpTestSupport.SECRET))) {
            var protocol = new RabbitMqAmqpConsumerProtocol(() -> new RefusingFactory(cause));
            var failure = assertThrows(AmqpConsumerProtocol.Failure.class, () -> protocol.open(
                    AmqpTestSupport.profile(), AmqpConsumerTestSupport.policy(),
                    AmqpTestSupport.SECRET.toCharArray(), 3));
            assertTrue(failure.permanent());
            assertEquals("amqp-consumer-authorization-failed", failure.getMessage());
            assertFalse(failure.getMessage().contains(AmqpTestSupport.SECRET));
        }
    }

    private static final class RefusingFactory extends ConnectionFactory {
        private final IOException failure;
        private boolean sslConfigured;
        private boolean hostnameVerification;

        private RefusingFactory(IOException failure) { this.failure = failure; }

        @Override public void useSslProtocol() {
            sslConfigured = true;
        }

        @Override public void enableHostnameVerification() {
            hostnameVerification = true;
        }

        @Override public Connection newConnection(String connectionName) throws IOException, TimeoutException {
            throw failure;
        }
    }
}
