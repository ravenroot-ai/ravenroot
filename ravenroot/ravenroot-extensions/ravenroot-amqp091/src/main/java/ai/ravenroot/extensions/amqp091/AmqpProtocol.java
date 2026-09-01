package ai.ravenroot.extensions.amqp091;

import java.util.Map;

/** Injectable protocol boundary: production uses RabbitMQ; tests drive deterministic wire events. */
interface AmqpProtocol {
    /** Captures credentials synchronously and returns an attempt whose resources remain owned until claim. */
    ConnectAttempt beginConnect(AmqpProfile profile, char[] password, int timeoutMs) throws ConnectFailure;

    interface ConnectAttempt {
        /** Performs all potentially blocking connection, channel and confirm-mode establishment. */
        void establish() throws ConnectFailure;

        /** Transfers the established session to the caller only while the attempt is still active. */
        Session claim() throws ConnectFailure;

        /** Promptly revokes the claim opportunity and starts non-blocking cleanup. */
        void cancel();
    }

    interface Session {
        /** Once invoked, a thrown failure is conservatively post-publish and therefore ambiguous. */
        void publish(Publication publication, Observer observer, int timeoutMs) throws Exception;

        /** Performs an orderly close using no more than the supplied remaining total budget. */
        void close(int timeoutMs) throws Exception;

        /** Forces socket teardown using only the supplied remainder of the invocation deadline. */
        void abort(int timeoutMs) throws Exception;
    }

    interface Observer {
        void confirmed();
        void nacked();
        void returned(ReturnMetadata metadata);
        void closed();
    }

    enum ConnectFailureKind { TEMPORARY, PERMANENT }

    final class ConnectFailure extends Exception {
        private final ConnectFailureKind kind;

        ConnectFailure(ConnectFailureKind kind) {
            super(kind == ConnectFailureKind.PERMANENT
                    ? "AMQP connection was permanently rejected" : "AMQP connection is temporarily unavailable");
            this.kind = kind;
        }

        ConnectFailureKind kind() {
            return kind;
        }
    }

    record ReturnMetadata(int replyCode, String replyText, String exchange, String routingKey) { }

    record Publication(
            String exchange,
            String routingKey,
            boolean mandatory,
            byte[] body,
            String contentType,
            String contentEncoding,
            boolean persistent,
            Integer priority,
            String expiration,
            String messageId,
            String correlationId,
            String replyTo,
            String type,
            String appId,
            Map<String, Object> headers) {

        public Publication {
            body = body.clone();
            headers = Map.copyOf(headers);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
