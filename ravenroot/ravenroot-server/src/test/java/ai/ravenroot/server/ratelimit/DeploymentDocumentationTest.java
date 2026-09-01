package ai.ravenroot.server.ratelimit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator documentation for these limits, checked mechanically.
 *
 * <p>Two things require automated verification. A cross-reference to a section that does
 * not exist is worse than no cross-reference: it tells the reader the caveat was written and sends
 * them looking for it. And the numbers in the table are the ones an operator will plan capacity
 * against, so they must be the numbers that actually ship rather than the ones that shipped when the
 * paragraph was written.</p>
 */
class DeploymentDocumentationTest {
    private static final Path DEPLOYMENT = Path.of("src/test/resources/documentation/deployment.md");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{2,4} (.+)$");
    private static final Pattern INTERNAL_LINK = Pattern.compile("\\]\\(#([a-z0-9-]+)\\)");

    @Test
    void everyInternalCrossReferenceResolvesToASectionThatExists() throws IOException {
        String document = read();
        List<String> anchors = new ArrayList<>();
        Matcher headings = HEADING.matcher(document);
        while (headings.find()) {
            anchors.add(anchor(headings.group(1)));
        }

        var dangling = new ArrayList<String>();
        Matcher links = INTERNAL_LINK.matcher(document);
        while (links.find()) {
            if (!anchors.contains(links.group(1))) {
                dangling.add(links.group(1));
            }
        }

        assertEquals(List.of(), dangling,
                "deployment.md points operators at sections that do not exist; known anchors are " + anchors);
    }

    /** The caveat the limits table sends operators to must be present and must say the honest thing. */
    @Test
    void theResidualRiskSectionExistsAndNamesTheGlobalCeiling() throws IOException {
        String document = read();

        assertTrue(document.contains("### Residual risks (rate limits and quotas)"),
                "the residual-risk section referenced by the limits table is missing");
        int section = document.indexOf("### Residual risks (rate limits and quotas)");
        String body = document.substring(section,
                document.indexOf("## Public exposure checklist", section));

        assertTrue(body.contains("global"), "the residual-risk section does not say the ceiling is global");
        assertTrue(body.contains("ACTIVE_EXECUTION_CEILING_REACHED"),
                "the residual-risk section does not name the refusal an operator will actually see");
        assertTrue(body.contains("MAX_TRACKED_TENANTS"),
                "the tenant registry cliff is not described where an operator would look for it");
    }

    /** The public-exposure checklist must carry the consequence of the default proxy configuration. */
    @Test
    void thePublicExposureChecklistNamesTheTrustedProxyVariables() throws IOException {
        String document = read();
        String checklist = document.substring(
                document.indexOf("## Public exposure checklist"));

        assertTrue(checklist.contains("RAVENROOT_TRUSTED_PROXY_HOPS"), checklist);
        assertTrue(checklist.contains("RAVENROOT_TRUSTED_PROXY_ADDRESSES"), checklist);
        assertTrue(checklist.contains("single bucket"),
                "the checklist names the variables but not the consequence of leaving them unset");
    }

    /** Numbers an operator plans capacity against must be the numbers the code ships with. */
    @Test
    void theDocumentedDefaultsAreTheDefaultsThatShip() throws IOException {
        String document = read();
        var defaults = RateLimitConfiguration.DEFAULTS;

        assertEquals(64, defaults.globalActiveExecutions());
        assertEquals(3600, defaults.executionMaxAge().toSeconds());
        assertTrue(document.contains("`RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS` | `3600`"),
                "the execution age-out is not documented at the value that ships");
        assertTrue(document.contains("ceiling `64`, reserve `8`, per-tenant quota `56`"),
                "the documented per-tenant execution share is not the one the configuration derives");
        assertEquals(8, defaults.reservedExecutionHeadroom());
        assertEquals(56, defaults.tenantActiveExecutions());
    }

    private static String read() throws IOException {
        assertTrue(Files.isRegularFile(DEPLOYMENT),
                "deployment documentation not found at " + DEPLOYMENT.toAbsolutePath());
        return Files.readString(DEPLOYMENT, StandardCharsets.UTF_8);
    }

    /** GitHub's heading-anchor rule, restricted to what these headings actually use. */
    private static String anchor(String heading) {
        var slug = new StringBuilder();
        for (char character : heading.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                slug.append(character);
            } else if (character == ' ' || character == '-') {
                slug.append('-');
            }
        }
        return slug.toString();
    }
}
