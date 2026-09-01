package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.deployment.GraphDeployment;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyBinding;
import ai.ravenroot.api.deployment.RequestReplyContext;
import ai.ravenroot.api.deployment.RequestReplyExchange;
import ai.ravenroot.api.deployment.RequestReplyIngress;
import ai.ravenroot.api.deployment.RequestReplyOutcome;
import ai.ravenroot.api.deployment.RequestReplyProjection;
import ai.ravenroot.api.deployment.RequestReplyRefusal;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Proves deployment/source implementations compiled before request/reply still link and fail closed. */
class RequestReplyBinaryCompatibilityTest {

    @Test
    void oldDeploymentAndSourceContextImplementationsUseNewDenyOnlyDefaults(@TempDir Path workspace)
            throws Exception {
        Path sources = Files.createDirectories(workspace.resolve("sources"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Path api = Files.createDirectories(sources.resolve("ai/ravenroot/api/deployment"));
        Path fixture = Files.createDirectories(sources.resolve("fixture"));

        Files.writeString(api.resolve("GraphDeployment.java"), """
                package ai.ravenroot.api.deployment;
                public interface GraphDeployment {
                    DeploymentId id();
                    DeploymentStatus status();
                    java.util.concurrent.CompletionStage<DeploymentStatus> start(
                        ai.ravenroot.api.security.SecurityContext security);
                    java.util.concurrent.CompletionStage<DeploymentStatus> stop();
                    java.util.concurrent.CompletionStage<DeploymentStatus> restart(
                        ai.ravenroot.api.security.SecurityContext security);
                    TrustedIngress ingress();
                }
                """);
        Files.writeString(api.resolve("InboundSourceContext.java"), """
                package ai.ravenroot.api.deployment;
                public interface InboundSourceContext {
                    DeploymentId deploymentId();
                    String nodeId();
                    ai.ravenroot.api.security.SecurityContext identity();
                    TrustedIngress ingress();
                    default java.util.Optional<ai.ravenroot.api.ingress.IngressRouteAuthority> ingressRoutes() {
                        return java.util.Optional.empty();
                    }
                    void reportDegraded(String reason);
                    void reportHealthy();
                }
                """);
        Files.writeString(api.resolve("RequestReplyIngress.java"), """
                package ai.ravenroot.api.deployment;
                public interface RequestReplyIngress {
                    RequestReplyAdmission request(IngressTarget target,
                        ai.ravenroot.api.payload.PayloadValue payload, java.time.Instant deadline);
                }
                """);
        Files.writeString(fixture.resolve("LegacyImplementations.java"), """
                package fixture;
                public final class LegacyImplementations {
                    /** An ingress compiled when request(..) was the only method. */
                    public static final class Ingress implements ai.ravenroot.api.deployment.RequestReplyIngress {
                        public ai.ravenroot.api.deployment.RequestReplyAdmission request(
                                ai.ravenroot.api.deployment.IngressTarget target,
                                ai.ravenroot.api.payload.PayloadValue payload, java.time.Instant deadline) {
                            return new ai.ravenroot.api.deployment.RequestReplyAdmission.Refused(
                                ai.ravenroot.api.deployment.RequestReplyRefusal.UNSUPPORTED);
                        }
                    }
                    public static final class Deployment implements ai.ravenroot.api.deployment.GraphDeployment {
                        public ai.ravenroot.api.deployment.DeploymentId id() { return null; }
                        public ai.ravenroot.api.deployment.DeploymentStatus status() { return null; }
                        public java.util.concurrent.CompletionStage<ai.ravenroot.api.deployment.DeploymentStatus> start(
                                ai.ravenroot.api.security.SecurityContext security) { return null; }
                        public java.util.concurrent.CompletionStage<ai.ravenroot.api.deployment.DeploymentStatus> stop() {
                            return null;
                        }
                        public java.util.concurrent.CompletionStage<ai.ravenroot.api.deployment.DeploymentStatus> restart(
                                ai.ravenroot.api.security.SecurityContext security) { return null; }
                        public ai.ravenroot.api.deployment.TrustedIngress ingress() { return null; }
                    }
                    public static final class Context implements ai.ravenroot.api.deployment.InboundSourceContext {
                        public ai.ravenroot.api.deployment.DeploymentId deploymentId() { return null; }
                        public String nodeId() { return "legacy"; }
                        public ai.ravenroot.api.security.SecurityContext identity() { return null; }
                        public ai.ravenroot.api.deployment.TrustedIngress ingress() { return null; }
                        public void reportDegraded(String reason) { }
                        public void reportHealthy() { }
                    }
                }
                """);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int status = compiler.run(null, null, null, "--release", "21", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(),
                api.resolve("GraphDeployment.java").toString(),
                api.resolve("InboundSourceContext.java").toString(),
                api.resolve("RequestReplyIngress.java").toString(),
                fixture.resolve("LegacyImplementations.java").toString());
        assertEquals(0, status);
        Files.delete(classes.resolve("ai/ravenroot/api/deployment/GraphDeployment.class"));
        Files.delete(classes.resolve("ai/ravenroot/api/deployment/InboundSourceContext.class"));
        Files.delete(classes.resolve("ai/ravenroot/api/deployment/RequestReplyIngress.class"));

        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                GraphDeployment.class.getClassLoader())) {
            GraphDeployment legacyDeployment = (GraphDeployment) loader
                    .loadClass("fixture.LegacyImplementations$Deployment").getConstructor().newInstance();
            InboundSourceContext legacyContext = (InboundSourceContext) loader
                    .loadClass("fixture.LegacyImplementations$Context").getConstructor().newInstance();
            RequestReplyIngress legacyIngress = (RequestReplyIngress) loader
                    .loadClass("fixture.LegacyImplementations$Ingress").getConstructor().newInstance();
            assertUnsupported(legacyDeployment.requestReply());
            assertUnsupported(legacyContext.requestReply());
            // The additional seam is a default method, so an ingress that never heard of it still
            // links and still fails closed rather than fabricating a projected capability.
            assertUnsupported(legacyIngress);
        }
    }

