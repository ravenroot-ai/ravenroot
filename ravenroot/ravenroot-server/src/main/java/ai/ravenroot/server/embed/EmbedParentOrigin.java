package ai.ravenroot.server.embed;

/** Canonical, exact HTTPS origin of the integrating parent allowed to frame the viewer. */
public record EmbedParentOrigin(String value) {
    public EmbedParentOrigin {
        value = EmbedHttpsOrigin.canonical(value, "embed parent origin");
    }
}
