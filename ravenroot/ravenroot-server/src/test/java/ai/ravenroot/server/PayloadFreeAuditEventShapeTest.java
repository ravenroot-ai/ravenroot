package ai.ravenroot.server;

import ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent;
import ai.ravenroot.api.security.AuthorizationAuditEvent;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code AuthorizationAuditEvent}, {@code RateLimitAuditEvent} and
 * {@code ArtifactLifecycleAuditEvent} were judged structurally out of scope for a secret canary --
 * "payload-free by design", per each sink's own Javadoc -- because none of the three has a free-text
 * field a secret could hide inside. That claim was never itself checked; this class spends the one
 * test that checks it instead of leaving it as three paragraphs of assertion.
 *
 * <h2>What "structurally out of scope" is proven to mean here</h2>
 * <p>Every {@code String} component of the three record types is checked against a closed allowlist
 * of the identifier/classification fields each type is documented to carry. A future field named
 * {@code payload}, {@code message}, {@code body}, {@code detail}, {@code content} or anything else not
 * on the closed allowlist fails this test immediately. The absence of a free-text field is enforced
 * by requiring every allowlist extension to be explicit rather than by nobody having added one yet.</p>
 */
class PayloadFreeAuditEventShapeTest {
    /**
     * Every {@code String} field these three types are allowed to carry: correlation ids, identity,
     * classification/action vocabulary, and short structural identifiers (artifact id, digest,
     * client address). None is a slot sized or named for an arbitrary caller- or document-supplied
     * body of text.
     */
    private static final Set<String> ALLOWED_STRING_FIELDS = Set.of(
            "requestId", "subject", "tenantId", "resourceType", "resourceId", "reason",
            "clientAddress", "method", "path", "code", "scope",
            "artifactId", "sha256", "evidenceDigest", "action");

    @TestFactory
    Stream<org.junit.jupiter.api.DynamicTest> everyStringFieldIsOnTheClosedAllowlist() {
        return Stream.of(AuthorizationAuditEvent.class, RateLimitAuditEvent.class,
                        ArtifactLifecycleAuditEvent.class)
                .map(type -> org.junit.jupiter.api.DynamicTest.dynamicTest(type.getSimpleName(), () -> {
                    RecordComponent[] components = type.getRecordComponents();
                    assertTrue(components.length > 0, type + " must be a record with components to check");
                    for (RecordComponent component : components) {
                        if (component.getType() != String.class) {
                            continue;
                        }
                        assertTrue(ALLOWED_STRING_FIELDS.contains(component.getName()),
                                () -> type.getSimpleName() + "." + component.getName() + " is a String field "
                                        + "not on the closed allowlist -- if this is genuinely a new "
                                        + "identifier, add it deliberately; if it is a free-text/payload "
                                        + "field, this type is no longer payload-free and its boundary "
                                        + "description is now wrong");
                    }
                }));
    }

    /**
     * The allowlist itself must not silently grow: every entry must actually be
     * used by at least one of the three types, or a stale entry could hide a future violation by
     * making the allowlist wider than what these types really carry.
     */
    @Test
    void everyAllowlistEntryIsActuallyUsedBySomeType() {
        Set<String> declared = Stream.of(AuthorizationAuditEvent.class, RateLimitAuditEvent.class,
                        ArtifactLifecycleAuditEvent.class)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .filter(component -> component.getType() == String.class)
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        for (String allowed : ALLOWED_STRING_FIELDS) {
            if (!declared.contains(allowed)) {
                fail("'" + allowed + "' is on the allowlist but no longer declared by any of the three "
                        + "types -- remove it so the allowlist keeps meaning what it claims to mean");
            }
        }
    }
}
