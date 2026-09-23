package io.github.devoracode.operatelog.spel;

import io.github.devoracode.operatelog.context.OperateLogContext;

/**
 * SpEL 执行器：表达式求值与 {@code #{...}} 模板渲染。
 * 可用变量（#root / #args / #result / #error 等）见 {@code @OperateLog} 与 README。
 */
public interface SpelEngine {
    /**
     * 求值纯 SpEL 表达式（非模板），用于 {@code businessId} 等取值场景。
     *
     * @param expression 表达式文本
     * @param context    提供表达式变量的执行上下文
     * @return 求值结果；表达式为空、引擎关闭或求值失败时为 {@code null}
     */
    Object evaluate(String expression, OperateLogContext context);

    /**
     * 渲染 {@code #{...}} 模板，用于 {@code description} 等文案场景。
     *
     * @param template  含 {@code #{...}} 占位的描述模板
     * @param context   提供表达式变量的执行上下文
     * @return 渲染后的文本；模板为空、引擎关闭或求值失败时原样返回 {@code template}
     */
    String evaluateTemplate(String template, OperateLogContext context);

    /**
     * 求值布尔表达式，用于 {@code condition} 过滤。
     *
     * @param expression 布尔表达式文本
     * @param context    提供表达式变量的执行上下文
     * @return 是否放行记录；表达式为空、引擎关闭或求值失败均视为通过（宁可多记不可漏记）
     */
    boolean evaluateBoolean(String expression, OperateLogContext context);
}
