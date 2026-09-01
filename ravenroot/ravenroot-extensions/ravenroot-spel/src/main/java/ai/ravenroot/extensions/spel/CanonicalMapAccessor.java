package ai.ravenroot.extensions.spel;

import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.util.Map;

final class CanonicalMapAccessor implements PropertyAccessor {
    static final CanonicalMapAccessor INSTANCE = new CanonicalMapAccessor();

    private CanonicalMapAccessor() {
    }

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[] {Map.class};
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return target instanceof Map<?, ?> map && map.containsKey(name);
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
        if (!(target instanceof Map<?, ?> map) || !map.containsKey(name)) {
            throw new AccessException("canonical map property is absent");
        }
        return new TypedValue(map.get(name));
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue)
            throws AccessException {
        throw new AccessException("canonical maps are read-only");
    }
}
