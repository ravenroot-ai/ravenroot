package ai.ravenroot.api.embed;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbedThemeTest {
    @Test
    void acceptsOnlyTheExactClosedWireEnum() {
        assertEquals(EmbedTheme.DARK, EmbedTheme.fromWire("dark"));
        assertEquals(EmbedTheme.LIGHT, EmbedTheme.fromWire("light"));
        for (String invalid : new String[]{"Dark", "LIGHT", "auto", "dark;body{}", "https://theme", ""}) {
            assertThrows(IllegalArgumentException.class, () -> EmbedTheme.fromWire(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> EmbedTheme.fromWire(null));
    }

    @Test
    void resolvesExplicitThenSavedThenSystemThenProductDefault() {
        assertEquals(EmbedTheme.LIGHT,
                EmbedTheme.resolve(EmbedTheme.LIGHT, EmbedTheme.DARK, true, EmbedTheme.DARK));
        assertEquals(EmbedTheme.DARK,
                EmbedTheme.resolve(null, EmbedTheme.DARK, false, EmbedTheme.LIGHT));
        assertEquals(EmbedTheme.DARK,
                EmbedTheme.resolve(null, null, true, EmbedTheme.LIGHT));
        assertEquals(EmbedTheme.LIGHT,
                EmbedTheme.resolve(null, null, false, EmbedTheme.DARK));
        assertEquals(EmbedTheme.DARK,
                EmbedTheme.resolve(null, null, null, EmbedTheme.DARK));
    }

    @Test
    void grantCarriesOnlyTheResolvedOptionalOverride() {
        var graph = new VerifiedEmbedGraphGrant("tenant", "resource", "deployment", 1,
                "graph", "version", "digest", "policy");
        var grant = new VerifiedEmbedSessionGrant("registration", 1, "issuer", "subject", "tenant",
                "https://parent.example", Set.of(EmbedCapability.GRAPH_READ), graph,
                EmbedTheme.sessionOverride(null, EmbedTheme.LIGHT));

        assertEquals(Optional.of(EmbedTheme.LIGHT), grant.themeOverride());
    }
}
