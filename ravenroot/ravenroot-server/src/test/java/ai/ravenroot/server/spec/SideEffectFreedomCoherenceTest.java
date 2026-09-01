package ai.ravenroot.server.spec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sideEffectFree} is a declaration, and these are the checks that stop it meaning "trust me"
 * directly.
 *
 * <p>The component replaced "the method is {@code GET}" as {@link AssistantPosture#READ}'s
 * precondition. A bare boolean with nothing checking it would be a weaker control than the proxy it
 * replaced — the verb was at least observable — so what matters is that the incoherent combinations
 * fail at construction rather than being noticed by a reader, and that this is exercised against
 * denials rather than only against the happy path.</p>
 */
class SideEffectFreedomCoherenceTest {

    @Test
    void readRequiresTheRouteToBeDeclaredSideEffectFree() {
        var refused = assertThrows(IllegalArgumentException.class, () -> new RouteDescriptor(
                Set.of("POST"), "/v1/pretend", "A mutating route claiming READ.", true, true, 200,
                List.of(), AssistantPosture.READ, false));
        assertTrue(refused.getMessage().contains("side-effect-free"), refused.getMessage());
    }

    @Test
    void readStillRequiresAnAuthenticatedRoute() {
        var refused = assertThrows(IllegalArgumentException.class, () -> new RouteDescriptor(
                Set.of("GET"), "/v1/pretend", "An unauthenticated route claiming READ.", false, true, 200,
                List.of(), AssistantPosture.READ, true));
        assertTrue(refused.getMessage().contains("authenticated"), refused.getMessage());
    }

    /**
     * The rule that keeps {@code /v1/program-artifacts} incapable of {@code READ} after the verb rule
     * retired. It was never a proxy: a per-path descriptor covering several methods describes a surface
     * on which one of them may mutate, and no per-path declaration can honestly deny that.
     */
    @Test
    void aMultiMethodRouteCannotDeclareItselfSideEffectFree() {
        var refused = assertThrows(IllegalArgumentException.class, () -> new RouteDescriptor(
                Set.of("GET", "POST"), "/v1/mixed", "Lists and creates.", true, true, 200,
                List.of(), AssistantPosture.NEVER, true));
        assertTrue(refused.getMessage().contains("multi-method"), refused.getMessage());
    }

    /** A route that answers "accepted for processing" has, by saying so, asserted an effect. */
    @Test
    void anAcceptedOrCreatedAnswerCannotDeclareItselfSideEffectFree() {
        for (int status : new int[]{201, 202}) {
            var refused = assertThrows(IllegalArgumentException.class, () -> new RouteDescriptor(
                    Set.of("POST"), "/v1/starts-work", "Starts work.", true, true, status,
                    List.of(), AssistantPosture.NEVER, true));
            assertTrue(refused.getMessage().contains("side-effect-free"), refused.getMessage());
        }
    }

    /**
     * The point of the change: a read-shaped POST is now expressible.
     *
     * <p>This construction must be allowed so the assistant does not lose its graph-inspection tool
     * merely because of the request shape rather than anything the route does.</p>
     */
    @Test
    void anAuthenticatedSideEffectFreePostMayBeRead() {
        var descriptor = new RouteDescriptor(Set.of("POST"), "/v1/graphs/inspect",
                "Parses and summarizes without executing.", true, true, 200, List.of(),
                AssistantPosture.READ, true);
        assertEquals(AssistantPosture.READ, descriptor.assistantPosture());
        assertTrue(descriptor.sideEffectFree());
    }

    /**
     * {@code sideEffectFree} is a precondition, never a trigger.
     *
     * <p>This is what stops the change from widening the assistant's reach to every route that merely
     * <em>could</em> qualify. {@code /health}, {@code /ready} and {@code /v1/assistant} are all
     * side-effect-free and all still {@code NEVER}, because the posture stays explicitly declared.</p>
     */
    @Test
    void declaringARouteSideEffectFreeDoesNotMakeItRead() {
        var stillNever = new RouteDescriptor(Set.of("GET"), "/v1/quiet", "Reads nothing mutable.",
                true, true, 200, List.of(), AssistantPosture.NEVER, true);
        assertEquals(AssistantPosture.NEVER, stillNever.assistantPosture());

        long freeButNotRead = RouteTable.ALL.stream()
                .filter(RouteDescriptor::sideEffectFree)
                .filter(route -> route.assistantPosture() != AssistantPosture.READ)
                .count();
        assertTrue(freeButNotRead > 0,
                "the live table should still contain side-effect-free routes the assistant may not "
                        + "reach; if it does not, the precondition has silently become a trigger");
    }

    /** Every {@code READ} route in the live table satisfies both preconditions, by construction. */
    @Test
    void everyReadRouteInTheTableIsAuthenticatedAndSideEffectFree() {
        for (RouteDescriptor route : RouteTable.ALL) {
            if (route.assistantPosture() == AssistantPosture.READ) {
                assertTrue(route.authenticated(), route.path());
                assertTrue(route.sideEffectFree(), route.path());
            }
        }
    }
}
