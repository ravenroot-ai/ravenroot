package ai.ravenroot.extensions.amqp091;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.LongString;
import com.rabbitmq.client.PossibleAuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;

import javax.net.ssl.SSLHandshakeException;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Official RabbitMQ adapter with a single channel-owning source thread. */
final class RabbitMqAmqpConsumerProtocol implements AmqpConsumerProtocol {
    private final Supplier<ConnectionFactory> factories;

    RabbitMqAmqpConsumerProtocol() { this(ConnectionFactory::new); }
    RabbitMqAmqpConsumerProtocol(Supplier<ConnectionFactory> factories) {
        this.factories = Objects.requireNonNull(factories);
    }

    @Override public Owner open(AmqpProfile profile, AmqpConsumerPolicy policy, char[] password,
                                int prefetch) throws Failure {
        Connection connection = null;
        Channel channel = null;
        try {
            ConnectionFactory factory = Objects.requireNonNull(factories.get());
            factory.setHost(profile.host()); factory.setPort(profile.port()); factory.setVirtualHost(profile.vhost());
            factory.setUsername(profile.username()); factory.setPassword(new String(password));
            factory.setConnectionTimeout(profile.timeoutMs()); factory.setHandshakeTimeout(profile.timeoutMs());
            factory.setChannelRpcTimeout(profile.timeoutMs()); factory.setShutdownTimeout(profile.timeoutMs());
            factory.setAutomaticRecoveryEnabled(false); factory.setTopologyRecoveryEnabled(false);
            if (profile.tls()) { factory.useSslProtocol(); factory.enableHostnameVerification(); }
            connection = factory.newConnection("ravenroot-amqp091-consumer");
            channel = connection.createChannel();
            if (channel == null) throw new java.io.IOException("channel unavailable");
            channel.basicQos(prefetch);
            RabbitOwner owner = new RabbitOwner(connection, channel, prefetch, policy);
            owner.subscribe(policy.queue());
            return owner;
        } catch (Exception failure) {
            if (channel != null) try { channel.abort(); } catch (Exception ignored) { }
            if (connection != null) try { connection.abort(); } catch (Exception ignored) { }
            throw new Failure(permanent(failure), permanent(failure)
                    ? "amqp-consumer-authorization-failed" : "amqp-consumer-unavailable");
        }
    }

    private static final class RabbitOwner implements Owner {
        private static final Object WAKE = new Object();
        private final Connection connection;
        private final Channel channel;
        private final ArrayBlockingQueue<Object> events;
        private final AmqpConsumerPolicy policy;
        private final AtomicBoolean closed = new AtomicBoolean();
        private String consumerTag;

        RabbitOwner(Connection connection, Channel channel, int prefetch, AmqpConsumerPolicy policy) {
            this.connection = connection; this.channel = channel;
            this.policy = policy;
            events = new ArrayBlockingQueue<>(prefetch + 8);
            connection.addShutdownListener(signal -> offer(new Disconnected("amqp-connection-lost")));
            channel.addShutdownListener(signal -> offer(new Disconnected("amqp-channel-lost")));
        }

        void subscribe(String queue) throws java.io.IOException {
            consumerTag = channel.basicConsume(queue, false, (tag, delivery) -> {
                long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                if (delivery.getBody().length > policy.maxBodyBytes()) {
                    offer(new Rejected(deliveryTag, "body-too-large"));
                    return;
                }
                try {
                    AMQP.BasicProperties p = delivery.getProperties();
                    Date timestamp = p.getTimestamp();
                    offer(new Delivery(deliveryTag, delivery.getEnvelope().isRedeliver(),
                            delivery.getEnvelope().getExchange(), delivery.getEnvelope().getRoutingKey(),
                            delivery.getBody(), new Properties(p.getContentType(), p.getContentEncoding(),
                            p.getMessageId(), p.getCorrelationId(), p.getReplyTo(), p.getType(), p.getAppId(),
                            timestamp == null ? -1 : timestamp.getTime(), copyHeaders(p.getHeaders(), policy))));
                } catch (InvalidMetadata invalid) {
                    offer(new Rejected(deliveryTag, invalid.safeReason));
                }
            }, tag -> offer(new Disconnected("amqp-consumer-cancelled")));
        }

