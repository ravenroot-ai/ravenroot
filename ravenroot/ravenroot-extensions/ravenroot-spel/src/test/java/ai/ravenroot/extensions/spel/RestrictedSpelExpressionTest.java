package ai.ravenroot.extensions.spel;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestrictedSpelExpressionTest {
    private static final Map<String, Object> ROOT = Map.of(
            "customer", Map.of("name", "Ada", "age", 37L, "active", true),
            "items", List.of(
                    Map.of("name", "one", "active", true),
                    Map.of("name", "two", "active", false)));

    @Test
    void supportsTheDocumentedPropertyIndexComparisonBooleanAndCollectionSubset() {
        assertEquals("Ada", evaluate("customer.name"));
        assertEquals("two", evaluate("items[1]['name']"));
        assertEquals(true, evaluate("customer.age >= 18 and customer.active"));
        assertEquals(false, evaluate("customer.age < 18 or not customer.active"));
        assertEquals("adult", evaluate("customer.age >= 18 ? 'adult' : 'minor'"));
        assertEquals("Ada", evaluate("customer.name ?: 'unknown'"));
        assertEquals(List.of(Map.of("name", "one", "active", true)),
                evaluate("items.?[active]"));
        assertEquals(List.of("one", "two"), evaluate("items.![name]"));
        assertEquals(List.of(1, 2), evaluate("{1,2}"));
        assertEquals(Map.of("answer", 42), evaluate("{'answer':42}"));
        assertEquals(null, evaluate("null"));
        assertEquals(2.5d, evaluate("2.5"));
        assertEquals(2L, evaluate("2L"));
    }

    @Test
    void exactClassAllowlistRefusesEveryExecutableOrAmbientLanguageFamily() {
        assertUnsupported("T(java.lang.Runtime)");
        assertUnsupported("new java.lang.String('x')");
        assertUnsupported("@environment");
        assertUnsupported("customer.name.toUpperCase()");
        assertUnsupported("getClass()");
        assertUnsupported("#root");
        assertUnsupported("#this");
        assertUnsupported("#fn()");
        assertUnsupported("customer.name = 'changed'");
        assertUnsupported("customer.age++");
        assertUnsupported("1 + 2");
        assertUnsupported("customer.name matches '.*'");
        assertUnsupported("customer.age between {1,100}");
        assertUnsupported("customer.name instanceof T(String)");
        assertEquals(false, evaluate("'1' == 1"));
    }

    @Test
    void springOperationBudgetTerminatesAnOtherwiseAllowedCollectionExpression() {
        var items = IntStream.range(0, SpelBounds.TREE.maxCollectionSize())
                .mapToObj(index -> Map.<String, Object>of("score", (long) index))
                .toList();
        String predicate = String.join(" and ", java.util.Collections.nCopies(12, "score >= 0"));
        var expression = RestrictedSpelExpression.compile("items.?[" + predicate + "]");

        SpelNodeException failure = assertThrows(SpelNodeException.class,
                () -> expression.evaluate(Map.of("items", items)));
        assertEquals(SpelNodeException.Code.EVALUATION_FAILED, failure.code());
        assertEquals("SPEL_EVALUATION_FAILED", failure.getMessage());
    }

    @Test
    void refusesMetaPropertiesSafeNavigationNestedCollectionOperatorsAndAstLimits() {
        assertCode("customer.class", SpelNodeException.Code.FORBIDDEN_PROPERTY);
        assertCode("customer?.ClAsS", SpelNodeException.Code.FORBIDDEN_PROPERTY);
        assertCode("items.![{'children':items.![name]}]", SpelNodeException.Code.AST_LIMIT_EXCEEDED);
        assertCode("!!!!!!!!!!!!!!!!!!!!true", SpelNodeException.Code.AST_LIMIT_EXCEEDED);
        assertCode("true and true and true and true and true and true and true and true and true and true"
                        + " and true and true and true and true and true and true and true and true",
                SpelNodeException.Code.AST_LIMIT_EXCEEDED);
        assertCode("x".repeat(SpelBounds.MAX_EXPRESSION_LENGTH + 1),
                SpelNodeException.Code.EXPRESSION_TOO_LONG);
    }

    @Test
    void refusesEveryNullSafeAndUndocumentedCollectionSemanticVariant() {
        for (String source : List.of(
                "customer?.name",
                "items?.[0]",
                "items?.?[active]",
                "items?.![name]",
                "items.^[active]",
                "items.$[active]",
                "items.![name?.[0]]")) {
            assertUnsupported(source);
        }
    }

    @Test
    void refusesStringComputedAndForbiddenIndexFormsBeforeSpringEvaluation() {
        assertUnsupported("'abc'[0]");
        assertUnsupported("customer[true ? 'class' : 'name']");
        assertUnsupported("customer[name]");
        for (String key : List.of("class", "getClass", "metaClass", "classLoader", "declaringClass",
                "protectionDomain", "__proto__", "prototype", "constructor")) {
            assertCode("customer['" + key + "']", SpelNodeException.Code.FORBIDDEN_PROPERTY);
            assertCode("{'" + key + "':'hidden'}", SpelNodeException.Code.FORBIDDEN_PROPERTY);
        }
        assertCode("customer[ClAsS]", SpelNodeException.Code.FORBIDDEN_PROPERTY);
        assertCode("{ClAsS:'hidden'}", SpelNodeException.Code.FORBIDDEN_PROPERTY);
        assertCode("{'safe':{'constructor':'hidden'}}", SpelNodeException.Code.FORBIDDEN_PROPERTY);
        assertCode("{{'__PrOtO__':'hidden'}}", SpelNodeException.Code.FORBIDDEN_PROPERTY);
        assertCode("items.![{'protectionDomain':'hidden'}['safe']]",
                SpelNodeException.Code.FORBIDDEN_PROPERTY);

        SpelNodeException nestedTextIndex = assertThrows(SpelNodeException.class,
                () -> RestrictedSpelExpression.compile("items.![name[0]]").evaluate(ROOT));
        assertEquals(SpelNodeException.Code.AST_UNSUPPORTED, nestedTextIndex.code());

        var customer = new CountingMap(Map.of("name", "Ada"));
        var root = new CountingMap(Map.of("customer", customer));
        RestrictedSpelExpression expression = RestrictedSpelExpression.compile("customer.name[0]");
        SpelNodeException failure = assertThrows(SpelNodeException.class, () -> expression.evaluate(root));
        assertEquals(SpelNodeException.Code.AST_UNSUPPORTED, failure.code());
        assertEquals(1, root.reads);
        assertEquals(1, customer.reads,
                "the semantic target guard must refuse before Spring evaluates the expression");
    }

    @Test
    void concurrentMixedListAndStringRootsCannotContaminateTheSharedIndexerCache() throws Exception {
        RestrictedSpelExpression expression = RestrictedSpelExpression.compile("value[0]");
        var executor = Executors.newFixedThreadPool(8);
        try {
            var evaluations = IntStream.range(0, 128).mapToObj(index -> CompletableFuture.runAsync(() -> {
                if ((index & 1) == 0) {
                    assertEquals((long) index, expression.evaluate(Map.of("value", List.of((long) index))));
                } else {
                    SpelNodeException failure = assertThrows(SpelNodeException.class,
                            () -> expression.evaluate(Map.of("value", "not-indexable")));
                    assertEquals(SpelNodeException.Code.AST_UNSUPPORTED, failure.code());
                }
            }, executor)).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(evaluations).join();
        } finally {
            executor.shutdownNow();
        }
    }

    private static Object evaluate(String source) {
        return RestrictedSpelExpression.compile(source).evaluate(ROOT);
    }

    private static void assertUnsupported(String source) {
        assertCode(source, SpelNodeException.Code.AST_UNSUPPORTED);
    }

    private static void assertCode(String source, SpelNodeException.Code code) {
        SpelNodeException failure = assertThrows(SpelNodeException.class,
                () -> RestrictedSpelExpression.compile(source));
        assertEquals(code, failure.code());
        assertEquals("SPEL_" + code.name(), failure.getMessage());
    }

    private static final class CountingMap extends AbstractMap<String, Object> {
        private final Map<String, Object> values;
        private int reads;

        private CountingMap(Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return values.entrySet();
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public Object get(Object key) {
            reads++;
            return values.get(key);
        }
    }
}
