package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the operator-owned graph limits into both live and recovered production execution. */
class GraphExecutionLimitCompositionWiringTest {
    private static final Path MAIN =
            Path.of("src/main/java/ai/ravenroot/server/RavenrootServerMain.java")
                    .toAbsolutePath().normalize();

    @Test
    void shippedCompositionUsesOneConfigurationForLiveReentryAndRecoveryDeliveryLimits()
            throws Exception {
        String source = Files.readString(MAIN);

        assertEquals(1, source.split(
                        "GraphExecutionLimits\\s*\\.fromEnvironment\\(System\\.getenv\\(\\)\\)", -1)
                        .length - 1,
                () -> MAIN + " must read operator graph limits exactly once");
        int resolution = source.indexOf("var graphExecutionLimits");
        int storeOpen = source.indexOf("ExecutionStoreBootstrap.openOwned(");
        assertTrue(resolution >= 0 && resolution < storeOpen,
                () -> MAIN + " must resolve graph limits before opening durable stores");
        assertTrue(source.contains("java.time.Clock.systemUTC(), graphExecutionLimits.graphMl()"),
                () -> MAIN + " must give the durable definition store the same graph byte limit");
        assertTrue(source.contains("embedConfiguration, userCredentials, graphExecutionLimits.graphMl()"),
                () -> MAIN + " must give HTTP admission and served configuration the same graph byte limit");
        assertRecoveryConstructorWiring(source);
        assertExecutionManifestBinding(source);
        assertTrue(source.contains("graphExecutionLimits.maxRecoveryDeliveriesPerAttempt())"),
                () -> MAIN + " must bound production recovery delivery attempts from operator configuration");
    }

    @Test
    void recoveryWiringProofRejectsMissingSwappedAndSecondManifestArguments() throws Exception {
        String source = Files.readString(MAIN);
        assertRecoveryConstructorWiring(source);

        String missingManifest = replaceOne(source,
                "executionManifests, executionRuntime\\.toolApprovalRunnerShutdownStepBound\\(\\)",
                "executionRuntime.toolApprovalRunnerShutdownStepBound()");
        assertThrows(AssertionError.class, () -> assertRecoveryConstructorWiring(missingManifest));
        assertThrows(AssertionError.class, () -> assertRecoveryConstructorWiring(replaceOne(source,
                "agentBudgets,\\s*\\n\\s*executionManifests, executionRuntime\\.toolApprovalRunnerShutdownStepBound",
                "executionManifests, agentBudgets, executionRuntime.toolApprovalRunnerShutdownStepBound")));
        assertThrows(AssertionError.class, () -> assertRecoveryConstructorWiring(replaceOne(source,
                "executionManifests, executionRuntime\\.humanTaskRunnerShutdownStepBound\\(\\)",
                "secondExecutionManifests, executionRuntime.humanTaskRunnerShutdownStepBound()")));

        String commentedDecoy = missingManifest + "\n// new PinnedGraphToolApprovalContinuationExecutor("
                + "ignored, recoveryConfiguration.leaseTtl(), graphExecutionLimits, agentBudgets, "
                + "executionManifests, executionRuntime.toolApprovalRunnerShutdownStepBound());\n";
        assertThrows(AssertionError.class, () -> assertRecoveryConstructorWiring(commentedDecoy));
    }

    private static void assertRecoveryConstructorWiring(String source) {
        assertEquals(List.of(
                        "recoveryConfiguration.leaseTtl()", "graphExecutionLimits", "agentBudgets",
                        "executionManifests",
                        "executionRuntime.toolApprovalRunnerShutdownStepBound()"),
                trailingArguments(constructorArguments(
                        source, "PinnedGraphToolApprovalContinuationExecutor"), 5),
                "tool re-entry must receive the lease bound, live limits, budgets, application manifest service, "
                        + "and its configured runner bound in their constructor positions");
        assertEquals(List.of(
                        "recoveryConfiguration.leaseTtl()", "graphExecutionLimits", "agentBudgets",
                        "executionManifests",
                        "executionRuntime.humanTaskRunnerShutdownStepBound()"),
                trailingArguments(constructorArguments(
                        source, "PinnedGraphHumanTaskContinuationExecutor"), 5),
                "human-task re-entry must receive the lease bound, live limits, budgets, application manifest "
                        + "service, and its configured runner bound in their constructor positions");
    }

    private static void assertExecutionManifestBinding(String source) {
        Matcher binding = Pattern.compile("\\bvar\\s+executionManifests\\s*=\\s*"
                        + "application\\.executionManifests\\s*\\(\\s*\\)\\s*;")
                .matcher(maskNonCode(source));
        assertTrue(binding.find(), () -> MAIN + " must take the manifest service from the composed application");
        assertTrue(!binding.find(), () -> MAIN + " must bind the application manifest service exactly once");
    }

