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
 * 默认 SpEL 执行器。
 *
 * <p>两级降级保护，表达式问题只影响字段质量、绝不中断日志链路：</p>
 * <ul>
 *   <li><b>总开关</b>（{@code operate-log.spel.enabled}，对应构造参数 {@code enabled}）：
 *       关闭后 {@link #evaluateBoolean} 恒通过（不过滤）、{@link #evaluateTemplate}
 *       原样输出模板文本、{@link #evaluate} 返回 {@code null}，完全零求值开销。</li>
 *   <li><b>单表达式失败</b>（语法错误、空指针访问、类型不匹配等）：按同样的方向就地降级
 *       ——condition 视为通过、模板输出原文、businessId 记 {@code null}，并以 debug
 *       日志暴露原因，不向上抛异常。</li>
 * </ul>
 *
 * <p>表达式缓存为定容 LRU（access-order {@link LinkedHashMap} + 容量上限），
 * 超出容量淘汰最久未使用项；容量到达后仍可继续缓存新表达式，
 * 避免旧「只挡新增、永不失效」策略在表达式数量持续增长时整体退化。
 * 缓存访问经 {@link Collections#synchronizedMap} 包装保证线程安全；
 * 并发下未命中重复解析仅是幂等浪费，无需加全局锁。</p>
 *
 * @author devoracode
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
