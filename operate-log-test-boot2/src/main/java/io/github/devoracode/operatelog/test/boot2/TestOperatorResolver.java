package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.resolver.OperatorResolver;

/**
 * 测试操作人解析器：支持 {@link ChaosFlags#OPERATOR} 故障注入，用于验证解析器故障
 * 只让 operator 字段降级为 {@code null}，不影响业务与其他字段；
 * {@link ChaosFlags#OPERATOR_EXTRA} 打开时按宿主扩展角色的写法往 {@code extra} 追加属性。
 */
public class TestOperatorResolver implements OperatorResolver {

    @Override
    public Operator resolve() {
        ChaosFlags.throwIfActive(ChaosFlags.OPERATOR, "chaos: operator resolver down");
        if (ChaosFlags.isActive(ChaosFlags.OPERATOR_EXTRA)) {
            OperateLogContextHolder.putExtra("roleId", "20001");
            OperateLogContextHolder.putExtra("roleCode", "ADMIN");
            OperateLogContextHolder.putExtra("roleName", "超级管理员");
        }
        return Operator.builder()
                .userId("10001")
                .userAccount("demo")
                .userName("测试用户")
                .build();
    }
}
