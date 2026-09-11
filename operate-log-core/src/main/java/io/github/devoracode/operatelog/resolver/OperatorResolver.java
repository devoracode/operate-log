package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.Operator;

/** 操作人解析器：从登录态 / Session / Token 等还原当前操作人。 */
public interface OperatorResolver {

    /** 当前操作人；取不到时返回 {@code null}。 */
    Operator resolve();
}
