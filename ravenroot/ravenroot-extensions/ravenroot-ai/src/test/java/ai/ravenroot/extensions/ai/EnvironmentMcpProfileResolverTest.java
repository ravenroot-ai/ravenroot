package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an operator writes, and everything this bundle refuses to read as a server.
 *
 * <p>The cases that matter are the refusals, and one of them is an contract requirement in its own
 * right: a credential on a plaintext origin has to be refused <b>when the profile is read</b>, so the
 * operator meets it against the server they named rather than at the first call, in front of a graph
 * author who cannot fix it.</p>
 */
class EnvironmentMcpProfileResolverTest {

    private static final String NAME = "corp";
    private static final String VARIABLE =
            EnvironmentMcpProfileResolver.VARIABLE_PREFIX + EnvironmentKeyCodec.hex(NAME);

    @Test
    @DisplayName("the variable name is the injective derivation every resolver in this repository uses")
    void theVariableNameIsTheSharedDerivation() {
        assertEquals(VARIABLE, EnvironmentMcpProfileResolver.environmentVariableName(NAME));
    }

    @Test
    @DisplayName("a complete declaration reads back as the operator wrote it")
    void aCompleteDeclarationReadsBack() {
        McpProfile profile = resolve("{\"endpoint\":\"https://mcp.example.test/mcp\","
                + "\"credentialBindingId\":\"mcp\",\"credentialReference\":\"mcp-token\","
                + "\"timeoutMs\":20000,\"maxResponseBytes\":524288,\"maxConcurrency\":3,"
                + "\"allowedTools\":[\"search\",\"fetch\"]}").orElseThrow();

        assertEquals(NAME, profile.name());
        assertEquals("https://mcp.example.test/mcp", profile.endpoint().toString());
        assertEquals(20_000, profile.timeoutMs());
        assertEquals(524_288, profile.maxResponseBytes());
        assertEquals(3, profile.maxConcurrency());
        assertEquals(Set.of("search", "fetch"), profile.allowedTools());
        assertTrue(profile.credentialBinding().isPresent());
        assertTrue(profile.permits("search"));
        assertFalse(profile.permits("delete"));
        assertEquals("corp__search", profile.exposedName("search"));
    }

    @Test
    @DisplayName("only the endpoint and the tool list are required; the rest has defaults")
    void onlyTheEndpointAndTheToolListAreRequired() {
        McpProfile profile = resolve("{\"endpoint\":\"http://127.0.0.1:9000/mcp\","
                + "\"allowedTools\":[\"search\"]}").orElseThrow();

        assertEquals(Optional.empty(), profile.credentialBinding());
        assertEquals(30_000, profile.timeoutMs());
        assertEquals(4, profile.maxConcurrency());
    }

    @Test
    @DisplayName("a credential on a plaintext origin is refused when the profile is read")
    void aCredentialOnAPlaintextOriginIsRefusedAtProfileRead() {
        // The contract requirement. The managed channel would refuse it later anyway; refusing here
        // turns "your call failed" into "your profile is wrong", while the operator can still see
        // which profile they wrote.
        assertTrue(resolve("{\"endpoint\":\"http://mcp.example.test/mcp\","
                + "\"credentialBindingId\":\"mcp\",\"credentialReference\":\"mcp-token\","
                + "\"allowedTools\":[\"search\"]}").isEmpty());

        // The same declaration over TLS is accepted, so the refusal above is about the scheme and not
        // about the credential members being present at all.
        assertTrue(resolve("{\"endpoint\":\"https://mcp.example.test/mcp\","
                + "\"credentialBindingId\":\"mcp\",\"credentialReference\":\"mcp-token\","
                + "\"allowedTools\":[\"search\"]}").isPresent());
    }

    @Test
    @DisplayName("an omitted tool list is a refusal and never an implicit 'everything'")
    void anOmittedToolListIsARefusal() {
        assertTrue(resolve("{\"endpoint\":\"https://mcp.example.test/mcp\"}").isEmpty());
        assertTrue(resolve("{\"endpoint\":\"https://mcp.example.test/mcp\",\"allowedTools\":[]}")
                .isEmpty());
        // And a mistyped member name does not quietly become "no list": unknown members are refused,
        // which is what makes the typo visible instead of permissive.
        assertTrue(resolve("{\"endpoint\":\"https://mcp.example.test/mcp\","
                + "\"allowedTool\":[\"search\"]}").isEmpty());
    }

    @Test
    @DisplayName("a name and tool that would not survive as one exposed name are refused at read")
    void anUnusableExposedNameIsRefusedAtRead() {
        // A dot is legal in a profile name and illegal in a function name an endpoint will accept, so
        // the combination has to fail somewhere. It fails here, in front of the operator who can
        // rename the server, and not at the first turn in front of an author who cannot.
        var withADot = new EnvironmentMcpProfileResolver(Map.of(
                EnvironmentMcpProfileResolver.environmentVariableName("corp.search"),
                AiTestSupport.encodedMcpProfile("{\"endpoint\":\"https://mcp.example.test/mcp\","
                        + "\"allowedTools\":[\"go\"]}")));
        assertTrue(withADot.resolve("corp.search").isEmpty());
    }

    @Test
    @DisplayName("malformed reads as absent: not decodable, not canonical, not an object")
    void malformedReadsAsAbsent() {
        assertTrue(new EnvironmentMcpProfileResolver(Map.of(VARIABLE, "not base64 at all"))
                .resolve(NAME).isEmpty());
        // Decodable but not canonical: a different string that happens to share a decoding must not
        // become a second spelling of one variable.
        assertTrue(new EnvironmentMcpProfileResolver(Map.of(VARIABLE, "eyJhIjoxfQ=="))
                .resolve(NAME).isEmpty());
        assertTrue(resolve("[\"not an object\"]").isEmpty());
        assertTrue(resolve("{\"endpoint\":\"not a uri at all\",\"allowedTools\":[\"go\"]}").isEmpty());
        assertTrue(new EnvironmentMcpProfileResolver(Map.of()).resolve(NAME).isEmpty());
    }

    @Test
    @DisplayName("a name outside the mask never reaches a variable lookup")
    void aNameOutsideTheMaskIsRefused() {
        var resolver = new EnvironmentMcpProfileResolver(Map.of(VARIABLE,
                AiTestSupport.encodedMcpProfile("{\"endpoint\":\"https://mcp.example.test/mcp\","
                        + "\"allowedTools\":[\"search\"]}")));
        assertTrue(resolver.resolve("corp/../etc").isEmpty());
        assertTrue(resolver.resolve("").isEmpty());
        assertTrue(resolver.resolve(null).isEmpty());
    }

    private static Optional<McpProfile> resolve(String json) {
        return new EnvironmentMcpProfileResolver(
                Map.of(VARIABLE, AiTestSupport.encodedMcpProfile(json))).resolve(NAME);
    }
}
