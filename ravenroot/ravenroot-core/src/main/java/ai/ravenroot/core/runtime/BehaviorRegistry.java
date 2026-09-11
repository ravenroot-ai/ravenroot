package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.NodeTypeDescriptorValidator;
import ai.ravenroot.api.catalog.NodeCatalogSource;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ResolvedOperationalPolicy;
import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import ai.ravenroot.api.publication.PublicationAuditSink;
import ai.ravenroot.api.publication.PublicationPolicyResolver;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.builtin.StandardBehaviorFactories;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit behavior composition; no reflection and no dependency-injection container. */
public final class BehaviorRegistry {
    private static final java.util.Set<String> LEGACY_CORE_WITHOUT_EXTERNAL_IO = java.util.Set.of(
            "log", "delay", "human-task", "template", "json-parse", "cel-transform",
            "cel-decision", "json-path", "boundary-guard");
    private final Map<String, NodeBehaviorFactory> factories = new ConcurrentHashMap<>();
    /**
     * The published catalog entry per behavior name, with runtime nature resolved once at
     * registration. Held beside {@code factories} rather than by wrapping the factory,
     * because {@link #sourceCapableBehavior} and {@link NodePackages} both pattern-match on the
     * factory's concrete type and a decorator would silently break that match.
     */
    private final Map<String, NodeTypeDescriptor> resolvedDescriptors = new ConcurrentHashMap<>();
    private final Map<String, NodeCatalogSource> catalogSources = new ConcurrentHashMap<>();
    /**
     * The full identity of every node package that registered a behavior here, keyed by package id.
     *
     * <p>{@link NodeCatalogSource} carries a bundle id and nothing else, and it is a published
     * catalog projection, so widening it would change a response shape for a reason unrelated to the
     * catalog. This map is held beside it instead, and records — as a digest, never as text — the two
     * facts a package declares about itself that the registry previously read and discarded: its own
     * build version, and the Node SDK contract it was compiled against. Both are needed to say
     * whether an execution's packages are the packages it was admitted with; a bundle id alone cannot
     * distinguish two builds. Nothing here constrains what a package may declare; see
     * {@link PinnedNodePackage} for why that is not a detail.</p>
     *
     * <p>Keyed by package id rather than by behavior name because a package is the versioned unit —
     * the same reason {@link ai.ravenroot.api.node.NodePackage} states for versioning the package and
     * not the individual behavior.</p>
     */
    private final Map<String, PinnedNodePackage> nodePackageIdentities = new ConcurrentHashMap<>();
    private final Map<String, RegisteredNodePackageBinding> nodePackageBindings = new ConcurrentHashMap<>();
    private final Map<String, String> behaviorPackageIds = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, ActiveOperationalPolicy> activeOperationalPolicies =
            new ConcurrentHashMap<>();
    private final Map<ai.ravenroot.api.deployment.InboundSourceContext, SourceBinding> sourceBindings =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private java.util.Optional<ResolvedOperationalPolicy.BuiltInHttpCapacity> builtInHttpCapacity =
            java.util.Optional.empty();

    public static BehaviorRegistry standard() {
        return standard(BehaviorEnvironment.safeDefaults());
    }

    public static BehaviorRegistry standard(BehaviorEnvironment environment) {
        return standard(environment, PublicationPolicyResolver.none(), PublicationAuditSink.noop());
    }

    /**
     * Builds the core catalog with operator-owned publication profiles and payload-free audit.
     * Existing overloads deliberately resolve no profiles, so {@code boundary-guard} remains visible
     * but fails closed until an application explicitly supplies this authority.
     */
    public static BehaviorRegistry standard(BehaviorEnvironment environment,
                                            PublicationPolicyResolver publicationPolicies,
                                            PublicationAuditSink publicationAudit) {
        return standard(environment, publicationPolicies, publicationAudit, null);
    }

    /** Builds the core catalog and arms {@code human-task} when a durable service is supplied. */
    public static BehaviorRegistry standard(BehaviorEnvironment environment,
                                            PublicationPolicyResolver publicationPolicies,
                                            PublicationAuditSink publicationAudit,
                                            ai.ravenroot.core.humantask.HumanTaskService humanTasks) {
        return standard(environment, publicationPolicies, publicationAudit, humanTasks,
                ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS);
    }

