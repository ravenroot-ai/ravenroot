package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;

import java.util.List;
import java.util.regex.Pattern;

final class BearerToken {
    private static final int MAX_AUTHORIZATION_LENGTH = 16_391;
    private static final Pattern VALUE = Pattern.compile("(?i)^Bearer ([A-Za-z0-9_~+/.=-]+)$");

    private BearerToken() {
    }

    static String extract(Headers headers) throws AuthenticationException {
        List<String> values = headers.get("Authorization");
        if (values == null || values.size() != 1) {
            throw new AuthenticationException("missing or repeated authorization header");
        }
        String value = values.getFirst();
        if (value == null || value.length() > MAX_AUTHORIZATION_LENGTH || value.indexOf(',') >= 0) {
            throw new AuthenticationException("invalid authorization header");
        }
        var matcher = VALUE.matcher(value);
        if (!matcher.matches()) {
            throw new AuthenticationException("authorization scheme is not an unambiguous Bearer value");
        }
        return matcher.group(1);
    }
}
