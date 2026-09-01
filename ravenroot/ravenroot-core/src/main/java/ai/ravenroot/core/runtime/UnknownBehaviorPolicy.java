package ai.ravenroot.core.runtime;

/**
 * Whether a graph naming a behavior the trusted catalog does not contain may run (SEC-09).
 *
 * <h2>A seam that is now a switch</h2>
 * <p>SEC-09 defines a <em>production mode</em> that is fail-closed for unknown
 * behaviors. It says mode, and that word is load-bearing: read as an unconditional reversal it would
 * contradict ADR 0003, ADR 0006, the README and threat-model invariant INV-04, all of which state
 * that an unregistered behavior stays valid, emits {@code NODE_DEFAULTED} and executes as an
 * observable pass-through. Read as written — a mode that is not the default — those texts stay true
 * of the default path, which is why the fail-closed policy is selectable.</p>
 *
 * <p>This interface is the decision point: {@link #passThrough()} remains the default, while
 * {@code RAVENROOT_UNKNOWN_BEHAVIOR=refuse} selects {@link #refuse()}. ADR 0003, ADR 0006, the
 * README and INV-04 all describe that default-plus-mode behavior rather than an unconditional
 * rule.</p>
 *
 * <p><strong>The default did not change.</strong> Pass-through remains what an unconfigured
 * deployment does, because it is a product capability rather than an accident: a partially
 * implemented graph must stay submittable, openable and inspectable, and a default that refused
 * everything not yet built would make the editor unusable for the person most likely to be using it.
 * Reversing the default is an ADR-level decision about what the product promises, not a switch.</p>
 *
 * <p>Selection still happens at a composition root, never here. Core takes no configuration object
 * and still must not invent one: {@code ravenroot-server} reads the variable and passes the chosen
 * policy inward as a parameter, so the question of where platform-wide configuration lives stays
 * open for whoever answers it deliberately.</p>
 *
 * <h2>Consulted once, when the node is composed</h2>
 * <p>{@link GraphRunner} asks this question while spawning, not on each message. That matches the
 * rule {@code GraphMlCapabilityEscalationTest} already pins for an unregistered agent runtime:
 * arming an id after a graph has been admitted must not retroactively arm the node. A per-message
 * consultation would make a policy that changed mid-run apply to a graph that was already running.</p>
 *
 * <h2>What "unknown" means here</h2>
 * <p>Exactly what it means to the rest of SEC-09: the behavior name is absent from the
 * {@link BehaviorRegistry}. {@code BehaviorRegistry.create} returns empty under precisely the
 * condition that makes {@code BehaviorRegistry.descriptor} empty, so this seam, the schema validator
 * and {@code BehaviorPropertySchema.unregisteredBehaviors} all partition behaviors the same way.
 * A <em>known</em> behavior that refuses because its adapter or runtime is unconfigured is a
 * different case entirely — CORE-05 and CORE-07 own it, it never reaches here, and it must keep
 * failing as itself rather than degrading into this path.</p>
 */
@FunctionalInterface
public interface UnknownBehaviorPolicy {

    /**
     * The one variable that selects the mode, and the one place its name is written.
     *
     * <p>It lives here, next to the vocabulary it selects, because <strong>two composition roots read
     * it and neither can see the other</strong>: {@code ravenroot-server} and the CLI's embedded path,
     * which deliberately does not depend on the server module. Spelling the name and the parsing rule
     * separately in each is how one setting becomes two settings that agree until the day one of them
     * is changed.</p>
     *
     * <p>This is not the configuration channel this class's Javadoc refuses to give core. Nothing in
     * core consults it: {@link #fromEnvironment} is a pure function from a map a caller supplies, core
     * never reads {@code System.getenv()} itself, and no core code path calls it. A composition root
     * still decides to ask, and still passes the answer inward as a parameter.</p>
     */
    String ENVIRONMENT_VARIABLE = "RAVENROOT_UNKNOWN_BEHAVIOR";

    /** The only value that opts in. Compared after trimming, case-insensitively. */
    String REFUSE_VALUE = "refuse";

    /** The default, and what every other value — including an unrecognised one — means. */
    String PASS_THROUGH_VALUE = "pass-through";

    /**
     * What {@link #mode()} reports when the two probes in {@link #mode()} disagree, proving the policy
     * is not a constant function. Never guessed at, never defaulted to silently: reached only
     * when {@link #admits(String)} is observed, on this policy, to answer differently for two distinct
     * inputs.
     */
    String UNKNOWN_VALUE = "unknown";

    /**
     * The first witness {@link #mode()} probes with. Namespaced under {@code ravenroot.probe.} so it
     * cannot collide with a real graph-supplied behavior name, which is drawn from the trusted catalog
     * or an author's own vocabulary, never from this prefix.
     *
     * <p>Its pairing with {@link #PROBE_BEHAVIOR_ALTERNATE} is deliberately asymmetric; that constant
     * documents why.</p>
     */
    String PROBE_BEHAVIOR = "ravenroot.probe.unknown-behavior";