    private static List<String> constructorArguments(String source, String simpleName) {
        String code = maskNonCode(source);
        Pattern constructor = Pattern.compile("new\\s+(?:[A-Za-z_$][\\w$]*\\.)*"
                + Pattern.quote(simpleName) + "\\s*\\(");
        Matcher matcher = constructor.matcher(code);
        assertTrue(matcher.find(), () -> "missing constructor " + simpleName);
        int opening = code.indexOf('(', matcher.start());
        int closing = matchingParenthesis(code, opening);
        assertTrue(!matcher.find(), () -> "duplicate constructor " + simpleName);
        return splitArguments(source.substring(opening + 1, closing));
    }

    private static List<String> trailingArguments(List<String> arguments, int count) {
        assertTrue(arguments.size() >= count, "constructor has too few arguments");
        return arguments.subList(arguments.size() - count, arguments.size());
    }

    private static int matchingParenthesis(String source, int opening) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = opening; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else if (current == '"') quoted = true;
            else if (current == '(') depth++;
            else if (current == ')' && --depth == 0) return index;
        }
        throw new IllegalStateException("unterminated constructor argument list");
    }

    private static List<String> splitArguments(String arguments) {
        var result = new ArrayList<String>();
        int start = 0;
        int round = 0;
        int square = 0;
        int braces = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < arguments.length(); index++) {
            char current = arguments.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '(') round++;
            else if (current == ')') round--;
            else if (current == '[') square++;
            else if (current == ']') square--;
            else if (current == '{') braces++;
            else if (current == '}') braces--;
            else if (current == ',' && round == 0 && square == 0 && braces == 0) {
                result.add(normalized(arguments.substring(start, index)));
                start = index + 1;
            }
        }
        result.add(normalized(arguments.substring(start)));
        return List.copyOf(result);
    }

    private static String normalized(String source) {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static String replaceOne(String source, String expression, String replacement) {
        Matcher matcher = Pattern.compile(expression).matcher(source);
        assertTrue(matcher.find(), () -> "missing source mutation anchor " + expression);
        int start = matcher.start();
        int end = matcher.end();
        assertTrue(!matcher.find(), () -> "duplicate source mutation anchor " + expression);
        String changed = source.substring(0, start) + replacement + source.substring(end);
        assertTrue(!changed.equals(source), () -> "source mutation did not change " + expression);
        return changed;
    }

    private static String maskNonCode(String source) {
        char[] masked = source.toCharArray();
        int state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 character, 5 text block
        boolean escaped = false;
        for (int index = 0; index < masked.length; index++) {
            char current = source.charAt(index);
            char next = index + 1 < masked.length ? source.charAt(index + 1) : '\0';
            if (state == 0) {
                if (current == '/' && next == '/') {
                    state = 1;
                    masked[index] = ' ';
                    continue;
                }
                if (current == '/' && next == '*') {
                    state = 2;
                    masked[index] = ' ';
                    continue;
                }
                if (current == '"' && index + 2 < masked.length
                        && source.charAt(index + 1) == '"' && source.charAt(index + 2) == '"') {
                    state = 5;
                    masked[index] = ' ';
                    masked[++index] = ' ';
                    masked[++index] = ' ';
                    continue;
                }
                if (current == '"') {
                    state = 3;
                    masked[index] = ' ';
                    continue;
                }
                if (current == '\'') {
                    state = 4;
                    masked[index] = ' ';
                    continue;
                }
                continue;
            }
            if (current != '\n' && current != '\r') masked[index] = ' ';
            if (state == 1 && (current == '\n' || current == '\r')) state = 0;
            else if (state == 2 && current == '*' && next == '/') {
                masked[++index] = ' ';
                state = 0;
            } else if ((state == 3 || state == 4) && escaped) escaped = false;
            else if ((state == 3 || state == 4) && current == '\\') escaped = true;
            else if (state == 3 && current == '"') state = 0;
            else if (state == 4 && current == '\'') state = 0;
            else if (state == 5 && current == '"' && index + 2 < masked.length
                    && source.charAt(index + 1) == '"' && source.charAt(index + 2) == '"') {
                masked[++index] = ' ';
                masked[++index] = ' ';
                state = 0;
            }
        }
        if (state == 2 || state == 3 || state == 4 || state == 5) {
            throw new IllegalStateException("unterminated Java non-code region");
        }
        return new String(masked);
    }
}
