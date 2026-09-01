package ai.ravenroot.testkit.api;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance tests every Ravenroot node package must pass, built-in or third-party (CORE-06).
 *
 * <p>The repository already has this shape three times — {@code ExecutionEngineContract},
 * {@code ExecutionStoreContract} and {@code PayloadTransportContract} — and it is used here for the
 * reason {@code PayloadTransportContract} states about itself: a contract that exists only as the
 * built-in catalog's own tests is a description of what the built-ins happen to do, while a contract
 * a package must pass is a contract. A third-party author extends this class, returns their package
 * from one method, and inherits every assertion.</p>
 *
 * <p>What is asserted here is the part that can be checked without a running engine: the package's
 * declared contract, the shape and stability of its catalog entries, and the handler protocol. It
 * deliberately does not execute a graph — that needs the runtime, and a node author should be able to
 * run this from a build that depends only on the SDK.</p>
 *
 * <p>Passing is necessary, not sufficient. It says the package is well-formed and can be trusted to
 * register; it says nothing about whether a behavior does what it claims.</p>
 */
public abstract class NodeBehaviorContract {

    /** The package under test. Called repeatedly; return the same logical package each time. */
    protected abstract NodePackage nodePackage();

    /**
     * A configuration to hand a behavior when building its action.
     *
     * <p>The default supplies every declared property with a placeholder, which is enough for
     * behaviors whose configuration is read but not deeply interpreted. Override when a behavior
     * needs values with real structure — a URI, a positive number — and the default would make the
     * handler-protocol assertions vacuous by failing before they run.</p>
     */
    protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        var properties = new LinkedHashMap<String, Object>();
        for (NodePropertyDescriptor property : descriptor.properties()) {
            properties.put(property.name(), placeholderFor(property));
        }
        return new NodeConfiguration("conformance-node", descriptor.behavior(), properties);
    }

    /** The placeholder used for one declared property. Override for types the default cannot satisfy. */
    protected String placeholderFor(NodePropertyDescriptor property) {
        if (!property.allowedValues().isEmpty()) {
            return property.allowedValues().getFirst();
        }
        return switch (property.type()) {
            case BOOLEAN -> "false";
            case INTEGER -> "1";
            case DECIMAL -> "1.0";
            case URI -> "https://example.invalid/conformance";
            case SECRET_REFERENCE -> "conformance-secret-ref";
            case STRING, TEXT, CEL_EXPRESSION -> "conformance";
        };
    }

    @Test
    void thePackageDeclaresAnSdkContractThisRuntimeHosts() {
        NodePackage under = requirePackage();
        assertNotNull(under.sdkContract(), "a node package must declare the SDK contract it was built against");
        assertTrue(NodeSdk.supports(under.sdkContract()),
                () -> "package '" + under.id() + "' declares SDK contract '" + under.sdkContract()
                        + "', which this SDK does not host (this SDK is '" + NodeSdk.CONTRACT + "'). Return "
                        + "NodeSdk.CONTRACT from sdkContract() and recompile against the target runtime.");
    }

    @Test
    void thePackageIdentifiesItself() {
        NodePackage under = requirePackage();
        assertNotNull(under.id(), "a node package must have an id");
        assertFalse(under.id().isBlank(), "a node package id cannot be blank: it is what names this package "
                + "in a startup failure an operator has to act on");
        assertNotNull(under.version(), "a node package must report its own version");
        assertFalse(under.version().isBlank(), "a node package version cannot be blank");
    }

    @Test
    void thePackageContributesAtLeastOneBehaviorAndNamesEachOnce() {
        List<NodeBehavior> behaviors = requireBehaviors();
        assertFalse(behaviors.isEmpty(), "a node package must contribute at least one behavior");

        var seen = new HashSet<String>();
        for (NodeBehavior behavior : behaviors) {
            NodeTypeDescriptor descriptor = requireDescriptor(behavior);
            assertTrue(seen.add(descriptor.behavior()),
                    () -> "behavior name '" + descriptor.behavior() + "' is declared more than once in package '"
                            + requirePackage().id() + "'. Names are a single flat namespace.");
        }
    }

    /**
     * The descriptor is read at registration, at graph validation and when the catalog is served to an
     * editor. One that varies between those points describes a node type that does not exist, and the
     * disagreement surfaces as a graph that validates and then behaves as though it had not.
     */
    @Test
    void everyDescriptorIsStableAcrossCalls() {
        for (NodeBehavior behavior : requireBehaviors()) {
            NodeTypeDescriptor first = requireDescriptor(behavior);
            NodeTypeDescriptor second = requireDescriptor(behavior);
            assertEquals(first, second, () -> "behavior '" + first.behavior()
                    + "' returns a different descriptor on each call. Build it from constants.");
        }
    }

    /**
     * A descriptor is the SEC-09 trust anchor: it is what makes "a graph supplies values, not schema"
     * true. A behavior that reads a property it never declared has opted that property out of type
     * checking, required-ness and allowed-value checking, and receives whatever the graph wrote.
     */
    @Test
    void noDescriptorDeclaresAPropertyGraphContentCanNeverSet() {
        for (NodeBehavior behavior : requireBehaviors()) {
            NodeTypeDescriptor descriptor = requireDescriptor(behavior);
            var propertyNames = new HashSet<String>();
            for (NodePropertyDescriptor property : descriptor.properties()) {
                assertNotNull(property.name(), "a declared property must have a name");
                assertFalse(property.name().isBlank(), "a declared property name cannot be blank");
                assertFalse(NodeSdk.isReservedProperty(property.name()),
                        () -> "behavior '" + descriptor.behavior() + "' declares property '" + property.name()
                                + "' under the reserved '" + NodeSdk.RESERVED_PROPERTY_PREFIX + "' prefix. Graph "
                                + "content may never set it, so the property can never be satisfied.");
                assertTrue(propertyNames.add(property.name()),
                        () -> "behavior '" + descriptor.behavior() + "' declares property '" + property.name()
                                + "' more than once");
            }
        }
    }

    @Test
    void everyBehaviorBuildsAnActionForAConformingNode() {
        for (NodeBehavior behavior : requireBehaviors()) {
            NodeTypeDescriptor descriptor = requireDescriptor(behavior);
            NodeAction action = behavior.create(configurationFor(descriptor));
            assertNotNull(action, () -> "behavior '" + descriptor.behavior()
                    + "' returned no action for a node satisfying its own declared schema");
        }
    }

    /**
     * The runtime attaches a traversal's terminal bookkeeping to the returned stage, so a node that
     * throws synchronously — or returns {@code null} — can escape that accounting entirely and leave
     * the traversal with nothing recorded. Whether it does depends on the installed engine adapter,
     * which is exactly the kind of variation a conformance contract exists to remove.
     */
    @Test
    void everyActionReportsThroughTheStageRatherThanByThrowing() {
        for (NodeBehavior behavior : requireBehaviors()) {
            NodeTypeDescriptor descriptor = requireDescriptor(behavior);
            NodeAction action = behavior.create(configurationFor(descriptor));
            CompletionStage<NodeResult> stage;
            try {
                stage = action.handle(conformanceMessage(descriptor));
            } catch (RuntimeException thrown) {
                throw new AssertionError("behavior '" + descriptor.behavior() + "' threw synchronously from "
                        + "handle(...) instead of returning an exceptionally completed stage. The traversal's "
                        + "failure event and join release are attached to the returned stage.", thrown);
            }
            assertNotNull(stage, () -> "behavior '" + descriptor.behavior() + "' returned a null stage");
        }
    }

    /**
     * The reserved namespace is the runtime's own operative state — identity, provenance and the rest.
     * Anything a node returns under it is discarded unread, so returning one is not an attack but it
     * is a bug: the author believes they are setting something and nothing downstream will ever see it.
     */
    @Test
    void noActionAttemptsToReturnRuntimeOwnedAttributes() {
        for (NodeBehavior behavior : requireBehaviors()) {
            NodeTypeDescriptor descriptor = requireDescriptor(behavior);
            NodeAction action = behavior.create(configurationFor(descriptor));
            NodeResult result;
            try {
                result = action.handle(conformanceMessage(descriptor)).toCompletableFuture().join();
            } catch (RuntimeException failed) {
                // A behavior that legitimately refuses a placeholder payload is not a conformance
                // failure; this assertion only has something to say about results that exist.
                continue;
            }
            if (result == null) {
                continue;
            }
            for (String key : result.attributes().keySet()) {
                assertFalse(NodeSdk.isReservedProperty(key),
                        () -> "behavior '" + descriptor.behavior() + "' returned attribute '" + key
                                + "' under the reserved '" + NodeSdk.RESERVED_PROPERTY_PREFIX + "' prefix. The "
                                + "runtime discards it unread, so nothing downstream will observe it.");
            }
        }
    }

    /**
     * One action, invoked concurrently, must survive it (ADR 0024 §3).
     *
     * <p>This became a contract when demand-driven worker instances landed. Before them each logical
     * node was backed by one actor, and an actor handles one message at a time, so every action ever
     * written was serialised by the runtime's representation of nodes rather than by anything it
     * promised. ADR 0024 removes that representation on purpose — serialising naturally parallel work
     * behind one mailbox is the bottleneck it exists to remove — so several traversals now run through
     * the same action object at the same time.
     *
     * <p>Without this case the amended {@code NodeAction} javadoc would be a release note. A
     * third-party package is written against the SDK and tested against this class, so this is the
     * only place an author finds out before production that their handler keeps a counter, a
     * collection or a lazily assigned field in a field.
     *
     * <p><b>What a failure here means, and what it does not.</b> It reports a behavior whose action
     * threw, returned null, or deadlocked when several threads called it at once. It does not prove
     * an action IS thread-safe: a race that needs a particular interleaving may not appear in eight
     * concurrent calls. Absence of a failure is weak evidence; a failure is strong evidence. A
     * behavior that legitimately refuses the placeholder payload is skipped, exactly as the other
     * handler-protocol cases here skip it, because a refusal is not a race.
     */
    @Test
    void everyActionToleratesConcurrentInvocation() throws Exception {
        int concurrency = 8;
        for (NodeBehavior behavior : requireBehaviors()) {
            NodeTypeDescriptor descriptor = requireDescriptor(behavior);
            NodeAction action = behavior.create(configurationFor(descriptor));
            if (!answersASingleCall(action, descriptor)) {
                continue;
            }
            var pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
            try {
                var start = new java.util.concurrent.CountDownLatch(1);
                var calls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
                for (int index = 0; index < concurrency; index++) {
                    calls.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        CompletionStage<NodeResult> stage = action.handle(conformanceMessage(descriptor));
                        assertNotNull(stage, "handle() must never return null");
                        stage.toCompletableFuture().join();
                    }, pool));
                }
                start.countDown();
                try {
                    java.util.concurrent.CompletableFuture
                            .allOf(calls.toArray(java.util.concurrent.CompletableFuture[]::new))
                            .get(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException timedOut) {
                    throw new AssertionError("behavior '" + descriptor.behavior() + "' did not complete "
                            + concurrency + " concurrent invocations within 60s. The runtime invokes "
                            + "one action from several threads at once; an action that "
                            + "serialises on its own lock, or deadlocks, blocks every traversal "
                            + "reaching that node.", timedOut);
                } catch (java.util.concurrent.ExecutionException failed) {
                    throw new AssertionError("behavior '" + descriptor.behavior() + "' failed under "
                            + concurrency + " concurrent invocations, but answered a single one. That "
                            + "is the signature of mutable state shared between invocations: one "
                            + "NodeAction serves many invocations at the same time, so "
                            + "per-invocation state belongs in local variables and shared state must "
                            + "carry its own synchronisation.", failed.getCause());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * Whether this action answers one ordinary call, so that a failure under concurrency means
     * concurrency.
     *
     * <p>Without this filter the case above would report every behavior that declines a placeholder
     * payload — an unconfigured adapter, a required value the default configuration cannot invent — as
     * a concurrency defect, which is both wrong and the kind of noise that gets a conformance test
     * disabled.
     */
    private boolean answersASingleCall(NodeAction action, NodeTypeDescriptor descriptor) {
        try {
            CompletionStage<NodeResult> stage = action.handle(conformanceMessage(descriptor));
            if (stage == null) {
                return false;
            }
            stage.toCompletableFuture().join();
            return true;
        } catch (RuntimeException declined) {
            return false;
        }
    }

    private NodeMessage conformanceMessage(NodeTypeDescriptor descriptor) {
        var security = new SecurityContext("conformance-request", "conformance-tenant", "conformance-subject",
                ai.ravenroot.api.security.PrincipalType.WORKLOAD, "urn:ravenroot:conformance");
        return new NodeMessage(security, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(), "conformance-node", "conformance-payload", Map.of());
    }

    private NodePackage requirePackage() {
        NodePackage under = nodePackage();
        assertNotNull(under, "nodePackage() must not return null");
        return under;
    }

    private List<NodeBehavior> requireBehaviors() {
        List<NodeBehavior> behaviors = requirePackage().behaviors();
        assertNotNull(behaviors, "behaviors() must not return null");
        behaviors.forEach(behavior -> assertNotNull(behavior, "behaviors() must not contain null"));
        return behaviors;
    }

    private NodeTypeDescriptor requireDescriptor(NodeBehavior behavior) {
        NodeTypeDescriptor descriptor = behavior.descriptor();
        assertNotNull(descriptor, () -> behavior.getClass().getName() + " has no descriptor");
        assertNotNull(descriptor.behavior(), "a descriptor must have a behavior name");
        assertFalse(descriptor.behavior().isBlank(), "a descriptor's behavior name cannot be blank");
        return descriptor;
    }
}
