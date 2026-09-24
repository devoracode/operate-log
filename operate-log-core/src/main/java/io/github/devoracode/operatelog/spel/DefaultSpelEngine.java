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
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 SpEL 执行器：表达式问题只影响字段质量，绝不中断日志链路。
 * 总开关 {@code enabled=false} 时零求值（条件恒通过、模板原样输出、求值返回 null）；
 * 单表达式失败按同一方向就地降级，原因以 debug 暴露。
 *
 * <p>表达式缓存为定容 {@link ConcurrentHashMap}，读路径无锁；写入侧在达到容量上限后不再放入
 * （key 空间由编译期表达式/方法集合天然有界，满了即冻结，重复解析只是幂等浪费）。</p>
 *
 * <p><b>安全沙箱</b>：本引擎使用 {@link SimpleEvaluationContext#forReadOnlyDataBinding()}，
 * 仅支持变量读取、属性访问与实例方法调用，<b>不支持</b>类型引用（{@code T(...)}）、构造函数
 * （{@code new ...}）、bean 引用（{@code @bean}）与静态方法调用——即使表达式文本来自编译期
 * 注解常量（{@code @OperateLog} 的属性值），也无法执行任意代码；运行时动态注入的表达式
 * 同样只能命中沙箱内能力，从源头杜绝远程代码执行（RCE）风险。越界表达式求值即失败，
 * 按同一降级方向处理（条件通过、模板原样、求值返回 null），不中断日志链路。</p>
 */
public class DefaultSpelEngine implements SpelEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSpelEngine.class);
    private static final String TEMPLATE_PREFIX = "T:";
    private static final String EXPRESSION_PREFIX = "E:";
    /**
     * 解析不到参数名时的哨兵：{@link ConcurrentHashMap} 不允许 null 值。
     */
    private static final String[] NO_PARAMETER_NAMES = new String[0];
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressionCache;
    /**
     * 方法参数名缓存：与 expressionCache 共享同一容量上限。
     */
    private final Map<Method, String[]> parameterNameCache;
    private final int maxCacheSize;
    private final boolean enabled;

    public DefaultSpelEngine(int cacheSize) {
        this(cacheSize, true);
    }

    public DefaultSpelEngine(int cacheSize, boolean enabled) {
        this.enabled = enabled;
        this.maxCacheSize = Math.max(cacheSize, 64);
        this.expressionCache = new ConcurrentHashMap<String, Expression>();
        this.parameterNameCache = new ConcurrentHashMap<Method, String[]>();
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
            // businessId 允许为空：表达式失败降级为 null
            logDegraded("evaluate", expression, ex);
            return null;
        }
    }

    @Override
    public String evaluateTemplate(String template, OperateLogContext context) {
        if (StringUtils.isEmpty(template)) {
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
            // 模板求值失败：输出原文
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
            // 条件求值失败：宁可多记不可漏记，视为通过
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
        if (this.expressionCache.size() < this.maxCacheSize) {
            this.expressionCache.putIfAbsent(cacheKey, parsedExpression);
        }
        return parsedExpression;
    }

    private SimpleEvaluationContext createEvaluationContext(OperateLogContext context) {
        // forReadOnlyDataBinding() 只装只读属性访问器，不装方法解析器（Spring 5.3.x 的 Builder
        // 默认 resolvers 为 emptyList），任何方法调用都会 METHOD_NOT_FOUND；
        // 显式 withInstanceMethods() 装上实例方法解析器（静态方法仍被过滤）
        SimpleEvaluationContext evaluationContext = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withInstanceMethods()
                .build();
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
        String[] parameterNames = resolveParameterNames(context.getMethod());
        for (int i = 0; i < parameterNames.length && i < arguments.length; i++) {
            String parameterName = parameterNames[i];
            if (StringUtils.isNotBlank(parameterName)) {
                evaluationContext.setVariable(parameterName, arguments[i]);
            }
        }
        return evaluationContext;
    }

    /**
     * 解析并缓存方法参数名。{@link DefaultParameterNameDiscoverer} 在 {@code -parameters} 不可用时
     * 会回落到读字节码，而每次求值都可能调用本方法，不缓存会把开销按 QPS 放大；解析不到时缓存空数组哨兵。
     */
    private String[] resolveParameterNames(Method method) {
        if (method == null) {
            return NO_PARAMETER_NAMES;
        }
        String[] cached = this.parameterNameCache.get(method);
        if (cached != null) {
            return cached;
        }
        String[] discovered = this.parameterNameDiscoverer.getParameterNames(method);
        String[] resolved = discovered == null ? NO_PARAMETER_NAMES : discovered;
        if (this.parameterNameCache.size() < this.maxCacheSize) {
            this.parameterNameCache.putIfAbsent(method, resolved);
        }
        return resolved;
    }
}
