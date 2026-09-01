package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** A positive RFC 9535 compiler and bounded, invocation-local evaluator. */
final class Rfc9535JsonPath {
    private static final int MAX_QUERY_SELECTORS = 256;
    private static final int MAX_EXPRESSION_DEPTH = 64;
    private static final int MAX_EXPRESSION_NODES = 512;
    private static final int MAX_EXPRESSION_EVALUATIONS = 10_000;
    private static final int MAX_IREGEXP_DEPTH = 64;
    private static final int MAX_IREGEXP_NODES = 4_096;
    private static final int MAX_NODELIST = PayloadLimits.DEFAULTS.maxValueCount();
    private static final int MAX_RESULT = PayloadLimits.DEFAULTS.maxCollectionSize();
    private static final long MAX_WORK = 100_000;
    private static final BigInteger MAX_IJSON_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
    private static final Object NOTHING = new Object();

    private final Query query;

    private Rfc9535JsonPath(Query query) {
        this.query = query;
    }

    static Rfc9535JsonPath compile(String source) {
        if (source == null || source.isBlank()) throw invalid();
        if (source.length() > PayloadLimits.DEFAULTS.maxTextLength()) throw resource();
        return new Rfc9535JsonPath(new Parser(source).parse());
    }

    List<PayloadValue> select(PayloadValue root) {
        var budget = new Budget(MAX_WORK);
        List<PayloadValue> selected = evaluate(query,
                new Context(root, root, budget, new ExpressionBudget()), MAX_RESULT);
        validateOutput(selected, budget);
        return selected;
    }

    /** Checks the eventual JSON array before the factory projects any selected subtree back to Java. */
    private static void validateOutput(List<PayloadValue> selected, Budget budget) {
        long values = 1;
        long encodedBytes = 2;
        boolean first = true;
        for (PayloadValue value : selected) {
            long subtreeValues = countValues(value, budget);
            if (values + subtreeValues > PayloadLimits.DEFAULTS.maxValueCount()) throw resource();
            values += subtreeValues;
            int valueBytes = PayloadJson.write(value).getBytes(StandardCharsets.UTF_8).length;
            encodedBytes += valueBytes + (first ? 0 : 1);
            first = false;
            if (encodedBytes > PayloadLimits.DEFAULTS.maxEncodedBytes()) throw resource();
        }
    }

    private static long countValues(PayloadValue value, Budget budget) {
        budget.consume(1);
        return switch (value) {
            case PayloadValue.ListValue list -> {
                long count = 1;
                for (PayloadValue child : list.values()) count += countValues(child, budget);
                yield count;
            }
            case PayloadValue.MapValue map -> {
                long count = 1;
                for (PayloadValue child : map.entries().values()) count += countValues(child, budget);
                yield count;
            }
            default -> 1;
        };
    }

    private static List<PayloadValue> evaluate(Query query, Context context, int resultLimit) {
        List<PayloadValue> current = List.of(query.absolute() ? context.root() : context.current());
        for (int index = 0; index < query.segments().size(); index++) {
            Segment segment = query.segments().get(index);
            int limit = index == query.segments().size() - 1 ? resultLimit : MAX_NODELIST;
            var next = new ArrayList<PayloadValue>();
            for (PayloadValue node : current) {
                if (segment.descendant()) {
                    visitDescendants(node, descendant -> applySelectors(segment.selectors(), descendant,
                            context, next, limit), context.budget());
                } else {
                    applySelectors(segment.selectors(), node, context, next, limit);
                }
            }
            current = List.copyOf(next);
            if (current.isEmpty()) break;
        }
        return current;
    }

    private static void visitDescendants(PayloadValue value, java.util.function.Consumer<PayloadValue> visitor,
                                         Budget budget) {
        budget.consume(1);
        visitor.accept(value);
        switch (value) {
            case PayloadValue.ListValue list -> list.values().forEach(child -> visitDescendants(child, visitor, budget));
            case PayloadValue.MapValue map -> orderedEntries(map).forEach(entry ->
                    visitDescendants(entry.getValue(), visitor, budget));
            default -> { }
        }
    }

    private static void applySelectors(List<Selector> selectors, PayloadValue input, Context context,
                                       List<PayloadValue> output, int limit) {
        for (Selector selector : selectors) {
            context.budget().consume(1);
            switch (selector) {
                case NameSelector name -> {
                    if (input instanceof PayloadValue.MapValue map) {
                        add(output, map.entries().get(name.name()), limit, context.budget());
                    }
                }
                case IndexSelector selected -> {
                    if (input instanceof PayloadValue.ListValue list) {
                        long normalized = selected.index() < 0 ? list.values().size() + selected.index() : selected.index();
                        if (normalized >= 0 && normalized < list.values().size()) {
                            add(output, list.values().get((int) normalized), limit, context.budget());
                        }
                    }
                }
                case WildcardSelector ignored -> {
                    if (input instanceof PayloadValue.ListValue list) {
                        list.values().forEach(value -> add(output, value, limit, context.budget()));
                    } else if (input instanceof PayloadValue.MapValue map) {
                        orderedEntries(map).forEach(entry -> add(output, entry.getValue(), limit, context.budget()));
                    }
                }
                case SliceSelector slice -> applySlice(slice, input, output, limit, context.budget());
                case FilterSelector filter -> applyFilter(filter, input, context, output, limit);
            }
        }
    }

