package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.resolver.OperatorResolver;

/**
 * 测试操作人解析器。
 *
 * <p>支持 {@link ChaosFlags#OPERATOR} 故障注入：命中时抛异常，用于验证
 * 「解析器故障只让 operator 字段降级为 {@code null}，绝不影响业务与其他字段」。</p>
 *
 * @author devoracode
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
