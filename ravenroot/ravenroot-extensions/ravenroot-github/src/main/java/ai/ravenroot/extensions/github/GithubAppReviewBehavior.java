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

/** GitHub App installation review bound to one supplied commit with stale and ambiguous reconciliation. */
public final class GithubAppReviewBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "github-app-review";
    private final GithubRuntime runtime;
    GithubAppReviewBehavior(GithubRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return GithubBehaviorDescriptors.descriptor(BEHAVIOR,
            "Submit GitHub App review", "Submits one content-bound formal review only after an exact-head check.",
            false, true, true); }
    @Override public NodeAction create(NodeConfiguration configuration) { return create(configuration, NodePackageServices.unavailable()); }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = GithubBehaviorDescriptors.profile(configuration);
        return message -> invoke(message, services, profileName);
    }

    private CompletionStage<NodeResult> invoke(NodeMessage message, NodePackageServices services, String profileName) {
        final Input input; final GithubProfile profile;
        try { input = Input.parse(message.payload()); profile = runtime.requireProfile(message.tenantId(), profileName); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
        long deadline = System.currentTimeMillis() + profile.timeoutMs();
        String key = input.pullNumber + ":" + input.commit + ":"
                + GithubValues.keyDigest("review-correlation", input.correlationId);
        try { return runtime.submit(message, services, profile, BEHAVIOR, key, input.canonical(), deadline,
                GithubOperationStore.BeginPolicy.forAmbiguousReconciliation(profile.timeoutMs()),
                (api, operation, control) -> review(api, profile, input, operation)); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure)); }
    }

    private static NodeResult review(GithubApi api, GithubProfile profile, Input input,
                                     GithubOperationStore.Lease operation) {
        attestRepository(api, profile);
        String marker = "<!-- ravenroot-review:" + GithubValues.sha256(input.correlationId + ":" + input.commit
                + ":" + input.verdict + ":" + GithubValues.sha256(input.body)).substring(0, 32) + " -->";
        Existing existing = findExisting(api, profile, input, marker);
        if (existing != null) {
            String currentHead = head(api, profile, input.pullNumber);
            if (!existing.commit.equals(currentHead)) {
                if ("PENDING".equals(existing.state)) {
                    try { GithubProtocol.requireSuccess(api.delete(path(profile, input.pullNumber, "/reviews/" + existing.id))); }
                    catch (RuntimeException ignored) { }
                }
                return result("stale", "stale", input, existing.id, "STALE_HEAD");
            }
            if ("PENDING".equals(existing.state)) return submitPending(api, profile, input, existing.id, marker);
            if (expectedState(input.verdict).equals(existing.state)) return result("continue", "already-recorded",
                    input, existing.id, "");
            return result("conflict", "conflict", input, existing.id, "REVIEW_STATE_CONFLICT");
        }
        if (!input.commit.equals(head(api, profile, input.pullNumber))) return result("stale", "stale", input, 0, "STALE_HEAD");
        final Map<String, Object> draft;
        try {
            GithubApi.Response drafted = api.post(path(profile, input.pullNumber, "/reviews"), Map.of(
                    "commit_id", input.commit, "body", input.body + "\n\n" + marker));
            draft = GithubProtocol.object(drafted);
        } catch (GithubProtocol.RateLimited limited) {
            return reconcileDraftUncertainty(api, profile, input, marker, limited.retryAt());
        }
        catch (GithubException failure) {
            if (failure.code() == GithubException.Code.TRANSPORT
                    || failure.code() == GithubException.Code.RESPONSE_INVALID)
                return reconcileDraftUncertainty(api, profile, input, marker, 0);
            throw failure;
        }
        final long reviewId;
        try {
            reviewId = GithubValues.number(draft.get("id"), 1, Long.MAX_VALUE);
            if (!"PENDING".equals(draft.get("state")) || !input.commit.equals(draft.get("commit_id"))
                    || !profile.reviewerLogin().equals(login(draft))) throw GithubValues.invalid();
        } catch (RuntimeException invalid) {
            return reconcileDraftUncertainty(api, profile, input, marker, 0);
        }
        final boolean draftHeadCurrent;
        try { draftHeadCurrent = input.commit.equals(head(api, profile, input.pullNumber)); }
        catch (GithubProtocol.RateLimited limited) {
            return result("ambiguous", "ambiguous", input, reviewId, "RATE_LIMITED", limited.retryAt());
        }
        if (!draftHeadCurrent) {
            try { GithubProtocol.requireSuccess(api.delete(path(profile, input.pullNumber, "/reviews/" + reviewId))); }
            catch (RuntimeException ignored) { }
            return result("stale", "stale", input, reviewId, "STALE_HEAD");
        }
        return submitPending(api, profile, input, reviewId, marker);
    }

    private static NodeResult submitPending(GithubApi api, GithubProfile profile, Input input, long reviewId,
                                            String marker) {
        final Map<String, Object> submittedReview;
        try {
            GithubApi.Response submitted = api.post(path(profile, input.pullNumber, "/reviews/" + reviewId + "/events"),
                    Map.of("event", input.verdict));
            submittedReview = GithubProtocol.object(submitted);
        }
        catch (GithubProtocol.RateLimited limited) {
            return reconcileSubmitUncertainty(api, profile, input, reviewId, marker, limited.retryAt());
        } catch (GithubException failure) {
            if (failure.code() == GithubException.Code.TRANSPORT
                    || failure.code() == GithubException.Code.RESPONSE_INVALID)
                return reconcileSubmitUncertainty(api, profile, input, reviewId, marker, 0);
            throw failure;
        }
        try {
            if (GithubValues.number(submittedReview.get("id"), 1, Long.MAX_VALUE) != reviewId
                    || !input.commit.equals(submittedReview.get("commit_id"))
                    || !profile.reviewerLogin().equals(login(submittedReview))
                    || !expectedState(input.verdict).equals(submittedReview.get("state")))
                throw GithubValues.invalid();
        } catch (RuntimeException invalid) {
            return reconcileSubmitUncertainty(api, profile, input, reviewId, marker, 0);
        }
        final String currentHead;
        try { currentHead = head(api, profile, input.pullNumber); }
        catch (GithubProtocol.RateLimited limited) {
            return result("ambiguous", "ambiguous", input, reviewId, "RATE_LIMITED", limited.retryAt());
        }
        if (!input.commit.equals(currentHead)) return result("stale", "stale", input, reviewId, "STALE_HEAD");
        return result("continue", "submitted", input, reviewId, "");
    }

    private static NodeResult reconcileDraftUncertainty(GithubApi api, GithubProfile profile, Input input,
                                                         String marker, long retryAt) {
        try {
            Existing existing = findExisting(api, profile, input, marker);
            if (existing != null) {
                if ("PENDING".equals(existing.state)) {
                    if (!input.commit.equals(head(api, profile, input.pullNumber)))
                        return result("stale", "stale", input, existing.id, "STALE_HEAD");
                    return submitPending(api, profile, input, existing.id, marker);
                }
                if (!expectedState(input.verdict).equals(existing.state))
                    return result("conflict", "conflict", input, existing.id, "REVIEW_STATE_CONFLICT");
                boolean current = input.commit.equals(head(api, profile, input.pullNumber));
                return result(current ? "continue" : "stale", current ? "already-recorded" : "stale", input,
                        existing.id, current ? "" : "STALE_HEAD");
            }
        } catch (GithubProtocol.RateLimited limited) {
            retryAt = Math.max(retryAt, limited.retryAt());
        } catch (RuntimeException ignored) { }
        return result("ambiguous", "ambiguous", input, 0,
                retryAt > 0 ? "RATE_LIMITED" : "REMOTE_STATE_UNKNOWN", retryAt);
    }

    private static NodeResult reconcileSubmitUncertainty(GithubApi api, GithubProfile profile, Input input,
                                                          long reviewId, String marker, long retryAt) {
        try {
            Existing existing = findExisting(api, profile, input, marker);
            if (existing != null && !"PENDING".equals(existing.state)) {
                if (!expectedState(input.verdict).equals(existing.state))
                    return result("conflict", "conflict", input, existing.id, "REVIEW_STATE_CONFLICT");
                boolean current = input.commit.equals(head(api, profile, input.pullNumber));
                return result(current ? "continue" : "stale", current ? "submitted" : "stale", input,
                        existing.id, current ? "" : "STALE_HEAD");
            }
        } catch (GithubProtocol.RateLimited limited) {
            retryAt = Math.max(retryAt, limited.retryAt());
        } catch (RuntimeException ignored) { }
        return result("ambiguous", "ambiguous", input, reviewId,
                retryAt > 0 ? "RATE_LIMITED" : "REMOTE_STATE_UNKNOWN", retryAt);
    }

    private static Existing findExisting(GithubApi api, GithubProfile profile, Input input, String marker) {
        for (int page = 1; page <= 10; page++) {
            List<Object> reviews = GithubProtocol.list(api.get(path(profile, input.pullNumber,
                    "/reviews?per_page=100&page=" + page)));
            for (Object raw : reviews) {
                Map<String, Object> review = GithubValues.object(raw);
                if (!profile.reviewerLogin().equals(login(review)) || !input.commit.equals(review.get("commit_id"))) continue;
                Object body = review.get("body"); if (!(body instanceof String text) || !text.contains(marker)) continue;
                String state = GithubValues.string(review.get("state"), 64);
                return new Existing(GithubValues.number(review.get("id"), 1, Long.MAX_VALUE), input.commit, state);
            }
            if (reviews.size() < 100) return null;
        }
        throw new GithubException(GithubException.Code.RESPONSE_INVALID);
    }

    private static String head(GithubApi api, GithubProfile profile, long pull) {
        Map<String, Object> pr = GithubProtocol.object(api.get(profile.repositoryPath() + "/pulls/" + pull));
        Map<String, Object> baseRepo = GithubValues.object(GithubValues.object(pr.get("base")).get("repo"));
        if (GithubValues.number(baseRepo.get("id"), 1, Long.MAX_VALUE) != profile.repositoryId())
            throw new GithubException(GithubException.Code.FORBIDDEN);
        return GithubValues.string(GithubValues.object(pr.get("head")).get("sha"), 40);
    }

    private static String login(Map<String, Object> review) {
        return GithubValues.string(GithubValues.object(review.get("user")).get("login"), 100);
    }
    private static void attestRepository(GithubApi api, GithubProfile profile) {
        Map<String, Object> repository = GithubProtocol.object(api.get("/repositories/" + profile.repositoryId()));
        if (GithubValues.number(repository.get("id"), 1, Long.MAX_VALUE) != profile.repositoryId()
                || !(profile.owner() + "/" + profile.repository()).equalsIgnoreCase(
                        GithubValues.string(repository.get("full_name"), 201)))
            throw new GithubException(GithubException.Code.FORBIDDEN);
    }
    private static String expectedState(String verdict) {
        return switch (verdict) {
            case "APPROVE" -> "APPROVED";
            case "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
            case "COMMENT" -> "COMMENTED";
            default -> throw GithubValues.invalid();
        };
    }
    private static String path(GithubProfile profile, long pull, String suffix) {
        return profile.repositoryPath() + "/pulls/" + pull + suffix;
    }
    private static NodeResult result(String outcome, String status, Input input, long reviewId, String reason) {
        return result(outcome, status, input, reviewId, reason, 0);
    }
    private static NodeResult result(String outcome, String status, Input input, long reviewId, String reason, long retryAt) {
        Map<String, Object> output = new LinkedHashMap<>(); output.put("version", "github.app-review.result.v1");
        output.put("status", status); output.put("pullNumber", input.pullNumber); output.put("commit", input.commit);
        output.put("verdict", input.verdict); output.put("remoteId", reviewId == 0 ? "" : Long.toString(reviewId));
        output.put("generation", 0L); output.put("attempts", 0L); if (!reason.isEmpty()) output.put("reason", reason);
        if (retryAt > 0) output.put("retryAtEpochMs", retryAt);
        return new NodeResult(outcome, Map.copyOf(output), Map.of());
    }
    private static GithubException sanitize(RuntimeException failure) {
        return failure instanceof GithubException safe ? safe : new GithubException(GithubException.Code.INVALID_INPUT);
    }
    private record Existing(long id, String commit, String state) { }
    private record Input(long pullNumber, String commit, String verdict, String body, String correlationId) {
        static Input parse(Object raw) {
            Map<String, Object> value = GithubValues.object(raw);
            GithubValues.exact(value, Set.of("version", "pullNumber", "commit", "verdict", "body", "correlationId"));
            if (!"github.app-review.v1".equals(value.get("version"))) throw GithubValues.invalid();
            String commit = GithubValues.string(value.get("commit"), 40);
            if (!commit.matches("[0-9a-f]{40}")) throw GithubValues.invalid();
            String verdict = GithubValues.string(value.get("verdict"), 32);
            if (!Set.of("APPROVE", "REQUEST_CHANGES", "COMMENT").contains(verdict)) throw GithubValues.invalid();
            String body = text(value.get("body"));
            if ((verdict.equals("REQUEST_CHANGES") || verdict.equals("COMMENT")) && body.isBlank()) throw GithubValues.invalid();
            return new Input(GithubValues.number(value.get("pullNumber"), 1, Integer.MAX_VALUE), commit,
                    verdict, body, GithubValues.string(value.get("correlationId"), 128));
        }
        private static String text(Object value) {
            if (!(value instanceof String text) || text.length() > 8_192
                    || text.codePoints().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t' || c == 0x7f)) throw GithubValues.invalid();
            return text;
        }
        Map<String, Object> canonical() { return Map.of("version", "github.app-review.v1", "pullNumber", pullNumber,
                "commit", commit, "verdict", verdict, "bodyDigest", GithubValues.sha256(body),
                "correlationId", correlationId); }
    }
}
