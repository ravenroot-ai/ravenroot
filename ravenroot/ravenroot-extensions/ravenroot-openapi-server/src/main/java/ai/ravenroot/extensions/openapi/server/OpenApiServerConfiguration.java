package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Package-wide authority plus immutable profiles; all graph choices only attenuate these values. */
public record OpenApiServerConfiguration(
        IngressAuthorityDeclaration authority,
        IngressRequestProjectionPolicy projection,
        Map<String, OpenApiServerProfile> profiles) {

    public static final String PACKAGE_ID = "ai.ravenroot.extensions.openapi.server";

    public OpenApiServerConfiguration {
        authority = Objects.requireNonNull(authority, "authority");
        projection = Objects.requireNonNull(projection, "projection");
        if (!PACKAGE_ID.equals(authority.packageId()) || !PACKAGE_ID.equals(projection.packageId())) throw invalid();
        profiles = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(profiles, "profiles")));
        if (profiles.isEmpty() || profiles.size() > authority.maxRoutes()) throw invalid();
        String idempotencyHeader = null;
        java.util.List<String> routeBases = new java.util.ArrayList<>();
        for (Map.Entry<String, OpenApiServerProfile> entry : profiles.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().name())) throw invalid();
            OpenApiServerProfile profile = entry.getValue();
            if (profile.maxRequestBytes() > authority.maxRequestBytes()
                    || profile.deadlineMs() > authority.requestTimeout().toMillis()
                    || profile.maxConcurrency() > authority.maxConcurrentRequests()
                    || (long) profile.maxIdempotencyBytes() * 2 + 128 > authority.maxResponseBytes()
                    || !projection.allowedHeaders().contains(profile.idempotencyHeader())
                    || !projection.allowedHeaders().contains("content-type")) throw invalid();
            if (idempotencyHeader == null) idempotencyHeader = profile.idempotencyHeader();
            else if (!idempotencyHeader.equals(profile.idempotencyHeader())) throw invalid();
            for (String existing : routeBases) {
                if (existing.equals(profile.routeBase()) || existing.startsWith(profile.routeBase() + "/")
                        || profile.routeBase().startsWith(existing + "/")) throw invalid();
            }
            routeBases.add(profile.routeBase());
        }
        if (!Objects.equals(idempotencyHeader, projection.idempotencyHeader())) throw invalid();
    }

    OpenApiServerProfile requireProfile(String name) {
        OpenApiServerProfile profile = profiles.get(name);
        if (profile == null) throw new OpenApiServerException(OpenApiServerException.Code.PROFILE_UNKNOWN);
        return profile;
    }

    private static OpenApiServerException invalid() { return OpenApiValues.invalid(); }
}
