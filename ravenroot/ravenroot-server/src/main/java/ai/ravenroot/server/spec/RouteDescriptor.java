package ai.ravenroot.server.spec;

import java.util.List;
import java.util.Set;

/**
 * One HTTP endpoint's public contract (API-05).
 *
 * <h2>Why this exists</h2>
 * <p>Three prose descriptions of this API already live in the repository with nothing keeping them
 * true. This type is what replaces "true when written" with "true because a test fails otherwise":
 * {@link RouteTable#ALL} is the one list the OpenAPI spec is generated from, and — for every entry
 * where {@link #registersContext()} is {@code true} — the one list {@code RavenrootServer} registers
 * its HTTP contexts from. Adding a route without an entry here does not register it; adding an entry
 * here without regenerating the checked-in spec fails a test. Drift becomes non-representable rather
 * than merely detected, for the routes this can drive registration for.</p>
 *
 * <h2>{@code registersContext}: the trap the deployment admission design named</h2>
 * <p>Most endpoints are a 1:1 {@code HttpServer#createContext} call, and for those this descriptor
 * <em>is</em> the registration — {@code registersContext() == true}. The program-artifact operation
 * endpoints are not: {@code POST /v1/program-artifacts/{id}/{operation}} is a single prefix-matched
 * context whose handler dispatches five real operations through a Java {@code switch}. A table driving
 * registration cannot capture that shape — there is no fifth {@code createContext} call to hang a
 * descriptor off. Those five, plus {@code /health} and {@code /ready} (registered directly on
 * {@code HttpServer}, before {@code RavenrootServer}'s own {@code apiContext} helper exists to collect
 * from), are declared here by hand with {@code registersContext() == false} — present in the spec,
 * verified against a live server by {@code RouteTableSpecServerAgreementTest} instead of by
 * construction, and named as the exception rather than silently folded into the mechanical guarantee
 * the rest of the table provides. The default-off embed-browser contexts are also declared false:
 * unlike the unconditional table-driven subset, they exist only when a complete typed
 * {@code EmbedBrowserConfiguration} is supplied, and their live integration test verifies that
 * conditional registration.</p>
 *
 * @param methods          HTTP methods this path answers. Note what this is <b>not</b>: the framework
 *                          only ever consults this set for CORS preflight
 *                          ({@code BrowserOriginPolicy#handlePreflight}) — actual method enforcement is
 *                          each handler's own {@code method(exchange, "GET")}-style check, independent
 *                          of this set. A spec built from this field describes what a browser preflight
 *                          sees, which happens to agree with enforcement today because every handler is
 *                          hand-kept in sync, not because the framework ties the two together.
 * @param path             the registered path, or (for a hand-declared operation) the full path
 *                          including its path parameter, e.g. {@code /v1/program-artifacts/{id}/validate}
 * @param summary           one line, for a spec's endpoint list
 * @param authenticated     whether a bearer token is required (false only for {@code /health}/{@code /ready})
 * @param registersContext  see above
 * @param successStatuses   every 2xx status this endpoint can answer with on success
 * @param wireErrorCodes    every wire error code (see {@link WireErrorCodes}) this endpoint can answer
 *                          with — deliberately a checked list rather than "whatever ErrorCode declares
 *                          413", because the vocabulary is the enum <em>plus</em> the rate limiter's own
 *                          string constants, and an endpoint the rate limiter guards can answer with
 *                          both.
 * @param assistantPosture  what the authoring assistant may do with this endpoint. Mandatory,
 *                          with no default and no defaulting overload: adding
 *                          a route <em>is</em> declaring its posture, or this constructor does not
 *                          compile. See {@link AssistantPosture} for what actually enforces it, and
 *                          the two structural rules below for what it refuses outright.
 */
