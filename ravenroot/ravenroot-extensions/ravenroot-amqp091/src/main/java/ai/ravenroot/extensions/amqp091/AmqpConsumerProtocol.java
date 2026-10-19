package ai.ravenroot.extensions.amqp091;

import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;

/** Poll-owner protocol seam. Callback threads may enqueue data but never call an AMQP channel. */
@FunctionalInterface
interface AmqpConsumerProtocol {
    Owner open(AmqpProfile profile, AmqpConsumerPolicy policy, char[] password, int prefetch) throws Failure;

    default Owner open(AmqpProfile profile, AmqpConsumerPolicy policy, char[] password, int prefetch,
                       Destination destination) throws Failure {
        if (destination.mode() != Destination.Mode.CONSUMER) {
            throw new Failure(true, "amqp-subscription-unsupported");
        }
        return open(profile, policy, password, prefetch);
    }

    record Destination(Mode mode, String queue, String exchange, String bindingPattern) {
        enum Mode { CONSUMER, SUBSCRIBER }

        public Destination {
            java.util.Objects.requireNonNull(mode, "mode");
            queue = queue == null ? "" : queue;
            exchange = exchange == null ? "" : exchange;
            bindingPattern = bindingPattern == null ? "" : bindingPattern;
            if (mode == Mode.CONSUMER && (queue.isEmpty() || !exchange.isEmpty() || !bindingPattern.isEmpty())) {
                throw new IllegalArgumentException("invalid consumer destination");
            }
            if (mode == Mode.SUBSCRIBER && (!queue.isEmpty() || exchange.isEmpty() || bindingPattern.isEmpty())) {
                throw new IllegalArgumentException("invalid subscriber destination");
            }
        }

        static Destination consumer(String queue) {
            return new Destination(Mode.CONSUMER, queue, "", "");
        }

        static Destination subscriber(String exchange, String bindingPattern) {
            return new Destination(Mode.SUBSCRIBER, "", exchange, bindingPattern);
        }

        String stableScope() {
            if (mode == Mode.CONSUMER) return "queue:" + queue;
            java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
            return "topic:" + encoder.encodeToString(exchange.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + ":" + encoder.encodeToString(bindingPattern.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    interface Owner {
        /** Actual broker resource for lifecycle diagnostics; never serialized into authored configuration. */
        default String resourceName() { return ""; }
        Event poll(Duration timeout) throws Failure;
        void ack(long deliveryTag) throws Failure;
        void nack(long deliveryTag, boolean requeue) throws Failure;
        void wakeup();
        void close(Duration timeout);
    }

    sealed interface Event permits Delivery, Rejected, Disconnected, Idle { }
    record Idle() implements Event { }
    record Disconnected(String safeReason) implements Event { }
    record Rejected(long deliveryTag, String safeReason) implements Event { }
    record Delivery(long deliveryTag, boolean redelivered, String exchange, String routingKey,
                    byte[] body, Properties properties) implements Event {
        public Delivery {
            body = body.clone();
        }
        @Override public byte[] body() { return body.clone(); }
    }
    record Properties(String contentType, String contentEncoding, String messageId,
                      String correlationId, String replyTo, String type, String appId,
                      long timestamp, Map<String, Object> headers) {
        public Properties { headers = copyHeaders(headers); }
        @Override public Map<String, Object> headers() { return copyHeaders(headers); }

        private static Map<String, Object> copyHeaders(Map<String, Object> input) {
            if (input == null || input.isEmpty()) return Map.of();
            Map<String, Object> copy = new LinkedHashMap<>();
            input.forEach((key, value) -> copy.put(key, value instanceof byte[] bytes ? bytes.clone() : value));
            return java.util.Collections.unmodifiableMap(copy);
        }
    }

    final class Failure extends Exception {
        private final boolean permanent;
        Failure(boolean permanent, String safeReason) { super(safeReason); this.permanent = permanent; }
        boolean permanent() { return permanent; }
    }
}
