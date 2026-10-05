package ai.ravenroot.extensions.kafka;

import org.apache.kafka.common.network.ChannelState;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.network.NetworkSend;
import org.apache.kafka.common.network.Selectable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Final socket boundary: refuses a connection unless its pinned address and port share one scope. */
final class GuardedKafkaSelectable implements Selectable {
    private final Selectable delegate;
    private final KafkaConnectionGuard guard;

    GuardedKafkaSelectable(Selectable delegate, KafkaConnectionGuard guard) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.guard = java.util.Objects.requireNonNull(guard);
    }

    @Override public void connect(String id, InetSocketAddress address, int sendBufferSize,
                                  int receiveBufferSize) throws IOException {
        guard.requirePinnedConnection(id, address);
        delegate.connect(id, address, sendBufferSize, receiveBufferSize);
    }

    @Override public void wakeup() { delegate.wakeup(); }
    @Override public void close() { delegate.close(); }
    @Override public void close(String id) { delegate.close(id); }
    @Override public void send(NetworkSend send) { delegate.send(send); }
    @Override public void poll(long timeout) throws IOException { delegate.poll(timeout); }
    @Override public List<NetworkSend> completedSends() { return delegate.completedSends(); }
    @Override public Collection<NetworkReceive> completedReceives() { return delegate.completedReceives(); }
    @Override public Map<String, ChannelState> disconnected() { return delegate.disconnected(); }
    @Override public List<String> connected() { return delegate.connected(); }
    @Override public void mute(String id) { delegate.mute(id); }
    @Override public void unmute(String id) { delegate.unmute(id); }
    @Override public void muteAll() { delegate.muteAll(); }
    @Override public void unmuteAll() { delegate.unmuteAll(); }
    @Override public boolean isChannelReady(String id) { return delegate.isChannelReady(id); }
}
