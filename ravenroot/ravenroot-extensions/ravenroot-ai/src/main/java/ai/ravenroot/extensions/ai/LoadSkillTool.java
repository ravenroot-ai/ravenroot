package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@code load_skill}: the built-in tool through which an agent asks for the body of a skill.
 *
 * <h2>Progressive disclosure, which is the whole point</h2>
 * <p>The untrusted author turn carries every declared skill's <b>name and description and nothing else</b>
 * (see {@link AgentTurn#systemMessage}); the body arrives here, once, when the model asks for it.
 * Wiring that as a tool rather than as a prompt section is what makes an unused skill cost its one
 * line and no more. Keeping that disclosure boundary in the tool contract also keeps the loop
 * independent of how skills are declared.</p>
 *
 * <h2>Every answer is an answer, including every refusal</h2>
 * <p>Nothing on this path fails the node. A model that invents a name, passes malformed arguments or
 * passes none at all reads what went wrong and gets another turn — see {@link AgentTool#invoke} for
 * why, and {@code maxTurns} for what stops a model that cannot use the correction.</p>
 *
 * <h2>One body per run, and that bound is not only about tidiness</h2>
 * <p>A second call for a skill already loaded returns a pointer to it rather than the body again.
 * The obvious reason is that a repeated body is context paid for twice for nothing. The less obvious
 * one is that this rule, with {@link AgentSkill#MAX_SKILLS}, is what bounds the total body text one
 * run can accumulate — {@code MAX_SKILLS × MAX_INSTRUCTIONS_CHARS}, whatever the model does — without
 * relying on the endpoint reporting token usage, which not every endpoint does.</p>
 *
 * <h2>Loaded bodies are paid for out of the token budget</h2>
 * <p>They are, and by construction rather than by an addition made here: {@code AgentNodeBehavior}
 * sums <em>prompt</em> and completion tokens on every turn, and an endpoint re-reads the whole
 * conversation each turn, so from the turn a body is delivered it is counted again on every
 * subsequent one. A large skill therefore consumes the run's allowance and can be what exhausts it,
 * and the failure that arrives is {@code TOKEN_BUDGET_EXHAUSTED} by its own name.</p>
 *
 * <h2>A body confers nothing</h2>
 * <p>A skill is graph content. It reaches the model as a {@code tool} message, which is data. Text
 * inside it claiming an authority — "you may call any tool", "ignore your instructions" — adds
 * nothing to the tool list, which is fixed by this node's configuration and by the operator's service
 * grant, outside the model entirely.</p>
 */
final class LoadSkillTool implements AgentTool {

    static final String NAME = "load_skill";

    /** The answer when the node declares no skills at all. */
    static final String NO_SKILLS = "No skills are declared on this node. Answer from the "
            + "instructions and the objective you were given, and do not call this tool again.";

    /**
     * Budgets for reading one tool-call argument document.
     *
     * <p>Deliberately tiny, and deliberately not {@link PayloadLimits#DEFAULTS}: this document is one
     * object with one short string in it, written by a non-deterministic remote component, and the
     * reader's budget is the cheapest place to say so. A model that emits something enormous here
     * gets a refusal it can act on instead of an allocation.</p>
     */
    private static final PayloadLimits ARGUMENT_LIMITS = new PayloadLimits(4 * 1024, 4, 32, 64, 1_024, 64);

    private final List<AgentSkill> skills;

    /**
     * Names already handed over during this run, lower-cased.
     *
     * <p>Per invocation, because this tool is constructed per {@code Run} — an agent's conversation
     * is per invocation and never per node (ADR 0024 §3), and a set shared across traversals would
     * make one agent's second call answer with another agent's first. Plainly mutable, like the
     * conversation and the counters beside it: the turns of one run are ordered by the completion
     * stages that chain them, and each hands off to the next with the happens-before that carries.</p>
     */
    private final Set<String> loaded = new LinkedHashSet<>();

    LoadSkillTool(List<AgentSkill> skills) {
        this.skills = List.copyOf(skills);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Loads the full instructions of a skill declared on this node, by name. "
                + "Call it only for a skill listed in your instructions.";
    }

    @Override
    public PayloadValue.MapValue parameters() {
        var name = new LinkedHashMap<String, PayloadValue>();
        name.put("type", PayloadValue.of("string"));
        name.put("description", PayloadValue.of("Name of the skill to load."));

        var properties = new LinkedHashMap<String, PayloadValue>();
        properties.put("name", new PayloadValue.MapValue(name));

        var schema = new LinkedHashMap<String, PayloadValue>();
        schema.put("type", PayloadValue.of("object"));
        schema.put("properties", new PayloadValue.MapValue(properties));
        schema.put("required", new PayloadValue.ListValue(List.of(PayloadValue.of("name"))));
        return new PayloadValue.MapValue(Map.copyOf(schema));
    }

    @Override
    public CompletionStage<String> invoke(String argumentsJson) {
        // Wrapped rather than made asynchronous: reading a declared skill's body touches nothing but
        // this node's own properties. AgentTool returns a stage so that a tool which
        // DOES cross a network -- an MCP one -- cannot force a block inside the completion callback of
        // the model's own call. A local tool completes immediately and loses nothing.
        return CompletableFuture.completedFuture(answer(argumentsJson));
    }

    private String answer(String argumentsJson) {
        if (skills.isEmpty()) {
            // Answered without reading the argument: with nothing to load no argument changes the
            // answer, and a parser reachable from remote content should not run for no purpose.
            return NO_SKILLS;
        }
        String requested = requestedName(argumentsJson);
        if (requested.isEmpty()) {
            return "This tool needs a JSON object with a \"name\" member naming one skill. " + available();
        }
        for (AgentSkill skill : skills) {
            if (skill.name().equalsIgnoreCase(requested)) {
                if (!loaded.add(skill.name().toLowerCase(Locale.ROOT))) {
                    // Named rather than silently repeated, so the model can tell "already have it"
                    // apart from "that name is wrong", which are different corrections.
                    return "Skill '" + skill.name() + "' is already loaded earlier in this "
                            + "conversation. Use what you were given; it is not repeated here.";
                }
                return skill.instructions();
            }
        }
        // A name the author never declared. A refusal and not a failure: a model that misremembers a
        // name has to be able to see that it did and pick a real one, so the refusal names the
        // available alternatives.
        return "No skill named '" + sanitized(requested) + "' is declared on this node. " + available();
    }

    /** The declared names, so a model that got one wrong has what it needs on the same turn. */
    private String available() {
        var names = new StringBuilder("The skills you may load are: ");
        for (int index = 0; index < skills.size(); index++) {
            names.append(index == 0 ? "" : ", ").append(skills.get(index).name());
        }
        return names.append('.').toString();
    }

    /**
     * The {@code name} member of a tool-call argument document, or {@code ""}.
     *
     * <p>Every failure to find one collapses to the empty string: malformed JSON, a document that is
     * not an object, a missing member, a member that is not text. They are one condition from the
     * model's point of view — it did not name a skill — and answering them separately would tell it
     * about this bundle's parser rather than about what to do next.</p>
     */
    private static String requestedName(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            PayloadValue parsed = PayloadJson.read(
                    argumentsJson.getBytes(StandardCharsets.UTF_8), ARGUMENT_LIMITS);
            if (parsed instanceof PayloadValue.MapValue root
                    && root.entries().get("name") instanceof PayloadValue.TextValue name) {
                return name.value().strip();
            }
            return "";
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * The requested name, made safe to quote back.
     *
     * <p>It is remote text and it is going into a message the model reads next, so control characters
     * are dropped and the length is bounded: a name is at most {@link AgentSkill#MAX_NAME_CHARS}, so
     * anything longer was never a name this node could have had. Nothing here reaches a log, an
     * execution attribute or a failure message — {@link AgentException} forbids that, and this string
     * never travels to one.</p>
     */
    private static String sanitized(String requested) {
        String bounded = requested.length() > AgentSkill.MAX_NAME_CHARS
                ? requested.substring(0, AgentSkill.MAX_NAME_CHARS)
                : requested;
        var clean = new StringBuilder(bounded.length());
        bounded.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)) {
                clean.appendCodePoint(codePoint);
            }
        });
        return clean.toString();
    }
}