public record RouteDescriptor(Set<String> methods, String path, String summary, boolean authenticated,
                              boolean registersContext, Set<Integer> successStatuses, List<String> wireErrorCodes,
                              AssistantPosture assistantPosture, boolean sideEffectFree) {
    /** Convenience constructor for the usual route with exactly one successful response status. */
    public RouteDescriptor(Set<String> methods, String path, String summary, boolean authenticated,
                           boolean registersContext, int successStatus, List<String> wireErrorCodes,
                           AssistantPosture assistantPosture, boolean sideEffectFree) {
        this(methods, path, summary, authenticated, registersContext, Set.of(successStatus), wireErrorCodes,
                assistantPosture, sideEffectFree);
    }

    public RouteDescriptor {
        if (methods == null || methods.isEmpty()) {
            throw new IllegalArgumentException("methods cannot be empty");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be blank");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary cannot be blank");
        }
        if (assistantPosture == null) {
            throw new IllegalArgumentException("assistantPosture must be declared; there is no default");
        }
        if (successStatuses == null || successStatuses.isEmpty()
                || successStatuses.stream().anyMatch(status -> status == null || status < 200 || status >= 300)) {
            throw new IllegalArgumentException("successStatuses must contain only 2xx statuses: " + successStatuses);
        }
        methods = Set.copyOf(methods);
        successStatuses = Set.copyOf(successStatuses);
        wireErrorCodes = List.copyOf(wireErrorCodes);
        requireDeclarableSideEffectFreedom(methods, path, successStatuses, sideEffectFree);
        requireCoherentPosture(methods, path, authenticated, assistantPosture, sideEffectFree);
    }

    /**
     * The combinations in which {@code sideEffectFree == true} is provably a false declaration.
     *
     * <h2>Why this exists at all</h2>
     * <p>{@code sideEffectFree} replaced "the method is {@code GET}" as {@link AssistantPosture#READ}'s
     * precondition, and a bare boolean with nothing checking it would be a worse control than the proxy
     * it replaced: the verb was at least observable. These rules are what stop the component from
     * meaning "trust me".</p>
     *
     * <h2>What they can and cannot catch, stated honestly</h2>
     * <p>They cannot prove a handler is pure — nothing available to a descriptor can. What they refuse
     * are the shapes that <em>contradict</em> the claim, so an incoherent declaration fails at class
     * initialisation rather than being discovered by a reader. The residual case is a single-method,
     * {@code 200}-answering route that mutates and declares itself free anyway. That remains a defect;
     * keeping the declaration as a component beside the handler makes the inconsistency locally visible,
     * unlike a comment, naming convention or separately maintained set of paths.</p>
     *
     * <ol>
     *   <li><b>A multi-method descriptor cannot be side-effect-free.</b> This table is per-path and
     *       carries a <em>set</em> of methods, so a descriptor answering both a listing {@code GET} and
     *       a creating {@code POST} ({@code /v1/program-artifacts}) describes a surface on which one
     *       method mutates. The surface as a whole is not free of effects, and no per-path declaration
     *       can honestly say otherwise. This is the one part of the old verb rule that was never a
     *       proxy: it is about the granularity mismatch, not about which verb was used.</li>
     *   <li><b>A {@code 201} or {@code 202} success cannot be side-effect-free.</b> Those statuses mean
     *       "created" and "accepted for processing"; both assert that something now exists or is under
     *       way because of the call. {@code POST /v1/executions} answers {@code 202} and is caught here
     *       rather than by anyone remembering.</li>
     * </ol>
     */
    private static void requireDeclarableSideEffectFreedom(Set<String> methods, String path,
                                                           Set<Integer> successStatuses,
                                                           boolean sideEffectFree) {
        if (!sideEffectFree) {
            return;
        }
        if (methods.size() != 1) {
            throw new IllegalArgumentException(
                    "a multi-method route cannot declare itself side-effect-free: a per-path descriptor "
                            + "covering several methods describes a surface on which one of them may "
                            + "mutate (" + methods + "): " + path);
        }
        if (successStatuses.contains(201) || successStatuses.contains(202)) {
            throw new IllegalArgumentException(
                    "a route answering " + successStatuses + " cannot declare itself side-effect-free: "
                            + "created/accepted assert that something exists or is under way because of "
                            + "the call: " + path);
        }
    }

    /**
     * The two rules that make {@link AssistantPosture#READ} mean something rather than being a label.
     *
     * <h2>1. An unauthenticated route can never be {@code READ}</h2>
     * <p>the assistant-tool boundary's central promise is that "a denial to the user is a denial to the
     * panel" — every assistant read passes the same authorization the user's own UI calls pass. An
     * unauthenticated endpoint has no principal to pass an authorization check <em>as</em>, so a
     * {@code READ} posture on one would be a route the assistant could reach outside the very
     * mechanism that promise rests on. {@code /health} and {@code /ready} are the two, and they are
     * {@code NEVER} for this reason and not because their content is uninteresting.</p>
     *
     * <h2>2. A route that changes state can never be {@code READ}</h2>
     * <p>This rule used to read "a route answering anything but {@code GET}". The verb was standing in
     * for the property, and the substitution was visible the moment it cost something real:
     * {@code POST /v1/graphs/inspect} is authenticated and does nothing but count a submitted
     * document's nodes and edges, yet was refused for the <em>shape of its request</em> — it is a
     * {@code POST} only because a GraphML document has to travel in a body. Excluding it protected
     * nothing, because there was no risk on that route to protect against.</p>
     *
     * <p>So the precondition is now the property itself: {@code sideEffectFree}, declared per route and
     * checked by {@link #requireDeclarableSideEffectFreedom}. The guarantee the rule exists for is
     * unchanged — the assistant reaches only what does not change state — while the proxy that stood in
     * for it is gone. The posture still follows state change rather than verb; what changed is that
     * the <em>test</em> for state change stopped being the HTTP
     * method.</p>
     *
     * <p><b>The mixed-method concern did not disappear with the verb rule</b> — it moved to where it was
     * always really about granularity rather than verbs. {@code /v1/program-artifacts} is one entry
     * answering both a listing {@code GET} and a creating {@code POST}, so it cannot declare itself
     * side-effect-free at all, and is therefore still incapable of being {@code READ}.</p>
     *
     * <p><b>{@code sideEffectFree} is a precondition, never a trigger.</b> Declaring a route free of
     * effects does not make it {@code READ}; the posture stays explicitly declared and unchanged. That
     * asymmetry stops the declaration from silently widening the assistant's reach to every route
     * that merely <em>could</em> qualify — {@code /health}, {@code /ready} and {@code /v1/assistant} are
     * all side-effect-free and all still {@code NEVER}, each for its own declared reason.</p>
     */
    private static void requireCoherentPosture(Set<String> methods, String path, boolean authenticated,
                                               AssistantPosture assistantPosture, boolean sideEffectFree) {
        if (assistantPosture != AssistantPosture.READ) {
            return;
        }
        if (!authenticated) {
            throw new IllegalArgumentException("READ posture requires an authenticated route: " + path);
        }
        if (!sideEffectFree) {
            throw new IllegalArgumentException(
                    "READ posture requires a route declared side-effect-free: " + path);
        }
    }
}