    /**
     * The second witness {@link #mode()} probes with, chosen to share nothing with
     * {@link #PROBE_BEHAVIOR}.
     *
     * <p><b>Why it looks nothing like the first.</b> Distinctness alone is not enough for the two calls
     * to disagree in practice. Previously this constant was {@link #PROBE_BEHAVIOR} plus a suffix, so
     * {@code ALTERNATE.startsWith(PROBE_BEHAVIOR)} held and the two shared most of their characters.
     * Every realistic discriminating predicate — a namespace prefix test, a catalog or allowlist
     * membership test, a charset or shape test — classified both witnesses <em>identically</em> and
     * escaped detection, which left the detected class close to "discriminates on these two exact
     * strings". A witness in a different namespace, with a different shape and no shared prefix, is
     * classified differently by exactly those predicates, so disagreement — the only observable proof
     * of non-constancy — actually occurs for the policies an integrator is likely to write.</p>
     *
     * <p><b>What that costs, stated rather than left implicit.</b> The first witness's collision-safety
     * argument is its namespace: nothing drawn from the trusted catalog or an author's vocabulary
     * begins with {@code ravenroot.probe.}. A dissimilar witness cannot borrow that argument, so it
     * rests on a different one — a random UUID under the {@code urn:uuid:} scheme, which no behavior
     * name is plausibly equal to and which nothing can arrive at by convention. That is a narrower
     * guarantee than "reserved by prefix" in kind, and a stronger one in practice against accidental
     * equality; it is written down here so a later reader can tell the trade was chosen rather than
     * fallen into. If a deployment ever did name a behavior this exact string, {@link #mode()} would
     * misreport that policy's stance — and nothing else, because {@code mode()} is observability and
     * gates no admission decision.</p>
     *
     * <p><b>Compatibility consequence.</b> This and {@link #PROBE_BEHAVIOR} are {@code public static
     * final String} constants on a public interface, so javac inlines their values into the constant
     * pool of every caller compiled against them (JLS 13.1). Changing a value is therefore a
     * <em>binary</em> incompatibility, not only a source one: an already-compiled downstream artifact
     * keeps probing the old string until it is recompiled, and would observe {@link #mode()} calling
     * {@link #admits(String)} with a name it no longer recognises. That cost is acceptable only before
     * the first release, while no published downstream binary exists. The only in-tree consumers of
     * either constant are {@link #mode()} itself and {@code UnknownBehaviorPolicyUnknownModeTest}.
     * Changing either value after a release would be incompatible, so treat both as frozen from the
     * first published artifact on.</p>
     */
    String PROBE_BEHAVIOR_ALTERNATE = "urn:uuid:0d5f8a6c-1e77-4b93-9c2e-6f0a4d31b8e5";

