package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressPrincipal;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Proves that packages compiled against the original ingress surface link against the current surface. */
class ManagedIngressBinaryCompatibilityTest {

    @Test void historicalIngressRequestConstructorStillLinks() throws Exception {
        var shape = new BinaryCompatibility.ConstructorShape("original ingress request",
                List.of(IngressPrincipal.class, String.class, String.class, Map.class, byte[].class),
                List.of("new ai.ravenroot.api.ingress.IngressPrincipal(\"tenant\", \"subject\", "
                                + "\"issuer\", \"USER\")",
                        "\"POST\"", "\"/orders\"", "java.util.Map.of()", "new byte[0]"));
        assertTrue(BinaryCompatibility.declaredConstructorDescriptors(IngressRequest.class)
                .contains(shape.descriptor()));
        assertTrue(BinaryCompatibility.linksAgainstCurrent(IngressRequest.class, shape));
    }

    @Test void oldContributorAndRouteAuthorityUseNewFailClosedDefaults(@TempDir Path workspace)
            throws Exception {
        Path sources = Files.createDirectories(workspace.resolve("sources"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Path ingress = Files.createDirectories(sources.resolve("ai/ravenroot/api/ingress"));
        Path fixture = Files.createDirectories(sources.resolve("fixture"));
        Files.writeString(ingress.resolve("IngressAuthorityContributor.java"), """
                package ai.ravenroot.api.ingress;
                public interface IngressAuthorityContributor {
                    java.util.List<IngressAuthorityDeclaration> ingressAuthorities();
                }
                """);
        Files.writeString(ingress.resolve("IngressRouteAuthority.java"), """
                package ai.ravenroot.api.ingress;
                public interface IngressRouteAuthority {
                    IngressRouteLease acquire(String routeId, String relativePath,
                        java.util.Set<String> methods, IngressRouteHandler handler);
                }
                """);
        Files.writeString(fixture.resolve("LegacyIngress.java"), """
                package fixture;
                public final class LegacyIngress {
                    public static final class Contributor
                            implements ai.ravenroot.api.ingress.IngressAuthorityContributor {
                        public java.util.List<ai.ravenroot.api.ingress.IngressAuthorityDeclaration>
                                ingressAuthorities() { return java.util.List.of(); }
                    }
                    public static final class Routes
                            implements ai.ravenroot.api.ingress.IngressRouteAuthority {
                        public ai.ravenroot.api.ingress.IngressRouteLease acquire(String routeId,
                                String relativePath, java.util.Set<String> methods,
                                ai.ravenroot.api.ingress.IngressRouteHandler handler) { return null; }
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int status = compiler.run(null, null, null, "--release", "21", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(),
                ingress.resolve("IngressAuthorityContributor.java").toString(),
                ingress.resolve("IngressRouteAuthority.java").toString(),
                fixture.resolve("LegacyIngress.java").toString());
        assertEquals(0, status);
        Files.delete(classes.resolve("ai/ravenroot/api/ingress/IngressAuthorityContributor.class"));
        Files.delete(classes.resolve("ai/ravenroot/api/ingress/IngressRouteAuthority.class"));

        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
                IngressAuthorityContributor.class.getClassLoader())) {
            var contributor = (IngressAuthorityContributor) loader
                    .loadClass("fixture.LegacyIngress$Contributor").getConstructor().newInstance();
            var routes = (IngressRouteAuthority) loader
                    .loadClass("fixture.LegacyIngress$Routes").getConstructor().newInstance();
            assertTrue(contributor.ingressRequestProjection().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> routes.acquirePrefix(
                    "route", "/route", java.util.Set.of("POST"), request -> null));
        }
    }

    @Test void aHandlerCompiledWithoutTheRequestContextStillReceivesEveryRequest(@TempDir Path workspace)
            throws Exception {
        Path sources = Files.createDirectories(workspace.resolve("sources"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Path ingress = Files.createDirectories(sources.resolve("ai/ravenroot/api/ingress"));
        Path fixture = Files.createDirectories(sources.resolve("fixture"));
        // The legacy interface, verbatim: one abstract method and no context overload.
        Files.writeString(ingress.resolve("IngressRouteHandler.java"), """
                package ai.ravenroot.api.ingress;
                @FunctionalInterface
                public interface IngressRouteHandler {
                    java.util.concurrent.CompletionStage<IngressResponse> handle(IngressRequest request);
                }
                """);
        Files.writeString(fixture.resolve("LegacyHandler.java"), """
                package fixture;
                public final class LegacyHandler implements ai.ravenroot.api.ingress.IngressRouteHandler {
                    public java.util.concurrent.CompletionStage<ai.ravenroot.api.ingress.IngressResponse>
                            handle(ai.ravenroot.api.ingress.IngressRequest request) {
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                new ai.ravenroot.api.ingress.IngressResponse(200, java.util.Map.of(),
                                        request.relativePath().getBytes()));
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int status = compiler.run(null, null, null, "--release", "21", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(),
                ingress.resolve("IngressRouteHandler.java").toString(),
                fixture.resolve("LegacyHandler.java").toString());
        assertEquals(0, status);
        Files.delete(classes.resolve("ai/ravenroot/api/ingress/IngressRouteHandler.class"));

        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
                IngressRouteHandler.class.getClassLoader())) {
            var handler = (IngressRouteHandler) loader.loadClass("fixture.LegacyHandler")
                    .getConstructor().newInstance();
            var request = new IngressRequest(new IngressPrincipal("tenant", "subject", "issuer", "USER"),
                    "POST", "/orders", Map.of(), new byte[0]);
            var context = new IngressRequestContext(Instant.now().plusSeconds(30), new CancellationSignal() {
                @Override public boolean cancelled() { return false; }
                @Override public void onCancel(Runnable listener) { }
            });
            // The adapter always calls the two-argument form. A handler that predates it must still be
            // reached, which is why this default delegates instead of failing closed the way the
            // prefix and projection defaults in this package do: ignoring a deadline wastes work, it
            // does not widen anything, so denying would break working packages to enforce nothing.
            var answer = handler.handle(request, context).toCompletableFuture()
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, answer.status());
            assertArrayEquals("/orders".getBytes(), answer.body());
        }
    }
}
