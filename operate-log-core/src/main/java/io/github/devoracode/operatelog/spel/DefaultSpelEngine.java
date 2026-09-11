package io.github.devoracode.operatelog.spel;

import io.github.devoracode.operatelog.context.OperateLogContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认 SpEL 执行器：表达式问题只影响字段质量，绝不中断日志链路。
 * 总开关 {@code operate-log.spel.enabled=false} 时零求值（条件恒通过、模板原样输出、
 * 求值返回 {@code null}）；单表达式失败按同一方向就地降级，原因以 debug 暴露。
 *
 * <p>表达式缓存为定容 LRU（超出容量淘汰最久未使用项），{@link Collections#synchronizedMap}
 * 保证线程安全；并发下未命中重复解析只是幂等浪费，因此不加全局锁。</p>
 */
public class DefaultSpelEngine implements SpelEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSpelEngine.class);
    private static final String TEMPLATE_PREFIX = "T:";
    private static final String EXPRESSION_PREFIX = "E:";
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressionCache;
    private final boolean enabled;

    public DefaultSpelEngine(int cacheSize) {
        this(cacheSize, true);
    }

    public DefaultSpelEngine(int cacheSize, boolean enabled) {
        this.enabled = enabled;
        final int maxCacheSize = Math.max(cacheSize, 64);
        this.expressionCache = Collections.synchronizedMap(new LinkedHashMap<String, Expression>(16,
                0.75f,
                true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Expression> eldest) {
                return size() > maxCacheSize;
            }
        });
    }

    @Override
    public Object evaluate(String expression, OperateLogContext context) {
        if (StringUtils.isBlank(expression)) {
            return null;
        }
        if (!this.enabled) {
            return null;
        }
        try {
            Expression spelExpression = getExpression(expression, false);
            return spelExpression.getValue(createEvaluationContext(context));
        } catch (Throwable ex) {
            // businessId 允许为空：表达式失败降级为 null，不影响日志记录
            logDegraded("evaluate", expression, ex);
            return null;
        }
    }

    @Override
    public String evaluateTemplate(String template, OperateLogContext context) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        if (!this.enabled) {
            // SpEL 关闭：描述输出模板原文，业务不因此丢失语义
            return template;
        }
        try {
            Expression spelExpression = getExpression(template, true);
            return spelExpression.getValue(createEvaluationContext(context), String.class);
        } catch (Throwable ex) {
            // 模板求值失败：输出原文优于整条日志丢失
            logDegraded("evaluateTemplate", template, ex);
            return template;
        }
    }

    @Override
    public boolean evaluateBoolean(String expression, OperateLogContext context) {
        if (StringUtils.isBlank(expression)) {
            return true;
        }
        if (!this.enabled) {
            return true;
        }
        try {
            Expression spelExpression = getExpression(expression, false);
            Boolean result = spelExpression.getValue(createEvaluationContext(context),
                    Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ex) {
            // 条件求值失败：宁可多记不可漏记，视为通过（误过滤审计日志代价更高）
            logDegraded("evaluateBoolean", expression, ex);
            return true;
        }
    }

    private void logDegraded(String operation, String expression, Throwable ex) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("operate-log: SpEL '{}' failed for expression [{}], degraded.",
                    operation,
                    expression,
                    ex);
        }
    }

    private Expression getExpression(String value, boolean template) {
        String prefix = template ? TEMPLATE_PREFIX : EXPRESSION_PREFIX;
        String cacheKey = prefix + value;
        Expression cached = this.expressionCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Expression parsedExpression = template ? this.parser.parseExpression(value,
                new TemplateParserContext()) : this.parser.parseExpression(value);
        // LRU 自动淘汰最久未使用项，无需容量判断；并发重复 put 幂等无害
        this.expressionCache.put(cacheKey, parsedExpression);
        return parsedExpression;
    }

    private StandardEvaluationContext createEvaluationContext(OperateLogContext context) {
        StandardEvaluationContext evaluationContext = new StandardEvaluationContext();
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
