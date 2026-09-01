package ai.ravenroot.api.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SEC-07 requirement "immutable and serializable context without a bearer token", as three falsifiable
 * properties rather than a claim.
 *
 * <p>Nothing in this repository serialises these types today — there is no Jackson, and the Pekko
 * adapter is local-only and configures no serialiser — so "serializable" cannot be tested by round
 * tripping. What is testable, and what actually matters, is that the types are <em>eligible</em> for
 * serialisation and carry no credential: every component is a value type, no component is or holds a
 * secret, and immutability is enforced rather than documented.</p>
 *
 * <p>These assertions are reflective on purpose. A reviewer reading the record today can see it holds
 * no token; the test exists for the edit two years from now that adds one "just for convenience", and
 * a convention would not notice that edit.</p>
 */
class SecurityContextSerializationTest {

    /** Names that betray a credential regardless of the declared type. */
    private static final Pattern CREDENTIAL_NAME =
            Pattern.compile("token|credential|secret|authorization|password");

    /** Types that are credentials, whatever a component is called. */
    private static final Set<Class<?>> CREDENTIAL_TYPES = Set.of(SecretValue.class, char[].class, byte[].class);

    @Test
    void neitherContextDeclaresAComponentNamedLikeACredential() {
        for (Class<?> type : List.of(SecurityContext.class, RequestContext.class)) {
            assertTrue(credentialNamedComponentsOf(type).isEmpty(),
                    type.getSimpleName() + " declares credential-named components "
                            + credentialNamedComponentsOf(type)
                            + "; the proof of identity must stay at the authentication boundary and only "
                            + "the conclusion may travel");
        }
    }

    @Test
    void neitherContextDeclaresAComponentWhoseTypeIsACredential() {
        for (Class<?> type : List.of(SecurityContext.class, RequestContext.class)) {
            assertTrue(credentialTypedComponentsOf(type).isEmpty(),
                    type.getSimpleName() + " declares credential-typed components "
                            + credentialTypedComponentsOf(type));
        }
    }

    /**
     * The control for the two assertions above.
     *
     * <p>A reflective check that never matches anything passes forever and proves nothing, which is
     * the failure mode this kind of test is most prone to. These deliberately-bad records establish
     * that the predicates have teeth before they are trusted to certify the real ones.</p>
     */
    @Test
    void theCredentialDetectorsActuallyRejectABadRecord() {
        assertEquals(List.of("bearerToken"), credentialNamedComponentsOf(LeakyByName.class));
        assertEquals(List.of("proof"), credentialTypedComponentsOf(LeakyByType.class));
        // And they do not fire on the legitimate shape, so they are discriminating rather than noisy.
        assertTrue(credentialNamedComponentsOf(LeakyByType.class).isEmpty());
        assertTrue(credentialTypedComponentsOf(LeakyByName.class).isEmpty());
    }

    private static List<String> credentialNamedComponentsOf(Class<?> type) {
        return java.util.Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> CREDENTIAL_NAME.matcher(name.toLowerCase(Locale.ROOT)).find())
                .toList();
    }

    private static List<String> credentialTypedComponentsOf(Class<?> type) {
        return java.util.Arrays.stream(type.getRecordComponents())
                .filter(component -> CREDENTIAL_TYPES.contains(component.getType()))
                .map(RecordComponent::getName)
                .toList();
    }

    private record LeakyByName(String subject, String bearerToken) {
    }

    private record LeakyByType(String subject, char[] proof) {
    }

    @Test
    void everyComponentIsASerialisableValueType() {
        for (Class<?> type : List.of(SecurityContext.class, RequestContext.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                Class<?> componentType = component.getType();
                assertTrue(componentType == String.class || componentType.isEnum()
                                || Set.class.isAssignableFrom(componentType),
                        type.getSimpleName() + "." + component.getName() + " is a " + componentType
                                + "; only strings, enums and sets of them may cross a boundary that a "
                                + "remote adapter must be able to reconstruct");
            }
        }
    }

    @Test
    void projectingTheIngressContextDropsAuthorityAndKeepsIdentity() {
        var request = new RequestContext("request-1", "alice", PrincipalType.USER, "urn:issuer", "tenant-a",
                Set.of(Role.PLATFORM_ADMIN), Set.of("ravenroot.execute"));

        SecurityContext projected = SecurityContext.of(request);

        assertEquals("request-1", projected.requestId());
        assertEquals("tenant-a", projected.tenantId());
        assertEquals("alice", projected.subject());
        assertEquals("urn:issuer|USER|alice", projected.qualifiedIdentity());
        // Authority does not travel into the interior: there is no component to read it from.
        assertTrue(java.util.Arrays.stream(SecurityContext.class.getRecordComponents())
                        .noneMatch(component -> component.getName().equals("roles")
                                || component.getName().equals("scopes")),
                "roles and scopes are ingress authorization inputs and must not reach node or tool code");
    }

    @Test
    void everyComponentIsMandatorySoIdentityCannotBePartiallyAbsent() {
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityContext(" ", "tenant-a", "alice", PrincipalType.USER, "urn:issuer"));
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityContext("request-1", "", "alice", PrincipalType.USER, "urn:issuer"));
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityContext("request-1", "tenant-a", null, PrincipalType.USER, "urn:issuer"));
        assertThrows(NullPointerException.class,
                () -> new SecurityContext("request-1", "tenant-a", "alice", null, "urn:issuer"));
    }

    @Test
    void theIngressContextDefensivelyCopiesItsCollections() {
        var mutableRoles = new java.util.HashSet<>(Set.of(Role.VIEWER));
        var mutableScopes = new java.util.HashSet<>(Set.of("ravenroot.status.read"));
        var request = new RequestContext("request-1", "alice", PrincipalType.USER, "urn:issuer", "tenant-a",
                mutableRoles, mutableScopes);

        assertNotSame(mutableRoles, request.roles());
        mutableRoles.add(Role.PLATFORM_ADMIN);
        mutableScopes.add("ravenroot.execute");

        assertEquals(Set.of(Role.VIEWER), request.roles(), "a caller must not be able to escalate after construction");
        assertEquals(Set.of("ravenroot.status.read"), request.scopes());
        assertThrows(UnsupportedOperationException.class, () -> request.roles().add(Role.PLATFORM_ADMIN));
    }
}
