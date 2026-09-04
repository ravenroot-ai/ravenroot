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
    private static final long MAX_EXACT_INTEGER = 9_007_199_254_740_991L;
    private static final String SNAPSHOT = """
            query($item:ID!){node(id:$item){... on ProjectV2Item{id project{id}
              content{... on PullRequest{id number repository{databaseId}} ... on Issue{id number repository{databaseId}}
                ... on DraftIssue{id}}
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
        if (input.transition().equals(profile.project().claimTransition())
                && input.expectedAttempts == Integer.MAX_VALUE)
            return CompletableFuture.failedFuture(new GithubException(GithubException.Code.INVALID_INPUT));
        long deadline = System.currentTimeMillis() + profile.timeoutMs();
        Map<String, Object> canonical = input.canonical();
        String key = profile.repositoryId() + ":" + GithubValues.keyDigest("project-item", input.itemId);
        try {
            return runtime.submit(message, services, profile, BEHAVIOR, key, canonical, deadline,
                    GithubOperationStore.BeginPolicy.project(input.expectedGeneration, profile.timeoutMs()),
                    (api, operation, control) -> transition(api, profile, input, operation));
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
    }

    private static NodeResult transition(GithubApi api, GithubProfile profile, Input input,
                                         GithubOperationStore.Lease operation) {
        Snapshot before = snapshot(api, profile, input.itemId);
        long wantedAttempts;
        long wantedGeneration;
        try {
            wantedAttempts = input.transition().equals(profile.project().claimTransition())
                    ? Math.addExact(input.expectedAttempts, 1) : input.expectedAttempts;
            wantedGeneration = Math.addExact(input.expectedGeneration, 1);
        } catch (ArithmeticException overflow) { throw new GithubException(GithubException.Code.INVALID_INPUT); }
        if (before.matches(input.toStatus, wantedAttempts, wantedGeneration))
            return finish(api, profile, input, before, wantedGeneration, wantedAttempts, "already-applied");
        String client = GithubValues.sha256(input.itemId + ":" + wantedGeneration + ":" + input.toStatus).substring(0, 32);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("project", profile.project().projectId()); variables.put("item", input.itemId);
        variables.put("statusField", profile.project().statusFieldId());
        variables.put("status", profile.project().statusOptions().get(input.toStatus));
        variables.put("attemptsField", profile.project().attemptsFieldId()); variables.put("attempts", wantedAttempts);
        variables.put("generationField", profile.project().generationFieldId()); variables.put("generation", wantedGeneration);
        variables.put("client", client);
        boolean reconciling = operation.takeover() || "AMBIGUOUS".equals(operation.record().state());
        if (reconciling && before.repairable(input, wantedAttempts))
            return repairPrefix(api, profile, input, variables, before, wantedGeneration, wantedAttempts);
        if (!before.matches(input.fromStatus, input.expectedAttempts, input.expectedGeneration)) return result(
                "conflict", "cas-lost", input, before.generation, before.attempts, "CAS_LOST");
        Dispatch dispatch = update(api, variables);
        if (dispatch.uncertain && !reconciling) {
            Snapshot observed;
            try { observed = safeSnapshot(api, profile, input.itemId); }
            catch (GithubProtocol.RateLimited limited) {
                return result("ambiguous", "ambiguous", input, input.expectedGeneration,
                        input.expectedAttempts, "RATE_LIMITED", limited.retryAt());
            }
            if (observed != null && observed.matches(input.toStatus, wantedAttempts, wantedGeneration))
                return finish(api, profile, input, observed, wantedGeneration, wantedAttempts, "applied");
            return result("ambiguous", "ambiguous", input,
                    observed == null ? input.expectedGeneration : observed.generation,
                    observed == null ? input.expectedAttempts : observed.attempts,
                    dispatch.retryAt > 0 ? "RATE_LIMITED" : "REMOTE_STATE_UNKNOWN", dispatch.retryAt);
        }
        Snapshot after;
        try { after = safeSnapshot(api, profile, input.itemId); }
        catch (GithubProtocol.RateLimited limited) {
            return result("ambiguous", "ambiguous", input, input.expectedGeneration,
                    input.expectedAttempts, "RATE_LIMITED", limited.retryAt());
        }
        if (after == null) return result("ambiguous", "ambiguous", input, input.expectedGeneration,
                input.expectedAttempts, "REMOTE_STATE_UNKNOWN", dispatch.retryAt);
        if (after.matches(input.toStatus, wantedAttempts, wantedGeneration))
            return finish(api, profile, input, after, wantedGeneration, wantedAttempts, "applied");
        if ((dispatch.partial || reconciling) && after.repairable(input, wantedAttempts))
            return repairPrefix(api, profile, input, variables, after, wantedGeneration, wantedAttempts);
        if (after.generation != input.expectedGeneration || after.attempts != input.expectedAttempts
                || !after.status.equals(input.fromStatus)) return result("conflict", "cas-lost", input,
                after.generation, after.attempts, "CAS_LOST");
        return result("ambiguous", "ambiguous", input, after.generation, after.attempts, "REMOTE_STATE_UNKNOWN");
    }

    private static NodeResult repairPrefix(GithubApi api, GithubProfile profile, Input input,
                                           Map<String, Object> variables, Snapshot observed,
                                           long wantedGeneration, long wantedAttempts) {
        Dispatch repair = update(api, variables);
        if (repair.uncertain) return result("ambiguous", "ambiguous", input,
                observed.generation, observed.attempts,
                repair.retryAt > 0 ? "RATE_LIMITED" : "REMOTE_STATE_UNKNOWN", repair.retryAt);
        Snapshot repaired;
        try { repaired = safeSnapshot(api, profile, input.itemId); }
        catch (GithubProtocol.RateLimited limited) {
            return result("ambiguous", "ambiguous", input, observed.generation, observed.attempts,
                    "RATE_LIMITED", limited.retryAt());
        }
        if (repaired == null) return result("ambiguous", "ambiguous", input,
                observed.generation, observed.attempts, "REMOTE_STATE_UNKNOWN");
        if (repaired.matches(input.toStatus, wantedAttempts, wantedGeneration))
            return finish(api, profile, input, repaired, wantedGeneration, wantedAttempts, "applied");
        if (repaired.repairable(input, wantedAttempts)) return result("ambiguous", "ambiguous", input,
                repaired.generation, repaired.attempts, "REMOTE_STATE_UNKNOWN");
        return result("conflict", "cas-lost", input, repaired.generation, repaired.attempts, "CAS_LOST");
    }

    private static Dispatch update(GithubApi api, Map<String, Object> variables) {
        try {
            GithubApi.Response response = api.graphql(UPDATE, variables);
            GithubProtocol.requireSuccess(response);
            Map<String, Object> root = response.object();
            if (root.get("errors") != null) return new Dispatch(false, true, 0);
            GithubValues.object(root.get("data"));
            return new Dispatch(false, false, 0);
        } catch (GithubProtocol.RateLimited limited) {
            return new Dispatch(true, false, limited.retryAt());
        } catch (GithubException failure) {
            if (failure.code() == GithubException.Code.TRANSPORT
                    || failure.code() == GithubException.Code.RESPONSE_INVALID)
                return new Dispatch(true, false, 0);
            throw failure;
        }
    }

    private static Snapshot safeSnapshot(GithubApi api, GithubProfile profile, String itemId) {
        try { return snapshot(api, profile, itemId); }
        catch (GithubException failure) {
            if (failure.code() == GithubException.Code.TRANSPORT
                    || failure.code() == GithubException.Code.RESPONSE_INVALID) return null;
            throw failure;
        }
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
        String subjectId = GithubValues.string(content.get("id"), 128);
        long issueNumber = GithubValues.number(content.get("number"), 1, Integer.MAX_VALUE);
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
                generation = GithubValues.number(value.get("number"), 0, MAX_EXACT_INTEGER);
            }
        }
        if (status.isEmpty() || attempts < 0 || generation < 0) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        return new Snapshot(status, attempts, generation, subjectId, issueNumber);
    }

    private static NodeResult finish(GithubApi api, GithubProfile profile, Input input, Snapshot snapshot,
                                     long generation, long attempts, String status) {
        if (input.comment == null) return result("continue", status, input, generation, attempts, "");
        String marker = "<!-- ravenroot-project-transition:" + GithubValues.sha256(profile.repositoryId() + ":"
                + input.itemId + ":" + snapshot.subjectId + ":" + generation + ":" + input.comment.kind
                + ":" + GithubValues.sha256(input.comment.body)).substring(0, 32) + " -->";
        if (findComment(api, profile, snapshot.issueNumber, marker))
            return result("continue", status, input, generation, attempts, "");
        try {
            Map<String, Object> posted = GithubProtocol.object(api.post(profile.repositoryPath() + "/issues/"
                    + snapshot.issueNumber + "/comments", Map.of("body", input.comment.body + "\n\n" + marker)));
            if (!marker.equals(markerIn(posted)) || !profile.reviewerLogin().equals(commentLogin(posted)))
                return reconcileComment(api, profile, input, snapshot, generation, attempts, status, marker, 0);
        } catch (GithubProtocol.RateLimited limited) {
            return reconcileComment(api, profile, input, snapshot, generation, attempts, status, marker,
                    limited.retryAt());
        } catch (GithubException failure) {
            if (failure.code() == GithubException.Code.TRANSPORT
                    || failure.code() == GithubException.Code.RESPONSE_INVALID)
                return reconcileComment(api, profile, input, snapshot, generation, attempts, status, marker, 0);
            throw failure;
        }
        return result("continue", status, input, generation, attempts, "");
    }

    private static NodeResult reconcileComment(GithubApi api, GithubProfile profile, Input input,
                                                Snapshot snapshot, long generation, long attempts,
                                                String status, String marker, long retryAt) {
        try {
            if (findComment(api, profile, snapshot.issueNumber, marker))
                return result("continue", status, input, generation, attempts, "");
        } catch (GithubProtocol.RateLimited limited) {
            retryAt = Math.max(retryAt, limited.retryAt());
        } catch (RuntimeException ignored) { }
        return result("ambiguous", "ambiguous", input, generation, attempts,
                retryAt > 0 ? "RATE_LIMITED" : "REMOTE_STATE_UNKNOWN", retryAt);
    }

    private static boolean findComment(GithubApi api, GithubProfile profile, long issueNumber, String marker) {
        for (int page = 1; page <= 10; page++) {
            List<Object> comments = GithubProtocol.list(api.get(profile.repositoryPath() + "/issues/" + issueNumber
                    + "/comments?per_page=100&page=" + page));
            for (Object raw : comments) {
                Map<String, Object> comment = GithubValues.object(raw);
                if (profile.reviewerLogin().equals(commentLogin(comment)) && marker.equals(markerIn(comment))) return true;
            }
            if (comments.size() < 100) return false;
        }
        throw new GithubException(GithubException.Code.RESPONSE_INVALID);
    }

    private static String markerIn(Map<String, Object> comment) {
        if (!(comment.get("body") instanceof String body) || body.isBlank() || body.length() > 8_320
                || body.codePoints().anyMatch(character -> character < 0x20 && character != '\n'
                && character != '\t' || character == 0x7f)) throw GithubValues.invalid();
        int start = body.lastIndexOf("<!-- ravenroot-project-transition:");
        return start < 0 ? "" : body.substring(start).strip();
    }
    private static String commentLogin(Map<String, Object> comment) {
        return GithubValues.string(GithubValues.object(comment.get("user")).get("login"), 100);
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
    private record Snapshot(String status, long attempts, long generation, String subjectId, long issueNumber) {
        boolean matches(String expectedStatus, long expectedAttempts, long expectedGeneration) {
            return status.equals(expectedStatus) && attempts == expectedAttempts && generation == expectedGeneration;
        }
        boolean repairable(Input input, long wantedAttempts) {
            return generation == input.expectedGeneration
                    && (status.equals(input.fromStatus) || status.equals(input.toStatus))
                    && (attempts == input.expectedAttempts || attempts == wantedAttempts);
        }
    }
    private record Dispatch(boolean uncertain, boolean partial, long retryAt) { }
    private record Input(String itemId, String fromStatus, String toStatus, long expectedGeneration,
                         long expectedAttempts, String correlationId, TransitionComment comment) {
        static Input parse(Object raw) {
            Map<String, Object> value = GithubValues.object(raw);
            GithubValues.exact(value, Set.of("version", "itemId", "fromStatus", "toStatus",
                    "expectedGeneration", "expectedAttempts", "correlationId", "comment"));
            if (!"github.project-transition.v1".equals(value.get("version"))) throw GithubValues.invalid();
            return new Input(GithubValues.string(value.get("itemId"), 128),
                    GithubValues.string(value.get("fromStatus"), 64), GithubValues.string(value.get("toStatus"), 64),
                    GithubValues.number(value.get("expectedGeneration"), 0, MAX_EXACT_INTEGER - 1),
                    GithubValues.number(value.get("expectedAttempts"), 0, Integer.MAX_VALUE),
                    GithubValues.string(value.get("correlationId"), 128), TransitionComment.parse(value.get("comment")));
        }
        void authorize(GithubProfile.ProjectPolicy policy) {
            if (!policy.statusOptions().containsKey(fromStatus) || !policy.statusOptions().containsKey(toStatus)
                    || !policy.allowedTransitions().contains(transition())) throw new GithubException(GithubException.Code.FORBIDDEN);
        }
        String transition() { return fromStatus + "->" + toStatus; }
        Map<String, Object> canonical() {
            Map<String, Object> value = new LinkedHashMap<>(); value.put("version", "github.project-transition.v1");
            value.put("itemId", itemId); value.put("fromStatus", fromStatus); value.put("toStatus", toStatus);
            value.put("expectedGeneration", expectedGeneration); value.put("expectedAttempts", expectedAttempts);
            value.put("correlationId", correlationId);
            if (comment != null) value.put("comment", Map.of("kind", comment.kind,
                    "bodyDigest", GithubValues.sha256(comment.body)));
            return Map.copyOf(value);
        }
    }
    private record TransitionComment(String kind, String body) {
        private static final Set<String> KINDS = Set.of("claim", "release", "rework", "done", "block");
        static TransitionComment parse(Object raw) {
            if (raw == null) return null;
            Map<String, Object> value = GithubValues.object(raw); GithubValues.exact(value, Set.of("kind", "body"));
            String kind = GithubValues.string(value.get("kind"), 16);
            if (!KINDS.contains(kind)) throw GithubValues.invalid();
            String body = GithubValues.string(value.get("body"), 4_096);
            return new TransitionComment(kind, body);
        }
    }
}
