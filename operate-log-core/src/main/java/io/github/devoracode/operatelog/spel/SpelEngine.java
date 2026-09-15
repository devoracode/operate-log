package io.github.devoracode.operatelog.spel;

import io.github.devoracode.operatelog.context.OperateLogContext;

/**
 * SpEL 执行器：表达式求值与 {@code #{...}} 模板渲染。
 * 可用变量（#root / #args / #result / #error 等）见 {@code @OperateLog} 与 README。
 */
public interface SpelEngine {
    Object evaluate(String expression, OperateLogContext context);

    String evaluateTemplate(String template, OperateLogContext context);

    boolean evaluateBoolean(String expression, OperateLogContext context);
}