    /**
     * Whether {@code environment} selects the fail-closed mode.
     *
     * <p>An unrecognised value selects the default rather than refusing to boot. A typo becoming an
     * outage is worse than a typo being ignored, and this switch's safe direction is the one that
     * keeps a deployment behaving the way it behaved yesterday.</p>
     */
    static boolean refusalSelected(java.util.Map<String, String> environment) {
        String declared = environment == null ? null : environment.get(ENVIRONMENT_VARIABLE);
        return declared != null
                && REFUSE_VALUE.equals(declared.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** The policy {@code environment} selects: {@link #refuse()} only on an explicit opt-in. */
    static UnknownBehaviorPolicy fromEnvironment(java.util.Map<String, String> environment) {
        return refusalSelected(environment) ? refuse() : passThrough();
    }

    /** What a composition root's startup line calls the active mode. */
    static String describe(java.util.Map<String, String> environment) {
        return refusalSelected(environment) ? REFUSE_VALUE : PASS_THROUGH_VALUE;
    }

    /**
     * Whether a node naming {@code behavior}, which the trusted catalog does not contain, may run.
     *
     * @param behavior the graph-supplied behavior name, which may be {@code null} for a behavior node
     *                 that names none at all
     * @return {@code true} to execute the observable pass-through, {@code false} to refuse the node
     *         when a traversal reaches it
     */
    boolean admits(String behavior);

    /**
     * The name of the mode this policy implements, for a status surface to publish.
     *
     * <p>Until this existed, a deployment's admission stance was knowable only from the environment of
     * the process that started it — which is the one place an operator debugging a graph that ran when
     * they expected a refusal, or refused when they expected a pass-through, cannot see. Publishing it
     * on {@code ApplicationStatus} makes the stance an observable property of the running server.</p>
     *
     * <h2>Why the default probes rather than being a field</h2>
     * <p>Both shipped policies are lambdas ({@link #passThrough()} and {@link #refuse()} each return
     * one), so there is no constructor to carry a mode name and no state to read it from. The default
     * therefore asks the policy questions the interface already defines, using behavior names the
     * trusted catalog cannot contain.</p>
     *
     * <p><b>Two witnesses, not one — and unknown rather than guessed.</b> A single probe cannot
     * tell a constant policy from one that merely agrees with it on that one name; reporting a mode
     * from one sample was a guess wearing the shape of an answer. This method calls {@link #admits}
     * twice, with {@link #PROBE_BEHAVIOR} and the distinct {@link #PROBE_BEHAVIOR_ALTERNATE}. When the
     * two answers agree, both shipped policies do, because they are constant functions and any constant
     * function agrees with itself everywhere. When they <em>disagree</em>, that is not ambiguous: it is
     * direct, observed proof — not an inference from a document, an assumption about lambdas, or a
     * default no code path exercises — that {@link #admits(String)} is not a constant function, and
     * {@link #UNKNOWN_VALUE} is returned rather than either named mode.</p>
     *
     * <p><b>The honest residual limitation.</b> Agreement across two witnesses is evidence a policy is
     * constant, not proof: a policy that discriminates on some third behavior name neither probe uses
     * would still report the majority answer of these two. This method narrows the earlier blind guess
     * to a specific, disclosed gap — a policy that differs only outside {@link #PROBE_BEHAVIOR} and
     * {@link #PROBE_BEHAVIOR_ALTERNATE} — rather than closing it, because closing it fully would mean
     * evaluating {@link #admits(String)} over every possible input, which is not something a finite
     * number of calls can do for an arbitrary function.</p>
     *
     * <p><b>How wide that gap actually is, which depends on the two names.</b> Two witnesses
     * bound the escaping class only if a realistic predicate can separate them. When the second name
     * was the first plus a suffix, none of the plausible ones could — a prefix or namespace test, a
     * catalog or allowlist membership test, a shape test all answered both the same way — so the
     * escaping case was the norm and the sentence above, though true, was read as promising more than
     * it delivered. The witnesses now share no prefix, namespace or shape, so those predicates
     * separate them and are detected. What still escapes is a policy that treats both of these
     * particular names alike and differs on some third — a real gap, and now genuinely a corner rather
     * than the common case. {@code UnknownBehaviorPolicyUnknownModeTest} pins both halves: the
     * detection, against real non-constant implementations — classes, since a lambda still cannot
     * override this method, so the {@link #UNKNOWN_VALUE} path is reached by production code rather
     * than asserted only by a test double — and the limit itself, by asserting the majority answer this
     * method gives for a policy that escapes. {@code UnknownBehaviorCapabilityTest} is not part of that
     * evidence: it covers the two shipped constant policies and defines no non-constant one.</p>
     *
     * <p>This is <b>observability, not a control</b>. Nothing gates on the published token, so a
     * misreport here misleads a reader; it does not admit a behavior the policy would refuse. The
     * admission decision is {@link #admits(String)} and is unaffected.</p>
     */
    default String mode() {
        boolean first = admits(PROBE_BEHAVIOR);
        boolean second = admits(PROBE_BEHAVIOR_ALTERNATE);
        if (first != second) {
            return UNKNOWN_VALUE;
        }
        return first ? PASS_THROUGH_VALUE : REFUSE_VALUE;
    }

    /**
     * The capability token {@code ApplicationStatus} publishes for this policy.
     *
     * <p>Namespaced so a reader of a flat capability set can tell which subsystem it describes, and so
     * it cannot collide with an {@code ExecutionEngine} capability enum name. One of three values:
     * {@code unknown-behavior:pass-through}, {@code unknown-behavior:refuse}, or, for a policy
     * {@link #mode()} cannot characterize with certainty, {@code unknown-behavior:unknown} — itself an
     * honest answer, not a missing one.</p>
     */
    default String capability() {
        return "unknown-behavior:" + mode();
    }

    /**
     * Today's behaviour, and the default: every unknown behavior executes as a pass-through.
     *
     * <p>This is what ADR 0003 and ADR 0006 describe and what the README documents — a graph may name
     * behaviors this deployment has not installed, so a partially implemented graph can still be
     * submitted, opened and validated.</p>
     */
    static UnknownBehaviorPolicy passThrough() {
        return behavior -> true;
    }

    /**
     * Fail-closed: a behavior the trusted catalog does not contain refuses when it is reached.
     *
     * <p><strong>Selectable and never the default.</strong> An operator opts in with
     * {@code RAVENROOT_UNKNOWN_BEHAVIOR=refuse}; every other value, and its absence, leaves
     * {@link #passThrough()} in force. ADR 0003, ADR 0006, the README and INV-04 describe a default
     * plus an opt-in rather than an
     * unconditional rule.</p>
     *
     * <p>This is the mode for someone testing a real graph, where a node that silently does nothing
     * is worse than a run that stops and says which node it could not perform. It is not the mode for
     * someone exploring the editor with half a graph built.</p>
     *
     * <p>The refusal is delivered when a traversal reaches the node, not when the graph is composed.
     * CORE-05 established that shape for an agent runtime id that resolves to nothing, so that a
     * graph naming something this deployment lacks still constructs and stays inspectable in the
     * editor. A refusal whose kind depends on which unconfigured thing caused it is one an operator
     * cannot learn, so this deliberately produces the same kind: an {@code IllegalStateException}
     * that names the node and what was missing, a {@code NODE_FAILED} event, and no
     * {@code NODE_DEFAULTED} — refusing must never look like the pass-through it is refusing to do.</p>
     */
    static UnknownBehaviorPolicy refuse() {
        return behavior -> false;
    }
}
