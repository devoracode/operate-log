package io.github.devoracode.operatelog.boot;

import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.boot.servlet.jakarta.OperateLogJakartaClientIpResolver;
import io.github.devoracode.operatelog.boot.servlet.jakarta.OperateLogJakartaHttpContextResolver;
import io.github.devoracode.operatelog.boot.servlet.javax.OperateLogJavaxClientIpResolver;
import io.github.devoracode.operatelog.boot.servlet.javax.OperateLogJavaxHttpContextResolver;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import io.github.devoracode.operatelog.handler.OperateLogHandler;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
import io.github.devoracode.operatelog.resolver.AnonymousOperatorResolver;
import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import io.github.devoracode.operatelog.resolver.OperatorResolver;
import io.github.devoracode.operatelog.sanitizer.DefaultSensitiveDataMasker;
import io.github.devoracode.operatelog.sanitizer.SensitiveDataMasker;
import io.github.devoracode.operatelog.serializer.DefaultOperateLogSerializer;
import io.github.devoracode.operatelog.serializer.OperateLogSerializer;
import io.github.devoracode.operatelog.spel.DefaultSpelEngine;
import io.github.devoracode.operatelog.spel.SpelEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 操作日志自动配置（Boot 2.x / 3.x 双栈通用）。本类只做总开关与属性绑定，bean 按职责
 * 分入四个内部配置类：{@code CommonConfiguration}（栈无关组件）、
 * {@code JakartaServletConfiguration}、{@code JavaxServletConfiguration}、
 * {@code FallbackConfiguration}（非 Web 兜底）。
 *
 * <p>三个 Servlet 相关配置的条件<b>两两互斥</b>，由 classpath 唯一决定：jakarta 存在 → jakarta 栈；
 * 只有 javax → javax 栈；两者皆无 → 兜底。「jakarta 优先」由 {@link ConditionalOnMissingClass} 表达。
 * 因此每个 bean 的取舍只取决于 classpath 与「用户是否已提供同类型 bean」，与 bean 方法、内部类的
 * 声明顺序无关，可任意重排；{@link ConditionalOnMissingBean} 只负责让位给用户自定义 bean。</p>
 *
 * <p><b>硬性纪律</b>：条件 bean 方法的返回 / 参数类型只能用 core 接口与 {@link OperateLogProperties}
 * 等跨栈类型——Spring 用 ASM 解析方法签名，签名里出现具体栈实现类会在条件
 * 未命中时触发类加载失败，所以栈专属类型只能在方法体内 {@code new}，且
 * {@code @ConditionalOnClass} / {@code @ConditionalOnMissingClass} 一律写成字符串形式。</p>
 *
 * <p>注册入口两套：{@code META-INF/spring.factories}（Boot 2.x）与
 * {@code META-INF/spring/...AutoConfiguration.imports}（Boot 3.x）。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperateLogProperties.class)
