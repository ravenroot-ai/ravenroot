package ai.ravenroot.server.embed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbedHttpsOriginTest {
    @Test
    void parentOriginIsOneExactCanonicalHttpsOrigin() {
        assertEquals("https://parent.example", new EmbedParentOrigin("https://parent.example").value());
        assertEquals("https://parent.example:8443",
                new EmbedParentOrigin("https://parent.example:8443").value());

        for (String invalid : invalidOrigins()) {
            assertThrows(IllegalArgumentException.class, () -> new EmbedParentOrigin(invalid), invalid);
        }
    }

    @Test
    void viewerOriginUsesTheSameCanonicalGrammar() {
        assertEquals("https://viewer.example", new EmbedViewerOrigin("https://viewer.example").value());
        for (String invalid : invalidOrigins()) {
            assertThrows(IllegalArgumentException.class, () -> new EmbedViewerOrigin(invalid), invalid);
        }
    }

    @Test
    void parentAndViewerMustBeDistinctAtEveryAuthorityBoundary() {
        var viewer = new EmbedViewerOrigin("https://viewer.example");
        assertEquals("https://parent.example",
                EmbedOriginBoundary.fromAuthority("https://parent.example", viewer).parent().value());
        assertThrows(IllegalArgumentException.class,
                () -> EmbedOriginBoundary.fromAuthority("https://viewer.example", viewer));
    }

    private static List<String> invalidOrigins() {
        return List.of(
                "*",
                "https://parent.example;frame-ancestors *",
                "https://parent.example/path",
                "https://user@parent.example",
                "HTTPS://parent.example",
                "https://Parent.example",
                "https://parent.example:443",
                "https://parent.example\r\nContent-Security-Policy: frame-ancestors *");
    }
}
