package ai.ravenroot.server.embed;

/** Canonical, distinct origin of the sandboxed embedded viewer. */
public record EmbedViewerOrigin(String value) {
    public EmbedViewerOrigin {
        value = EmbedHttpsOrigin.canonical(value, "embed viewer origin");
    }
}
