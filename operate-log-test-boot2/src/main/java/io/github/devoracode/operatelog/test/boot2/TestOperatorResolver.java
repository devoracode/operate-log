package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.resolver.OperatorResolver;

/**
 * 测试操作人解析器：支持 {@link ChaosFlags#OPERATOR} 故障注入，用于验证解析器故障
 * 只让 operator 字段降级为 {@code null}，不影响业务与其他字段。
 */
public class TestOperatorResolver implements OperatorResolver {

    @Override
    public Operator resolve() {
        ChaosFlags.throwIfActive(ChaosFlags.OPERATOR, "chaos: operator resolver down");
        return Operator.builder()
                .userId("10001")
                .userAccount("demo")
                .userName("测试用户")
                .build();
    }
}
