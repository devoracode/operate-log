package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.Operator;

/**
 * 操作人解析器：从登录态 / Session / Token 等还原当前操作人。
 *
 * <p>角色、部门等 {@link Operator} 之外的属性不占用记录的字段形态：{@link #resolve()} 在线程日志上下文
 * 绑定之后调用，实现内可用 {@code OperateLogContextHolder#putExtra} 写入 {@code extra}，
 * 与业务方法写入的键共存，同样受 {@code payload.max-extra-length} 按实际 JSON 长度限长。</p>
 */
public interface OperatorResolver {

    /**
     * 当前操作人。
     *
     * @return 操作人 {@link Operator}；无登录态时为 {@code null}
     */
    Operator resolve();
}
