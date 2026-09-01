package ai.ravenroot.server.assistant;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.AuthorizationDecision;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.assistant.provider.AssistantProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantGraphProposalTest {

    private static final AssistantTurn BOUND_TURN =
            new AssistantTurn("add a branch", """
                    {"graph":{"nodes":[],"edges":[]},"catalog":[{"behavior":"template",
                    "properties":[{"name":"template","type":"STRING"}]}]}
                    """, List.of("graph", "catalog"), "document-a", 7, "catalog-a");
    private static final AssistantTurn TOPOLOGY_TURN =
            new AssistantTurn("change the graph", """
                    {"graph":{"nodes":[
                      {"id":"n1","behavior":"template"},
                      {"id":"n2","behavior":"template"},
                      {"id":"n3","behavior":"template"}],
                    "edges":[{"id":"e1","source":"n1","target":"n2","outcome":"continue"}]},
                    "catalog":[{"behavior":"template",
                    "properties":[{"name":"template","type":"STRING"}]}]}
                    """, List.of("graph", "catalog"), "document-a", 7, "catalog-a");
    private static final String VALID = """
            {"summary":"Add two steps","operations":[
              {"op":"create-node","ref":"first","id":"first-id","behavior":"template",
               "properties":[{"name":"template","value":"hello"}]},
              {"op":"create-node","ref":"second","id":"second-id","behavior":"template"},
              {"op":"create-edge","ref":"edge","id":"edge-id","source":{"created":"first"},
               "destination":{"created":"second"},"outcome":"continue"}
            ]}
            """;
    private static final String QWEN_MISSING_REF = """
            {"summary":"Add a log node","operations":[
              {"op":"create-node","id":"log-node","behavior":"template"}
            ]}
            """;

    @Test
    void discriminatedSchemaAndValidatorShareAllSixRequiredAndAllowedFieldContracts() {
        PayloadValue.MapValue schema = assertInstanceOf(PayloadValue.MapValue.class,
                PayloadJson.read(AssistantGraphProposal.toolSpec().inputSchemaJson()
                                .getBytes(StandardCharsets.UTF_8),
                        AssistantTurn.TURN_LIMITS));
        PayloadValue.MapValue properties = map(schema.entries().get("properties"));
        PayloadValue.MapValue operations = map(properties.entries().get("operations"));
        PayloadValue.MapValue items = map(operations.entries().get("items"));
        PayloadValue.ListValue variants = assertInstanceOf(PayloadValue.ListValue.class,
                items.entries().get("oneOf"));
        Map<String, AssistantGraphProposal.OperationContract> declared = new HashMap<>();
        for (PayloadValue value : variants.values()) {
            PayloadValue.MapValue variant = map(value);
            PayloadValue.MapValue fields = map(variant.entries().get("properties"));
            PayloadValue.MapValue op = map(fields.entries().get("op"));
            String wireName = ((PayloadValue.TextValue) op.entries().get("const")).value();
            Set<String> required = ((PayloadValue.ListValue) variant.entries().get("required")).values()
                    .stream().map(PayloadValue.TextValue.class::cast)
                    .map(PayloadValue.TextValue::value).collect(Collectors.toSet());
            declared.put(wireName,
                    new AssistantGraphProposal.OperationContract(fields.entries().keySet(), required));
        }

        assertEquals(AssistantGraphProposal.operationContracts(), declared,
                "the schema and validator must consume the same six operation field contracts");
        assertEquals(6, declared.size());
        assertEquals(Set.of("op", "ref", "id", "behavior"),
                declared.get("create-node").required());
    }

    @Test
    void parsesOneClosedBoundedProposalAndKeepsTheCompatibleReplyMembers() {
        var proposal = AssistantGraphProposal.read(VALID, BOUND_TURN, "model-a");

        assertEquals("document-a", proposal.documentIncarnation());
        assertEquals(7, proposal.documentRevision());
        assertEquals("catalog-a", proposal.catalogDigest());
        String json = proposal.toJson();
        assertTrue(json.contains("\"text\":\"Add two steps\""));
        assertTrue(json.contains("\"model\":\"model-a\""));
        assertTrue(json.contains("\"truncated\":false"));
        assertTrue(json.contains("\"proposal\":"));
    }

    @Test
    void refusesUnknownDuplicateAndSecretClassFields() {
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                VALID.replace("\"summary\":", "\"confirmed\":true,\"summary\":"), BOUND_TURN, "m"));
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                VALID.replace("\"ref\":\"second\"", "\"ref\":\"first\""), BOUND_TURN, "m"));
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                VALID.replace("\"template\",\"value\"", "\"credentialRef\",\"value\""),
                BOUND_TURN, "m"));
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                VALID, new AssistantTurn("x", null, List.of()), "m"));
    }

    @Test
    void documentBindingIsOptionalForOldReadOnlyTurnsAndStrictWhenPresent() {
        var old = AssistantTurn.read("{\"prompt\":\"hello\",\"context\":{},\"attached\":[]}".getBytes());
        assertFalse(old.hasDocumentBinding());
        assertFalse(AssistantGraphProposal.canOffer(old));
        var bound = AssistantTurn.read(("{\"prompt\":\"hello\",\"document\":{"
                + "\"incarnation\":\"i\",\"revision\":3,\"catalogDigest\":\"d\"}}")
                .getBytes());
        assertTrue(bound.hasDocumentBinding());
        assertEquals(3, bound.documentRevision());
        assertThrows(IllegalArgumentException.class, () -> AssistantTurn.read(("{\"prompt\":\"x\","
                + "\"document\":{\"incarnation\":\"i\",\"revision\":0,"
                + "\"catalogDigest\":\"d\",\"extra\":true}}}").getBytes()));
    }

    @Test
    void acceptsOnlyTheClosedPositionShapeOnAnExistingNodeUpdate() {
        String valid = """
                {"summary":"Move one node","operations":[
                  {"op":"update-node","target":{"existing":"n1"},
                   "position":{"x":12.5,"y":-3}}
                ]}
                """;

        var proposal = AssistantGraphProposal.read(valid, TOPOLOGY_TURN, "model-a");
        assertTrue(proposal.operationsJson().contains("\"position\""));
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                valid.replace("\"y\":-3", "\"y\":-3,\"z\":0"), TOPOLOGY_TURN, "model-a"));
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                valid.replace(",\"y\":-3", ""), TOPOLOGY_TURN, "model-a"));
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                valid.replace("12.5", "\"12.5\""), TOPOLOGY_TURN, "model-a"));
    }

    @Test
    void rejectsDirectEdgeChangesThatWouldBeHiddenByADeleteNodeCascade() {
        String createdThenCascade = """
                {"summary":"Contradictory create","operations":[
                  {"op":"create-edge","ref":"new-edge","id":"e2",
                   "source":{"existing":"n1"},"destination":{"existing":"n3"}},
                  {"op":"delete-node","target":{"existing":"n1"}}
                ]}
                """;
        String updatedThenCascade = """
                {"summary":"Contradictory update","operations":[
                  {"op":"update-edge","target":{"existing":"e1"},"outcome":"changed"},
                  {"op":"delete-node","target":{"existing":"n1"}}
                ]}
                """;
        String cascadeThenUpdate = """
                {"summary":"Reference an implicit deletion","operations":[
                  {"op":"delete-node","target":{"existing":"n1"}},
                  {"op":"update-edge","target":{"existing":"e1"},"outcome":"changed"}
                ]}
                """;

        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                createdThenCascade, TOPOLOGY_TURN, "model-a"));
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                updatedThenCascade, TOPOLOGY_TURN, "model-a"));
        assertThrows(IllegalArgumentException.class, () -> AssistantGraphProposal.read(
                cascadeThenUpdate, TOPOLOGY_TURN, "model-a"));
    }

    @Test
    void allowsAnExistingNodeDeleteToCascadeOnlyUntouchedPreExistingEdges() {
        var proposal = AssistantGraphProposal.read("""
                {"summary":"Delete one node","operations":[
                  {"op":"delete-node","target":{"existing":"n1"}}
                ]}
                """, TOPOLOGY_TURN, "model-a");
        var rerouted = AssistantGraphProposal.read("""
                {"summary":"Move an edge away before deleting","operations":[
                  {"op":"update-edge","target":{"existing":"e1"},
                   "source":{"existing":"n3"}},
                  {"op":"delete-node","target":{"existing":"n1"}}
                ]}
                """, TOPOLOGY_TURN, "model-a");

        assertTrue(proposal.operationsJson().contains("\"delete-node\""));
        assertTrue(rerouted.operationsJson().contains("\"update-edge\""));
    }

    @Test
    void serviceRecoversInvalidAndMixedProposalsWithoutApplyingAnything() {
        try (var engine = new PekkoExecutionEngine("assistant-proposal-test")) {
            var application = application(engine);
            var context = author();
            var one = new AssistantHarness.ScriptedProviderView()
                    .callingTool(AssistantGraphProposal.TOOL_NAME, VALID);
            assertInstanceOf(AssistantOutcome.Proposal.class,
                    one.service().send(context, application, BOUND_TURN));
            assertTrue(one.received().getFirst().tools().stream()
                    .anyMatch(tool -> AssistantGraphProposal.TOOL_NAME.equals(tool.name())));

            var proposalUse = new AssistantProvider.Content.ToolUse("proposal",
                    AssistantGraphProposal.TOOL_NAME, VALID);
            var readUse = new AssistantProvider.Content.ToolUse("read", "ravenroot_status", "{}");
            var mixed = new AssistantHarness.ScriptedProviderView()
                    .callingTools(proposalUse, readUse)
                    .callingTool(AssistantGraphProposal.TOOL_NAME, VALID);
            assertInstanceOf(AssistantOutcome.Proposal.class,
                    mixed.service().send(context, application, BOUND_TURN));
            assertEquals(2, mixed.callCount());
            String recovered = String.valueOf(mixed.received().get(1).messages());
            assertTrue(recovered.contains("GRAPH_PROPOSAL_NOT_ISOLATED"), recovered);
            assertTrue(recovered.contains("executionEngine"),
                    "the authorized read mixed with the proposal must still be answered: " + recovered);

            var multiple = new AssistantHarness.ScriptedProviderView()
                    .callingTools(proposalUse, new AssistantProvider.Content.ToolUse("proposal-2",
                            AssistantGraphProposal.TOOL_NAME, VALID))
                    .callingTool(AssistantGraphProposal.TOOL_NAME, VALID);
            assertInstanceOf(AssistantOutcome.Proposal.class,
                    multiple.service().send(context, application, BOUND_TURN));

            var unknown = new AssistantHarness.ScriptedProviderView()
                    .callingTool("ravenroot_apply_graph_change", VALID)
                    .answering("The unknown call was not executed");
            assertInstanceOf(AssistantOutcome.Reply.class,
                    unknown.service().send(context, application, BOUND_TURN));

            var invalidThenCorrected = new AssistantHarness.ScriptedProviderView()
                    .callingTool(AssistantGraphProposal.TOOL_NAME, QWEN_MISSING_REF)
                    .callingTool(AssistantGraphProposal.TOOL_NAME, VALID);
            assertInstanceOf(AssistantOutcome.Proposal.class,
                    invalidThenCorrected.service().send(context, application, BOUND_TURN));
            assertEquals(2, invalidThenCorrected.callCount());
            String feedback = toolResultContent(invalidThenCorrected.received().get(1));
            assertTrue(feedback.contains("MISSING_REQUIRED_FIELDS"), feedback);
            assertTrue(feedback.contains("create-node"), feedback);
            assertTrue(feedback.contains("ref"), feedback);
            assertTrue(feedback.length() < 512, feedback);

            String unknownCanary = "provider-output-must-not-be-reflected";
            var unknownThenCorrected = new AssistantHarness.ScriptedProviderView()
                    .callingTool(AssistantGraphProposal.TOOL_NAME,
                            VALID.replace("\"summary\":", "\"" + unknownCanary
                                    + "\":true,\"summary\":"))
                    .callingTool(AssistantGraphProposal.TOOL_NAME, VALID);
            assertInstanceOf(AssistantOutcome.Proposal.class,
                    unknownThenCorrected.service().send(context, application, BOUND_TURN));
            String unknownFeedback = toolResultContent(unknownThenCorrected.received().get(1));
            assertTrue(unknownFeedback.length() < 512, unknownFeedback);
            assertFalse(unknownFeedback.contains(unknownCanary), unknownFeedback);

            var exhausted = new AssistantHarness.ScriptedProviderView()
                    .callingTool(AssistantGraphProposal.TOOL_NAME, QWEN_MISSING_REF)
                    .callingTool(AssistantGraphProposal.TOOL_NAME, QWEN_MISSING_REF)
                    .callingTool(AssistantGraphProposal.TOOL_NAME, QWEN_MISSING_REF);
            var failure = assertInstanceOf(AssistantOutcome.Failure.class,
                    exhausted.service().send(context, application, BOUND_TURN));
            assertEquals(AssistantOutcome.Reason.MODEL_PROPOSAL_INVALID, failure.reason());
            assertEquals(3, exhausted.callCount());
        }
    }

    private static PayloadValue.MapValue map(PayloadValue value) {
        return assertInstanceOf(PayloadValue.MapValue.class, value);
    }

    private static String toolResultContent(AssistantProvider.Request request) {
        return request.messages().getLast().content().stream()
                .filter(AssistantProvider.Content.ToolResult.class::isInstance)
                .map(AssistantProvider.Content.ToolResult.class::cast)
                .map(AssistantProvider.Content.ToolResult::content)
                .findFirst().orElseThrow();
    }

    private static AuthorizedRavenrootApplication application(PekkoExecutionEngine engine) {
        return new AuthorizedRavenrootApplication(
                new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                (context, action, resource) -> new AuthorizationDecision(true, "test"),
                event -> { }, false);
    }

    private static RequestContext author() {
        return new RequestContext(UUID.randomUUID().toString(), "assistant-author", PrincipalType.USER,
                "urn:ravenroot:test", "tenant", Set.of(Role.PLATFORM_ADMIN),
                Arrays.stream(ai.ravenroot.api.security.AuthorizationAction.values())
                        .filter(ai.ravenroot.api.security.AuthorizationAction::available)
                        .map(ai.ravenroot.api.security.AuthorizationAction::requiredScope)
                        .collect(Collectors.toUnmodifiableSet()));
    }
}
