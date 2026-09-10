package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.resolver.OperatorResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 测试配置。
 *
 * @author devoracode
 */
@Configuration
public class TestConfig {

 @Bean
 public OperatorResolver operatorResolver() {
     return new TestOperatorResolver();
 }
}
 