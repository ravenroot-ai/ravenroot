package ai.ravenroot.extensions.spel;

import ai.ravenroot.api.payload.PayloadValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CanonicalTree {
    private CanonicalTree() {
    }

    static Object input(Object raw) {
        try {
            PayloadValue value = PayloadValue.fromJava(raw, SpelBounds.TREE);
            SpelBounds.TREE.enforce(value);
            rejectForbiddenKeys(value);
            return freeze(value);
        } catch (SpelNodeException rejected) {
            throw rejected;
        } catch (RuntimeException rejected) {
            throw new SpelNodeException(SpelNodeException.Code.INPUT_REJECTED);
        }
    }

    static Object result(Object raw) {
        try {
            PayloadValue value = PayloadValue.fromJava(raw, SpelBounds.TREE);
            SpelBounds.TREE.enforce(value);
            rejectForbiddenKeys(value);
            return freeze(value);
        } catch (SpelNodeException rejected) {
            throw rejected;
        } catch (RuntimeException rejected) {
            throw new SpelNodeException(SpelNodeException.Code.RESULT_REJECTED);
        }
    }

    private static void rejectForbiddenKeys(PayloadValue value) {
        switch (value) {
            case PayloadValue.MapValue map -> map.entries().forEach((key, child) -> {
                if (RestrictedSpelAstPolicy.forbiddenKey(key)) {
                    throw new SpelNodeException(SpelNodeException.Code.FORBIDDEN_PROPERTY);
                }
                rejectForbiddenKeys(child);
            });
            case PayloadValue.ListValue list -> list.values().forEach(CanonicalTree::rejectForbiddenKeys);
            default -> {
            }
        }
    }

    private static Object freeze(PayloadValue value) {
        return switch (value) {
            case PayloadValue.NullValue ignored -> null;
            case PayloadValue.BooleanValue booleanValue -> booleanValue.value();
            case PayloadValue.IntegerValue integer -> integer.value();
            case PayloadValue.DecimalValue decimal -> decimal.value();
            case PayloadValue.TextValue text -> text.value();
            case PayloadValue.ListValue list -> {
                var values = new ArrayList<>(list.values().size());
                list.values().forEach(child -> values.add(freeze(child)));
                yield Collections.unmodifiableList(values);
            }
            case PayloadValue.MapValue map -> {
                var entries = new LinkedHashMap<String, Object>();
                map.entries().forEach((key, child) -> entries.put(key, freeze(child)));
                yield Collections.unmodifiableMap(entries);
            }
        };
    }
}
