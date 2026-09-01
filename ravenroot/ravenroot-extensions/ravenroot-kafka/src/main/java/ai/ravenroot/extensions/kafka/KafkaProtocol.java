package ai.ravenroot.extensions.kafka;

import java.util.Map;

/** Injectable boundary around the official Kafka producer. */
interface KafkaProtocol {
    CreateAttempt beginCreate(KafkaProfile profile, char[] password, int timeoutMs) throws ClientFailure;

    interface CreateAttempt {
        void establish() throws ClientFailure;
        Client claim() throws ClientFailure;
        void cancel();
    }

    interface Client {
        void send(Record record, Observer observer, int timeoutMs) throws Exception;
        void flush() throws Exception;
        /** Calls ownershipRevoked after this client rejects new use and before bounded close work. */
        void close(int timeoutMs, Runnable ownershipRevoked) throws Exception;
    }

    interface Observer {
        void acknowledged(Metadata metadata);
        void failed(Throwable failure);
    }

    enum FailureKind { TEMPORARY, PERMANENT }
    final class ClientFailure extends Exception {
        private final FailureKind kind;
        ClientFailure(FailureKind kind) { super("Kafka client creation failed"); this.kind = kind; }
        FailureKind kind() { return kind; }
    }

    record Record(String topic, Integer partition, Long timestamp, byte[] key, byte[] value,
                  Map<String, byte[]> headers) {
        public Record { key = key == null ? null : key.clone(); value = value.clone(); headers = Map.copyOf(headers); }
        @Override public byte[] key() { return key == null ? null : key.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
    record Metadata(String topic, int partition, long offset, long timestamp, int keyBytes, int valueBytes) { }
}
