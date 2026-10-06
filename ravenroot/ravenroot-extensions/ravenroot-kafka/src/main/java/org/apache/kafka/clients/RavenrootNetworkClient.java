package org.apache.kafka.clients;

import org.apache.kafka.common.Node;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.network.Selectable;
import org.apache.kafka.common.telemetry.internals.ClientTelemetrySender;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;

import java.util.function.Consumer;

/** Kafka 4.1.2 network client that registers every direct node before connection setup. */
public final class RavenrootNetworkClient extends NetworkClient {
    private final Consumer<Node> nodeAdmission;

    /**
     * Mirrors Kafka 4.1.2's complete public constructor and adds a fail-closed node admission hook.
     *
     * @param metadataUpdater optional metadata updater
     * @param metadata client metadata
     * @param selector guarded transport selector
     * @param clientId Kafka client identifier
     * @param maxInFlightRequestsPerConnection request concurrency limit
     * @param reconnectBackoffMs initial reconnect backoff
     * @param reconnectBackoffMax maximum reconnect backoff
     * @param socketSendBuffer socket send buffer size
     * @param socketReceiveBuffer socket receive buffer size
     * @param requestTimeoutMs request timeout
     * @param connectionSetupTimeoutMs initial connection setup timeout
     * @param connectionSetupTimeoutMaxMs maximum connection setup timeout
     * @param time Kafka time source
     * @param discoverBrokerVersions whether to discover broker API versions
     * @param apiVersions broker API-version state
     * @param throttleTimeSensor request throttle sensor
     * @param logContext Kafka log context
     * @param hostResolver scoped host resolver
     * @param telemetrySender optional telemetry sender
     * @param rebootstrapTriggerMs metadata rebootstrap threshold
     * @param recoveryStrategy metadata recovery strategy
     * @param nodeAdmission exact node admission invoked before every direct ready/connect path
     */
    public RavenrootNetworkClient(MetadataUpdater metadataUpdater, Metadata metadata, Selectable selector,
                                  String clientId, int maxInFlightRequestsPerConnection,
                                  long reconnectBackoffMs, long reconnectBackoffMax,
                                  int socketSendBuffer, int socketReceiveBuffer, int requestTimeoutMs,
                                  long connectionSetupTimeoutMs, long connectionSetupTimeoutMaxMs,
                                  Time time, boolean discoverBrokerVersions, ApiVersions apiVersions,
                                  Sensor throttleTimeSensor, LogContext logContext,
                                  HostResolver hostResolver, ClientTelemetrySender telemetrySender,
                                  long rebootstrapTriggerMs, MetadataRecoveryStrategy recoveryStrategy,
                                  Consumer<Node> nodeAdmission) {
        super(metadataUpdater, metadata, selector, clientId, maxInFlightRequestsPerConnection,
                reconnectBackoffMs, reconnectBackoffMax, socketSendBuffer, socketReceiveBuffer,
                requestTimeoutMs, connectionSetupTimeoutMs, connectionSetupTimeoutMaxMs, time,
                discoverBrokerVersions, apiVersions, throttleTimeSensor, logContext, hostResolver,
                telemetrySender, rebootstrapTriggerMs, recoveryStrategy);
        this.nodeAdmission = java.util.Objects.requireNonNull(nodeAdmission);
    }

    /**
     * Registers the original node identity before Kafka can start a direct connection.
     *
     * @param node exact Kafka node selected for connection
     * @param now current Kafka time in milliseconds
     * @return whether the node is ready for requests
     */
    @Override public boolean ready(Node node, long now) {
        nodeAdmission.accept(node);
        return super.ready(node, now);
    }
}
