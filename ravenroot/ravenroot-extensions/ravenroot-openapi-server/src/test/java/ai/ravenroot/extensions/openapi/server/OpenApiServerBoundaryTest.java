package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.node.ManagedIngressSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiServerBoundaryTest {
    @Test void productionImportsOnlyPublishedApplicationApiAndJdkTypes() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("import ai.ravenroot.core"), file.toString());
                assertFalse(text.contains("import ai.ravenroot.server"), file.toString());
                assertFalse(text.contains("com.sun.net.httpserver"), file.toString());
                assertFalse(text.contains("java.net.http.HttpClient"), file.toString());
                assertFalse(text.contains("Class.forName"), file.toString());
            }
        }
    }

    @Test void packageDeclaresOnlyManagedIngressAndNoGeneratedServer() {
        OpenApiServerNodePackage nodePackage = new OpenApiServerNodePackage(
                () -> java.util.Optional.of(OpenApiServerTestSupport.configuration()));
        assertInstanceOf(IngressAuthorityContributor.class, nodePackage);
        var behavior = nodePackage.behaviors().getFirst();
        var source = ((ai.ravenroot.api.node.InboundSourceCapable) behavior).createSource(
                new ai.ravenroot.api.node.NodeConfiguration("openapi", OpenApiReceiveNodeBehavior.BEHAVIOR,
                        java.util.Map.of("apiProfile", "orders")),
                new OpenApiServerTestSupport.FakeContext(new OpenApiServerTestSupport.FakeIngress()));
        assertInstanceOf(ManagedIngressSource.class, source);
        assertTrue(nodePackage.behaviors().stream().allMatch(value -> value.requiredServices().isEmpty()));
        assertFalse(Files.exists(Path.of("src/main/generated")));
    }

    @Test void extensionIsAbsentFromTheDefaultDistribution() throws Exception {
        String distribution = Files.readString(Path.of("../../ravenroot-distribution/pom.xml"));
        assertFalse(distribution.contains("ravenroot-openapi-server"));
        assertFalse(distribution.contains(OpenApiServerConfiguration.PACKAGE_ID));
    }

    @Test void packageIntentionallyHasNoPrivateTelemetryPlaneOrHighCardinalityEmissions() throws Exception {
        List<String> forbidden = List.of("io.opentelemetry", "io.micrometer", "java.util.logging",
                "org.slf4j", "System.out", "System.err");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                forbidden.forEach(fragment -> assertFalse(text.contains(fragment), file + ": " + fragment));
            }
        }
        String pom = Files.readString(Path.of("pom.xml"));
        assertFalse(pom.contains("opentelemetry"));
        assertFalse(pom.contains("micrometer"));
        assertFalse(pom.contains("slf4j"));
        String readme = Files.readString(Path.of("README.md"));
        assertTrue(readme.contains("deliberately emits no package-specific metrics or logs"));
        assertTrue(readme.contains("package, route, generation and state"));
        assertTrue(readme.contains("Tenant, deployment, traversal, request, header, body and"));
    }
}
