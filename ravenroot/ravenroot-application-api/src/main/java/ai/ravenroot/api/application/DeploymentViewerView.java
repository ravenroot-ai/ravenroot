package ai.ravenroot.api.application;

import ai.ravenroot.api.embed.EmbedGraphProjection;

import java.util.Objects;

/**
 * Browser-safe, immutable view of one process-local deployment incarnation.
 *
 * <p>The source tuple is the observation authority. A deployment id by itself is deliberately not
 * enough: undeploying and registering the same bytes again creates a different incarnation, so an
 * existing viewer can report replacement rather than silently following an ABA swap.</p>
 * @param viewerSourceVersion version of the deployment-view source contract
 * @param source immutable logical and physical source identity
 * @param lifecycle lifecycle observed for the captured source
 * @param canonicalDigest digest binding the captured immutable graph definition
 * @param projection closed browser-safe graph projection
 */
public record DeploymentViewerView(String viewerSourceVersion, Source source,
                                   LocalDeploymentState lifecycle, String canonicalDigest,
                                   EmbedGraphProjection projection) {
    /** Current version of the deployment-view source contract. */
    public static final String CURRENT_SOURCE_VERSION = "1";

    /** Validates that the immutable source identity and projection coordinates agree. */
    public DeploymentViewerView {
        viewerSourceVersion = requireText(viewerSourceVersion, "viewerSourceVersion");
        if (!CURRENT_SOURCE_VERSION.equals(viewerSourceVersion)) {
            throw new IllegalArgumentException("unsupported viewer source version");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lifecycle, "lifecycle");
        canonicalDigest = requireText(canonicalDigest, "canonicalDigest");
        Objects.requireNonNull(projection, "projection");
        if (!source.deploymentId().equals(projection.graphId())
                || !source.graphVersion().equals(projection.graphVersionId())
                || !canonicalDigest.equals(projection.canonicalDigest())) {
            throw new IllegalArgumentException("deployment source and projection identity must match");
        }
    }

    /**
     * Exact logical and physical deployment identity captured by a viewer read.
     * @param kind fixed {@code deployment} source discriminator
     * @param deploymentId tenant-scoped logical deployment identifier
     * @param incarnationId unique physical registration incarnation
     * @param graphVersion immutable graph version bound to the incarnation
     */
    public record Source(String kind, String deploymentId, String incarnationId, String graphVersion) {
        /** Validates the closed source discriminator and immutable identity tuple. */
        public Source {
            kind = requireText(kind, "source.kind");
            if (!"deployment".equals(kind)) throw new IllegalArgumentException("source.kind must be deployment");
            deploymentId = requireText(deploymentId, "source.deploymentId");
            incarnationId = requireText(incarnationId, "source.incarnationId");
            graphVersion = requireText(graphVersion, "source.graphVersion");
        }

        /**
         * Creates the source tuple for a live deployment.
         * @param deploymentId tenant-scoped logical deployment identifier
         * @param incarnationId unique physical registration incarnation
         * @param graphVersion immutable graph version bound to the incarnation
         * @return validated deployment source tuple
         */
        public static Source deployment(String deploymentId, String incarnationId, String graphVersion) {
            return new Source("deployment", deploymentId, incarnationId, graphVersion);
        }
    }

    /**
     * Closed allowlisted JSON; raw GraphML and graph property maps have no representation here.
     * @return serialized browser-safe deployment view
     */
    public String toJson() {
        return "{\"viewerSourceVersion\":\"" + escape(viewerSourceVersion)
                + "\",\"source\":{\"kind\":\"deployment\",\"deploymentId\":\""
                + escape(source.deploymentId()) + "\",\"incarnationId\":\""
                + escape(source.incarnationId()) + "\",\"graphVersion\":\""
                + escape(source.graphVersion()) + "\"},\"lifecycle\":\"" + lifecycle
                + "\",\"canonicalDigest\":\"" + escape(canonicalDigest)
                + "\",\"projection\":" + projection.toJson() + "}";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }
}
