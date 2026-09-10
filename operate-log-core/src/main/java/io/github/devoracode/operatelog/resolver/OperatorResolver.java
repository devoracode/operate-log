package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.Operator;

/**
 * 操作人解析器。
 *
 * @author devoracode
 */
public interface OperatorResolver {

    /**
     * 获取当前操作人。
     *
     * @return 当前操作人，没有操作人时返回 {@code null}
     */
    Operator resolve();
}
