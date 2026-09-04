package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.NodeTypeDescriptorValidator;
import ai.ravenroot.api.catalog.NodeCatalogSource;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeBehavior;
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
    private final Map<String, NodeBehaviorFactory> factories = new ConcurrentHashMap<>();
    /**
     * The published catalog entry per behavior name, with runtime nature resolved once at
     * registration. Held beside {@code factories} rather than by wrapping the factory,
     * because {@link #sourceCapableBehavior} and {@link NodePackages} both pattern-match on the
     * factory's concrete type and a decorator would silently break that match.
     */
    private final Map<String, NodeTypeDescriptor> resolvedDescriptors = new ConcurrentHashMap<>();
    private final Map<String, NodeCatalogSource> catalogSources = new ConcurrentHashMap<>();

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
        var registry = new BehaviorRegistry();
        StandardBehaviorFactories.all(environment, publicationPolicies, publicationAudit)
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

    BehaviorRegistry registerPackageFactory(NodeBehaviorFactory factory, String packageId) {
        return registerFactory(factory, NodeCatalogSource.bundle(packageId));
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

    /** Backward-compatible lookup for configuration-independent handlers. */
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
