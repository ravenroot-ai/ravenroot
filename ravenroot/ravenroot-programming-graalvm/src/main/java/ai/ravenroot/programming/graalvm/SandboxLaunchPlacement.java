package ai.ravenroot.programming.graalvm;

import java.nio.charset.StandardCharsets;

/** Deployment filesystem placement, deliberately excluded from the semantic sandbox policy. */
public record SandboxLaunchPlacement(String resourceCachePropertyValue) {
    public static final SandboxLaunchPlacement LEGACY = new SandboxLaunchPlacement(null);
    // A fixed launch-argument safety bound, not an execution budget or operator setting.
    private static final int MAX_PROPERTY_BYTES = 16 * 1024;

    public SandboxLaunchPlacement {
        if (resourceCachePropertyValue != null && (resourceCachePropertyValue.indexOf('\0') >= 0
                || resourceCachePropertyValue.getBytes(StandardCharsets.UTF_8).length > MAX_PROPERTY_BYTES)) {
            throw new IllegalArgumentException("Invalid Graal resource-cache property value");
        }
    }

    /** Present-empty preserves the standard Graal property's presence semantics. */
    public boolean hasOverride() { return resourceCachePropertyValue != null; }
}