    private static void applySlice(SliceSelector slice, PayloadValue input, List<PayloadValue> output,
                                   int limit, Budget budget) {
        if (!(input instanceof PayloadValue.ListValue list)) return;
        long length = list.values().size();
        long step = slice.step() == null ? 1 : slice.step();
        if (step == 0) return;
        long start = slice.start() == null ? (step > 0 ? 0 : length - 1) : slice.start();
        long end = slice.end() == null ? (step > 0 ? length : -length - 1) : slice.end();
        long normalizedStart = start >= 0 ? start : length + start;
        long normalizedEnd = end >= 0 ? end : length + end;
        if (step > 0) {
            long lower = Math.min(Math.max(normalizedStart, 0), length);
            long upper = Math.min(Math.max(normalizedEnd, 0), length);
            for (long index = lower; index < upper; index += step) {
                add(output, list.values().get((int) index), limit, budget);
            }
        } else {
            long upper = Math.min(Math.max(normalizedStart, -1), length - 1);
            long lower = Math.min(Math.max(normalizedEnd, -1), length - 1);
            for (long index = upper; lower < index; index += step) {
                add(output, list.values().get((int) index), limit, budget);
            }
        }
    }

    private static void applyFilter(FilterSelector filter, PayloadValue input, Context context,
                                    List<PayloadValue> output, int limit) {
        if (input instanceof PayloadValue.ListValue list) {
            for (PayloadValue candidate : list.values()) {
                context.budget().consume(1);
                if (logical(filter.expression(), context.withCurrent(candidate))) {
                    add(output, candidate, limit, context.budget());
                }
            }
        } else if (input instanceof PayloadValue.MapValue map) {
            for (Map.Entry<String, PayloadValue> entry : orderedEntries(map)) {
                context.budget().consume(1);
                if (logical(filter.expression(), context.withCurrent(entry.getValue()))) {
                    add(output, entry.getValue(), limit, context.budget());
                }
            }
        }
    }

    private static void add(List<PayloadValue> output, PayloadValue value, int limit, Budget budget) {
        if (value == null) return;
        budget.consume(1);
        if (output.size() >= limit) throw resource();
        output.add(value);
    }