@ConditionalOnProperty(prefix = "operate-log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OperateLogAutoConfiguration {

    /**
     * 与 Servlet 栈无关的通用组件：序列化、脱敏、SpEL、载荷防护、Handler 与切面本体。
     * 切面只依赖 {@link HttpContextResolver} / {@link ClientIpResolver} 接口，
     * 实现由下面三个栈配置之一提供，因此本类无需任何 Servlet 相关条件。
     */
    @Configuration(proxyBeanMethods = false)
    static class CommonConfiguration {

        /** 默认操作人解析器：匿名（返回 {@code null}）。 */
        @Bean
        @ConditionalOnMissingBean(OperatorResolver.class)
        public OperatorResolver operateLogOperatorResolver() {
            return new AnonymousOperatorResolver();
        }

        /** 默认序列化器：用组件自带的 JSON 实现，不依赖宿主 Jackson（Boot 4 已换成 Jackson 3）。 */
        @Bean
        @ConditionalOnMissingBean(OperateLogSerializer.class)
        public OperateLogSerializer operateLogSerializer() {
            return new DefaultOperateLogSerializer();
        }

        /** 默认脱敏器：在组件自带 JSON 实现的动态树上只替换命中字段名的值。 */
        @Bean
        @ConditionalOnMissingBean(SensitiveDataMasker.class)
        public SensitiveDataMasker operateLogSensitiveDataMasker(OperateLogProperties properties) {
            return new DefaultSensitiveDataMasker(properties.getMask().getFields(),
                    properties.getMask().getMaskText());
        }

        /** 默认 SpEL 引擎（表达式缓存与降级策略见 {@code DefaultSpelEngine}）。 */
        @Bean
        @ConditionalOnMissingBean(SpelEngine.class)
        public SpelEngine operateLogSpelEngine(OperateLogProperties properties) {
            return new DefaultSpelEngine(properties.getSpel().getCacheSize(), properties.getSpel().isEnabled());
        }

        /** 载荷防护策略：大字段截断 + 忽略类型过滤。 */
        @Bean
        @ConditionalOnMissingBean(PayloadPolicy.class)
        public PayloadPolicy operateLogPayloadPolicy(OperateLogProperties properties) {
            OperateLogProperties.Payload payload = properties.getPayload();
            return new PayloadPolicy(payload.getMaxRequestLength(),
                    payload.getMaxResponseLength(),
                    payload.getMaxErrorStackLength(),
                    payload.getIgnoreTypes());
        }

        /** 默认处理器：单行 JSON 输出到 SLF4J。 */
        @Bean
        @ConditionalOnMissingBean(OperateLogHandler.class)
        public OperateLogHandler operateLogHandler() {
            return new DefaultOperateLogHandler();
        }

        /** 操作日志切面：组装上述全部组件。 */
        @Bean
        @ConditionalOnMissingBean(OperateLogAspect.class)
        public OperateLogAspect operateLogAspect(OperateLogHandler handler,
                                                 OperatorResolver operatorResolver,
                                                 HttpContextResolver httpContextResolver,
                                                 OperateLogSerializer serializer,
                                                 SensitiveDataMasker sensitiveDataMasker,
                                                 SpelEngine spelEngine,
                                                 PayloadPolicy payloadPolicy,
                                                 OperateLogProperties properties) {
            return new OperateLogAspect(handler,
                    operatorResolver,
                    httpContextResolver,
                    serializer,
                    sensitiveDataMasker,
                    spelEngine,
                    properties.getApplication(),
                    properties.getEnvironment(),
                    properties.getVersion(),
                    properties.getMask().isEnabled(),
                    payloadPolicy,
                    properties.getTraceIdMdcKey());
        }
    }

    /** jakarta 栈（Boot 3.x）装配：classpath 存在 {@code jakarta.servlet} 时生效。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    static class JakartaServletConfiguration {

        /** jakarta 栈客户端 IP 解析器（返回类型必须是 core 接口，理由见类注释）。 */
        @Bean
        @ConditionalOnMissingBean(ClientIpResolver.class)
        public ClientIpResolver operateLogJakartaClientIpResolver(OperateLogProperties properties) {
            return new OperateLogJakartaClientIpResolver(properties.getHttp().isTrustProxy());
        }

        /** jakarta 栈 HTTP 上下文解析器。 */
        @Bean
        @ConditionalOnMissingBean(HttpContextResolver.class)
        public HttpContextResolver operateLogJakartaHttpContextResolver(ClientIpResolver clientIpResolver,
                                                                       OperateLogProperties properties) {
            return new OperateLogJakartaHttpContextResolver(clientIpResolver, properties.getHttp().isCaptureHeaders());
        }
    }

    /**
     * javax 栈（Boot 2.x）装配：存在 {@code javax.servlet} 且<b>不存在</b> {@code jakarta.servlet}
     * 时生效——后半个条件即「jakarta 优先」，两栈共存时不会重复装配。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
    @ConditionalOnMissingClass("jakarta.servlet.http.HttpServletRequest")
    static class JavaxServletConfiguration {

        /** javax 栈客户端 IP 解析器（返回类型必须是 core 接口，理由见类注释）。 */
        @Bean
        @ConditionalOnMissingBean(ClientIpResolver.class)
        public ClientIpResolver operateLogJavaxClientIpResolver(OperateLogProperties properties) {
            return new OperateLogJavaxClientIpResolver(properties.getHttp().isTrustProxy());
        }

        /** javax 栈 HTTP 上下文解析器。 */
        @Bean
        @ConditionalOnMissingBean(HttpContextResolver.class)
        public HttpContextResolver operateLogJavaxHttpContextResolver(ClientIpResolver clientIpResolver,
                                                                      OperateLogProperties properties) {
            return new OperateLogJavaxHttpContextResolver(clientIpResolver, properties.getHttp().isCaptureHeaders());
        }
    }

    /**
     * 非 Web 环境（定时任务、后台服务等）兜底：两种 Servlet API 都不存在时生效，
     * 用返回 {@code null} 的空实现保证 {@link OperateLogAspect} 的构造注入永远成立。
     * 用户只提供其中一个实现时，另一个仍走兜底 bean，避免注入缺口。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass({
            "jakarta.servlet.http.HttpServletRequest",
            "javax.servlet.http.HttpServletRequest"})
    static class FallbackConfiguration {

        /** 兜底客户端 IP 解析器：恒返回 {@code null}。 */
        @Bean
        @ConditionalOnMissingBean(ClientIpResolver.class)
        public ClientIpResolver operateLogFallbackClientIpResolver() {
            return new ClientIpResolver() {
                @Override
                public String resolve() {
                    return null;
                }
            };
        }

        /** 兜底 HTTP 上下文解析器：恒返回 {@code null}，日志中 HTTP 字段一律为空。 */
        @Bean
        @ConditionalOnMissingBean(HttpContextResolver.class)
        public HttpContextResolver operateLogFallbackHttpContextResolver() {
            return new HttpContextResolver() {
                @Override
                public HttpContext resolve() {
                    return null;
                }
            };
        }
    }
}
