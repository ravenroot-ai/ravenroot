package ai.ravenroot.api.embed;

import java.util.Objects;
import java.util.Optional;

/** Closed initial presentation theme for a distinct-origin embedded viewer session. */
public enum EmbedTheme {
    /** Dark viewer palette, selected when it wins session precedence. */
    DARK("dark"),
    /** Light viewer palette, selected when it wins session precedence. */
    LIGHT("light");

    private final String wireValue;

    EmbedTheme(String wireValue) {
        this.wireValue = wireValue;
    }

/**
 * Returns the closed public wire spelling of this theme.
 * @return lower-case theme token accepted by {@link #fromWire(String)}
 */
    public String wireValue() {
        return wireValue;
    }

/**
 * Parse only the exact public wire values; CSS, URLs and aliases are not theme values.
 * @param value exact lower-case wire token supplied by the trusted session configuration
 * @return matching closed theme member
 */
    public static EmbedTheme fromWire(String value) {
        if (value == null) throw new IllegalArgumentException("unsupported embed theme");
        return switch (value) {
            case "dark" -> DARK;
            case "light" -> LIGHT;
            default -> throw new IllegalArgumentException("unsupported embed theme");
        };
    }

/**
 * Resolve the server-side portion of the precedence before a session is minted.
 * @param explicitTheme deployment-selected theme, which has highest precedence
 * @param savedUserPreference saved user selection used only when no explicit theme exists
 * @return optional server-selected override to embed in the session grant
 */
    public static Optional<EmbedTheme> sessionOverride(EmbedTheme explicitTheme,
                                                        EmbedTheme savedUserPreference) {
        return Optional.ofNullable(explicitTheme != null ? explicitTheme : savedUserPreference);
    }

/**
 * Pure complete precedence used by compositions and contract tests.
 * @param explicitTheme deployment-selected theme, which has highest precedence
 * @param savedUserPreference saved user selection used when no explicit override exists
 * @param systemPrefersDark client media preference; {@code null} means unavailable
 * @param productDefault required fallback when no higher-precedence preference exists
 * @return final deterministic theme selected by the precedence rules
 */
    public static EmbedTheme resolve(EmbedTheme explicitTheme, EmbedTheme savedUserPreference,
                                     Boolean systemPrefersDark, EmbedTheme productDefault) {
        return sessionOverride(explicitTheme, savedUserPreference)
                .orElseGet(() -> systemPrefersDark == null
                        ? Objects.requireNonNull(productDefault, "productDefault")
                        : (systemPrefersDark ? DARK : LIGHT));
    }
}