    private static List<Map.Entry<String, PayloadValue>> orderedEntries(PayloadValue.MapValue map) {
        return map.entries().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    private static boolean logical(Expression expression, Context context) {
        Object result = evaluateExpression(expression, context);
        return result instanceof Boolean flag && flag;
    }

    private static Object evaluateExpression(Expression expression, Context context) {
        context.expressionBudget().enter();
        try {
            return expression.evaluate(context);
        } finally {
            context.expressionBudget().exit();
        }
    }

    private static Object comparable(Object value) {
        if (value instanceof List<?> nodes) {
            return nodes.size() == 1 ? nodes.getFirst() : NOTHING;
        }
        return value;
    }

    private static boolean equalValues(Object left, Object right) {
        left = comparable(left);
        right = comparable(right);
        if (left == NOTHING || right == NOTHING) return left == right;
        if (left instanceof PayloadValue leftPayload && right instanceof PayloadValue rightPayload) {
            return equalPayload(leftPayload, rightPayload);
        }
        return left.equals(right);
    }

    private static boolean equalPayload(PayloadValue left, PayloadValue right) {
        if (left instanceof PayloadValue.IntegerValue leftInteger
                && right instanceof PayloadValue.DecimalValue rightDecimal) {
            return BigDecimal.valueOf(leftInteger.value()).compareTo(BigDecimal.valueOf(rightDecimal.value())) == 0;
        }
        if (left instanceof PayloadValue.DecimalValue leftDecimal
                && right instanceof PayloadValue.IntegerValue rightInteger) {
            return BigDecimal.valueOf(leftDecimal.value()).compareTo(BigDecimal.valueOf(rightInteger.value())) == 0;
        }
        if (left instanceof PayloadValue.ListValue leftList && right instanceof PayloadValue.ListValue rightList) {
            if (leftList.values().size() != rightList.values().size()) return false;
            for (int index = 0; index < leftList.values().size(); index++) {
                if (!equalPayload(leftList.values().get(index), rightList.values().get(index))) return false;
            }
            return true;
        }
        if (left instanceof PayloadValue.MapValue leftMap && right instanceof PayloadValue.MapValue rightMap) {
            if (!leftMap.entries().keySet().equals(rightMap.entries().keySet())) return false;
            return leftMap.entries().entrySet().stream()
                    .allMatch(entry -> equalPayload(entry.getValue(), rightMap.entries().get(entry.getKey())));
        }
        return left.equals(right);
    }

    private static Integer order(Object left, Object right) {
        left = comparable(left);
        right = comparable(right);
        if (left instanceof PayloadValue.IntegerValue leftInteger) {
            BigDecimal leftNumber = BigDecimal.valueOf(leftInteger.value());
            if (right instanceof PayloadValue.IntegerValue rightInteger) {
                return leftNumber.compareTo(BigDecimal.valueOf(rightInteger.value()));
            }
            if (right instanceof PayloadValue.DecimalValue rightDecimal) {
                return leftNumber.compareTo(BigDecimal.valueOf(rightDecimal.value()));
            }
        }
        if (left instanceof PayloadValue.DecimalValue leftDecimal) {
            BigDecimal leftNumber = BigDecimal.valueOf(leftDecimal.value());
            if (right instanceof PayloadValue.IntegerValue rightInteger) {
                return leftNumber.compareTo(BigDecimal.valueOf(rightInteger.value()));
            }
            if (right instanceof PayloadValue.DecimalValue rightDecimal) {
                return leftNumber.compareTo(BigDecimal.valueOf(rightDecimal.value()));
            }
        }
        if (left instanceof PayloadValue.TextValue leftText && right instanceof PayloadValue.TextValue rightText) {
            return compareUnicode(leftText.value(), rightText.value());
        }
        return null;
    }

    private static int compareUnicode(String left, String right) {
        var leftPoints = left.codePoints().iterator();
        var rightPoints = right.codePoints().iterator();
        while (leftPoints.hasNext() && rightPoints.hasNext()) {
            int compared = Integer.compare(leftPoints.nextInt(), rightPoints.nextInt());
            if (compared != 0) return compared;
        }
        return Boolean.compare(leftPoints.hasNext(), rightPoints.hasNext());
    }

    private record Context(PayloadValue root, PayloadValue current, Budget budget,
                           ExpressionBudget expressionBudget) {
        Context withCurrent(PayloadValue next) {
            return new Context(root, next, budget, expressionBudget);
        }
    }

    private static final class Budget {
        private long remaining;

        Budget(long remaining) {
            this.remaining = remaining;
        }

        void consume(long units) {
            if (units < 0 || remaining < units) throw resource();
            remaining -= units;
        }
    }

    /** Bounds every recursive expression descent independently of the broader evaluator work budget. */
    private static final class ExpressionBudget {
        private int depth;
        private int evaluations;

        void enter() {
            if (depth >= MAX_EXPRESSION_DEPTH || evaluations >= MAX_EXPRESSION_EVALUATIONS) {
                throw resource();
            }
            depth++;
            evaluations++;
        }

        void exit() {
            depth--;
        }
    }

    private record Query(boolean absolute, List<Segment> segments, boolean singular) { }
    private record Segment(boolean descendant, List<Selector> selectors) { }

    private sealed interface Selector permits NameSelector, WildcardSelector, IndexSelector, SliceSelector,
            FilterSelector { }
    private record NameSelector(String name) implements Selector { }
    private record WildcardSelector() implements Selector { }
    private record IndexSelector(long index) implements Selector { }
    private record SliceSelector(Long start, Long end, Long step) implements Selector { }
    private record FilterSelector(Expression expression) implements Selector { }

    private enum StaticType { VALUE, LOGICAL, NODES }

    private sealed interface Expression permits LiteralExpression, QueryExpression, FunctionExpression,
            ComparisonExpression, LogicalExpression, NotExpression {
        StaticType type();
        Object evaluate(Context context);
    }

    private record LiteralExpression(PayloadValue value) implements Expression {
        public StaticType type() { return StaticType.VALUE; }
        public Object evaluate(Context context) {
            context.budget().consume(1);
            return value;
        }
    }

    private record QueryExpression(Query query) implements Expression {
        public StaticType type() { return StaticType.NODES; }
        public Object evaluate(Context context) {
            context.budget().consume(1);
            return Rfc9535JsonPath.evaluate(query, context, MAX_NODELIST);
        }
    }

    private enum FunctionName { LENGTH, COUNT, MATCH, SEARCH, VALUE }

    private record FunctionExpression(FunctionName name, List<Expression> arguments, StaticType type)
            implements Expression {
        public Object evaluate(Context context) {
            context.budget().consume(1);
            return switch (name) {
                case LENGTH -> length(comparable(evaluateExpression(arguments.getFirst(), context)));
                case COUNT -> PayloadValue.of(((List<?>) evaluateExpression(arguments.getFirst(), context)).size());
                case VALUE -> comparable(evaluateExpression(arguments.getFirst(), context));
                case MATCH, SEARCH -> regex(context, name == FunctionName.MATCH);
            };
        }

        private Object regex(Context context, boolean entire) {
            Object subjectValue = comparable(evaluateExpression(arguments.get(0), context));
            Object patternValue = comparable(evaluateExpression(arguments.get(1), context));
            if (!(subjectValue instanceof PayloadValue.TextValue subject)
                    || !(patternValue instanceof PayloadValue.TextValue expression)) return false;
            context.budget().consume((long) subject.value().length() + expression.value().length());
            try {
                String translated = IRegexp.translate(expression.value(), entire);
                if (translated == null) return false;
                return Pattern.compile(translated).matcher(subject.value()).find();
            } catch (PatternSyntaxException invalidPattern) {
                return false;
            }
        }

        private static Object length(Object value) {
            return switch (value) {
                case PayloadValue.TextValue text -> PayloadValue.of(text.value().codePointCount(0, text.value().length()));
                case PayloadValue.ListValue list -> PayloadValue.of(list.values().size());
                case PayloadValue.MapValue map -> PayloadValue.of(map.entries().size());
                default -> NOTHING;
            };
        }
    }

    private enum ComparisonOperator { EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL }

    private record ComparisonExpression(Expression left, ComparisonOperator operator, Expression right)
            implements Expression {
        public StaticType type() { return StaticType.LOGICAL; }
        public Object evaluate(Context context) {
            context.budget().consume(1);
            Object leftValue = evaluateExpression(left, context);
            Object rightValue = evaluateExpression(right, context);
            if (operator == ComparisonOperator.EQUAL) return equalValues(leftValue, rightValue);
            if (operator == ComparisonOperator.NOT_EQUAL) return !equalValues(leftValue, rightValue);
            if (equalValues(leftValue, rightValue)) {
                return operator == ComparisonOperator.LESS_EQUAL || operator == ComparisonOperator.GREATER_EQUAL;
            }
            Integer ordered = order(leftValue, rightValue);
            if (ordered == null) return false;
            return switch (operator) {
                case LESS -> ordered < 0;
                case LESS_EQUAL -> ordered <= 0;
                case GREATER -> ordered > 0;
                case GREATER_EQUAL -> ordered >= 0;
                default -> throw new AssertionError(operator);
            };
        }
    }

    private enum LogicalOperator { AND, OR }

    private record LogicalExpression(Expression left, LogicalOperator operator, Expression right)
            implements Expression {
        public StaticType type() { return StaticType.LOGICAL; }
        public Object evaluate(Context context) {
            context.budget().consume(1);
            boolean leftValue = logical(left, context);
            return operator == LogicalOperator.AND
                    ? leftValue && logical(right, context)
                    : leftValue || logical(right, context);
        }
    }

    private record NotExpression(Expression operand) implements Expression {
        public StaticType type() { return StaticType.LOGICAL; }
        public Object evaluate(Context context) {
            context.budget().consume(1);
            return !logical(operand, context);
        }
    }

    private static final class Parser {
        private final String source;
        private int offset;
        private int selectors;
        private int expressionDepth;
        private int expressionNodes;
        private final IdentityHashMap<Expression, Integer> expressionDepths = new IdentityHashMap<>();

        Parser(String source) {
            this.source = source;
        }

        Query parse() {
            Query parsed = parseQuery();
            if (!parsed.absolute() || offset != source.length()) throw invalid();
            return parsed;
        }

        private Query parseQuery() {
            boolean absolute;
            if (take('$')) absolute = true;
            else if (take('@')) absolute = false;
            else throw invalid();
            List<Segment> segments = parseSegments();
            boolean singular = segments.stream().allMatch(segment -> !segment.descendant()
                    && segment.selectors().size() == 1
                    && (segment.selectors().getFirst() instanceof NameSelector
                    || segment.selectors().getFirst() instanceof IndexSelector));
            return new Query(absolute, segments, singular);
        }

        private List<Segment> parseSegments() {
            var segments = new ArrayList<Segment>();
            while (true) {
                int beforeSpace = offset;
                skipSpace();
                if (peek('[')) {
                    segments.add(new Segment(false, parseBracketedSelection()));
                } else if (peek('.') && peek(1, '.')) {
                    offset += 2;
                    segments.add(new Segment(true, parseDescendantSelectors()));
                } else if (take('.')) {
                    segments.add(new Segment(false, List.of(parseShorthandSelector())));
                } else {
                    offset = beforeSpace;
                    break;
                }
            }
            return List.copyOf(segments);
        }

        private List<Selector> parseDescendantSelectors() {
            if (peek('[')) return parseBracketedSelection();
            return List.of(parseShorthandSelector());
        }

        private Selector parseShorthandSelector() {
            countSelector();
            if (take('*')) return new WildcardSelector();
            return new NameSelector(parseMemberName());
        }

        private List<Selector> parseBracketedSelection() {
            expect('[');
            skipSpace();
            var result = new ArrayList<Selector>();
            result.add(parseSelector());
            skipSpace();
            while (take(',')) {
                skipSpace();
                result.add(parseSelector());
                skipSpace();
            }
            expect(']');
            return List.copyOf(result);
        }

        private Selector parseSelector() {
            countSelector();
            if (peek('\'') || peek('"')) return new NameSelector(parseString());
            if (take('*')) return new WildcardSelector();
            if (take('?')) {
                skipSpace();
                return new FilterSelector(parseLogicalExpression());
            }
            return parseIndexOrSlice();
        }

        private Selector parseIndexOrSlice() {
            Long first = null;
            if (!peek(':')) first = parseInteger(false);
            int afterFirst = offset;
            skipSpace();
            if (!take(':')) {
                offset = afterFirst;
                if (first == null) throw invalid();
                return new IndexSelector(first);
            }
            skipSpace();
            Long end = null;
            if (!peek(':') && !peek(']') && !peek(',')) end = parseInteger(false);
            skipSpace();
            Long step = null;
            if (take(':')) {
                skipSpace();
                if (!peek(']') && !peek(',')) step = parseInteger(false);
                skipSpace();
            }
            return new SliceSelector(first, end, step);
        }

        private Expression parseLogicalExpression() {
            enterExpression();
            try {
                Expression result = parseLogicalAnd();
                skipSpace();
                while (take('|')) {
                    expect('|');
                    skipSpace();
                    Expression right = parseLogicalAnd();
                    result = recordExpression(new LogicalExpression(result, LogicalOperator.OR, right), result, right);
                    skipSpace();
                }
                return result;
            } finally {
                expressionDepth--;
            }
        }

        private Expression parseLogicalAnd() {
            Expression result = parseBasicExpression();
            skipSpace();
            while (take('&')) {
                expect('&');
                skipSpace();
                Expression right = parseBasicExpression();
                result = recordExpression(new LogicalExpression(result, LogicalOperator.AND, right), result, right);
                skipSpace();
            }
            return result;
        }

        private Expression parseBasicExpression() {
            boolean negated = take('!');
            if (negated) skipSpace();
            if (take('(')) {
                skipSpace();
                Expression grouped = parseLogicalExpression();
                skipSpace();
                expect(')');
                return negated ? recordExpression(new NotExpression(grouped), grouped) : grouped;
            }
            Expression left = parseAtom();
            skipSpace();
            ComparisonOperator operator = parseComparisonOperator();
            if (operator != null) {
                if (negated || !isComparable(left)) throw invalid();
                skipSpace();
                Expression right = parseAtom();
                if (!isComparable(right)) throw invalid();
                return recordExpression(new ComparisonExpression(left, operator, right), left, right);
            }
            if (!isTest(left)) throw invalid();
            Expression tested = left;
            if (left.type() == StaticType.NODES) {
                Expression count = recordExpression(
                        new FunctionExpression(FunctionName.COUNT, List.of(left), StaticType.VALUE), left);
                Expression zero = recordExpression(new LiteralExpression(PayloadValue.of(0)));
                tested = recordExpression(new ComparisonExpression(count, ComparisonOperator.GREATER, zero),
                        count, zero);
            }
            return negated ? recordExpression(new NotExpression(tested), tested) : tested;
        }

        private Expression parseFunctionArgument() {
            if (peek('!') || peek('(')) return parseLogicalExpression();
            int savedOffset = offset;
            int savedSelectors = selectors;
            int savedDepth = expressionDepth;
            int savedNodes = expressionNodes;
            Expression atom = parseAtom();
            skipSpace();
            if (peek(',') || peek(')')) return atom;
            offset = savedOffset;
            selectors = savedSelectors;
            expressionDepth = savedDepth;
            expressionNodes = savedNodes;
            return parseLogicalExpression();
        }

        private Expression parseAtom() {
            if (peek('\'') || peek('"') || peek('-') || isDigit(current())
                    || startsWith("true") || startsWith("false") || startsWith("null")) {
                return recordExpression(new LiteralExpression(parseLiteral()));
            }
            if (peek('$') || peek('@')) {
                Query query = parseQuery();
                return recordExpression(new QueryExpression(query), expressionsIn(query));
            }
            return parseFunction();
        }

        private FunctionExpression parseFunction() {
            enterExpression();
            try {
                String name = parseFunctionName();
                expect('(');
                skipSpace();
                var arguments = new ArrayList<Expression>();
                if (!peek(')')) {
                    arguments.add(parseFunctionArgument());
                    skipSpace();
                    while (take(',')) {
                        skipSpace();
                        arguments.add(parseFunctionArgument());
                        skipSpace();
                    }
                }
                expect(')');
                FunctionName function = switch (name) {
                    case "length" -> FunctionName.LENGTH;
                    case "count" -> FunctionName.COUNT;
                    case "match" -> FunctionName.MATCH;
                    case "search" -> FunctionName.SEARCH;
                    case "value" -> FunctionName.VALUE;
                    default -> throw invalid();
                };
                List<StaticType> parameters = switch (function) {
                    case LENGTH -> List.of(StaticType.VALUE);
                    case COUNT, VALUE -> List.of(StaticType.NODES);
                    case MATCH, SEARCH -> List.of(StaticType.VALUE, StaticType.VALUE);
                };
                if (arguments.size() != parameters.size()) throw invalid();
                for (int index = 0; index < parameters.size(); index++) {
                    if (!assignable(arguments.get(index), parameters.get(index))) throw invalid();
                }
                StaticType result = switch (function) {
                    case LENGTH, COUNT, VALUE -> StaticType.VALUE;
                    case MATCH, SEARCH -> StaticType.LOGICAL;
                };
                return recordExpression(new FunctionExpression(function, List.copyOf(arguments), result), arguments);
            } finally {
                expressionDepth--;
            }
        }

        private List<Expression> expressionsIn(Query query) {
            var expressions = new ArrayList<Expression>();
            for (Segment segment : query.segments()) {
                for (Selector selector : segment.selectors()) {
                    if (selector instanceof FilterSelector filter) expressions.add(filter.expression());
                }
            }
            return expressions;
        }

        private <T extends Expression> T recordExpression(T expression, Expression... children) {
            return recordExpression(expression, List.of(children));
        }

        private <T extends Expression> T recordExpression(T expression, List<? extends Expression> children) {
            if (++expressionNodes > MAX_EXPRESSION_NODES) throw resource();
            int depth = 1;
            for (Expression child : children) {
                Integer childDepth = expressionDepths.get(child);
                if (childDepth == null) throw new AssertionError("unrecorded JSONPath expression");
                depth = Math.max(depth, childDepth + 1);
            }
            if (depth > MAX_EXPRESSION_DEPTH) throw resource();
            expressionDepths.put(expression, depth);
            return expression;
        }

        private static boolean assignable(Expression expression, StaticType expected) {
            if (expression.type() == expected) return true;
            return expected == StaticType.VALUE && expression instanceof QueryExpression query && query.query().singular()
                    || expected == StaticType.LOGICAL && expression.type() == StaticType.NODES;
        }

        private static boolean isComparable(Expression expression) {
            return expression.type() == StaticType.VALUE
                    || expression instanceof QueryExpression query && query.query().singular();
        }

        private static boolean isTest(Expression expression) {
            return expression.type() == StaticType.LOGICAL || expression.type() == StaticType.NODES;
        }

        private ComparisonOperator parseComparisonOperator() {
            if (take("==")) return ComparisonOperator.EQUAL;
            if (take("!=")) return ComparisonOperator.NOT_EQUAL;
            if (take("<=")) return ComparisonOperator.LESS_EQUAL;
            if (take(">=")) return ComparisonOperator.GREATER_EQUAL;
            if (take('<')) return ComparisonOperator.LESS;
            if (take('>')) return ComparisonOperator.GREATER;
            return null;
        }

        private PayloadValue parseLiteral() {
            if (peek('\'') || peek('"')) return PayloadValue.of(parseString());
            if (take("true")) return PayloadValue.of(true);
            if (take("false")) return PayloadValue.of(false);
            if (take("null")) return PayloadValue.NULL;
            int start = offset;
            parseNumberSyntax();
            String number = source.substring(start, offset);
            try {
                if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                    BigInteger integer = new BigInteger(number);
                    requireIjson(integer);
                    return PayloadValue.of(integer.longValueExact());
                }
                double decimal = new BigDecimal(number).doubleValue();
                if (!Double.isFinite(decimal)) throw invalid();
                return PayloadValue.of(decimal);
            } catch (ArithmeticException invalidNumber) {
                throw invalid();
            }
        }

        private void parseNumberSyntax() {
            take('-');
            if (take('0')) {
                if (isDigit(current())) throw invalid();
            } else {
                requireNonZeroDigit();
                while (isDigit(current())) offset++;
            }
            if (take('.')) {
                requireDigit();
                while (isDigit(current())) offset++;
            }
            if (take('e') || take('E')) {
                if (!take('-')) take('+');
                requireDigit();
                while (isDigit(current())) offset++;
            }
        }

        private long parseInteger(boolean allowNegativeZero) {
            int start = offset;
            boolean negative = take('-');
            if (take('0')) {
                if (isDigit(current()) || negative && !allowNegativeZero) throw invalid();
            } else {
                requireNonZeroDigit();
                while (isDigit(current())) offset++;
            }
            BigInteger integer = new BigInteger(source.substring(start, offset));
            requireIjson(integer);
            return integer.longValueExact();
        }

        private static void requireIjson(BigInteger integer) {
            if (integer.abs().compareTo(MAX_IJSON_INTEGER) > 0) throw invalid();
        }

        private String parseString() {
            char quote = current();
            if (quote != '\'' && quote != '"') throw invalid();
            offset++;
            var result = new StringBuilder();
            while (offset < source.length()) {
                char current = source.charAt(offset++);
                if (current == quote) return result.toString();
                if (current == '\\') {
                    parseEscape(quote, result);
                    continue;
                }
                if (current < 0x20 || current == '\'' || current == '"' || current == '\\') {
                    if (current == (quote == '\'' ? '"' : '\'')) result.append(current);
                    else throw invalid();
                } else if (Character.isHighSurrogate(current)) {
                    if (offset >= source.length() || !Character.isLowSurrogate(source.charAt(offset))) throw invalid();
                    result.append(current).append(source.charAt(offset++));
                } else if (Character.isLowSurrogate(current)) {
                    throw invalid();
                } else {
                    result.append(current);
                }
            }
            throw invalid();
        }

        private void parseEscape(char quote, StringBuilder result) {
            if (offset >= source.length()) throw invalid();
            char escaped = source.charAt(offset++);
            switch (escaped) {
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '/' -> result.append('/');
                case '\\' -> result.append('\\');
                case '\'', '"' -> {
                    if (escaped != quote) throw invalid();
                    result.append(escaped);
                }
                case 'u' -> parseUnicodeEscape(result);
                default -> throw invalid();
            }
        }

        private void parseUnicodeEscape(StringBuilder result) {
            int first = parseFourHex();
            if (first >= 0xD800 && first <= 0xDBFF) {
                if (!take('\\') || !take('u')) throw invalid();
                int second = parseFourHex();
                if (second < 0xDC00 || second > 0xDFFF) throw invalid();
                result.appendCodePoint(Character.toCodePoint((char) first, (char) second));
            } else if (first >= 0xDC00 && first <= 0xDFFF) {
                throw invalid();
            } else {
                result.append((char) first);
            }
        }

        private int parseFourHex() {
            if (offset + 4 > source.length()) throw invalid();
            int value = 0;
            for (int count = 0; count < 4; count++) {
                char digit = source.charAt(offset++);
                int hex = digit >= '0' && digit <= '9' ? digit - '0'
                        : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10
                        : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10 : -1;
                if (hex < 0) throw invalid();
                value = value * 16 + hex;
            }
            return value;
        }

        private String parseMemberName() {
            int start = offset;
            int point = readCodePoint();
            if (!isNameFirst(point)) throw invalid();
            while (offset < source.length()) {
                int saved = offset;
                point = readCodePoint();
                if (!isNameFirst(point) && !(point >= '0' && point <= '9')) {
                    offset = saved;
                    break;
                }
            }
            return source.substring(start, offset);
        }

        private String parseFunctionName() {
            int start = offset;
            char first = current();
            if (first < 'a' || first > 'z') throw invalid();
            offset++;
            while (offset < source.length()) {
                char current = source.charAt(offset);
                if (current >= 'a' && current <= 'z' || current == '_' || isDigit(current)) offset++;
                else break;
            }
            return source.substring(start, offset);
        }

        private int readCodePoint() {
            if (offset >= source.length()) throw invalid();
            char first = source.charAt(offset++);
            if (Character.isHighSurrogate(first)) {
                if (offset >= source.length() || !Character.isLowSurrogate(source.charAt(offset))) throw invalid();
                return Character.toCodePoint(first, source.charAt(offset++));
            }
            if (Character.isLowSurrogate(first)) throw invalid();
            return first;
        }

        private static boolean isNameFirst(int point) {
            return point >= 'A' && point <= 'Z' || point >= 'a' && point <= 'z' || point == '_'
                    || point >= 0x80 && point <= 0xD7FF || point >= 0xE000 && point <= 0x10FFFF;
        }

        private void countSelector() {
            if (++selectors > MAX_QUERY_SELECTORS) throw resource();
        }

        private void enterExpression() {
            if (++expressionDepth > MAX_EXPRESSION_DEPTH) throw resource();
        }

        private void skipSpace() {
            while (offset < source.length()) {
                char current = source.charAt(offset);
                if (current == ' ' || current == '\t' || current == '\n' || current == '\r') offset++;
                else break;
            }
        }

        private void requireDigit() {
            if (!isDigit(current())) throw invalid();
        }

        private void requireNonZeroDigit() {
            char current = current();
            if (current < '1' || current > '9') throw invalid();
            offset++;
        }

        private char current() {
            return offset < source.length() ? source.charAt(offset) : 0;
        }

        private boolean startsWith(String value) {
            return source.startsWith(value, offset);
        }

        private boolean peek(char value) {
            return current() == value;
        }

        private boolean peek(int relative, char value) {
            return offset + relative < source.length() && source.charAt(offset + relative) == value;
        }

        private boolean take(char value) {
            if (!peek(value)) return false;
            offset++;
            return true;
        }

        private boolean take(String value) {
            if (!startsWith(value)) return false;
            offset += value.length();
            return true;
        }

        private void expect(char value) {
            if (!take(value)) throw invalid();
        }
    }

