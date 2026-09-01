package ai.ravenroot.extensions.spel;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.TypeConverter;

/** Prevents SpEL from introducing values through implicit type coercion. */
final class NoConversionTypeConverter implements TypeConverter {
    static final NoConversionTypeConverter INSTANCE = new NoConversionTypeConverter();

    private NoConversionTypeConverter() {
    }

    @Override
    public boolean canConvert(TypeDescriptor sourceType, TypeDescriptor targetType) {
        return sourceType != null && sourceType.isAssignableTo(targetType);
    }

    @Override
    public Object convertValue(Object value, TypeDescriptor sourceType, TypeDescriptor targetType) {
        if (value == null && !targetType.isPrimitive()) {
            return null;
        }
        if (sourceType != null && sourceType.isAssignableTo(targetType)) {
            return value;
        }
        throw new EvaluationException("type conversion is disabled");
    }
}
