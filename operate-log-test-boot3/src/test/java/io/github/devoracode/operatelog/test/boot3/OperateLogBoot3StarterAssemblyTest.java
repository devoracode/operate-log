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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                    // getStartupFailure() 返回 Throwable，不是 Optional（Boot 2 / 3 皆如此）
                    assertNull(context.getStartupFailure(),
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
                        "operate-log.payload.max-error-length=32",
                        "operate-log.mask.mask-text=[hidden]")
                .run((context) -> {
                    PayloadPolicy policy = context.getBean(PayloadPolicy.class);
                    // 上限含截断标记：结果长度必须恰好等于配置值，不得超过
                    assertEquals(64, policy.truncateResponse(text(500)).length());
                    assertEquals(120, policy.truncateRequest(text(500)).length());
                    assertEquals(32, policy.truncateErrorStack(text(500)).length());
                    assertEquals(32, policy.truncateErrorMessage(text(500)).length());
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

    /**
     * 脱敏字段配置是「追加」而非「整体替换」：只想加一个字段的配置动作不得静默关掉内置默认项。
     * query string 掩码复用同一份字段集合，一并在此钉死。
     */
    @Test
    void configuredMaskFieldsAreAppendedToBuiltInDefaults() {
        this.runner.withPropertyValues("operate-log.mask.fields=mobile,idCard")
                .run((context) -> {
                    SensitiveDataMasker masker = context.getBean(SensitiveDataMasker.class);

                    // 追加字段生效（JSON 与 query 两条通道）
                    String custom = masker.mask("{\"mobile\":\"13800000000\",\"idCard\":\"11010119900101\"}");
                    assertFalse(custom.contains("13800000000"), custom);
                    assertFalse(custom.contains("11010119900101"), custom);
                    assertFalse(masker.maskQuery("mobile=13800000000&page=1").contains("13800000000"));

                    // 内置默认字段不被配置顶掉——本用例的核心断言
                    String builtIn = masker.mask("{\"password\":\"p\",\"authorization\":\"Bearer t\",\"cookie\":\"c\"}");
                    assertFalse(builtIn.contains("\"p\""), builtIn);
                    assertFalse(builtIn.contains("Bearer t"), builtIn);
                    assertFalse(builtIn.contains("\"c\""), builtIn);
                    assertTrue(builtIn.contains("******"), builtIn);
                    assertFalse(masker.maskQuery("token=abc&page=1").contains("abc"));

                    // 未配置任何字段时默认集同样生效
                    assertFalse(masker.mask("{\"password\":\"p\"}").contains("\"p\""));
                });
    }

    /**
     * 参数名 URL 编码后仍须命中同一份敏感字段集合，否则 {@code %74oken=abc}
     * 这类写法会绕过 query 脱敏（P1-3）。
     */
    @Test
    void encodedQueryParameterNamesCannotBypassMasking() {
        this.runner.withPropertyValues("operate-log.mask.fields=mobile").run((context) -> {
            SensitiveDataMasker masker = context.getBean(SensitiveDataMasker.class);

            // 全编码参数名解码后等于 token
            String fullyEncoded = masker.maskQuery("%74oken=abc&page=1");
            assertFalse(fullyEncoded.contains("abc"), fullyEncoded);
            assertTrue(fullyEncoded.contains("page=1"), fullyEncoded);

            // 部分编码 + 大小写混合，解码后仍忽略大小写匹配
            String partial = masker.maskQuery("Pass%77ord=s3cr3t&mobile=13800000000");
            assertFalse(partial.contains("s3cr3t"), partial);
            assertFalse(partial.contains("13800000000"), partial);

            // 参数名原始写法保留，只替换值
            assertTrue(fullyEncoded.startsWith("%74oken="), fullyEncoded);

            // 未命中字段与非法的 % 序列按原样保留，不影响同串其余参数的脱敏
            String mixed = masker.maskQuery("bad%=1&token=abc");
            assertTrue(mixed.contains("bad%=1"), mixed);
            assertFalse(mixed.contains("abc"), mixed);
        });
    }

    /**
     * 切面顺序必须显式声明：默认值（未声明）会让与 {@code @Transactional} 的相对次序变成
     * 实现细节，success 语义随之不可预期。声明为 LOWEST_PRECEDENCE 后语义锁定为
     * 「业务方法未抛异常」，且不再依赖默认值推断（P1-5）。
     */
    @Test
    void aspectOrderIsExplicitlyDeclared() {
        Order order = OperateLogAspect.class.getAnnotation(Order.class);
        assertTrue(order != null, "OperateLogAspect 必须显式声明 @Order，否则与 @Transactional 顺序未定义");
        assertEquals(Ordered.LOWEST_PRECEDENCE, order.value());
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
