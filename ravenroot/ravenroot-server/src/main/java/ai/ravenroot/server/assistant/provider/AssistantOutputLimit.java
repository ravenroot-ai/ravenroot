package ai.ravenroot.server.assistant.provider;

import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.server.assistant.AssistantOutcome;

import java.nio.charset.StandardCharsets;

/** Final projection accounting shared by the HTTP assistant adapters. */
final class AssistantOutputLimit {
    private AssistantOutputLimit() { }

    static long ceiling(long decodedBytes) {
        try {
            return Math.min(Integer.MAX_VALUE, Math.addExact(Math.multiplyExact(decodedBytes, 2L), 64L * 1024));
        } catch (ArithmeticException overflow) {
            return Integer.MAX_VALUE;
        }
    }

    static AssistantProvider.Turn requireWithin(AssistantProvider.Turn turn, ExternalIoLimits limits)
            throws AssistantProviderException {
        try {
            long bytes = switch (turn) {
                case AssistantProvider.Turn.Answer answer -> add(64, text(answer.text()), text(answer.model()));
                case AssistantProvider.Turn.Refused refused -> add(32, text(refused.category()));
                case AssistantProvider.Turn.ToolCalls tools -> {
                    long count = add(64, text(tools.model()));
                    for (AssistantProvider.Content.ToolUse call : tools.calls()) count = add(count, tool(call));
                    for (AssistantProvider.Content content : tools.assistantContent()) {
                        count = add(count, switch (content) {
                            case AssistantProvider.Content.Text value -> text(value.text());
                            case AssistantProvider.Content.ToolUse value -> tool(value);
                            case AssistantProvider.Content.ToolResult value ->
                                    add(16, text(value.toolUseId()), text(value.content()));
                        });
                    }
                    yield count;
                }
            };
            limits.requireOutputBytes(bytes);
            return turn;
        } catch (ArithmeticException | IllegalArgumentException oversized) {
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider output exceeded its projection budget", null);
        }
    }

    private static long tool(AssistantProvider.Content.ToolUse value) {
        return add(32, text(value.id()), text(value.name()), text(value.inputJson()));
    }

    private static long text(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long add(long initial, long... values) {
        long total = initial;
        for (long value : values) total = Math.addExact(total, value);
        return total;
    }
}
