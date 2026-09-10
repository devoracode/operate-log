package io.github.devoracode.operatelog.spel;

import io.github.devoracode.operatelog.context.OperateLogContext;

/**
 * SpEL 执行器。
 *
 * @author devoracode
 */
public interface SpelEngine {
    Object evaluate(String expression, OperateLogContext context);

    String evaluateTemplate(String template, OperateLogContext context);

    boolean evaluateBoolean(String expression, OperateLogContext context);
}
