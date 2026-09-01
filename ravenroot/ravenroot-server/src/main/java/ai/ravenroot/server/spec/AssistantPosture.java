package ai.ravenroot.server.spec;

/**
 * What the authoring assistant may do with one HTTP endpoint under the assistant-route posture contract.
 *
 * <h2>Why this is a mandatory {@link RouteDescriptor} component and not an annotation</h2>
 * <p>the assistant-route posture contract says the posture is "a mandatory record component with no default, so
 * adding an API <em>is</em> declaring its posture or the table does not compile". That is what this
 * type is for: {@code RouteDescriptor}'s canonical constructor takes it positionally, so a new route
 * cannot be added without someone typing a value here. There is deliberately no {@code UNSET} and no
 * defaulting overload — a posture nobody chose is exactly the thing this design refuses to have.</p>
 *
 * <h2>What enforces it today, honestly stated</h2>
 * <p>Three things, and it is worth being exact about which each one can catch, because a declaration
 * no code reads is the "control that could not fail" this repository has already catalogued
 * thirty-five of:</p>
 * <ol>
 *   <li>{@link RouteDescriptor}'s own compact constructor — {@code READ} is refused at construction
 *       for a route that is unauthenticated, or that answers any method other than {@code GET}. Both
 *       rules are structural, not stylistic: see {@code RouteDescriptor}.</li>
 *   <li>{@link ai.ravenroot.server.assistant.tools.AssistantInternalContext} — every tier-0 read it
 *       performs names the route path it mirrors, and its construction fails if that route's posture
 *       is not {@code READ}. This makes the field load-bearing: flipping {@code /v1/node-types} to
 *       {@code NEVER} reds a test.</li>
 *   <li>{@link OpenApiSpecGenerator} emits it as {@code x-assistant-posture}, so a posture change
 *       that skips regenerating {@code docs/api/openapi.json} reds
 *       {@code RouteTableSpecServerAgreementTest}.</li>
 * </ol>
 *
 * <p><b>{@link #CONFIRM} is declared by exactly one route</b> — {@code POST /v1/executions} — and it
 * grants nothing today. No effectors exist, so there is nothing for a confirmation card to confirm,
 * and no tool may be built on the posture: enforcement point 2
 * above accepts {@code READ} only, so a tool naming a {@code CONFIRM} route is refused exactly as one
 * naming a {@code NEVER} route is. What the declaration does is record the documented contract — the model
 * proposes, the user presses — against the one route where the distinction between "must never" and
 * "only with a press" matters.</p>
 *
 * <p>That route is now side-effect-free by server policy (Play / {@code TEST_PASSTHROUGH}) and is
 * still {@code CONFIRM}, because it mints an execution id and charges the tenant's active-execution
 * budget. See {@link RouteTable}'s own note for the complete resource-accounting rationale.</p>
 */
public enum AssistantPosture {
    /**
     * The assistant may read this endpoint's data on the author's behalf, through
     * {@code AuthorizedRavenrootApplication} under the author's own {@code SecurityContext} — never
     * by calling the HTTP route itself. Restricted by {@link RouteDescriptor}'s compact constructor
     * to authenticated, {@code GET}-only routes.
     */
    READ,

    /**
     * Reserved for effectors: an action the assistant may propose but that the author
     * must confirm. Declared by nothing today — see this enum's own Javadoc.
     */
    CONFIRM,

    /**
     * The assistant may never reach this endpoint. Every mutation, every control operation, every
     * unauthenticated probe, and the assistant's own two routes.
     */
    NEVER
}
