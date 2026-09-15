package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.resolver.OperatorResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册测试用 {@code OperatorResolver}；其余组件一律走自动配置的默认实现。 */
@Configuration
public class TestConfig {

    @Bean
    public OperatorResolver operatorResolver() {
        return new TestOperatorResolver();
    }
}
