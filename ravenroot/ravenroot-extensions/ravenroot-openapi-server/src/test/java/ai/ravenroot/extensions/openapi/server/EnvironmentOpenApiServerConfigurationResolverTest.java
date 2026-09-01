package ai.ravenroot.extensions.openapi.server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentOpenApiServerConfigurationResolverTest {
    @Test void strictOperatorConfigurationCrossesPackageAuthorityAndProfileCompilation() {
        String encoded = encode(configurationJson(""));
        EnvironmentOpenApiServerConfigurationResolver resolver =
                new EnvironmentOpenApiServerConfigurationResolver(Map.of(
                        EnvironmentOpenApiServerConfigurationResolver.VARIABLE, encoded));
        OpenApiServerConfiguration configuration = resolver.resolve().orElseThrow();
        assertEquals("/managed/openapi", configuration.authority().pathPrefix());
        assertEquals("idempotency-key", configuration.projection().idempotencyHeader());
        assertEquals("/api", configuration.requireProfile("orders").routeBase());
        OpenApiIngressPlan.compile(configuration.requireProfile("orders"), java.util.Set.of("createOrder"),
                configuration.projection().allowedHeaders());

        OpenApiServerNodePackage nodePackage = new OpenApiServerNodePackage(resolver);
        assertEquals(OpenApiServerConfiguration.PACKAGE_ID, nodePackage.ingressAuthorities().getFirst().packageId());
        assertTrue(nodePackage.ingressRequestProjection().isPresent());
    }

    @Test void missingMalformedNonCanonicalAndUnknownFieldsFailClosedWithoutContent() {
        assertTrue(new EnvironmentOpenApiServerConfigurationResolver(Map.of()).resolve().isEmpty());
        assertTrue(new EnvironmentOpenApiServerConfigurationResolver(Map.of(
                EnvironmentOpenApiServerConfigurationResolver.VARIABLE, "not-base64")).resolve().isEmpty());
        String canonical = encode(configurationJson(""));
        assertTrue(new EnvironmentOpenApiServerConfigurationResolver(Map.of(
                EnvironmentOpenApiServerConfigurationResolver.VARIABLE, canonical.substring(0, canonical.length() - 1)))
                .resolve().isEmpty());
        assertTrue(new EnvironmentOpenApiServerConfigurationResolver(Map.of(
                EnvironmentOpenApiServerConfigurationResolver.VARIABLE,
                encode(configurationJson(",\"graphRoute\":\"/forbidden\"")))).resolve().isEmpty());
        OpenApiServerNodePackage missing = new OpenApiServerNodePackage(() -> java.util.Optional.empty());
        OpenApiServerException failure = assertThrows(OpenApiServerException.class, missing::ingressAuthorities);
        assertEquals(OpenApiServerException.Code.CONFIGURATION_INVALID, failure.code());
        assertFalse(failure.getMessage().contains("not-base64"));
    }

    @Test void profilesCannotDisagreeOnIdempotencyProjectionOrWidenAuthority() {
        OpenApiServerConfiguration base = OpenApiServerTestSupport.configuration();
        OpenApiServerProfile profile = OpenApiServerTestSupport.profile();
        OpenApiServerProfile otherHeader = new OpenApiServerProfile("other", profile.specification(),
                profile.specificationSha256(), "/other", profile.allowedOperations(), profile.allowedPrincipalTypes(),
                "other-key", null, profile.maxRequestBytes(), profile.maxIdempotencyBytes(), profile.deadlineMs(),
                profile.maxConcurrency());
        assertThrows(OpenApiServerException.class, () -> new OpenApiServerConfiguration(base.authority(),
                base.projection(), Map.of("orders", profile, "other", otherHeader)));
        OpenApiServerProfile tooLarge = new OpenApiServerProfile("orders", profile.specification(),
                profile.specificationSha256(), profile.routeBase(), profile.allowedOperations(),
                profile.allowedPrincipalTypes(), profile.idempotencyHeader(), null, 9000,
                profile.maxIdempotencyBytes(), profile.deadlineMs(), profile.maxConcurrency());
        assertThrows(OpenApiServerException.class, () -> new OpenApiServerConfiguration(base.authority(),
                base.projection(), Map.of("orders", tooLarge)));
        OpenApiServerProfile overlapping = new OpenApiServerProfile("overlap", profile.specification(),
                profile.specificationSha256(), "/api/v2", profile.allowedOperations(),
                profile.allowedPrincipalTypes(), profile.idempotencyHeader(), null, profile.maxRequestBytes(),
                profile.maxIdempotencyBytes(), profile.deadlineMs(), profile.maxConcurrency());
        assertThrows(OpenApiServerException.class, () -> new OpenApiServerConfiguration(base.authority(),
                base.projection(), Map.of("orders", profile, "overlap", overlapping)));
        assertThrows(OpenApiServerException.class, () -> new OpenApiServerProfile("targeted",
                profile.specification(), profile.specificationSha256(), "/targeted", profile.allowedOperations(),
                profile.allowedPrincipalTypes(), profile.idempotencyHeader(), "named-node",
                profile.maxRequestBytes(), profile.maxIdempotencyBytes(), profile.deadlineMs(),
                profile.maxConcurrency()));
    }

    private static String configurationJson(String extraProfileField) {
        String spec = Base64.getEncoder().encodeToString(OpenApiServerTestSupport.SPEC);
        return """
                {"authority":{"listenerId":"main","pathPrefix":"/managed/openapi","requiredScopes":["graph:execute"],"maxRoutes":8,"maxConcurrentRequests":8,"maxRequestBytes":8192,"maxResponseBytes":1024,"requestTimeoutMs":2000},
                 "projection":{"allowedHeaders":["content-type","idempotency-key","x-trace"],"idempotencyHeader":"idempotency-key","maxRelativePathBytes":1024,"maxQueryParameters":32,"maxQueryBytes":2048,"maxHeaderCount":8,"maxHeaderBytes":2048,"maxHeaderValueBytes":256},
                 "profiles":{"orders":{"specBase64":"%s","specSha256":"%s","routeBase":"/api","operations":["createOrder","specialOrder"],"principalTypes":["USER"],"idempotencyHeader":"idempotency-key","maxRequestBytes":4096,"maxIdempotencyBytes":128,"deadlineMs":1000,"maxConcurrency":2%s}}}
                """.formatted(spec, OpenApiServerTestSupport.sha256(OpenApiServerTestSupport.SPEC), extraProfileField);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
