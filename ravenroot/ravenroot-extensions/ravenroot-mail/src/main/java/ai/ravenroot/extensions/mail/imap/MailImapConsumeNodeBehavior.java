package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.PropertyCondition;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Deployment-scoped, read-only IMAP polling source. */
public final class MailImapConsumeNodeBehavior implements NodeBehavior, InboundSourceCapable {
    public static final String BEHAVIOR = "mail.imap.consume";
    private static final Set<String> CONFIGURATION = Set.of("profile", "folder", "pollIntervalMs",
            "batchSize", "maxInFlight", "retryBackoffMs", "maxRetryBackoffMs", "poisonAttempts",
            "contentMode", "previewChars", "allowedHeaders", "checkpointPolicy");

    private final CredentialResolver credentials;
    private final ImapProfileResolver profiles;
    private final ImapConsumerPolicyResolver policies;
    private final ImapConsumerProtocol protocol;
    private final Executor executor;
    private final Clock clock;

    public MailImapConsumeNodeBehavior() {
        this(new EnvironmentImapProfileResolver(),
                new ai.ravenroot.extensions.mail.EnvironmentMailCredentialResolver(),
                new EnvironmentImapConsumerPolicyResolver(), new AngusImapConsumerProtocol(),
                defaultExecutor(), Clock.systemUTC());
    }

    MailImapConsumeNodeBehavior(ImapProfileResolver profiles, CredentialResolver credentials,
                                ImapConsumerPolicyResolver policies, ImapConsumerProtocol protocol,
                                Executor executor, Clock clock) {
        this.profiles = Objects.requireNonNull(profiles);
        this.credentials = Objects.requireNonNull(credentials);
        this.policies = Objects.requireNonNull(policies);
        this.protocol = Objects.requireNonNull(protocol);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public NodeTypeDescriptor descriptor() {
        List<NodePropertyDescriptor> properties = new ArrayList<>();
        properties.add(NodePropertyDescriptor.required("profile", "Mail profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; endpoint and credentials never enter the graph."));
        properties.add(optional("folder", "Source folder", NodePropertyType.STRING,
                "Optional confirmation of the exact operator-authorized source folder."));
        properties.add(optional("pollIntervalMs", "Poll interval (ms)", NodePropertyType.INTEGER,
                "May only increase the operator interval, up to 60000 ms."));
        properties.add(optional("batchSize", "Batch size", NodePropertyType.INTEGER,
                "Tightening-only messages per bounded scan."));
        properties.add(new NodePropertyDescriptor("maxInFlight", "Maximum in-flight",
                NodePropertyType.INTEGER, false, "The consumer is serial; only 1 is accepted.",
                "1", List.of("1")));
        properties.add(optional("retryBackoffMs", "Retry backoff (ms)", NodePropertyType.INTEGER,
                "May only increase the operator reconnect/admission backoff, up to 60000 ms."));
        properties.add(optional("maxRetryBackoffMs", "Maximum retry backoff (ms)", NodePropertyType.INTEGER,
                "May only increase the operator maximum backoff, up to 60000 ms."));
        properties.add(optional("poisonAttempts", "Poison attempts", NodePropertyType.INTEGER,
                "Tightening-only projection/admission retry ceiling."));
        properties.add(new NodePropertyDescriptor("contentMode", "Content mode", NodePropertyType.STRING,
                false, "Metadata-only by default; preview requires operator authority.", "metadata",
                List.of("metadata", "preview")));
        PropertyCondition preview = PropertyCondition.equalTo("contentMode", "preview");
        properties.add(new NodePropertyDescriptor("previewChars", "Preview characters",
                NodePropertyType.INTEGER, false, "Tightening-only text and HTML preview bound.", "",
                List.of(), false, preview, preview));
        properties.add(optional("allowedHeaders", "Allowed headers", NodePropertyType.STRING,
                "Optional comma-separated tightening of the operator-authorized inbound header allowlist."));
        properties.add(new NodePropertyDescriptor("checkpointPolicy", "Checkpoint policy",
                NodePropertyType.STRING, false, "Only durable checkpointing is supported.",
                "require-durable", List.of("require-durable")));
        return new NodeTypeDescriptor(BEHAVIOR, "Consume mailbox messages", "Mail",
                "Polls one operator-authorized IMAP folder and starts one durable traversal per message.",
                "actor", false, List.copyOf(properties),
                Set.of("network", "credential-reference", "inbound-source"));
    }

    private static NodePropertyDescriptor optional(String name, String label, NodePropertyType type,
                                                   String description) {
        return NodePropertyDescriptor.optional(name, label, type, description, "");
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> java.util.concurrent.CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload()));
    }

    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return new ImapConsumerSource(configuration, credentials, profiles, policies, protocol, executor, clock);
    }

    static Set<String> knownConfiguration() { return CONFIGURATION; }

    private static Executor defaultExecutor() {
        ClassLoader pluginLoader = MailImapConsumeNodeBehavior.class.getClassLoader();
        return task -> Thread.ofVirtual().name("ravenroot-imap-consumer").start(() -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(pluginLoader);
                task.run();
            } finally { thread.setContextClassLoader(previous); }
        });
    }
}