    /** Checking RFC 9485 mapper to the RE2 syntax recommended by RFC 9485 section 5.4. */
    private static final class IRegexp {
        private static final java.util.Set<String> PROPERTIES = java.util.Set.of(
                "L", "Ll", "Lm", "Lo", "Lt", "Lu", "M", "Mc", "Me", "Mn",
                "N", "Nd", "Nl", "No", "P", "Pc", "Pd", "Pe", "Pf", "Pi", "Po", "Ps",
                "Z", "Zl", "Zp", "Zs", "S", "Sc", "Sk", "Sm", "So",
                "C", "Cc", "Cf", "Cn", "Co");

        private final String source;
        private final StringBuilder translated = new StringBuilder();
        private int offset;
        private int depth;
        private int nodes;

        private IRegexp(String source) {
            this.source = source;
        }

        static String translate(String source, boolean entire) {
            try {
                var checker = new IRegexp(source);
                checker.parseExpression(false);
                if (checker.offset != source.length()) return null;
                return entire ? "\\A(?:" + checker.translated + ")\\z" : checker.translated.toString();
            } catch (JsonPathNodeException resourceLimit) {
                throw resourceLimit;
            } catch (IllegalArgumentException invalidExpression) {
                return null;
            }
        }

        private void parseExpression(boolean parenthesized) {
            enterExpression();
            try {
                parseBranch();
                while (take('|')) {
                    translated.append('|');
                    parseBranch();
                }
                if (parenthesized) {
                    expect(')');
                    translated.append(')');
                }
            } finally {
                depth--;
            }
        }

