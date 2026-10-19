package ai.ravenroot.server.embed;

import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded, non-authoritative locale configuration carried by one embed launch ticket. */
public record EmbedPresentation(String locale, String catalogJson) {
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{1,8})*");
    /** Maximum decoded Java/JavaScript UTF-16 code units, not UTF-8 bytes. */
    public static final int MAX_CATALOG_CHARACTERS = 48 * 1024;

    public EmbedPresentation {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(catalogJson, "catalogJson");
        if (!LOCALE.matcher(locale).matches() || locale.length() > 64) {
            throw new IllegalArgumentException("invalid embed locale");
        }
        locale = locale.replace('_', '-');
        String stripped = catalogJson.strip();
        if (catalogJson.length() > MAX_CATALOG_CHARACTERS
                || (!catalogJson.isEmpty() && !(stripped.startsWith("{") && stripped.endsWith("}")))) {
            throw new IllegalArgumentException("invalid embed catalog envelope");
        }
    }

    public static EmbedPresentation english() { return new EmbedPresentation("en", ""); }
}
