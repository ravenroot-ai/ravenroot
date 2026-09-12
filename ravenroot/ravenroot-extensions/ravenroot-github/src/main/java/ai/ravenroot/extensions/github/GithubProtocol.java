package ai.ravenroot.extensions.github;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Published GitHub HTTP constants; these are wire vocabulary rather than operator settings. */
final class GithubProtocol {
    static final String API_VERSION = "2022-11-28";
    static final String ACCEPT = "accept";
    static final String CONTENT_TYPE = "content-type";
    static final String API_VERSION_HEADER = "x-github-api-version";
    static final String USER_AGENT = "user-agent";
    static final String USER_AGENT_VALUE = "ravenroot-github/1";
    static final String JSON = "application/json";
    static final String GITHUB_JSON = "application/vnd.github+json";
    static final String SIGNATURE = "x-hub-signature-256";
    static final String DELIVERY = "x-github-delivery";
    static final String EVENT = "x-github-event";
    static final Set<String> WEBHOOK_HEADERS = Set.of(SIGNATURE, DELIVERY, EVENT);

    private GithubProtocol() { }

    static Map<String, Object> object(GithubApi.Response response) {
        requireSuccess(response); return response.object();
    }

    static List<Object> list(GithubApi.Response response) {
        requireSuccess(response); return GithubValues.list(response.value());
    }

    static Map<String, Object> graphql(GithubApi.Response response) {
        Map<String, Object> root = object(response);
        if (root.get("errors") != null) throw new GithubException(GithubException.Code.RESPONSE_INVALID);
        return GithubValues.object(root.get("data"));
    }

    static void requireSuccess(GithubApi.Response response) {
        if (response.rateLimited()) throw new RateLimited(response.retryAfterEpochMs());
        if (response.status() == 401) throw new GithubException(GithubException.Code.AUTHENTICATION_FAILED);
        if (response.status() == 403 || response.status() == 404) throw new GithubException(GithubException.Code.FORBIDDEN);
        if (response.status() < 200 || response.status() >= 300) throw new GithubException(
                response.status() >= 500 ? GithubException.Code.TRANSPORT : GithubException.Code.RESPONSE_INVALID);
    }

    static final class RateLimited extends GithubException {
        private final long retryAt;
        RateLimited(long retryAt) { super(Code.RATE_LIMITED); this.retryAt = retryAt; }
        long retryAt() { return retryAt; }
    }
}
