package ai.ravenroot.api.security;

import java.util.Objects;

/**
 * The immutable tenant and principal context that crosses every interior boundary (SEC-07):
 * execution, node, tool, store and event.
 *
 * <p>This is the <em>interior</em> projection of {@link RequestContext}. It is established once, by a
 * trusted adapter at ingress, and from that point on it is carried by value and never re-derived.
 * Nothing downstream of the reference monitor may construct one from graph content or from a
 * payload.</p>
 *
 * <h2>Why this is narrower than {@link RequestContext}</h2>
 * <p>{@code roles} and {@code scopes} are deliberately <strong>absent</strong>. They are ingress
 * authorization <em>inputs</em>, consumed by {@link AuthorizationService} before any execution
 * starts. Carrying them into node and tool code would let interior code reason about authority it
 * was never meant to see, which is how ambient authority is reintroduced one convenience at a time.
 * What the interior needs is identity for <em>scoping and audit</em> — which tenant owns this work
 * and which principal asked for it — not the authority to re-decide a question already decided.</p>
 *
 * <p>Carrying less is the reversible direction: adding a component later is additive, while removing
 * one after node authors have come to depend on it is not.</p>
 *
 * <h2>Serializable without a bearer token</h2>
 * <p>Every component is a {@code String} or an enum. No credential type — no {@code BearerToken}, no
 * {@link SecretValue}, no {@code char[]} — appears here or in {@link RequestContext}, and none may be
 * added. The credential that <em>proved</em> this identity stays at the authentication boundary; only
 * the conclusion travels. {@code SecurityContextSerializationTest} enforces this reflectively rather
 * than by convention, because a convention is exactly what a future edit will not notice breaking.</p>
 * @param requestId ingress request correlation identifier
 * @param tenantId tenant selected at the authentication boundary
 * @param subject authenticated subject name
 * @param principalType kind of authenticated actor
 * @param issuer identity-provider issuer that qualifies the subject
 */
public record SecurityContext(String requestId, String tenantId, String subject,
                              PrincipalType principalType, String issuer) {

    /**
     * Key prefix reserved for identity and security state, rejected wherever untrusted input becomes
     * a property or attribute map: GraphML node and edge data, and start payloads.
     *
     * <p>Identity does not actually travel under these names — it travels as typed components on
     * {@code NodeMessage} and {@code ExecutionEvent}, which is what makes it non-overridable. The
     * prefix is reserved anyway so that a graph cannot present <em>plausible-looking</em> identity to
     * a node author, a template rendering {@code {{attributes.*}}}, or a log line, and thereby be
     * believed by a human or by code that has not been taught the difference.</p>
     */
    public static final String RESERVED_KEY_PREFIX = "ravenroot.security.";

/**
 * True when {@code key} is reserved and must be refused from untrusted input.
 * @param key candidate attribute name
 * @return whether the name is in the case-insensitive {@code ravenroot.} namespace
 */
    public static boolean isReservedKey(String key) {
        return key != null && key.trim().toLowerCase(java.util.Locale.ROOT).startsWith(RESERVED_KEY_PREFIX);
    }

/**
 * Rejects blank identity fields and a missing principal type.
 */
    public SecurityContext {
        requestId = requireText(requestId, "requestId");
        tenantId = requireText(tenantId, "tenantId");
        subject = requireText(subject, "subject");
        principalType = Objects.requireNonNull(principalType, "principalType");
        issuer = requireText(issuer, "issuer");
    }

/**
 * Projects the ingress context, dropping roles and scopes. The only supported way to build one.
 * @param context authenticated ingress context from a trusted adapter
 * @return security-only projection that intentionally drops roles and scopes
 */
    public static SecurityContext of(RequestContext context) {
        Objects.requireNonNull(context, "context");
        return new SecurityContext(context.requestId(), context.tenantId(), context.subject(),
                context.principalType(), context.issuer());
    }

    /**
     * The audit-stable principal name, in the same {@code issuer|TYPE|subject} form the artifact
     * lifecycle already records. A bare subject is not unique across issuers, so it cannot be the
     * correlation key.
 * @return issuer, principal type, and subject joined into the audit-stable identity key
     */
    public String qualifiedIdentity() {
        return issuer + "|" + principalType.name() + "|" + subject;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
