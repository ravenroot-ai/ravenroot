package ai.ravenroot.extensions.spel;

import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.nio.charset.StandardCharsets;

final class RestrictedSpelExpression {
    private static final SpelParserConfiguration CONFIGURATION = new SpelParserConfiguration(
            SpelCompilerMode.OFF, null, false, false, 0,
            SpelBounds.MAX_EXPRESSION_LENGTH, SpelBounds.MAX_OPERATIONS);

    private final SpelExpression expression;

    private RestrictedSpelExpression(SpelExpression expression) {
        this.expression = expression;
    }

    static RestrictedSpelExpression compile(String source) {
        if (source == null || source.isBlank()) {
            throw new SpelNodeException(SpelNodeException.Code.EXPRESSION_MISSING);
        }
        if (source.length() > SpelBounds.MAX_EXPRESSION_LENGTH
                || source.getBytes(StandardCharsets.UTF_8).length > SpelBounds.MAX_EXPRESSION_UTF8) {
            throw new SpelNodeException(SpelNodeException.Code.EXPRESSION_TOO_LONG);
        }
        try {
            SpelExpression parsed = new SpelExpressionParser(CONFIGURATION).parseRaw(source);
            RestrictedSpelAstPolicy.validate(parsed.getAST());
            return new RestrictedSpelExpression(parsed);
        } catch (SpelNodeException rejected) {
            throw rejected;
        } catch (ParseException rejected) {
            throw new SpelNodeException(SpelNodeException.Code.EXPRESSION_INVALID);
        }
    }

    Object evaluate(Object root) {
        try {
            RestrictedSpelAstPolicy.guardRuntimeIndexTargets(expression.getAST(), root);
            var context = SimpleEvaluationContext.forPropertyAccessors(CanonicalMapAccessor.INSTANCE)
                    .withAssignmentDisabled()
                    .withTypeConverter(NoConversionTypeConverter.INSTANCE)
                    .build();
            return expression.getValue(context, root);
        } catch (SpelNodeException rejected) {
            throw rejected;
        } catch (RuntimeException rejected) {
            throw new SpelNodeException(SpelNodeException.Code.EVALUATION_FAILED);
        }
    }

}
