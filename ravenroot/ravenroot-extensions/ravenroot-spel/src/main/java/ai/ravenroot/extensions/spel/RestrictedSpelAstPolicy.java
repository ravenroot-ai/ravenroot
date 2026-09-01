package ai.ravenroot.extensions.spel;

import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.BooleanLiteral;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.Elvis;
import org.springframework.expression.spel.ast.FloatLiteral;
import org.springframework.expression.spel.ast.Indexer;
import org.springframework.expression.spel.ast.InlineList;
import org.springframework.expression.spel.ast.InlineMap;
import org.springframework.expression.spel.ast.IntLiteral;
import org.springframework.expression.spel.ast.Literal;
import org.springframework.expression.spel.ast.LongLiteral;
import org.springframework.expression.spel.ast.NullLiteral;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpEQ;
import org.springframework.expression.spel.ast.OpGE;
import org.springframework.expression.spel.ast.OpGT;
import org.springframework.expression.spel.ast.OpLE;
import org.springframework.expression.spel.ast.OpLT;
import org.springframework.expression.spel.ast.OpNE;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.OperatorNot;
import org.springframework.expression.spel.ast.Projection;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.RealLiteral;
import org.springframework.expression.spel.ast.Selection;
import org.springframework.expression.spel.ast.StringLiteral;
import org.springframework.expression.spel.ast.Ternary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Closed structural and semantic policy for the supported canonical-tree SpEL subset. */
final class RestrictedSpelAstPolicy {
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "class", "getclass", "metaclass", "classloader", "declaringclass", "protectiondomain",
            "__proto__", "prototype", "constructor");
    private static final Object UNKNOWN = new Object();

    private RestrictedSpelAstPolicy() {
    }

    static void validate(SpelNode root) {
        validate(root, null, -1, 1, new Budget());
    }

    /** Refuses native SpEL indexing unless every reached target is a canonical map or list. */
    static void guardRuntimeIndexTargets(SpelNode root, Object value) {
        guard(root, value);
    }

    static boolean forbiddenKey(String key) {
        return key != null && FORBIDDEN_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    private static void validate(SpelNode node, SpelNode parent, int childIndex, int depth, Budget budget) {
        if (depth > SpelBounds.MAX_AST_DEPTH || ++budget.nodes > SpelBounds.MAX_AST_NODES) {
            reject(SpelNodeException.Code.AST_LIMIT_EXCEEDED);
        }

        Class<?> type = node.getClass();
        if (type == PropertyOrFieldReference.class) {
            PropertyOrFieldReference property = (PropertyOrFieldReference) node;
            if (forbiddenKey(property.getName())) reject(SpelNodeException.Code.FORBIDDEN_PROPERTY);
            if (property.isNullSafe()) reject(SpelNodeException.Code.AST_UNSUPPORTED);
        } else if (type == Indexer.class) {
            validateIndexer((Indexer) node, parent, childIndex);
        } else if (type == Selection.class) {
            Selection selection = (Selection) node;
            if (selection.isNullSafe() || !selection.toStringAST().startsWith("?[")) {
                reject(SpelNodeException.Code.AST_UNSUPPORTED);
            }
            if (++budget.collectionOperators > 1) reject(SpelNodeException.Code.AST_LIMIT_EXCEEDED);
        } else if (type == Projection.class) {
            if (((Projection) node).isNullSafe()) reject(SpelNodeException.Code.AST_UNSUPPORTED);
            if (++budget.collectionOperators > 1) reject(SpelNodeException.Code.AST_LIMIT_EXCEEDED);
        } else if (type == InlineMap.class) {
            validateInlineMap((InlineMap) node);
        } else if (!plainAllowed(type)) {
            reject(SpelNodeException.Code.AST_UNSUPPORTED);
        }

        for (int index = 0; index < node.getChildCount(); index++) {
            validate(node.getChild(index), node, index, depth + 1, budget);
        }
    }

    private static void validateIndexer(Indexer indexer, SpelNode parent, int childIndex) {
        if (indexer.isNullSafe() || indexer.getChildCount() != 1) {
            reject(SpelNodeException.Code.AST_UNSUPPORTED);
        }
        SpelNode key = indexer.getChild(0);
        if (key.getClass() == StringLiteral.class) {
            if (forbiddenKey(String.valueOf(((StringLiteral) key).getLiteralValue().getValue()))) {
                reject(SpelNodeException.Code.FORBIDDEN_PROPERTY);
            }
        } else if (key.getClass() == PropertyOrFieldReference.class
                && forbiddenKey(((PropertyOrFieldReference) key).getName())) {
            reject(SpelNodeException.Code.FORBIDDEN_PROPERTY);
        } else if (key.getClass() != IntLiteral.class) {
            // Dynamic/computed keys and non-integer collection indexes are outside the contract.
            reject(SpelNodeException.Code.AST_UNSUPPORTED);
        }
        if (parent != null && parent.getClass() == CompoundExpression.class && childIndex > 0) {
            Class<?> target = parent.getChild(childIndex - 1).getClass();
            if (target != PropertyOrFieldReference.class && target != Indexer.class
                    && target != InlineList.class && target != InlineMap.class) {
                reject(SpelNodeException.Code.AST_UNSUPPORTED);
            }
        }
    }

    private static void validateInlineMap(InlineMap map) {
        if ((map.getChildCount() & 1) != 0) reject(SpelNodeException.Code.AST_UNSUPPORTED);
        for (int index = 0; index < map.getChildCount(); index += 2) {
            SpelNode key = map.getChild(index);
            if (key.getClass() == PropertyOrFieldReference.class
                    && forbiddenKey(((PropertyOrFieldReference) key).getName())) {
                reject(SpelNodeException.Code.FORBIDDEN_PROPERTY);
            }
            if (key.getClass() != StringLiteral.class) reject(SpelNodeException.Code.AST_UNSUPPORTED);
            if (forbiddenKey(String.valueOf(((StringLiteral) key).getLiteralValue().getValue()))) {
                reject(SpelNodeException.Code.FORBIDDEN_PROPERTY);
            }
        }
    }

    private static boolean plainAllowed(Class<?> type) {
        return type == CompoundExpression.class || type == NullLiteral.class || type == BooleanLiteral.class
                || type == IntLiteral.class || type == LongLiteral.class || type == FloatLiteral.class
                || type == RealLiteral.class || type == StringLiteral.class || type == OpAnd.class
                || type == OpOr.class || type == OperatorNot.class || type == OpEQ.class || type == OpNE.class
                || type == OpGT.class || type == OpGE.class || type == OpLT.class || type == OpLE.class
                || type == Ternary.class || type == Elvis.class || type == InlineList.class;
    }

    private static Object guard(SpelNode node, Object active) {
        Class<?> type = node.getClass();
        if (type == CompoundExpression.class) {
            Object current = active;
            for (int index = 0; index < node.getChildCount(); index++) current = guard(node.getChild(index), current);
            return current;
        }
        if (type == PropertyOrFieldReference.class) {
            if (active == UNKNOWN || active == null) return UNKNOWN;
            if (!(active instanceof Map<?, ?> map)) return UNKNOWN;
            String name = ((PropertyOrFieldReference) node).getName();
            return map.containsKey(name) ? map.get(name) : UNKNOWN;
        }
        if (type == Indexer.class) {
            Object key = ((Literal) node.getChild(0)).getLiteralValue().getValue();
            if (active instanceof Map<?, ?> map && key instanceof String) {
                return map.containsKey(key) ? map.get(key) : UNKNOWN;
            }
            if (active instanceof List<?> list && key instanceof Integer position) {
                return position >= 0 && position < list.size() ? list.get(position) : UNKNOWN;
            }
            reject(SpelNodeException.Code.AST_UNSUPPORTED);
        }
        if (type == InlineMap.class) {
            var map = new LinkedHashMap<String, Object>();
            for (int index = 0; index < node.getChildCount(); index += 2) {
                String key = String.valueOf(((StringLiteral) node.getChild(index)).getLiteralValue().getValue());
                map.put(key, guard(node.getChild(index + 1), active));
            }
            return map;
        }
        if (type == InlineList.class) {
            var list = new ArrayList<>();
            for (int index = 0; index < node.getChildCount(); index++) list.add(guard(node.getChild(index), active));
            return list;
        }
        if (node instanceof Literal literal) return literal.getLiteralValue().getValue();
        if (type == Selection.class || type == Projection.class) {
            if (active == UNKNOWN || active == null) return UNKNOWN;
            if (!(active instanceof List<?>)) reject(SpelNodeException.Code.AST_UNSUPPORTED);
            List<?> list = (List<?>) active;
            for (Object element : list) guard(node.getChild(0), element);
            return UNKNOWN;
        }
        for (int index = 0; index < node.getChildCount(); index++) guard(node.getChild(index), active);
        return UNKNOWN;
    }

    private static void reject(SpelNodeException.Code code) {
        throw new SpelNodeException(code);
    }

    private static final class Budget {
        private int nodes;
        private int collectionOperators;
    }
}
