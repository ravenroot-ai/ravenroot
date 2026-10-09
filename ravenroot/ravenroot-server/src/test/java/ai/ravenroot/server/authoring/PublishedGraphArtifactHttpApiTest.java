package ai.ravenroot.server.authoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishedGraphArtifactHttpApiTest {
    @Test
    void acceptsOnlyCanonicalExactlyRepresentableReleaseVersions() {
        assertEquals(1L, PublishedGraphArtifactHttpApi.parseReleaseVersion("1"));
        assertEquals(9_007_199_254_740_991L,
                PublishedGraphArtifactHttpApi.parseReleaseVersion("9007199254740991"));

        for (String rejected : new String[]{"0", "01", "+1", "-1", "1.0", "1e0",
                "9007199254740992", "9223372036854775808"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PublishedGraphArtifactHttpApi.parseReleaseVersion(rejected), rejected);
        }
    }
}
