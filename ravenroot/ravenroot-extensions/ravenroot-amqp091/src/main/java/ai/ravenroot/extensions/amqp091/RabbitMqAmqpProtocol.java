package ai.ravenroot.extensions.amqp091;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.PossibleAuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Official RabbitMQ Java-client adapter. Automatic recovery is deliberately disabled per invocation. */
final class RabbitMqAmqpProtocol implements AmqpProtocol {
    private final Supplier<ConnectionFactory> factories;
    private final LongSupplier ticker;

    RabbitMqAmqpProtocol() {
        this(ConnectionFactory::new, System::nanoTime);
    }

    RabbitMqAmqpProtocol(Supplier<ConnectionFactory> factories) {
        this(factories, System::nanoTime);
    }

    RabbitMqAmqpProtocol(Supplier<ConnectionFactory> factories, LongSupplier ticker) {
        this.factories = Objects.requireNonNull(factories);
        this.ticker = Objects.requireNonNull(ticker);
    }

    @Override
    public ConnectAttempt beginConnect(AmqpProfile profile, char[] password, int timeoutMs) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(password);
        return new RabbitConnectAttempt(profile, new String(password), timeoutMs);
    }

    private final class RabbitConnectAttempt implements ConnectAttempt {
        private final AmqpProfile profile;
        private final String password;
        private final long deadline;
        private final AtomicBoolean abortScheduled = new AtomicBoolean();
        private boolean cancelled;
        private boolean established;
        private boolean claimed;
        private Connection connection;
        private Channel channel;

        private RabbitConnectAttempt(AmqpProfile profile, String password, int timeoutMs) {
            this.profile = profile;
            this.password = password;
            this.deadline = deadline(timeoutMs);
        }

        @Override
        public void establish() throws ConnectFailure {
            try {
                requireActiveBudget();
                ConnectionFactory factory = Objects.requireNonNull(factories.get());
                int initialBudget = requireActiveBudget();
                factory.setHost(profile.host());
                factory.setPort(profile.port());
                factory.setVirtualHost(profile.vhost());
                factory.setUsername(profile.username());
                factory.setPassword(password);
                factory.setConnectionTimeout(initialBudget);
                factory.setHandshakeTimeout(initialBudget);
                factory.setChannelRpcTimeout(initialBudget);
                factory.setShutdownTimeout(initialBudget);
                factory.setAutomaticRecoveryEnabled(false);
                factory.setTopologyRecoveryEnabled(false);
                if (profile.tls()) {
                    factory.useSslProtocol();
                    factory.enableHostnameVerification();
                }
                requireActiveBudget();
                Connection opened = factory.newConnection("ravenroot-amqp091");
                if (!register(opened)) throw new ConnectFailure(ConnectFailureKind.TEMPORARY);
                requireActiveBudget();
                Channel openedChannel = opened.createChannel();
                if (openedChannel == null) throw new IOException("channel unavailable");
                synchronized (this) {
                    if (cancelled) throw new ConnectFailure(ConnectFailureKind.TEMPORARY);
                    channel = openedChannel;
                }
                requireActiveBudget();
                try {
                    openedChannel.confirmSelect();
                } catch (Exception protocolFailure) {
                    throw new ConnectFailure(ConnectFailureKind.PERMANENT);
                }
                requireActiveBudget();
                synchronized (this) {
                    if (cancelled) throw new ConnectFailure(ConnectFailureKind.TEMPORARY);
                    established = true;
                }
            } catch (ConnectFailure classified) {
                cancel();
                throw classified;
            } catch (Exception unavailable) {
                cancel();
                throw new ConnectFailure(permanent(unavailable)
                        ? ConnectFailureKind.PERMANENT : ConnectFailureKind.TEMPORARY);
            }
        }

        @Override
        public synchronized Session claim() throws ConnectFailure {
            if (cancelled || !established || claimed || remainingMillis(deadline) == 0 || connection == null) {
                cancel();
                throw new ConnectFailure(ConnectFailureKind.TEMPORARY);
            }
            claimed = true;
            Connection transferred = connection;
            Channel transferredChannel = channel;
            connection = null;
            channel = null;
            return new RabbitSession(transferred, transferredChannel);
        }

        @Override
        public void cancel() {
            Connection owned;
            synchronized (this) {
                if (claimed) return;
                cancelled = true;
                owned = connection;
                connection = null;
                channel = null;
            }
            if (owned != null) abortOwned(owned);
        }

        private synchronized int requireActiveBudget() throws ConnectFailure {
            int remaining = remainingMillis(deadline);
            if (cancelled || remaining == 0) throw new ConnectFailure(ConnectFailureKind.TEMPORARY);
            return remaining;
        }

        private boolean register(Connection opened) {
            Objects.requireNonNull(opened);
            synchronized (this) {
                if (!cancelled) {
                    connection = opened;
                    return true;
                }
            }
            abortOwned(opened);
            return false;
        }

        private void abortOwned(Connection owned) {
            if (!abortScheduled.compareAndSet(false, true)) return;
            int remaining = remainingMillis(deadline);
            Thread.ofVirtual().name("ravenroot-amqp091-connect-abort").start(() -> {
                try {
                    if (owned.isOpen()) owned.abort(remaining);
                } catch (RuntimeException ignored) { }
            });
        }
    }

    private static final class RabbitSession implements Session {
        private final Connection connection;
        private final Channel channel;

        private RabbitSession(Connection connection, Channel channel) {
            this.connection = connection;
            this.channel = channel;
        }

        @Override
        public void publish(Publication publication, Observer observer, int timeoutMs) throws Exception {
            channel.addReturnListener(returned -> observer.returned(new ReturnMetadata(returned.getReplyCode(),
                    returned.getReplyText(), returned.getExchange(), returned.getRoutingKey())));
            channel.addConfirmListener((sequence, multiple) -> observer.confirmed(),
                    (sequence, multiple) -> observer.nacked());
            channel.addShutdownListener(ignored -> observer.closed());
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType(publication.contentType())
                    .contentEncoding(publication.contentEncoding())
                    .deliveryMode(publication.persistent() ? 2 : 1)
                    .priority(publication.priority())
                    .expiration(publication.expiration())
                    .messageId(publication.messageId())
                    .correlationId(publication.correlationId())
                    .replyTo(publication.replyTo())
                    .type(publication.type())
                    .appId(publication.appId())
                    .headers(publication.headers())
                    .build();
            channel.basicPublish(publication.exchange(), publication.routingKey(), publication.mandatory(),
                    properties, publication.body());
        }

        @Override
        public void close(int timeoutMs) throws IOException {
            if (connection.isOpen()) connection.close(Math.max(1, timeoutMs));
        }

        @Override
        public void abort(int timeoutMs) {
            if (connection.isOpen()) connection.abort(Math.max(0, timeoutMs));
        }
    }

    private long deadline(int timeoutMs) {
        long duration = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        try {
            return Math.addExact(ticker.getAsLong(), duration);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private int remainingMillis(long deadline) {
        long remaining;
        try {
            remaining = Math.subtractExact(deadline, ticker.getAsLong());
        } catch (ArithmeticException overflow) {
            return 0;
        }
        if (remaining <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private static boolean permanent(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof PossibleAuthenticationFailureException
                    || current instanceof SSLHandshakeException || current instanceof GeneralSecurityException)
                return true;
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
