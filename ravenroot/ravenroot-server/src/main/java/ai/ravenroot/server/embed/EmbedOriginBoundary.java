package ai.ravenroot.server.embed;

import java.util.Objects;

/** Exact, canonical and distinct parent/viewer origins at an embed authority boundary. */
public record EmbedOriginBoundary(EmbedParentOrigin parent, EmbedViewerOrigin viewer) {
    public EmbedOriginBoundary {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(viewer, "viewer");
        if (parent.value().equals(viewer.value())) {
            throw new IllegalArgumentException("embed parent and viewer origins must be distinct");
        }
    }

    public static EmbedOriginBoundary fromAuthority(String parentOrigin, EmbedViewerOrigin viewerOrigin) {
        return new EmbedOriginBoundary(new EmbedParentOrigin(parentOrigin), viewerOrigin);
    }
}
