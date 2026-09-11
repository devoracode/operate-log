package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.boot.OperateLogAutoConfiguration;
import io.github.devoracode.operatelog.boot.servlet.jakarta.OperateLogJakartaClientIpResolver;
import io.github.devoracode.operatelog.boot.servlet.jakarta.OperateLogJakartaHttpContextResolver;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import io.github.devoracode.operatelog.sanitizer.SensitiveDataMasker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starter 装配规则验证（放在 Boot 3 测试工程里用真实宿主 classpath 跑）：jakarta 栈命中、
 * 无 Servlet API 时兜底、总开关关闭、用户 bean 让位、属性绑定。其中「兜底」与「让位」两种形态
 * 在真实 Web 应用里不会自然出现，只能靠 {@link ApplicationContextRunner} +
 * {@link FilteredClassLoader} 模拟；另两条反射断言用来钉死公共 API 的形状。
 */
class OperateLogBoot3StarterAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OperateLogAutoConfiguration.class));

    @Test
    void jakartaStackSelectedOnBoot3Classpath() {
        this.runner.run((context) -> {
            assertTrue(!context.getBeansOfType(HttpContextResolver.class).isEmpty());
            // 一个宿主只能有一套实现：两套同时出现说明互斥条件退化成了「bean 方法顺序」
            assertEquals(1, context.getBeansOfType(HttpContextResolver.class).size());
            assertEquals(1, context.getBeansOfType(ClientIpResolver.class).size());
            assertTrue(context.getBean(HttpContextResolver.class) instanceof OperateLogJakartaHttpContextResolver);
            assertTrue(context.getBean(ClientIpResolver.class) instanceof OperateLogJakartaClientIpResolver);
            assertTrue(context.getBeanNamesForType(OperateLogAspect.class).length == 1);
        });
    }

    @Test
    void fallbackSelectedWhenNoServletApiPresent() {
        this.runner.withClassLoader(new FilteredClassLoader(jakarta.servlet.http.HttpServletRequest.class))
                .run((context) -> {
                    assertTrue(!context.getStartupFailure().isPresent(),
                            "无 Servlet API 时也必须正常启动: " + context.getStartupFailure());
                    HttpContextResolver resolver = context.getBean(HttpContextResolver.class);
                    assertTrue(resolver.resolve() == null, "兜底实现必须返回 null");
                    assertTrue(context.getBean(ClientIpResolver.class).resolve() == null, "兜底 IP 必须为 null");
                    // 兜底实现不得是任一栈的类（否则非 Web 应用会因类加载失败而启动不了）
                    assertTrue(!(resolver instanceof OperateLogJakartaHttpContextResolver));
                    assertTrue(context.getBeansOfType(OperateLogAspect.class).size() == 1);
                });
    }

    @Test
    void disabledSwitchSkipsWholeAutoConfiguration() {
        this.runner.withPropertyValues("operate-log.enabled=false").run((context) -> {
            assertEquals(0, context.getBeansOfType(OperateLogAspect.class).size());
            assertEquals(0, context.getBeansOfType(HttpContextResolver.class).size());
            assertEquals(0, context.getBeansOfType(ClientIpResolver.class).size());
            assertEquals(0, context.getBeansOfType(PayloadPolicy.class).size());
        });
    }

    @Test
    void userProvidedResolverWinsAndStackIpResolverStillProvided() {
        HttpContextResolver custom = new HttpContextResolver() {
            @Override
            public HttpContext resolve() {
                return HttpContext.builder().uri("/from-user-bean").build();
            }
        };
        this.runner.withBean(HttpContextResolver.class, () -> custom).run((context) -> {
            assertEquals(1, context.getBeansOfType(HttpContextResolver.class).size());
            assertSame(custom, context.getBean(HttpContextResolver.class));
            assertEquals("/from-user-bean", context.getBean(HttpContextResolver.class).resolve().getUri());
            // 用户只覆盖 HttpContextResolver 时，栈内 ClientIpResolver 仍需提供，否则注入失败
            assertTrue(context.getBean(ClientIpResolver.class) instanceof OperateLogJakartaClientIpResolver);
        });
    }

    @Test
    void propertiesBoundIntoPayloadPolicyAndMasker() {
        this.runner.withPropertyValues(
                        "operate-log.payload.max-request-length=120",
                        "operate-log.payload.max-response-length=64",
                        "operate-log.payload.max-error-stack-length=32",
                        "operate-log.mask.mask-text=[hidden]")
                .run((context) -> {
                    PayloadPolicy policy = context.getBean(PayloadPolicy.class);
                    // 上限含截断标记：结果长度必须恰好等于配置值，不得超过
                    assertEquals(64, policy.truncateResponse(text(500)).length());
                    assertEquals(120, policy.truncateRequest(text(500)).length());
                    assertEquals(32, policy.truncateErrorStack(text(500)).length());
                    SensitiveDataMasker masker = context.getBean(SensitiveDataMasker.class);
                    assertTrue(masker.mask("{\"password\":\"x\"}").contains("[hidden]"));
                });
    }

    /** 注解只允许方法级：类级标注会连带拦截整类方法，此断言防止 {@code @Target} 被顺手放宽。 */
    @Test
    void operateLogAnnotationTargetsMethodsOnly() {
        Target target = OperateLog.class.getAnnotation(Target.class);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
    }

    /** 日志模型与 HTTP 上下文都不携带状态码字段（取舍见 {@code HttpContextResolver}）。 */
    @Test
    void logModelHasNoHttpStatusField() {
        assertFalse(hasField(OperateLogRecord.class, "httpStatus"), "OperateLogRecord 不应再有 httpStatus 字段");
        assertFalse(hasField(OperateLogRecord.class, "requestStatus"), "不应以其他名字重新引入状态码");
        assertFalse(hasField(HttpContext.class, "status"), "HttpContext 不应再有 status 字段");
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (name.equals(field.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String text(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append('a');
        }
        return builder.toString();
    }

}
