package ai.ravenroot.extensions.github;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Optimistic generation-fenced Project transition with absolute Attempts writes and reconciliation. */
public final class ProjectTransitionBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "project-transition";
    private static final String SNAPSHOT = """
            query($item:ID!){node(id:$item){... on ProjectV2Item{id project{id}
              content{... on PullRequest{repository{databaseId}} ... on Issue{repository{databaseId}} ... on DraftIssue{id}}
              fieldValues(first:100){pageInfo{hasNextPage} nodes{
              ... on ProjectV2ItemFieldSingleSelectValue{field{... on ProjectV2SingleSelectField{id}} optionId}
              ... on ProjectV2ItemFieldNumberValue{field{... on ProjectV2FieldCommon{id}} number}
            }}}}}
            """;
    private static final String UPDATE = """
            mutation($project:ID!,$item:ID!,$statusField:ID!,$status:String!,$attemptsField:ID!,
                     $attempts:Float!,$generationField:ID!,$generation:Float!,$client:String!){
              status:updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$statusField,
                value:{singleSelectOptionId:$status},clientMutationId:$client}){clientMutationId}
              attempts:updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$attemptsField,
                value:{number:$attempts},clientMutationId:$client}){clientMutationId}
              generation:updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$generationField,
                value:{number:$generation},clientMutationId:$client}){clientMutationId}}
            """;
    private final GithubRuntime runtime;
    ProjectTransitionBehavior(GithubRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return GithubBehaviorDescriptors.descriptor(BEHAVIOR,
            "Transition GitHub Project item", "Applies one generation-fenced Project status transition.",
            false, true, true); }
    @Override public NodeAction create(NodeConfiguration configuration) { return create(configuration, NodePackageServices.unavailable()); }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = GithubBehaviorDescriptors.profile(configuration);
        return message -> invoke(message, services, profileName);
    }

    private CompletionStage<NodeResult> invoke(NodeMessage message, NodePackageServices services, String profileName) {
        final Input input;
        final GithubProfile profile;
        try { input = Input.parse(message.payload()); profile = runtime.requireProfile(message.tenantId(), profileName); input.authorize(profile.project()); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
        long deadline = System.currentTimeMillis() + profile.timeoutMs();
        Map<String, Object> canonical = input.canonical();
        String key = profile.repositoryId() + ":" + input.itemId;
        try {
            return runtime.submit(message, services, profile, BEHAVIOR, key, canonical, deadline,
                    GithubOperationStore.BeginPolicy.project(input.expectedGeneration),
                    (api, operation, control) -> transition(api, profile, input, operation));
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
    }

    private static NodeResult transition(GithubApi api, GithubProfile profile, Input input,
                                         GithubOperationStore.Lease operation) {
        Snapshot before = snapshot(api, profile, input.itemId);
        long wantedAttempts = input.expectedAttempts + (input.transition().equals(profile.project().claimTransition()) ? 1 : 0);
        long wantedGeneration = input.expectedGeneration + 1;
        if (before.matches(input.toStatus, wantedAttempts, wantedGeneration)) return result("continue", "already-applied",
                input, wantedGeneration, wantedAttempts, "");
        if (!before.matches(input.fromStatus, input.expectedAttempts, input.expectedGeneration)) return result(
                "conflict", "cas-lost", input, before.generation, before.attempts, "CAS_LOST");
        if ("AMBIGUOUS".equals(operation.record().state())) return result("ambiguous", "ambiguous", input,
                before.generation, before.attempts, "REMOTE_STATE_UNKNOWN");
        String client = GithubValues.sha256(input.itemId + ":" + wantedGeneration + ":" + input.toStatus).substring(0, 32);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("project", profile.project().projectId()); variables.put("item", input.itemId);
        variables.put("statusField", profile.project().statusFieldId());
        variables.put("status", profile.project().statusOptions().get(input.toStatus));
        variables.put("attemptsField", profile.project().attemptsFieldId()); variables.put("attempts", wantedAttempts);
        variables.put("generationField", profile.project().generationFieldId()); variables.put("generation", wantedGeneration);
        variables.put("client", client);
        try { GithubProtocol.graphql(api.graphql(UPDATE, variables)); }
        catch (GithubProtocol.RateLimited limited) { return result("failed", "rate-limited", input,
                input.expectedGeneration, input.expectedAttempts, "RATE_LIMITED", limited.retryAt()); }
        catch (GithubException ambiguous) {
            if (ambiguous.code() != GithubException.Code.TRANSPORT
                    && ambiguous.code() != GithubException.Code.RESPONSE_INVALID) throw ambiguous;
        }
        Snapshot after = snapshot(api, profile, input.itemId);
        if (after.matches(input.toStatus, wantedAttempts, wantedGeneration)) return result("continue", "applied",
                input, wantedGeneration, wantedAttempts, "");
        if (after.generation != input.expectedGeneration || after.attempts != input.expectedAttempts
                || !after.status.equals(input.fromStatus)) return result("conflict", "cas-lost", input,
                after.generation, after.attempts, "CAS_LOST");
        return result("ambiguous", "ambiguous", input, after.generation, after.attempts, "REMOTE_STATE_UNKNOWN");
    }

    private static Snapshot snapshot(GithubApi api, GithubProfile profile, String itemId) {
        Map<String, Object> data = GithubProtocol.graphql(api.graphql(SNAPSHOT, Map.of("item", itemId)));
        Map<String, Object> node = GithubValues.object(data.get("node"));
        if (!itemId.equals(node.get("id")) || !profile.project().projectId().equals(
                GithubValues.object(node.get("project")).get("id"))) throw new GithubException(GithubException.Code.FORBIDDEN);
        Map<String, Object> content = GithubValues.object(node.get("content"));
        if (!(content.get("repository") instanceof Map<?, ?>)) throw new GithubException(GithubException.Code.FORBIDDEN);
        if (GithubValues.number(GithubValues.object(content.get("repository")).get("databaseId"),
                1, Long.MAX_VALUE) != profile.repositoryId()) throw new GithubException(GithubException.Code.FORBIDDEN);
        String status = ""; long attempts = -1; long generation = -1;
        Map<String, Object> fieldValues = GithubValues.object(node.get("fieldValues"));
        Object hasNextPage = GithubValues.object(fieldValues.get("pageInfo")).get("hasNextPage");
        if (!(hasNextPage instanceof Boolean)) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        if ((Boolean) hasNextPage)
            throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        List<Object> values = GithubValues.list(fieldValues.get("nodes"));
        for (Object raw : values) {
            Map<String, Object> value = GithubValues.object(raw);
            Object rawField = value.get("field"); if (rawField == null) continue;
            String field = GithubValues.string(GithubValues.object(rawField).get("id"), 128);
            if (field.equals(profile.project().statusFieldId()) && value.get("optionId") instanceof String option) {
                status = profile.project().statusOptions().entrySet().stream()
                        .filter(entry -> entry.getValue().equals(option)).map(Map.Entry::getKey).findFirst().orElse("");
            } else if (field.equals(profile.project().attemptsFieldId())) {
                attempts = GithubValues.number(value.get("number"), 0, Integer.MAX_VALUE);
            } else if (field.equals(profile.project().generationFieldId())) {
                generation = GithubValues.number(value.get("number"), 0, Long.MAX_VALUE - 1);
            }
        }
        if (status.isEmpty() || attempts < 0 || generation < 0) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        return new Snapshot(status, attempts, generation);
    }

    private static NodeResult result(String outcome, String status, Input input, long generation,
                                     long attempts, String reason) {
        return result(outcome, status, input, generation, attempts, reason, 0);
    }
    private static NodeResult result(String outcome, String status, Input input, long generation,
                                     long attempts, String reason, long retryAt) {
        Map<String, Object> output = new LinkedHashMap<>(); output.put("version", "github.project-transition.result.v1");
        output.put("status", status); output.put("itemId", input.itemId); output.put("fromStatus", input.fromStatus);
        output.put("toStatus", input.toStatus); output.put("generation", generation); output.put("attempts", attempts);
        output.put("remoteId", input.itemId); if (!reason.isEmpty()) output.put("reason", reason);
        if (retryAt > 0) output.put("retryAtEpochMs", retryAt);
        return new NodeResult(outcome, Map.copyOf(output), Map.of());
    }

    private static GithubException sanitize(RuntimeException failure) {
        return failure instanceof GithubException safe ? safe : new GithubException(GithubException.Code.INVALID_INPUT);
    }
    private record Snapshot(String status, long attempts, long generation) {
        boolean matches(String expectedStatus, long expectedAttempts, long expectedGeneration) {
            return status.equals(expectedStatus) && attempts == expectedAttempts && generation == expectedGeneration;
        }
    }
    private record Input(String itemId, String fromStatus, String toStatus, long expectedGeneration,
                         long expectedAttempts, String correlationId) {
        static Input parse(Object raw) {
            Map<String, Object> value = GithubValues.object(raw);
            GithubValues.exact(value, Set.of("version", "itemId", "fromStatus", "toStatus",
                    "expectedGeneration", "expectedAttempts", "correlationId"));
            if (!"github.project-transition.v1".equals(value.get("version"))) throw GithubValues.invalid();
            return new Input(GithubValues.string(value.get("itemId"), 128),
                    GithubValues.string(value.get("fromStatus"), 64), GithubValues.string(value.get("toStatus"), 64),
                    GithubValues.number(value.get("expectedGeneration"), 0, Long.MAX_VALUE - 1),
                    GithubValues.number(value.get("expectedAttempts"), 0, Integer.MAX_VALUE),
                    GithubValues.string(value.get("correlationId"), 128));
        }
        void authorize(GithubProfile.ProjectPolicy policy) {
            if (!policy.statusOptions().containsKey(fromStatus) || !policy.statusOptions().containsKey(toStatus)
                    || !policy.allowedTransitions().contains(transition())) throw new GithubException(GithubException.Code.FORBIDDEN);
        }
        String transition() { return fromStatus + "->" + toStatus; }
        Map<String, Object> canonical() { return Map.of("version", "github.project-transition.v1", "itemId", itemId,
                "fromStatus", fromStatus, "toStatus", toStatus, "expectedGeneration", expectedGeneration,
                "expectedAttempts", expectedAttempts, "correlationId", correlationId); }
    }
}
