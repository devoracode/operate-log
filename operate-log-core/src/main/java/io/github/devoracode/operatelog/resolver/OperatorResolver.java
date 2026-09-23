package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.Operator;

/** 操作人解析器：从登录态 / Session / Token 等还原当前操作人。 */
public interface OperatorResolver {

    /**
     * 当前操作人；取不到时返回 {@code null}。
     *
     * @return 操作人 {@link Operator}（用户 ID / 账号 / 名称），落到记录的对应字段；无登录态时为 {@code null}
     */
    Operator resolve();
}
