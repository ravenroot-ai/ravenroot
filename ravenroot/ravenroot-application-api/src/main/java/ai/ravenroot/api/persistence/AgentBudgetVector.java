package ai.ravenroot.api.persistence;

/**
 * Exact, non-negative agent authority and economic accounting dimensions.
 *
 * @param turns model-turn proposals
 * @param inputTokens model input tokens
 * @param outputTokens model output tokens
 * @param elapsedMillis elapsed execution milliseconds
 * @param costMicros monetary cost in root currency micros
 * @param toolCalls tool-call proposals
 * @param delegationDepth maximum delegation depth
 * @param teamCumulative cumulative unique child grants
 * @param teamActive concurrently active child grants
 */
public record AgentBudgetVector(long turns, long inputTokens, long outputTokens,
                                long elapsedMillis, long costMicros, long toolCalls,
                                long delegationDepth, long teamCumulative, long teamActive) {
    public static final AgentBudgetVector ZERO = new AgentBudgetVector(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Validates that every accounting dimension is non-negative. */
    public AgentBudgetVector {
        if (turns < 0 || inputTokens < 0 || outputTokens < 0 || elapsedMillis < 0
                || costMicros < 0 || toolCalls < 0 || delegationDepth < 0
                || teamCumulative < 0 || teamActive < 0) {
            throw new IllegalArgumentException("agent budget values cannot be negative");
        }
    }

    /**
     * Adds accounting dimensions exactly, saturating no values.
     *
     * @param other vector to add
     * @return summed vector
     */
    public AgentBudgetVector plus(AgentBudgetVector other) {
        return new AgentBudgetVector(add(turns, other.turns), add(inputTokens, other.inputTokens),
                add(outputTokens, other.outputTokens), add(elapsedMillis, other.elapsedMillis),
                add(costMicros, other.costMicros), add(toolCalls, other.toolCalls),
                Math.max(delegationDepth, other.delegationDepth),
                add(teamCumulative, other.teamCumulative), add(teamActive, other.teamActive));
    }

    /**
     * Subtracts accounting dimensions without permitting underflow.
     *
     * @param other vector to subtract
     * @return difference vector
     */
    public AgentBudgetVector minus(AgentBudgetVector other) {
        return new AgentBudgetVector(subtract(turns, other.turns), subtract(inputTokens, other.inputTokens),
                subtract(outputTokens, other.outputTokens), subtract(elapsedMillis, other.elapsedMillis),
                subtract(costMicros, other.costMicros), subtract(toolCalls, other.toolCalls),
                delegationDepth, subtract(teamCumulative, other.teamCumulative),
                subtract(teamActive, other.teamActive));
    }

    /**
     * Tests whether used and additional resources fit this maximum.
     *
     * @param used resources already consumed or reserved
     * @param additional proposed additional resources
     * @return {@code true} when all dimensions fit
     */
    public boolean contains(AgentBudgetVector used, AgentBudgetVector additional) {
        return fits(turns, used.turns, additional.turns)
                && fits(inputTokens, used.inputTokens, additional.inputTokens)
                && fits(outputTokens, used.outputTokens, additional.outputTokens)
                && fits(elapsedMillis, used.elapsedMillis, additional.elapsedMillis)
                && fits(costMicros, used.costMicros, additional.costMicros)
                && fits(toolCalls, used.toolCalls, additional.toolCalls)
                && delegationDepth >= additional.delegationDepth
                && fits(teamCumulative, used.teamCumulative, additional.teamCumulative)
                && fits(teamActive, used.teamActive, additional.teamActive);
    }

    /**
     * Tests componentwise ordering.
     *
     * @param ceiling candidate upper bound
     * @return {@code true} when this vector does not exceed the ceiling
     */
    public boolean componentwiseAtMost(AgentBudgetVector ceiling) {
        return turns <= ceiling.turns && inputTokens <= ceiling.inputTokens
                && outputTokens <= ceiling.outputTokens && elapsedMillis <= ceiling.elapsedMillis
                && costMicros <= ceiling.costMicros && toolCalls <= ceiling.toolCalls
                && delegationDepth <= ceiling.delegationDepth
                && teamCumulative <= ceiling.teamCumulative && teamActive <= ceiling.teamActive;
    }

    /**
     * Tests strict componentwise attenuation.
     *
     * @param ceiling candidate upper bound
     * @return {@code true} when this vector fits and differs in at least one dimension
     */
    public boolean strictlySmallerThan(AgentBudgetVector ceiling) {
        return componentwiseAtMost(ceiling) && !equals(ceiling);
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("agent budget arithmetic overflow", overflow);
        }
    }

    private static long subtract(long left, long right) {
        if (right > left) throw new IllegalArgumentException("agent budget accounting underflow");
        return left - right;
    }

    private static boolean fits(long maximum, long used, long additional) {
        return used <= maximum && additional <= maximum - used;
    }
}
