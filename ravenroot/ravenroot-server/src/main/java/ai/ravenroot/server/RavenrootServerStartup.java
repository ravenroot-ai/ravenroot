package ai.ravenroot.server;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.server.ingress.ManagedIngressRegistry;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Package-private two-phase server startup boundary.
 *
 * <p>Enabled-package authority is validated in {@link #prepare} before a listener factory is ever
 * invoked. Only the composition root can cross the second phase: packages receive the attenuated
 * managed-ingress capability through graph source context and never this handle or the server.</p>
 */
final class RavenrootServerStartup {
    private RavenrootServerStartup() {
    }

    static Prepared prepare(List<NodePackage> packages, Map<String, String> environment) {
        Objects.requireNonNull(packages, "packages");
        Objects.requireNonNull(environment, "environment");
        var declarations = new java.util.ArrayList<ai.ravenroot.api.ingress.IngressAuthorityDeclaration>();
        var projections = new LinkedHashMap<String, ai.ravenroot.api.ingress.IngressRequestProjectionPolicy>();
        for (NodePackage nodePackage : List.copyOf(packages)) {
            if (!(nodePackage instanceof IngressAuthorityContributor contributor)) continue;
            var contributed = List.copyOf(contributor.ingressAuthorities());
            if (contributed.stream().anyMatch(declaration ->
                    !nodePackage.id().equals(declaration.packageId()))) {
                throw new IllegalArgumentException(
                        "ingress declaration package id does not match enabled package");
            }
            declarations.addAll(contributed);
            var projection = Objects.requireNonNull(contributor.ingressRequestProjection(),
                    "ingressRequestProjection");
            projection.ifPresent(policy -> {
                if (!nodePackage.id().equals(policy.packageId())) {
                    throw new IllegalArgumentException(
                            "ingress projection package id does not match enabled package");
                }
                if (projections.putIfAbsent(policy.packageId(), policy) != null) {
                    throw new IllegalArgumentException("duplicate ingress projection for package");
                }
            });
        }
        if (declarations.isEmpty() && !projections.isEmpty()) {
            throw new IllegalArgumentException("ingress projection has no authority declaration");
        }
        ManagedIngressRegistry ingress = declarations.isEmpty() ? null
                : ManagedIngressRegistry.prepare(declarations, projections, "main",
                        RavenrootServerMain.replicaCount(environment) == 1);
        return new Prepared(ingress);
    }

    static final class Prepared implements AutoCloseable {
        private final ManagedIngressRegistry ingress;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private Prepared(ManagedIngressRegistry ingress) {
            this.ingress = ingress;
        }

        void installInto(Consumer<ai.ravenroot.api.ingress.ManagedIngress> applicationInstaller) {
            if (ingress != null) {
                Objects.requireNonNull(applicationInstaller, "applicationInstaller").accept(ingress);
            }
        }

        int activeRouteCount() {
            return ingress == null ? 0 : ingress.inventory().size();
        }

        String readinessDetail() {
            if (ingress == null) return "routes=[]";
            return ingress.inventory().stream()
                    .map(route -> route.packageId() + ":" + route.routeId() + ":"
                            + route.graphGeneration() + ":" + route.state())
                    .collect(java.util.stream.Collectors.joining(",", "routes=[", "]"));
        }

        boolean managedIngressEnabled() {
            return ingress != null;
        }

        Handle bind(ListenerFactory factory) {
            Objects.requireNonNull(factory, "factory");
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException("prepared startup is already consumed");
            }
            Listener listener = null;
            try {
                listener = Objects.requireNonNull(factory.create(), "listener");
                if (ingress != null) listener.install(ingress);
                return new Handle(listener);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(listener, failure);
                throw failure;
            }
        }

        @Override public void close() {
            if (consumed.compareAndSet(false, true) && ingress != null) ingress.close();
        }

        private void closeAfterFailure(Listener listener, Throwable failure) {
            try {
                if (listener != null) listener.close();
                else if (ingress != null) ingress.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    static final class Handle implements AutoCloseable {
        private final Listener listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile boolean ready;

        private Handle(Listener listener) {
            this.listener = listener;
        }

        boolean ready() {
            return ready && !closed.get();
        }

        void start() {
            if (closed.get() || !started.compareAndSet(false, true)) {
                throw new IllegalStateException("startup handle cannot start");
            }
            try {
                listener.start();
                ready = true;
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        void gracefulShutdown() {
            if (closed.compareAndSet(false, true)) {
                ready = false;
                listener.gracefulShutdown();
            }
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                ready = false;
                listener.close();
            }
        }
    }

    @FunctionalInterface
    interface ListenerFactory {
        Listener create();
    }

    interface Listener extends AutoCloseable {
        default void install(ManagedIngressRegistry ingress) {
        }

        void start();

        default void gracefulShutdown() {
            close();
        }

        @Override void close();
    }
}