    @Test
    void publicRequestReplyContractContainsNoEngineOrActorTypes() {
        for (Class<?> type : List.of(GraphDeployment.class, InboundSourceContext.class,
                RequestReplyIngress.class, RequestReplyAdmission.class, RequestReplyExchange.class,
                RequestReplyOutcome.class, RequestReplyBinding.class, RequestReplyContext.class,
                RequestReplyProjection.class)) {
            java.util.stream.Stream.concat(java.util.Arrays.stream(type.getMethods())
                            .flatMap(method -> java.util.stream.Stream.concat(
                                    java.util.stream.Stream.of(method.getReturnType()),
                                    java.util.Arrays.stream(method.getParameterTypes()))),
                    java.util.Arrays.stream(type.getRecordComponents() == null
                                    ? new java.lang.reflect.RecordComponent[0] : type.getRecordComponents())
                            .map(java.lang.reflect.RecordComponent::getType))
                    .map(Class::getName)
                    .forEach(name -> {
                        String lower = name.toLowerCase(java.util.Locale.ROOT);
                        assertFalse(lower.contains("akka") || lower.contains("pekko") || lower.contains("actor"),
                                () -> type.getName() + " exposes engine-specific type " + name);
                    });
        }
    }

    private static void assertUnsupported(RequestReplyIngress ingress) {
        var refusal = (RequestReplyAdmission.Refused) ingress.request(IngressTarget.start(),
                PayloadValue.of("payload"), Instant.now().plusSeconds(1));
        assertEquals(RequestReplyRefusal.UNSUPPORTED, refusal.reason());

        var projected = (RequestReplyAdmission.Refused) ingress.requestProjected(IngressTarget.start(),
                context -> PayloadValue.of("projected"), Instant.now().plusSeconds(1));
        assertEquals(RequestReplyRefusal.UNSUPPORTED, projected.reason());
    }
}
