package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.NodeTypeDescriptorValidator;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.ReservedGraphProperties;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Registers a third-party {@link NodePackage} into the trusted catalog (CORE-06).
 *
 * <h2>This is the whole trust boundary, and it is deliberately small</h2>
 * <p>A package becomes trusted because a deployment called this method — from an embedding
 * application's composition root, or from the runtime's own operator-supplied configuration. There
 * is no scanning, no {@code ServiceLoader}, and nothing that consults graph content. A graph names a
 * behavior; if the name is not already registered, nothing is searched for and nothing is loaded.</p>
 *
 * <h2>Why the SDK path goes through a real descriptor</h2>
 * <p>{@link BehaviorRegistry#register(String, NodeHandler)} — the handler-only path an embedding
 * application uses today — synthesises a descriptor with no properties. {@code BehaviorPropertySchema}
 * skips behaviors it cannot find a descriptor for, so a handler registered that way receives whatever
 * the graph wrote, unvalidated. That is tolerable for an embedder wiring a closure into its own
 * process; it is not a foundation for a published SDK, because the descriptor is the SEC-09 trust
 * anchor: it is what makes "a graph supplies values, not schema" true.</p>
 *
 * <p>So every behavior registered here carries its author's real {@link NodeTypeDescriptor} into
 * {@link BehaviorRegistry#registerFactory}, which is the same path the built-in catalog uses, and is
 * therefore covered by the same validation. The handler-only overload is left exactly as it is.</p>
 *
 * <h2>Failing at registration rather than at execution</h2>
 * <p>Every check here runs while the deployment is starting, before any graph is admitted. A
 * malformed package is a deployment defect and is reported as one; discovering it when a traversal
 * reaches one of its nodes would surface it at the point where it is least attributable and most
 * expensive.</p>
 */
public final class NodePackages {

    private NodePackages() {
    }

    /**
     * Registers every behavior in {@code nodePackage} into {@code registry}.
     *
     * <p>All-or-nothing in intent: each package is fully validated before anything is registered, so
     * a package with one bad behavior does not leave half its node types installed.</p>
     *
     * @throws NodeSdk.IncompatibleNodePackageException when the package declares an unsupported SDK contract
     * @throws IllegalArgumentException                 when a behavior is malformed or its name is already taken
     */
    public static BehaviorRegistry register(BehaviorRegistry registry, NodePackage nodePackage) {
        return register(registry, nodePackage, NodePackageServiceRegistry.empty());
    }

    /** Registers one package against an explicit, package-scoped operator service composition. */
    public static BehaviorRegistry register(BehaviorRegistry registry, NodePackage nodePackage,
                                            NodePackageServiceRegistry services) {
        return registerAll(registry, List.of(nodePackage), services);
    }

    /** Registers several packages, in order. */
    public static BehaviorRegistry registerAll(BehaviorRegistry registry, List<NodePackage> packages) {
        return registerAll(registry, packages, NodePackageServiceRegistry.empty());
    }

    /**
     * Preflights every package, descriptor, collision and required grant before the first mutation.
     */
    public static BehaviorRegistry registerAll(BehaviorRegistry registry, List<NodePackage> packages,
                                               NodePackageServiceRegistry services) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(packages, "packages");
        Objects.requireNonNull(services, "services");

        var packageIds = new HashSet<String>();
        var behaviorNames = new HashSet<String>();
        var plans = new ArrayList<RegistrationPlan>();
        for (NodePackage nodePackage : packages) {
            Objects.requireNonNull(nodePackage, "nodePackage");
            // Contract first: an unsupported package must not have its descriptors inspected at all.
            NodeSdk.requireSupported(nodePackage);
            String packageId = requireValidPackageId(nodePackage.id());
            if (!packageIds.add(packageId)) {
                throw new IllegalArgumentException("Node package id '" + packageId
                        + "' is declared more than once");
            }

            List<NodeBehavior> behaviors = nodePackage.behaviors();
            if (behaviors == null || behaviors.isEmpty()) {
                throw new IllegalArgumentException("Node package '" + packageId
                        + "' declares no behaviors. A package that contributes nothing is a deployment "
                        + "mistake rather than a no-op, so it is refused instead of ignored.");
            }

            boolean serviceAware = NodeSdk.CONTRACT.equals(nodePackage.sdkContract());
            NodePackageServices packageServices = serviceAware
                    ? services.servicesFor(packageId) : NodePackageServices.unavailable();
            Set<NodePackageCapability> grantedCapabilities = serviceAware
                    ? services.capabilitiesFor(packageId) : Set.of();
            var validated = new ArrayList<NodeBehavior>(behaviors.size());
            var namesInPackage = new HashSet<String>();
            for (NodeBehavior behavior : behaviors) {
                NodeBehavior safe = validate(nodePackage, behavior, registry, namesInPackage);
                String behaviorName = safe.descriptor().behavior();
                if (!behaviorNames.add(behaviorName)) {
                    throw new IllegalArgumentException("Behavior '" + behaviorName
                            + "' is declared by more than one node package");
                }
                if (serviceAware) {
                    Set<NodePackageCapability> required = Set.copyOf(Objects.requireNonNull(
                            safe.requiredServices(), "requiredServices"));
                    if (!grantedCapabilities.containsAll(required)) {
                        Set<NodePackageCapability> missing = new LinkedHashSet<>(required);
                        missing.removeAll(grantedCapabilities);
                        String names = missing.stream().map(NodePackageCapability::capabilityName)
                                .sorted().collect(java.util.stream.Collectors.joining(","));
                        throw new IllegalArgumentException("Node package '" + packageId
                                + "' is missing required operator services: " + names);
                    }
                }
                validated.add(safe);
            }
            plans.add(new RegistrationPlan(packageId, serviceAware, packageServices, List.copyOf(validated)));
        }

        for (RegistrationPlan plan : plans) {
            plan.behaviors().forEach(behavior -> registry.registerPackageFactory(
                    new SdkNodeBehaviorFactory(behavior, plan.services(), plan.serviceAware()), plan.packageId()));
        }
        return registry;
    }

    static String requireValidPackageId(String candidate) {
        String id = candidate == null ? "" : candidate.strip();
        if (id.isEmpty() || id.length() > 200 || !id.equals(id.toLowerCase(java.util.Locale.ROOT))
                || !id.matches("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")) {
            throw new IllegalArgumentException("Node package id must be a lowercase safe token of 1-200 characters");
        }
        return id;
    }

    private static NodeBehavior validate(NodePackage nodePackage, NodeBehavior behavior,
                                         BehaviorRegistry registry, HashSet<String> namesInPackage) {
        if (behavior == null) {
            throw new IllegalArgumentException("Node package '" + nodePackage.id()
                    + "' contributed a null behavior");
        }
        NodeTypeDescriptor descriptor = behavior.descriptor();
        if (descriptor == null) {
            throw new IllegalArgumentException("Node package '" + nodePackage.id() + "' behavior "
                    + behavior.getClass().getName() + " has no descriptor. The descriptor is what makes a "
                    + "node type catalogued and schema-validated; a behavior without one cannot be trusted.");
        }
        String name = descriptor.behavior();
        NodeTypeDescriptorValidator.validate(descriptor);
        if (behavior instanceof InboundSourceCapable && descriptor.declaresNature()
                && !descriptor.effectiveAllowedNatures().contains(NodeRuntimeNature.SOURCE)) {
            throw new IllegalArgumentException("Behavior '" + name + "' implements InboundSourceCapable but "
                    + "its descriptor does not permit SOURCE");
        }
        if (!namesInPackage.add(name)) {
            throw new IllegalArgumentException("Node package '" + nodePackage.id() + "' declares behavior '"
                    + name + "' more than once");
        }
        // A name already in the catalog is refused rather than replaced. Silent replacement would
        // make a node type's meaning depend on registration order, and a graph that ran correctly
        // yesterday would change behaviour because an unrelated dependency was added.
        if (registry.descriptor(name).isPresent()) {
            throw new IllegalArgumentException("Node package '" + nodePackage.id() + "' declares behavior '"
                    + name + "', which is already registered. Behavior names are a single flat namespace and "
                    + "a package may not replace an existing entry; rename the behavior in the package.");
        }
        for (NodePropertyDescriptor property : descriptor.properties()) {
            // The reserved namespace is refused from graph content at ingest. A descriptor declaring
            // a property there would describe a property no graph can ever set, so the node would be
            // permanently unsatisfiable — and if it were required, every graph using it would be
            // rejected with a message pointing at the graph rather than at the package.
            if (ReservedGraphProperties.isReserved(property.name())) {
                throw new IllegalArgumentException("Node package '" + nodePackage.id() + "' behavior '"
                        + name + "' declares property '" + property.name() + "' in the reserved '"
                        + ReservedGraphProperties.PREFIX + "' namespace, which graph content may never set.");
            }
        }
        return behavior;
    }

    /**
     * Adapts an SDK behavior to the runtime's internal factory contract.
     *
     * <p>This adapter is the reason the SDK can be versioned independently of the runtime: node
     * authors bind to {@link NodeBehavior} and {@link NodeConfiguration}, which are stable and
     * dependency-light, while the runtime side of this class is free to change with the engine.</p>
     *
     * <p>Package-private rather than {@code private}: {@link BehaviorRegistry} needs to tell
     * an SDK-backed factory from a built-in or handler-only one, to answer "does this behavior name a
     * source" without letting anything outside this package reach the wrapped {@link NodeBehavior}
     * except through {@link #create}, which is the same construction path {@link NodeBehaviorFactory}
     * already exposes.</p>
     */
    record SdkNodeBehaviorFactory(NodeBehavior behavior, NodePackageServices services,
                                  boolean serviceAware) implements NodeBehaviorFactory {

        SdkNodeBehaviorFactory(NodeBehavior behavior) {
            this(behavior, NodePackageServices.unavailable(), false);
        }

        @Override
        public NodeTypeDescriptor descriptor() {
            return behavior.descriptor();
        }

        @Override
        public NodeHandler create(GraphNode node) {
            NodeConfiguration configuration = new NodeConfiguration(
                    node.id(), node.behavior(), node.properties());
            NodeAction action = serviceAware
                    ? behavior.create(configuration, services)
                    : behavior.create(configuration);
            if (action == null) {
                throw new IllegalStateException("Behavior '" + node.behavior() + "' returned no action for node '"
                        + node.id() + "'");
            }
            return new NodeHandler() {
                @Override
                public CompletionStage<NodeResult> handle(NodeMessage message) {
                    return checked(action.handle(message));
                }

                @Override
                public CompletionStage<NodeResult> handle(NodeMessage message, CancellationSignal cancellation) {
                    return checked(action.handle(message, cancellation));
                }

                private CompletionStage<NodeResult> checked(CompletionStage<NodeResult> stage) {
                    if (stage == null) {
                        // A null stage would surface as a NullPointerException inside the runner's
                        // dispatch, attributed to the engine rather than to the node that produced it.
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Behavior '" + node.behavior() + "' returned no result stage for node '"
                                        + node.id() + "'"));
                    }
                    return stage;
                }
            };
        }

        InboundSource createSource(GraphNode node, InboundSourceContext context) {
            if (!(behavior instanceof InboundSourceCapable capable)) {
                throw new IllegalStateException("Behavior '" + node.behavior()
                        + "' does not declare inbound source lifecycle");
            }
            NodeConfiguration configuration = new NodeConfiguration(
                    node.id(), node.behavior(), node.properties());
            return serviceAware
                    ? capable.createSource(configuration, context, services)
                    : capable.createSource(configuration, context);
        }
    }

    private record RegistrationPlan(String packageId, boolean serviceAware,
                                    NodePackageServices services, List<NodeBehavior> behaviors) {
    }
}