        @Override public Event poll(Duration timeout) throws Failure {
            try {
                Object event = events.poll(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
                if (event == WAKE) throw new Failure(false, "amqp-consumer-wakeup");
                return event instanceof Event value ? value : new Idle();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new Failure(false, "amqp-consumer-interrupted");
            }
        }

        @Override public void ack(long deliveryTag) throws Failure {
            try { channel.basicAck(deliveryTag, false); }
            catch (Exception failure) { throw new Failure(false, "amqp-ack-failed"); }
        }

        @Override public void nack(long deliveryTag, boolean requeue) throws Failure {
            try { channel.basicNack(deliveryTag, false, requeue); }
            catch (Exception failure) { throw new Failure(false, "amqp-nack-failed"); }
        }

        @Override public void wakeup() { offer(WAKE); }

        @Override public void close(Duration timeout) {
            if (!closed.compareAndSet(false, true)) return;
            int millis = (int) Math.min(Integer.MAX_VALUE, Math.max(0, timeout.toMillis()));
            if (millis == 0) {
                try { if (channel.isOpen()) channel.abort(); } catch (Exception ignored) { }
                try { connection.abort(); } catch (Exception ignored) { }
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            AtomicBoolean finished = new AtomicBoolean();
            Thread watchdog = Thread.ofVirtual().name("ravenroot-amqp091-close-watchdog").start(() -> {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
                if (!finished.get()) try { connection.abort(); } catch (Exception ignored) { }
            });
            try { if (channel.isOpen() && consumerTag != null) channel.basicCancel(consumerTag); }
            catch (Exception ignored) { }
            try { if (channel.isOpen()) channel.close(); } catch (Exception ignored) { }
            try {
                long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (connection.isOpen()) connection.close((int) Math.min(Integer.MAX_VALUE, remaining));
            } catch (Exception ignored) {
                try { connection.abort(); } catch (Exception ignoredAgain) { }
            } finally {
                finished.set(true); watchdog.interrupt();
                try { if (connection.isOpen()) connection.abort(); } catch (Exception ignored) { }
            }
        }

        private void offer(Object event) {
            if (!events.offer(event) && event instanceof Disconnected) {
                events.poll(); events.offer(event);
            }
        }

        private static Map<String, Object> copyHeaders(Map<String, Object> headers, AmqpConsumerPolicy policy) {
            if (headers == null || headers.isEmpty()) return Map.of();
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            int total = 0;
            for (String key : policy.headers().stream().sorted().toList()) {
                if (!headers.containsKey(key)) continue;
                Object value = headers.get(key);
                long valueSize = size(value);
                if (valueSize < 0) throw new InvalidMetadata("unsupported-header");
                long next = (long) total + key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + valueSize;
                if (next > policy.maxHeaderBytes()) throw new InvalidMetadata("headers-too-large");
                total = (int) next;
                copy.put(key, value instanceof LongString text ? text.getBytes()
                        : value instanceof byte[] bytes ? bytes.clone() : value);
            }
            return java.util.Collections.unmodifiableMap(copy);
        }

        private static long size(Object value) {
            if (value instanceof LongString text) return text.length();
            if (value instanceof byte[] bytes) return bytes.length;
            if (value instanceof String text) return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long || value instanceof Float
                    || value instanceof Double) return value.toString().length();
            return -1;
        }

        private static final class InvalidMetadata extends RuntimeException {
            private final String safeReason;
            private InvalidMetadata(String safeReason) { this.safeReason = safeReason; }
        }
    }

    private static boolean permanent(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof PossibleAuthenticationFailureException || current instanceof SSLHandshakeException
                    || current instanceof java.security.GeneralSecurityException) return true;
            if (current instanceof ShutdownSignalException shutdown) {
                int code = shutdown.getReason() instanceof AMQP.Connection.Close close ? close.getReplyCode()
                        : shutdown.getReason() instanceof AMQP.Channel.Close close ? close.getReplyCode() : 0;
                if (code == 402 || code == 403 || code == 404 || code == 405 || code == 406
                        || code >= 501 && code <= 505 || code == 530 || code == 540) return true;
            }
        }
        return false;
    }
}
