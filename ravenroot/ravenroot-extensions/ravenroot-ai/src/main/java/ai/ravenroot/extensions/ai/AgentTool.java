package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.payload.PayloadValue;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * One thing an agent may call during its loop.
 *
 * <h2>The contract that keeps a tool from ending an execution</h2>
 * <p>{@link #invoke(String)} <b>answers</b>; it does not fail. A model that passes a wrong argument,
 * names something that does not exist, or asks for a thing it may not have gets a refusal
 * <em>as a tool result</em>, which re-enters the loop as an ordinary message and lets the model
 * correct itself. Turning that into a node failure would make a single malformed argument from a
 * non-deterministic component terminate a traversal, which is the opposite of what an agent is for.</p>
 *
 * <p>The budget is the thing that stops a model that cannot correct itself: a refusal costs a turn,
 * and {@code maxTurns} is finite. That is the intended division — tools answer, budgets terminate.</p>
 *
 * <h2>Results are remote-influenced text</h2>
 * <p>A result goes straight into the model's next turn and, for the built-in tools, is derived from
 * graph content. It is never put into a failure message, a log line or an execution attribute by
 * this bundle. See {@link AgentException} for the rule and its reason.</p>
 */
interface AgentTool {

    /** The name the model calls. Stable, and unique within one node's tool set. */
    String name();

    /** What the model is told this tool does. Short: it is paid for on every turn. */
    String description();

    /** JSON Schema object describing the arguments, exactly as the wire document expects it. */
    PayloadValue.MapValue parameters();

    /**
     * Runs the tool and produces the text that becomes the {@code tool} message.
     *
     * <h2>Why this is a stage and not a {@code String}</h2>
     * <p>It was a {@code String} while every tool was local. {@code load_skill} reads a node property
     * and returns; an MCP tool crosses a network. Making that call synchronous would mean blocking
     * inside the completion callback of the model's own HTTP call — on a thread this bundle does not
     * own and whose pool it cannot see — which is a deadlock whose likelihood depends on which engine
     * adapter is installed. The same reasoning the class-level rules already apply to synchronous
     * throws applies here: this bundle does not decide how the runtime's threads are used.</p>
     *
     * <p>So a tool answers <em>eventually</em>. A local tool completes its stage immediately and
     * loses nothing.</p>
     *
     * @param argumentsJson the bounded canonical argument object the server-side reference monitor
     *     evaluated; never the unchecked string the model produced
     * @return a stage carrying non-null model-facing text and its independent terminal effect
     *     outcome. Text is never empty, because an empty tool message reads to a model as a
     *     successful call that returned nothing. The stage itself should not fail — a tool that
     *     fails it is treated by {@link AgentNodeBehavior} as a refusal costing one turn, never as a
     *     node failure
     */
    CompletionStage<Result> invoke(String argumentsJson);

    /** Terminal status of the authorized effect, independent of what the model is told. */
    enum Outcome {
        SUCCEEDED,
        FAILED
    }

    /** Model-facing text kept structurally separate from the terminal effect outcome used by audit. */
    record Result(String text, Outcome outcome) {
        public Result {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(outcome, "outcome");
            if (text.isEmpty()) {
                throw new IllegalArgumentException("tool result text cannot be empty");
            }
        }

        static Result succeeded(String text) {
            return new Result(text, Outcome.SUCCEEDED);
        }

        static Result failed(String text) {
            return new Result(text, Outcome.FAILED);
        }

        boolean succeeded() {
            return outcome == Outcome.SUCCEEDED;
        }
    }
}
