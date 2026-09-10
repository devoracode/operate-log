package io.github.devoracode.operatelog.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.boot.servlet.jakarta.OperateLogJakartaClientIpResolver;
import io.github.devoracode.operatelog.boot.servlet.jakarta.OperateLogJakartaHttpContextResolver;
import io.github.devoracode.operatelog.boot.servlet.javax.OperateLogJavaxClientIpResolver;
import io.github.devoracode.operatelog.boot.servlet.javax.OperateLogJavaxHttpContextResolver;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import io.github.devoracode.operatelog.resolver.OperatorResolver;
import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import io.github.devoracode.operatelog.spel.DefaultSpelEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.filter.FilteredClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双栈自动配置装配顺序测试：jakarta 优先 → javax 次之 → 无 Servlet API 时 fallback；
 * 以及 {@code @ConditionalOnMissingBean} 让位与 enabled 开关。
 *
 * <p>starter 测试类路径同时含 javax 与 jakarta 两套 servlet-api（provided 依赖对
 * test 可见），恰好覆盖「双栈共存」「单栈」「无栈」三种宿主形态。</p>
 *
 * @author devoracode
 */
class OperateLogAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withConfiguration(AutoConfigurations.of(OperateLogAutoConfiguration.class));

    @Test
    void fullWiringWhenServletApisPresent() {
        this.runner.run(context -> assertThat(context)
                .hasSingleBean(OperateLogAspect.class)
                .hasSingleBean(DefaultOperateLogHandler.class)
                .hasSingleBean(DefaultSpelEngine.class)
                .hasBean("operateLogPayloadPolicy"));
    }

    @Test
    void jakartaPreferredWhenBothStacksOnClasspath() {
        this.runner.run(context -> {
            assertThat(context.getBean(HttpContextResolver.class))
                    .isInstanceOf(OperateLogJakartaHttpContextResolver.class);
            assertThat(context.getBean(ClientIpResolver.class))
                    .isInstanceOf(OperateLogJakartaClientIpResolver.class);
        });
    }

    @Test
    void javaxSelectedWhenJakartaStackFilteredOut() {
        this.runner.withClassLoader(new FilteredClassLoader("jakarta.servlet"))
                .run(context -> {
                    assertThat(context.getBean(HttpContextResolver.class))
                            .isInstanceOf(OperateLogJavaxHttpContextResolver.class);
                    assertThat(context.getBean(ClientIpResolver.class))
                            .isInstanceOf(OperateLogJavaxClientIpResolver.class);
                });
    }

    @Test
    void fallbackResolverWhenNoServletApiAtAll() {
        this.runner.withClassLoader(new FilteredClassLoader(
                        "jakarta.servlet", "javax.servlet"))
                .run(context -> {
                    HttpContextResolver resolver =
                            context.getBean(HttpContextResolver.class);
                    // fallback：匿名实现，永不装配 jakarta/javax 实现
                    assertThat(resolver.getClass().getName())
                            .startsWith(OperateLogAutoConfiguration.class.getName());
                    assertThat(resolver.resolve()).isNull();
                    assertThat(context.getBean(ClientIpResolver.class).resolve()).isNull();
                });
    }

    @Test
    void userProvidedResolversWin() {
        HttpContextResolver custom = () -> null;
        OperatorResolver customOperator = () -> null;
        this.runner.withBean("myResolver", HttpContextResolver.class, () -> custom)
                .withBean("myOperator", OperatorResolver.class, () -> customOperator)
                .run(context -> {
                    assertThat(context).hasSingleBean(HttpContextResolver.class);
                    assertThat(context.getBean(HttpContextResolver.class)).isSameAs(custom);
                    assertThat(context.getBean(OperatorResolver.class)).isSameAs(customOperator);
                    // 自动配置的 jakarta resolver 因 ConditionalOnMissingBean 让位
                    assertThat(context).doesNotHaveBean(
                            OperateLogJakartaHttpContextResolver.class);
                });
    }

    @Test
    void disabledSwitchRemovesAllBeans() {
        this.runner.withPropertyValues("operate-log.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(OperateLogAspect.class)
                        .doesNotHaveBean(HttpContextResolver.class)
                        .doesNotHaveBean("operateLogPayloadPolicy"));
    }

    @Test
    void propertiesBindIntoComponents() {
        // 属性绑定冒烟：mask 字段配置应传入 SensitiveDataMasker 而不报错
        this.runner.withPropertyValues(
                        "operate-log.application=my-svc",
                        "operate-log.mask.enabled=true",
                        "operate-log.mask.fields=password,ssn",
                        "operate-log.payload.max-request-length=64",
                        "operate-log.trace-id-mdc-key=correlationId")
                .run(context -> {
                    assertThat(context).hasSingleBean(OperateLogAspect.class);
                    OperateLogProperties properties =
                            context.getBean(OperateLogProperties.class);
                    assertThat(properties.getApplication()).isEqualTo("my-svc");
                    assertThat(properties.getTraceIdMdcKey()).isEqualTo("correlationId");
                    assertThat(properties.getMask().getFields()).contains("password", "ssn");
                });
    }
}
