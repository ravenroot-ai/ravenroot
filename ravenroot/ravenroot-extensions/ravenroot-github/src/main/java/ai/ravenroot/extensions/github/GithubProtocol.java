package ai.ravenroot.extensions.github;

import java.util.List;
import java.util.Map;

final class GithubProtocol {
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