        private void parseBranch() {
            consumeNode();
            while (offset < source.length() && current() != '|' && current() != ')') {
                parseAtom();
                parseQuantifier();
            }
        }

        private void parseAtom() {
            consumeNode();
            char current = current();
            if (take('(')) {
                translated.append('(');
                parseExpression(true);
            } else if (take('.')) {
                translated.append("[^\\n\\r]");
            } else if (take('[')) {
                translated.append('[');
                parseCharacterClass();
            } else if (take('\\')) {
                translated.append('\\');
                parseEscape();
            } else {
                int point = readScalar();
                if (!normalCharacter(point)) throw new IllegalArgumentException();
                translated.appendCodePoint(point);
            }
        }

        private void parseQuantifier() {
            if (take('*') || take('+') || take('?')) {
                consumeNode();
                translated.append(source.charAt(offset - 1));
                return;
            }
            if (!take('{')) return;
            consumeNode();
            translated.append('{');
            copyDigits();
            if (take(',')) {
                translated.append(',');
                if (isDigit(current())) copyDigits();
            }
            expect('}');
            translated.append('}');
        }

        private void parseCharacterClass() {
            boolean complement = take('^');
            if (complement) translated.append('^');
            if (complement && current() == ']') throw new IllegalArgumentException();
            if (take('-')) translated.append('-');
            while (offset < source.length() && current() != ']') {
                if (take('-')) {
                    translated.append('-');
                    if (current() != ']') throw new IllegalArgumentException();
                    break;
                }
                boolean rangeCapable = parseClassElement();
                if (current() == '-' && offset + 1 < source.length() && source.charAt(offset + 1) != ']') {
                    if (!rangeCapable) throw new IllegalArgumentException();
                    offset++;
                    translated.append('-');
                    if (!parseClassElement()) throw new IllegalArgumentException();
                }
            }
            expect(']');
            translated.append(']');
        }