    /** Builds the core catalog with the exact Human Task policy used by the running server. */
    public static BehaviorRegistry standard(BehaviorEnvironment environment,
                                            PublicationPolicyResolver publicationPolicies,
                                            PublicationAuditSink publicationAudit,
                                            ai.ravenroot.core.humantask.HumanTaskService humanTasks,
                                            ai.ravenroot.api.persistence.HumanTaskPolicy humanTaskPolicy) {
        var registry = new BehaviorRegistry();
        ai.ravenroot.core.security.OutboundHttpPolicy http = environment.outboundHttpPolicy();
        registry.builtInHttpCapacity = java.util.Optional.of(
                new ResolvedOperationalPolicy.BuiltInHttpCapacity(http.maximumRequestBytes(),
                        http.maximumResponseBytes(), http.maximumTimeout()));
        StandardBehaviorFactories.all(environment, publicationPolicies, publicationAudit,
                        humanTasks, humanTaskPolicy, registry::operationalPolicyFor)
                .forEach(factory -> registry.registerFactory(factory, NodeCatalogSource.core()));
        return registry;
    }

    public BehaviorRegistry register(String name, NodeHandler handler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Behavior name cannot be blank");
        }
        if (handler == null) throw new IllegalArgumentException("Behavior handler cannot be null");
        registerFactory(new LegacyNodeBehaviorFactory(name, handler), NodeCatalogSource.bundle("application"));
        return this;
    }

    /**
     * Registers {@code factory} under its descriptor's behavior name.
     *
     * <h2>A name already registered is refused, never replaced</h2>
     * <p>Behavior names are the identity key a GraphML document stores and the catalog exposes to the
     * UI. A second registration under a name already in use would let whichever caller registers last
     * decide what that name means, silently -- the graph keeps referring to the same name, the UI
     * keeps showing the same node, and execution goes somewhere else. Nobody would have decided that;
     * registration order would have. That is a last-writer authority flaw: a control granting authority
     * to whoever arrives last rather than to whoever was meant to hold it.</p>
     *
     * <p><b>Why registration refuses rather than providing a distinct replacement operation.</b> An
     * explicitly named replacement could preserve deliberate re-registration such as plugin
     * hot-reload. However, nothing in this codebase deliberately re-registers a name today, and
     * {@code ravenroot-plugin-bundle}'s own {@code PluginActivation} documents why not --
     * "bundles are immutable, read-only build material with no hot-reload story." Building a named
     * replacement path for a capability nothing exercises and nothing has committed to needing would
     * be surface added on spec. Plain refusal is also where this codebase had already, independently,
     * converged: {@code NodePackages#register}'s own pre-existing check (a narrower guard, covering
     * only the SDK-package path) already refuses rather than replaces, for the identical reason stated
     * here. If a real re-registration need appears later -- hot-reload chief among them -- it is a new
     * decision informed by an actual caller, not a parameter added here speculatively.</p>
     *
     * <h2>Every registration path, not only the plugin one</h2>
     * <p>This is the one method every path funnels through -- built-ins
     * ({@link StandardBehaviorFactories}), the legacy handler-only {@link #register}, and SDK node
     * packages ({@link NodePackages#register}) alike -- the same reason {@link NodeTypeDescriptorValidator#validate}
     * was anchored here rather than in any one caller. A check placed in {@code NodePackages}
     * alone would leave built-ins and the legacy path unguarded; refusing here closes all three by
     * construction rather than by remembering to add the check to the next caller too.</p>
     *
     * @throws IllegalArgumentException if {@code factory} or its descriptor is null, or if a factory
     *                                  is already registered under this descriptor's behavior name
     */
    public BehaviorRegistry registerFactory(NodeBehaviorFactory factory) {
        return registerFactory(factory, NodeCatalogSource.bundle("application"));
    }

    BehaviorRegistry registerPackageFactory(NodeBehaviorFactory factory, String packageId,
                                            PinnedNodePackage pinned,
                                            java.util.Optional<NodePackageEgressCapacityProfile> capacity) {
        registerFactory(factory, NodeCatalogSource.bundle(packageId));
        // Recorded after the registration succeeds, so a refused behavior never leaves an identity
        // claiming a package contributed something it did not. The identity itself was built during
        // planning, so nothing about it can fail here.
        nodePackageIdentities.put(packageId, pinned);
        nodePackageBindings.put(packageId, new RegisteredNodePackageBinding(pinned, capacity));
        behaviorPackageIds.put(factory.descriptor().behavior(), packageId);
        return this;
    }

    private BehaviorRegistry registerFactory(NodeBehaviorFactory factory, NodeCatalogSource source) {
        if (factory == null || factory.descriptor() == null) {
            throw new IllegalArgumentException("Behavior factory and descriptor are required");
        }
        // Fail-closed descriptor validation on the ONE path every registration takes --
        // built-ins, SDK node packages and plugin bundles alike. Validating only in NodePackages
        // would leave built-in descriptors unchecked, and a control that cannot fire on the paths
        // that matter is not a control.
        NodeTypeDescriptorValidator.validate(factory.descriptor());
        String name = factory.descriptor().behavior();
        // Resolved before insertion, so a contradiction refuses registration rather than
        // landing a catalog entry whose declaration disagrees with its own code.
        NodeTypeDescriptor resolved = resolveNature(factory);
        // The same anchor, the same reasoning -- see this method's own Javadoc.
        NodeBehaviorFactory existing = factories.putIfAbsent(name, factory);
        if (existing != null) {
            throw new IllegalArgumentException("Behavior '" + name + "' is already registered ("
                    + existing.getClass().getSimpleName() + "); a name already in use is refused rather "
                    + "than replaced (#304) -- rename the new behavior, or address the collision as a "
                    + "deliberate decision rather than by registration order.");
        }
        resolvedDescriptors.put(name, resolved);
        catalogSources.put(name, source);
        return this;
    }

    /**
     * The descriptor this registry publishes for {@code factory}, with its runtime nature resolved
     * from what the behavior's code already declares (ADR 0024 §2).
     *
     * <h2>Derivation is not a rewrite</h2>
     * <p>{@code implements InboundSourceCapable} <em>is</em> the plugin's declaration that this node
     * opens a deployment-scoped inbound resource — it is the same fact {@link #sourceCapableBehavior}
     * reads to decide whether to create a source at all. Computing {@code SOURCE} from it overrides
     * nothing, because a descriptor written without a nature field has nothing to override; there is
     * nothing there to lose. This is the catalog deciding, from a declared code fact, and never from
     * topology — which is the distinction ADR 0024 draws when it rejects inference.</p>
     *
     * <p>The alternative was to require the four extension modules to declare {@code SOURCE}
     * explicitly. That spends cross-boundary changes to restate information the interface already
     * carries, and it fails open in the meantime: until every package is updated, a source node's
     * effective nature would read {@code WORKER}, which is precisely the wrong answer for the one
     * nature that has a working implementation today.</p>
     *
     * <h2>An explicit declaration wins, and a contradiction fails loudly</h2>
     * <p>When a descriptor does declare natures, they stand. But a descriptor whose permitted set
     * excludes {@code SOURCE} while its behavior implements {@code InboundSourceCapable} is refused at
     * registration: the two halves of that package disagree about what the node is, and the
     * disagreement would otherwise be settled at deployment by whichever half was consulted — the
     * interface, silently creating and starting a source for a node the catalog calls a worker. That
     * check can fail, and its test proves it does.</p>
     */
    private static NodeTypeDescriptor resolveNature(NodeBehaviorFactory factory) {
        NodeTypeDescriptor descriptor = factory.descriptor();
        boolean sourceCapable = factory instanceof NodePackages.SdkNodeBehaviorFactory sdk
                && sdk.behavior() instanceof InboundSourceCapable;
        if (!sourceCapable) {
            return descriptor;
        }
        if (!descriptor.declaresNature()) {
            return descriptor.withNature(NodeRuntimeNature.SOURCE, java.util.Set.of(NodeRuntimeNature.SOURCE));
        }
        if (!descriptor.effectiveAllowedNatures().contains(NodeRuntimeNature.SOURCE)) {
            throw new IllegalArgumentException("Behavior '" + descriptor.behavior() + "' implements "
                    + "InboundSourceCapable but its descriptor permits only "
                    + descriptor.allowedNatureIdentifiers() + ". The code declares an inbound source "
                    + "and the catalog entry forbids one; a deployment would create and start that "
                    + "source anyway, because source discovery reads the interface. Permit SOURCE, or "
                    + "stop implementing InboundSourceCapable.");
        }
        return descriptor;
    }

    /**
     * Looks up and materializes a configuration-independent handler registered under {@code name}.
     *
     * <p>This backward-compatible convenience method creates the handler with a synthetic
     * property-less behavior node. A registered factory that requires node properties may therefore
     * throw while the handler is being materialized. The {@link Optional} describes only whether a
     * behavior name is registered; it does not turn a factory's configuration failure into an empty
     * result.</p>
     *
     * <p>For a property-dependent behavior, use {@link #create(GraphNode)} with a graph node carrying
     * the configuration required by the behavior's descriptor.</p>
     *
     * @param name the registered behavior name to look up
     * @return the materialized handler, or empty when no behavior is registered under {@code name}
     * @throws IllegalArgumentException when the registered factory cannot materialize a handler
     *                                  without node properties
     */
    public Optional<NodeHandler> find(String name) {
        var factory = factories.get(name);
        return factory == null ? Optional.empty()
                : Optional.of(factory.create(GraphNode.behavior("registry-lookup", name)));
    }

    public Optional<NodeHandler> create(GraphNode node) {
        if (node == null || node.behavior() == null) return Optional.empty();
        var factory = factories.get(node.behavior());
        return factory == null ? Optional.empty() : Optional.of(factory.create(node));
    }

    /** Materializes a node under the exact manifest-restored external-I/O snapshot, when required. */
    Optional<NodeHandler> create(GraphNode node,
            java.util.Optional<ai.ravenroot.api.node.service.NodeExternalIoCapacity> externalIo) {
        if (node == null || node.behavior() == null) return Optional.empty();
        var factory = factories.get(node.behavior());
        if (factory == null) return Optional.empty();
        if (factory instanceof NodePackages.SdkNodeBehaviorFactory sdk) {
            return Optional.of(sdk.create(node, externalIo));
        }
        if (externalIo.isPresent()) {
            throw new IllegalStateException("non-package behavior received external-I/O capacity");
        }
        return Optional.of(factory.create(node));
    }

    /** Canonical constrained identity for one node/package/behavior capacity binding. */
    public String externalIoBindingDigest(GraphNode node) {
        java.util.Objects.requireNonNull(node, "node");
        NodeCatalogSource source = catalogSources.get(node.behavior());
        if (source == null || source.bundleId() == null) {
            throw new IllegalArgumentException("node has no package binding");
        }
        return ai.ravenroot.api.persistence.ExecutionManifestDigest.component(
                "ravenroot.node-external-io-binding.v1",
                java.util.List.of(node.id(), source.bundleId(), node.behavior()));
    }

    /** Resolves the closed capacity snapshots contributed by pin-capable nodes. */
    public java.util.List<ai.ravenroot.api.persistence.ResolvedOperationalPolicy.NodeIoCapacity>
            nodeExternalIoCapacitiesFor(java.util.Collection<GraphNode> nodes) {
        java.util.Objects.requireNonNull(nodes, "nodes");
        var resolved = new java.util.ArrayList<
                ai.ravenroot.api.persistence.ResolvedOperationalPolicy.NodeIoCapacity>();
        var seenNodes = new java.util.HashSet<String>();
        for (GraphNode node : nodes) {
            java.util.Objects.requireNonNull(node, "node");
            if (!seenNodes.add(node.id())) throw new IllegalArgumentException("duplicate graph node id");
            if (!requiresExternalIoCapacity(node)) {
                continue;
            }
            var factory = factories.get(node.behavior());
            if (factory instanceof NodePackages.SdkNodeBehaviorFactory sdk) {
                sdk.resolveExecutionIoCapacity(node).ifPresent(capacity -> resolved.add(
                        new ai.ravenroot.api.persistence.ResolvedOperationalPolicy.NodeIoCapacity(
                                externalIoBindingDigest(node), capacity)));
            }
        }
        return java.util.List.copyOf(resolved);
    }

    public boolean requiresExternalIoCapacity(GraphNode node) {
        if (node == null || node.behavior() == null
                || ai.ravenroot.api.catalog.NodeBypassProperty.isBypassed(node.properties())) return false;
        var factory = factories.get(node.behavior());
        return factory instanceof NodePackages.SdkNodeBehaviorFactory sdk
                && sdk.behavior() instanceof ai.ravenroot.api.node.ExecutionIoCapacityCapable;
    }

    /**
     * Creates a construction-time deny-only source binding. The deployment activates it immediately
     * before {@code source.start}; closing it revokes every operation and handed-off session.
     */
    public SourceRegistration registerSourceAuthority(
            ai.ravenroot.api.deployment.InboundSourceContext context, String packageId,
            ai.ravenroot.api.deployment.DeploymentId deploymentId, String nodeId, long activationGeneration,
            ai.ravenroot.api.security.SecurityContext identity) {
        java.util.Objects.requireNonNull(context, "context");
        java.util.Objects.requireNonNull(deploymentId, "deploymentId");
        java.util.Objects.requireNonNull(identity, "identity");
        RegisteredNodePackageBinding packageBinding = nodePackageBindings.get(packageId);
        if (packageBinding == null) {
            throw new IllegalArgumentException("source package is not registered");
        }
        if (packageBinding.capacity().flatMap(NodePackageEgressCapacityProfile::limits).isEmpty()) {
            return new SourceRegistration() {
                @Override public void activate() { }
                @Override public void close() { }
            };
        }
        var limits = packageBinding.capacity().orElseThrow().limits().orElseThrow();
        SourceBinding binding = new SourceBinding(packageId, deploymentId, nodeId,
                activationGeneration, identity, limits);
        synchronized (sourceBindings) {
            if (sourceBindings.putIfAbsent(context, binding) != null) {
                throw new IllegalStateException("source context is already registered");
            }
        }
        return new SourceRegistration() {
            private final java.util.concurrent.atomic.AtomicBoolean closed =
                    new java.util.concurrent.atomic.AtomicBoolean();
            @Override public void activate() { binding.activate(); }
            @Override public void close() {
                if (!closed.compareAndSet(false, true)) return;
                synchronized (sourceBindings) { sourceBindings.remove(context, binding); }
                binding.revoke();
            }
        };
    }

    /** Resolves only exact runtime-created context objects under their registered package. */
    public ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices.SourceAuthority
            sourceAuthorityFor(String packageId,
            ai.ravenroot.api.deployment.InboundSourceContext context) {
        SourceBinding binding;
        synchronized (sourceBindings) { binding = sourceBindings.get(context); }
        if (binding == null || !binding.packageId.equals(packageId)) {
            throw new ai.ravenroot.api.node.service.NodePackageServiceException(
                    ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        }
        binding.requireActive();
        return binding;
    }

    public interface SourceRegistration extends AutoCloseable {
        void activate();
        @Override void close();
    }

    private static final class SourceBinding implements
            ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices.SourceAuthority {
        private enum State { PROVISIONAL, ACTIVE, REVOKED }
        private final String packageId;
        @SuppressWarnings("unused") private final ai.ravenroot.api.deployment.DeploymentId deploymentId;
        @SuppressWarnings("unused") private final String nodeId;
        @SuppressWarnings("unused") private final long activationGeneration;
        private final ai.ravenroot.api.security.SecurityContext identity;
        private final NodePackageEgressCapacityProfile.Limits capacity;
        private final java.util.IdentityHashMap<SourceOperation, Runnable> operations =
                new java.util.IdentityHashMap<>();
        private State state = State.PROVISIONAL;

        SourceBinding(String packageId, ai.ravenroot.api.deployment.DeploymentId deploymentId,
                      String nodeId, long activationGeneration,
                      ai.ravenroot.api.security.SecurityContext identity,
                      NodePackageEgressCapacityProfile.Limits capacity) {
            this.packageId = java.util.Objects.requireNonNull(packageId, "packageId");
            this.deploymentId = java.util.Objects.requireNonNull(deploymentId, "deploymentId");
            this.nodeId = java.util.Objects.requireNonNull(nodeId, "nodeId");
            this.activationGeneration = activationGeneration;
            this.identity = java.util.Objects.requireNonNull(identity, "identity");
            this.capacity = java.util.Objects.requireNonNull(capacity, "capacity");
        }

        synchronized void activate() {
            if (state != State.PROVISIONAL) throw new IllegalStateException("source binding cannot activate");
            state = State.ACTIVE;
        }

        void revoke() {
            java.util.List<Runnable> cancellations;
            synchronized (this) {
                if (state == State.REVOKED) return;
                state = State.REVOKED;
                cancellations = java.util.List.copyOf(operations.values());
                operations.clear();
            }
            cancellations.forEach(cancel -> {
                try { cancel.run(); } catch (RuntimeException ignored) { }
            });
        }

        @Override public ai.ravenroot.api.security.SecurityContext identity() {
            requireActive();
            return identity;
        }

        @Override public NodePackageEgressCapacityProfile.Limits capacity() {
            requireActive();
            return capacity;
        }

        @Override public synchronized void requireActive() {
            if (state != State.ACTIVE) throw new ai.ravenroot.api.node.service.NodePackageServiceException(
                    ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        }

        @Override public synchronized
                ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices.SourceOperation
                track(Runnable cancel) {
            requireActive();
            var operation = new SourceOperation(this, java.util.Objects.requireNonNull(cancel, "cancel"));
            operations.put(operation, cancel);
            return operation;
        }

        private static final class SourceOperation implements
                ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices.SourceOperation {
            private final SourceBinding owner;
            private boolean closed;
            SourceOperation(SourceBinding owner, Runnable cancel) { this.owner = owner; }

            @Override public SourceOperation transfer(Runnable cancel) {
                Runnable safe = java.util.Objects.requireNonNull(cancel, "cancel");
                synchronized (owner) {
                    if (closed || owner.state != State.ACTIVE) {
                        safe.run();
                        throw new ai.ravenroot.api.node.service.NodePackageServiceException(
                                ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
                    }
                    owner.operations.put(this, safe);
                    return this;
                }
            }

            @Override public void close() {
                synchronized (owner) {
                    if (closed) return;
                    closed = true;
                    owner.operations.remove(this);
                }
            }
        }
    }

    /** Runs the registered factory's side-effect-free admission check for one configured node. */
    public void validate(GraphNode node) {
        if (node == null || node.behavior() == null) return;
        var factory = factories.get(node.behavior());
        if (factory != null) factory.validate(node);
    }

    /** Resolves durable re-entry only through the already registered trusted behavior factory. */
    public Optional<ai.ravenroot.api.node.ToolCallContinuationAction> createToolCallContinuation(GraphNode node) {
        if (node == null || node.behavior() == null) return Optional.empty();
        var factory = factories.get(node.behavior());
        return factory == null ? Optional.empty() : factory.createToolCallContinuation(node);
    }

    /**
     * The trusted catalog entry for {@code behavior}, or empty when the behavior is not registered.
     *
     * <p>This is the authority for SEC-09: the schema of a behavior's properties, and its declared
     * capabilities, come from the registered factory's descriptor and never from graph content. A
     * graph supplies property <em>values</em>; it cannot introduce a property, change its type, or
     * claim a capability.</p>
     */
    public Optional<NodeTypeDescriptor> descriptor(String behavior) {
        // The resolved entry, not the factory's raw one, so every consumer -- schema validation,
        // the nature validator, the catalog API -- sees the same nature the registry decided at load.
        return behavior == null ? Optional.empty() : Optional.ofNullable(resolvedDescriptors.get(behavior));
    }

    /**
     * The {@link NodeBehavior} registered for {@code behaviorName}, if it both came from an SDK
     * {@link ai.ravenroot.api.node.NodePackage} and declares {@link InboundSourceCapable} —
     * empty for a built-in behavior, a handler-only {@link #register}, an unknown name, or an SDK
     * behavior that never opted into source lifecycle.
     *
     * <p>This is a lookup, not activity: it returns the same behavior instance the catalog already
     * holds and calls nothing on it. Whether a source is actually created and started is entirely the
     * caller's decision, made while a specific deployment's graph is being spawned — never here.</p>
     */
    public Optional<NodeBehavior> sourceCapableBehavior(String behaviorName) {
        if (behaviorName == null) {
            return Optional.empty();
        }
        var factory = factories.get(behaviorName);
        if (factory instanceof NodePackages.SdkNodeBehaviorFactory sdk
                && sdk.behavior() instanceof InboundSourceCapable) {
            return Optional.of(sdk.behavior());
        }
        return Optional.empty();
    }

    Optional<NodePackages.SdkNodeBehaviorFactory> sourceCapableFactory(String behaviorName) {
        if (behaviorName == null) {
            return Optional.empty();
        }
        var factory = factories.get(behaviorName);
        if (factory instanceof NodePackages.SdkNodeBehaviorFactory sdk
                && sdk.behavior() instanceof InboundSourceCapable) {
            return Optional.of(sdk);
        }
        return Optional.empty();
    }

    public List<NodeTypeDescriptor> descriptors() {
        return resolvedDescriptors.values().stream()
                .sorted(java.util.Comparator.comparing(NodeTypeDescriptor::category)
                        .thenComparing(NodeTypeDescriptor::displayName))
                .toList();
    }

    public Map<String, NodeCatalogSource> catalogSources() { return Map.copyOf(catalogSources); }

    /**
     * The identity of every node package that contributed a behavior to this registry.
     *
     * <p>Sorted, so a manifest built from this list is stable across registration orders. Built-in
     * behaviors contribute nothing here: they are part of the runtime rather than an installed
     * dependency, and their identity is the build's, which a manifest pins through the format
     * versions it already records.</p>
     *
     * @return immutable, sorted package identities; empty when only built-ins are registered.
     */
    public List<PinnedNodePackage> nodePackageIdentities() {
        return nodePackageIdentities.values().stream().sorted().toList();
    }

    /** Returns the installed binding for one package identity. */
    public java.util.Optional<RegisteredNodePackageBinding> nodePackageBinding(String packageId) {
        return java.util.Optional.ofNullable(nodePackageBindings.get(packageId));
    }

    /**
     * Resolves only packages referenced by the accepted graph. Built-ins and legacy application
     * handlers have no package identity and therefore contribute no entry.
     */
    public List<RegisteredNodePackageBinding> nodePackageBindingsFor(
            java.util.Collection<String> behaviorNames) {
        var packageIds = new java.util.TreeSet<String>();
        for (String behavior : behaviorNames) {
            String packageId = behaviorPackageIds.get(behavior);
            if (packageId != null) packageIds.add(packageId);
        }
        return packageIds.stream().map(nodePackageBindings::get).toList();
    }

    /**
     * Whether trusted registration facts prove that every named behavior lacks managed egress.
     * Core and unknown pass-through behaviors have no package service. An SDK package is safe only
     * when its declared capacity explicitly says it has no managed egress; legacy application
     * handlers remain unknown because they may have captured arbitrary services.
     */
    public boolean provesNoManagedEgress(java.util.Collection<String> behaviorNames) {
        for (String behavior : java.util.Objects.requireNonNull(behaviorNames, "behaviorNames")) {
            String packageId = behaviorPackageIds.get(behavior);
            if (packageId != null) {
                RegisteredNodePackageBinding binding = nodePackageBindings.get(packageId);
                if (binding == null || binding.capacity().isEmpty()
                        || binding.capacity().orElseThrow().limits().isPresent()) return false;
                continue;
            }
            NodeCatalogSource source = catalogSources.get(behavior);
            if (source != null && (source.origin() != NodeCatalogSource.Origin.CORE
                    || !LEGACY_CORE_WITHOUT_EXTERNAL_IO.contains(behavior))) return false;
        }
        return true;
    }

    /** Returns the core HTTP capacity only when the accepted graph uses that behavior. */
    public java.util.Optional<ResolvedOperationalPolicy.BuiltInHttpCapacity> builtInHttpCapacityFor(
            java.util.Collection<String> behaviorNames) {
        java.util.Objects.requireNonNull(behaviorNames, "behaviorNames");
        return behaviorNames.contains("http-request") ? builtInHttpCapacity : java.util.Optional.empty();
    }

    /**
     * Captures startup capacities for the explicit no-manifest embedding path. This value is bound
     * to a traversal exactly like a manifest policy, so a missing or released binding still refuses.
     */
    ResolvedOperationalPolicy unpinnedOperationalPolicy(GraphExecutionLimits limits) {
        java.util.Objects.requireNonNull(limits, "limits");
        var packages = nodePackageBindings.values().stream()
                .filter(binding -> binding.capacity().isPresent())
                .map(binding -> new ResolvedOperationalPolicy.PackageCapacity(
                        binding.identity().packageId(), binding.capacity().orElseThrow()))
                .toList();
        ai.ravenroot.core.graph.GraphMlLimits graphMl = limits.graphMl();
        ai.ravenroot.api.payload.PayloadLimits payload = limits.payload();
        var graph = new ResolvedOperationalPolicy.GraphLimits(
                graphMl.maxBytes(), graphMl.maxNodes(), graphMl.maxEdges(), graphMl.maxProperties(),
                graphMl.maxDepth(), graphMl.maxStringLength(), graphMl.maxKeys(), graphMl.maxElements(),
                graphMl.maxAttributes(), graphMl.maxNamespaceDeclarations(), payload.maxEncodedBytes(),
                payload.maxDepth(), payload.maxCollectionSize(), payload.maxValueCount(),
                payload.maxTextLength(), payload.maxKeyLength(), limits.maxFanOut(),
                limits.maxResidentActors(), limits.maxLiveActorsPerTraversal(),
                limits.maxInFlightHopsPerTraversal(), limits.maxQueuedAdmissionsPerNode(),
                limits.maxTraversalSteps(), limits.maxAmplifiedDeliveries(),
                limits.maxCumulativePayloadBytes(), limits.maxRecoveryDeliveriesPerAttempt());
        return new ResolvedOperationalPolicy(graph,
                new ResolvedOperationalPolicy.ResultLimits(false, payload.maxEncodedBytes()),
                builtInHttpCapacity, packages);
    }

    /** Compatibility binding for policy consumers that do not materialize node-scoped handlers. */
    void bindOperationalPolicy(ExecutionKey key, java.util.UUID traversalId,
                               ResolvedOperationalPolicy policy) {
        bindOperationalPolicy(key, traversalId, policy, java.util.List.of(), false);
    }

    /** Binds core-resolved policy to one live traversal before its first node dispatch. */
    void bindOperationalPolicy(ExecutionKey key, java.util.UUID traversalId,
                               ResolvedOperationalPolicy policy,
                               java.util.Collection<GraphNode> graphNodes,
                               boolean materializeExecutionIo) {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(traversalId, "traversalId");
        java.util.Objects.requireNonNull(graphNodes, "graphNodes");
        Map<String, NodeHandler> executionIoHandlers = materializeExecutionIo
                ? executionIoHandlers(policy, graphNodes) : Map.of();
        ActiveOperationalPolicy candidate = new ActiveOperationalPolicy(key, policy, executionIoHandlers);
        ActiveOperationalPolicy existing = activeOperationalPolicies.putIfAbsent(traversalId, candidate);
        if (existing != null && (!existing.key().equals(key)
                || !java.util.Objects.equals(existing.policy(), policy))) {
            throw new IllegalStateException("traversal operational policy is already bound");
        }
    }

    private Map<String, NodeHandler> executionIoHandlers(ResolvedOperationalPolicy policy,
                                                          java.util.Collection<GraphNode> graphNodes) {
        if (policy == null) {
            throw new ai.ravenroot.api.node.service.NodePackageServiceException(
                    ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        }
        var capacities = new java.util.LinkedHashMap<String,
                ai.ravenroot.api.node.service.NodeExternalIoCapacity>();
        for (ResolvedOperationalPolicy.NodeIoCapacity entry : policy.nodeExternalIo()) {
            if (capacities.putIfAbsent(entry.bindingDigest(), entry.capacity()) != null) {
                throw new IllegalArgumentException("duplicate node external-I/O capacity binding");
            }
        }
        var handlers = new java.util.LinkedHashMap<String, NodeHandler>();
        var expected = new java.util.HashSet<String>();
        for (GraphNode node : graphNodes) {
            if (!requiresExternalIoCapacity(node)) continue;
            String binding = externalIoBindingDigest(node);
            if (!expected.add(binding)) {
                throw new IllegalArgumentException("duplicate pin-capable node binding");
            }
            var capacity = java.util.Optional.ofNullable(capacities.get(binding));
            if (capacity.isEmpty()) {
                throw new IllegalArgumentException("external-I/O capacities do not match pin-capable graph nodes");
            }
            NodeHandler handler = create(node, capacity).orElseThrow(() ->
                    new IllegalArgumentException("pin-capable graph behavior is not registered"));
            handlers.put(binding, handler);
        }
        if (!capacities.keySet().equals(expected)) {
            throw new IllegalArgumentException("external-I/O capacities do not match pin-capable graph nodes");
        }
        return Map.copyOf(handlers);
    }

    /** Releases the active traversal binding after all node instances have stopped. */
    void releaseOperationalPolicy(java.util.UUID traversalId) {
        activeOperationalPolicies.remove(java.util.Objects.requireNonNull(traversalId, "traversalId"));
    }

    int activeOperationalPolicyCount() {
        return activeOperationalPolicies.size();
    }

    /**
     * Resolves policy from core-retained execution state. The message supplies identity only and is
     * never itself an authority for capacity values.
     */
    ResolvedOperationalPolicy operationalPolicyFor(ai.ravenroot.api.execution.NodeMessage message) {
        java.util.Objects.requireNonNull(message, "message");
        ActiveOperationalPolicy active = activeOperationalPolicies.get(message.traversalId());
        ExecutionKey delivered = new ExecutionKey(message.tenantId(), message.processInstanceId());
        if (active == null || !active.key().equals(delivered)) {
            throw new ai.ravenroot.api.node.service.NodePackageServiceException(
                    ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        }
        if (active.policy() == null) {
            throw new ai.ravenroot.api.node.service.NodePackageServiceException(
                    ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        }
        return active.policy();
    }

    /** Selects the once-per-traversal handler created from this execution's exact node pin. */
    NodeHandler executionIoHandler(GraphNode node, ai.ravenroot.api.execution.NodeMessage message) {
        ResolvedOperationalPolicy ignored = operationalPolicyFor(message);
        ActiveOperationalPolicy active = activeOperationalPolicies.get(message.traversalId());
        if (!message.nodeId().equals(node.id())) {
            throw new ai.ravenroot.api.node.service.NodePackageServiceException(
                    ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        }
        NodeHandler handler = active.executionIoHandlers().get(externalIoBindingDigest(node));
        if (handler == null) {
            throw new ai.ravenroot.api.node.service.NodePackageServiceException(
                    ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        }
        return handler;
    }

    private record ActiveOperationalPolicy(ExecutionKey key, ResolvedOperationalPolicy policy,
                                           Map<String, NodeHandler> executionIoHandlers) { }

    /** Immutable package identity paired with its quantitative service declaration. */
    public record RegisteredNodePackageBinding(PinnedNodePackage identity,
                                               java.util.Optional<NodePackageEgressCapacityProfile> capacity) {
        public RegisteredNodePackageBinding {
            java.util.Objects.requireNonNull(identity, "identity");
            java.util.Objects.requireNonNull(capacity, "capacity");
        }
    }

    private record LegacyNodeBehaviorFactory(String name, NodeHandler handler) implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(name, name, "Custom", "Application-provided behavior",
                    "actor", false, List.of(), java.util.Set.of("embedded"));
        }

        @Override
        public NodeHandler create(GraphNode node) {
            return handler;
        }
    }
}
