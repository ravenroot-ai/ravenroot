package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code allowedFrom} and {@code allowedReplyTo} must not be the same set <em>by
 * construction</em>. The ten-field compatibility shape remains the explicit exception rather than
 * an artefact nobody wrote down.
 *
 * <h2>What was wrong, and why a single regression case would not have been enough</h2>
 * <p>{@code EnvironmentMailProfileResolver} passed {@code csv(p[6])} -- the sender field -- to both
 * constructor arguments, over a format with no field of its own for Reply-To. The allow-list was real
 * and enforced ({@code MailSendNodeBehavior} refuses a Reply-To outside it), so this was not dead
 * code: it was a control that could not be configured. An operator who wrote {@code *} among the
 * senders opened Reply-To to {@code *} at the same time, invisibly and irreversibly, and Reply-To is
 * precisely the field that redirects a reply to an address the profile never authorised.
 *
 * <p>{@link #wildcardSenderNoLongerOpensReplyTo} exercises the wildcard scenario, and
 * {@link #elevenFieldProfileGivesReplyToAnAllowListOfItsOwn} is the structural proof: a profile whose
 * two allow-lists are disjoint <em>cannot</em> be produced by a resolver that derives one from the
 * other, whatever the derivation looks like. {@link #tenFieldProfileKeepsReplyToEqualToSendersByDecision}
 * asserts the other half out loud -- the compatibility shape still yields equal sets, deliberately,
 * because rejecting it would make every already-provisioned profile unresolvable. Retiring that
 * compatibility shape would be an explicit compatibility change.
 */
class MailProfileReplyToPolicyTest {
    private static final String TENANT = "default";
    private static final String NAME = "primary";
    private static final String TEN_FIELD_PREFIX =
            "smtp.example.test;587;STARTTLS;false;mailer;primary;";

    /** Ten mandatory fields with the given sender field, and no eleventh field at all. */
    private static String ten(String senderField) {
        return TEN_FIELD_PREFIX + senderField + ";to@example.test;X-Trace;2";
    }

    /** The same ten fields plus an explicit eleventh, {@code allowedReplyTo}. */
    private static String eleven(String senderField, String replyToField) {
        return ten(senderField) + ";" + replyToField;
    }

    @Test void elevenFieldProfileGivesReplyToAnAllowListOfItsOwn() {
        MailProfile profile = resolve(eleven("sender@example.test", "replies@example.test"));
        assertEquals(Set.of("sender@example.test"), profile.allowedFrom());
        assertEquals(Set.of("replies@example.test"), profile.allowedReplyTo());
        assertNotEquals(profile.allowedFrom(), profile.allowedReplyTo(),
                "the two allow-lists must be independently configurable: no derivation of one from the "
                        + "other can produce a profile whose sets are disjoint");
        assertTrue(java.util.Collections.disjoint(profile.allowedFrom(), profile.allowedReplyTo()));
    }

    /** {@code *} among the senders must no longer widen Reply-To. */
    @Test void wildcardSenderNoLongerOpensReplyTo() {
        MailProfile narrowed = resolve(eleven("*", "replies@example.test"));
        assertTrue(narrowed.allowsAddress(narrowed.allowedFrom(), "anyone@example.test"),
                "the wildcard still authorises any sender -- that is what the operator wrote");
        assertFalse(narrowed.allowsAddress(narrowed.allowedReplyTo(), "attacker@evil.test"),
                "and it must no longer authorise any Reply-To: that widening is the defect");
        assertTrue(narrowed.allowsAddress(narrowed.allowedReplyTo(), "replies@example.test"));

        // The cost of the compatibility shape, asserted rather than left implicit: the same wildcard
        // written in a ten-field profile still opens both. That is why
        // the eleventh field exists, and why retiring the ten-field shape is a compatibility change.
        MailProfile legacy = resolve(ten("*"));
        assertTrue(legacy.allowsAddress(legacy.allowedReplyTo(), "attacker@evil.test"));
    }

    @Test void blankEleventhFieldDeniesEveryReplyToInsteadOfInheritingTheSenders() {
        MailProfile profile = resolve(eleven("sender@example.test", ""));
        assertEquals(Set.of(), profile.allowedReplyTo(),
                "a present but blank eleventh field is an empty allow-list -- the way to forbid "
                        + "Reply-To outright -- and must stay distinguishable from omitting the field");
        assertEquals(Set.of("sender@example.test"), profile.allowedFrom(),
                "an empty Reply-To policy must not be confused with an empty sender policy, which "
                        + "MailProfile rejects outright");
        assertFalse(profile.allowsAddress(profile.allowedReplyTo(), "sender@example.test"));
    }

    @Test void tenFieldProfileKeepsReplyToEqualToSendersByDecision() {
        MailProfile profile = resolve(ten("sender@example.test"));
        assertEquals(profile.allowedFrom(), profile.allowedReplyTo(),
                "Compatibility rule, not coincidence: with no eleventh field the Reply-To allow-list is "
                        + "the sender allow-list, preserving the established ten-field interpretation. "
                        + "Rejecting the ten-field shape instead would make "
                        + "every such profile unresolvable -- measured: an unexpected field count is "
                        + "rejected with FIELD_COUNT, and MailSendNodeBehavior turns the resulting "
                        + "empty Optional into a CONFIGURATION failure of every send. Do not 'fix' "
                        + "this assertion; changing it means deciding to break those profiles.");
    }

    /**
     * End-to-end, through the enforcement site rather than the record. The refused address is the
     * profile's own <em>sender</em>: under the old derivation it was in the Reply-To allow-list by
     * definition and would have been accepted, so this asserts the enforcement now reads the eleventh
     * field and nothing else. The permitted address gets past that check and fails later for an
     * unrelated reason (no credential is available here), which is what tells "the allow-list
     * accepted it" apart from "nothing was checked at all".
     */
    @Test void enforcementUsesTheEleventhFieldAndNotTheSenderList() {
        MailProfile profile = resolve(eleven("sender@example.test", "replies@example.test"));
        var behavior = new MailSendNodeBehavior(ref -> Optional.empty(), (tenant, name) -> Optional.of(profile));

        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(behavior, payload("sender@example.test")));
        assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, () -> execute(behavior, payload("replies@example.test")));
    }

    private static Map<String, Object> payload(String replyTo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", "mail.send.v1");
        payload.put("to", List.of("to@example.test"));
        payload.put("text", "body");
        payload.put("replyTo", replyTo);
        return payload;
    }

    private static MailProfile resolve(String raw) {
        return new EnvironmentMailProfileResolver(Map.of(
                EnvironmentMailProfileResolver.environmentVariableName(TENANT, NAME), raw))
                .resolve(TENANT, NAME)
                .orElseThrow(() -> new AssertionError("this profile was expected to resolve: " + raw));
    }

    private static Object execute(MailSendNodeBehavior behavior, Map<String, Object> payload) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mailProfile", NAME);
        return behavior.create(new NodeConfiguration("mail", "mail.send", values))
                .handle(message(payload)).toCompletableFuture().join();
    }

    /** The security context's tenant is {@link #TENANT} because {@code Settings.from} refuses a
     *  profile whose own tenant and name do not match the request's -- the binding check, not
     *  anything this test is about. */
    private static NodeMessage message(Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", TENANT, "s", PrincipalType.USER, "i"),
                id, id, id, id, Set.of(), "mail", payload, Map.of());
    }

    private static void assertCode(MailSendException.Code code, org.junit.jupiter.api.function.Executable call) {
        Throwable failure = assertThrows(Throwable.class, call);
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        assertInstanceOf(MailSendException.class, failure);
        assertEquals(code, ((MailSendException) failure).code());
    }
}