        private boolean parseClassElement() {
            consumeNode();
            if (take('\\')) {
                translated.append('\\');
                return parseEscape();
            }
            int point = readScalar();
            boolean valid = point <= 0x2C || point >= 0x2E && point <= 0x5A
                    || point >= 0x5E && point <= 0xD7FF || point >= 0xE000 && point <= 0x10FFFF;
            if (!valid) throw new IllegalArgumentException();
            translated.appendCodePoint(point);
            return true;
        }

        private void enterExpression() {
            if (depth >= MAX_IREGEXP_DEPTH) throw resource();
            depth++;
            consumeNode();
        }

        private void consumeNode() {
            if (nodes >= MAX_IREGEXP_NODES) throw resource();
            nodes++;
        }

        private boolean parseEscape() {
            char escaped = current();
            if (escaped == 'p' || escaped == 'P') {
                offset++;
                translated.append(escaped);
                expectAndAppend('{');
                int start = offset;
                while (offset < source.length() && source.charAt(offset) != '}') offset++;
                String property = source.substring(start, offset);
                if (!PROPERTIES.contains(property)) throw new IllegalArgumentException();
                translated.append(property);
                expectAndAppend('}');
                return false;
            }
            if ("()*+-.?[\\]^nrt{}".indexOf(escaped) < 0) throw new IllegalArgumentException();
            offset++;
            translated.append(escaped);
            return true;
        }

