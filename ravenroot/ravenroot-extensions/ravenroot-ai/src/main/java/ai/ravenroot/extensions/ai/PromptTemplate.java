package ai.ravenroot.extensions.ai;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders {@code {{payload}}}, {@code {{payload.a.b}}}, {@code {{attributes.x}}} and
 * {@code {{properties.y}}} into a prompt.
 *
 * <h2>Where this came from, and why it is a copy</h2>
 * <p>Carried across from the {@code llm-prompt} the core used to ship, through
 * {@code ravenroot-dev-harness}'s {@code LlmPromptNodeBehaviorFactory}, which carried it first when
 * the node moved out of {@code ai.ravenroot.core.runtime.builtin}. The original lives in the
 * core's package-private {@code NodeProperties} and is not public API, and a plugin bundle cannot
 * reach a package-private core class in any case: the bundle classloader delegates
 * {@code ai.ravenroot.core.*} parent-first, but package-private access needs the same package and
 * the same loader, and a bundle has neither.</p>
 *
 * <p><b>The known cost, stated rather than discovered later:</b> this is a copy, so a change to the
 * core's rendering does not reach it. {@code PromptTemplateTest} pins the behaviour this copy
 * promises, which is what makes a future divergence a failing test in this module rather than a
 * silent difference between two nodes that look identical to an author.</p>
 *
 * <h2>An unresolvable path fails; it never renders empty</h2>
 * <p>Rendering an unresolvable token as an empty string, or leaving the token in place, are both
 * silent — and a prompt that quietly sends {@code Summarize } to a model is the same class of defect
 * as a run that reports success having done nothing.</p>
 */
final class PromptTemplate {

    private static final Pattern PAYLOAD_TOKEN = Pattern.compile("\\{\\{payload(\\.[^{}]*)?}}");

    private PromptTemplate() {
    }

    static String render(String template, Object payload, Map<String, Object> attributes,
                         Map<String, Object> properties) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PAYLOAD_TOKEN.matcher(template);
        var rendered = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            Object resolved = path == null || path.isEmpty()
                    ? requirePresent(payload, matcher.group())
                    : resolve(payload, path.substring(1), matcher.group());
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(String.valueOf(resolved)));
        }
        matcher.appendTail(rendered);
        String result = rendered.toString();
        for (var entry : attributes.entrySet()) {
            result = result.replace("{{attributes." + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        for (var entry : properties.entrySet()) {
            result = result.replace("{{properties." + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static Object requirePresent(Object payload, String token) {
        if (payload == null) {
            throw unrenderable(token);
        }
        return payload;
    }

    private static Object resolve(Object payload, String path, String token) {
        Object current = requirePresent(payload, token);
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                throw unrenderable(token);
            }
            current = step(current, segment, token);
        }
        return current;
    }

    private static Object step(Object current, String segment, String token) {
        if (current instanceof Map<?, ?> map) {
            Object next = map.get(segment);
            if (!map.containsKey(segment) || next == null) {
                throw unrenderable(token);
            }
            return next;
        }
        // Arrays are indexed alongside lists deliberately: a CEL list literal arrives as an Object[],
        // not a java.util.List, so treating only List would make {{payload.items.0}} work or not
        // depending on which upstream behavior produced the sequence.
        boolean isList = current instanceof List<?>;
        if (isList || current.getClass().isArray()) {
            int size = isList ? ((List<?>) current).size() : Array.getLength(current);
            int index;
            try {
                index = Integer.parseInt(segment);
            } catch (NumberFormatException notAnIndex) {
                throw unrenderable(token);
            }
            if (index < 0 || index >= size) {
                throw unrenderable(token);
            }
            Object next = isList ? ((List<?>) current).get(index) : Array.get(current, index);
            if (next == null) {
                throw unrenderable(token);
            }
            return next;
        }
        throw unrenderable(token);
    }

    /**
     * The token is the only thing that travels, and it is author-written template text rather than
     * payload content — the failure has to name something, and the alternative is a refusal that
     * says a prompt could not be rendered without saying which part of it. Truncated because the
     * path inside a token is unbounded by the pattern.
     */
    private static LlmPromptException unrenderable(String token) {
        return new LlmPromptException(LlmPromptException.Code.PROMPT_UNRENDERABLE,
                token.length() <= 64 ? token : token.substring(0, 64));
    }
}
