package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.resolver.OperatorResolver;

/**
 * 测试操作人解析器。
 *
 * @author devoracode
 */
public class TestOperatorResolver implements OperatorResolver {

    @Override
    public Operator resolve() {
        return Operator.builder()
                .userId("10001")
                .userAccount("demo")
                .userName("测试用户")
                .build();
    }
}