        private void copyDigits() {
            if (!isDigit(current())) throw new IllegalArgumentException();
            while (isDigit(current())) translated.append(source.charAt(offset++));
        }

        private int readScalar() {
            if (offset >= source.length()) throw new IllegalArgumentException();
            char first = source.charAt(offset++);
            if (Character.isHighSurrogate(first)) {
                if (offset >= source.length() || !Character.isLowSurrogate(source.charAt(offset))) {
                    throw new IllegalArgumentException();
                }
                return Character.toCodePoint(first, source.charAt(offset++));
            }
            if (Character.isLowSurrogate(first)) throw new IllegalArgumentException();
            return first;
        }

        private static boolean normalCharacter(int point) {
            return point <= 0x27 || point == ',' || point == '-' || point >= 0x2F && point <= 0x3E
                    || point >= 0x40 && point <= 0x5A || point >= 0x5E && point <= 0x7A
                    || point >= 0x7E && point <= 0xD7FF || point >= 0xE000 && point <= 0x10FFFF;
        }

        private char current() {
            return offset < source.length() ? source.charAt(offset) : 0;
        }

        private boolean take(char expected) {
            if (current() != expected) return false;
            offset++;
            return true;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException();
        }

        private void expectAndAppend(char expected) {
            expect(expected);
            translated.append(expected);
        }
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static JsonPathNodeException invalid() {
        return new JsonPathNodeException(JsonPathNodeException.Reason.INVALID_PATH);
    }

    private static JsonPathNodeException resource() {
        return new JsonPathNodeException(JsonPathNodeException.Reason.RESOURCE_LIMIT);
    }
}
