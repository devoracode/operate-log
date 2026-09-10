package io.github.devoracode.operatelog.spel;

import io.github.devoracode.operatelog.context.OperateLogContext;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认 SpEL 执行器。
 *
 * @author devoracode
 */
public class DefaultSpelEngine implements SpelEngine {

    private static final String TEMPLATE_PREFIX = "T:";

    private static final String EXPRESSION_PREFIX = "E:";

    private final ExpressionParser parser = new SpelExpressionParser();

    private final DefaultParameterNameDiscoverer parameterNameDiscoverer =
            new DefaultParameterNameDiscoverer();

    private final ConcurrentMap<String, Expression> expressionCache =
            new ConcurrentHashMap<String, Expression>();

    private final int cacheSize;

    public DefaultSpelEngine(int cacheSize) {
        this.cacheSize = Math.max(cacheSize, 64);
    }

    @Override
    public Object evaluate(String expression, OperateLogContext context) {
        if (StringUtils.isBlank(expression)) {
            return null;
        }

        Expression spelExpression = getExpression(expression, false);
        return spelExpression.getValue(createEvaluationContext(context));
    }

    @Override
    public String evaluateTemplate(String template, OperateLogContext context) {
        if (template == null) {
            return null;
        }

        if (template.isEmpty()) {
            return template;
        }

        Expression spelExpression = getExpression(template, true);
        return spelExpression.getValue(
                createEvaluationContext(context), String.class);
    }

    @Override
    public boolean evaluateBoolean(String expression, OperateLogContext context) {
        if (StringUtils.isBlank(expression)) {
            return true;
        }

        Expression spelExpression = getExpression(expression, false);
        Boolean result = spelExpression.getValue(
                createEvaluationContext(context), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    private Expression getExpression(String value, boolean template) {
        String prefix = template ? TEMPLATE_PREFIX : EXPRESSION_PREFIX;
        String cacheKey = prefix + value;
        Expression expression = this.expressionCache.get(cacheKey);

        if (expression != null) {
            return expression;
        }

        Expression parsedExpression = template
                ? this.parser.parseExpression(value, new TemplateParserContext())
                : this.parser.parseExpression(value);

        if (this.expressionCache.size() >= this.cacheSize) {
            return parsedExpression;
        }

        Expression previous = this.expressionCache.putIfAbsent(
                cacheKey, parsedExpression);
        return previous == null ? parsedExpression : previous;
    }

    private StandardEvaluationContext createEvaluationContext(
            OperateLogContext context) {
        StandardEvaluationContext evaluationContext =
                new StandardEvaluationContext();

        evaluationContext.setVariable("context", context);
        evaluationContext.setVariable("annotation", context.getAnnotation());
        evaluationContext.setVariable("result", context.getResult());
        evaluationContext.setVariable("error", context.getError());
        evaluationContext.setVariable("http", context.getHttp());
        evaluationContext.setVariable("operator", context.getOperator());
        evaluationContext.setVariable("traceId", context.getTraceId());
        evaluationContext.setVariable("success", context.isSuccess());
        evaluationContext.setVariable("costTime", context.getCostTime());
        evaluationContext.setVariable("startTime", context.getStartTime());
        evaluationContext.setVariable("endTime", context.getEndTime());

        Object[] arguments = context.getArguments();
        for (int i = 0; i < arguments.length; i++) {
            evaluationContext.setVariable("p" + i, arguments[i]);
            evaluationContext.setVariable("a" + i, arguments[i]);
        }

        Method method = context.getMethod();
        String[] parameterNames = this.parameterNameDiscoverer.getParameterNames(method);

        if (parameterNames == null) {
            return evaluationContext;
        }

        for (int i = 0; i < parameterNames.length && i < arguments.length; i++) {
            String parameterName = parameterNames[i];

            if (StringUtils.isNotBlank(parameterName)) {
                evaluationContext.setVariable(parameterName, arguments[i]);
            }
        }

        return evaluationContext;
    }
}
