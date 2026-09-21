package io.github.devoracode.operatelog.boot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 操作日志自动配置（Boot 2.x / 3.x 双栈通用）。本类只做总开关与属性绑定，
 * bean 按职责分入四个内部配置类。
 *
 * <p>三个 Servlet 配置的条件两两互斥，由 classpath 唯一决定：
 * jakarta 存在 → jakarta 栈；只有 javax → javax 栈；两者皆无 → 兜底。</p>
 *
 * <p>硬性纪律：条件 bean 方法的返回 / 参数类型只能用 core 接口与跨栈类型
 * （签名里出现栈专属实现类会在条件未命中时触发类加载失败）；
 * {@code @ConditionalOnClass} / {@code @ConditionalOnMissingClass} 一律写成字符串形式。</p>
 *
 * <p>边界纪律：本配置<b>不注册</b> {@link ObjectMapper} bean。自动配置按类名排序，
 * 本类（{@code io.github...}）排在 Boot 的 {@code JacksonAutoConfiguration}（{@code org.springframework.boot...}）
 * 之前，一旦注册就会顶掉宿主的 {@code spring.jackson.*} 与自定义 Module，波及宿主自身的
 * MVC 序列化。日志侧只取宿主 mapper 的防御性副本，宿主完全没有时才自建兜底实例。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperateLogProperties.class)
@ConditionalOnProperty(prefix = "operate-log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OperateLogAutoConfiguration {
    /**
     * 与 Servlet 栈无关的通用组件。
     * 切面只依赖 {@link HttpContextResolver} / {@link ClientIpResolver} 接口，
     * 实现由下面三个栈配置之一提供。
     */
    @Configuration(proxyBeanMethods = false)
    static class CommonConfiguration {
        private final ObjectProvider<ObjectMapper> objectMapperProvider;
        /**
         * serializer / masker / handler 三个 bean 共享同一份宿主 mapper 副本，{@code copy()} 只做一次。
         */
        private volatile ObjectMapper sharedSafeTimeMapper;

        CommonConfiguration(ObjectProvider<ObjectMapper> objectMapperProvider) {
            this.objectMapperProvider = objectMapperProvider;
        }

        /**
         * 默认操作人解析器：匿名（返回 {@code null}）。
         */
        @Bean
        @ConditionalOnMissingBean(OperatorResolver.class)
        public OperatorResolver operateLogOperatorResolver() {
            return new AnonymousOperatorResolver();
        }

        /**
         * 默认序列化器：用宿主 ObjectMapper 副本序列化请求/响应体（mapper 获取方式见 {@link #safeTimeMapper()}）。
         */
        @Bean
        @ConditionalOnMissingBean(OperateLogSerializer.class)
        public OperateLogSerializer operateLogSerializer() {
            return new DefaultOperateLogSerializer(safeTimeMapper());
        }

        /**
         * 默认脱敏器：在 Jackson JsonNode 动态树上只替换命中字段名的值。
         */
        @Bean
        @ConditionalOnMissingBean(SensitiveDataMasker.class)
        public SensitiveDataMasker operateLogSensitiveDataMasker(OperateLogProperties properties) {
            return new DefaultSensitiveDataMasker(safeTimeMapper(),
                    properties.getMask().getFields(),
                    properties.getMask().getMaskText());
        }

        /**
         * 默认 SpEL 引擎（表达式缓存与降级策略见 {@code DefaultSpelEngine}）。
         */
        @Bean
        @ConditionalOnMissingBean(SpelEngine.class)
        public SpelEngine operateLogSpelEngine(OperateLogProperties properties) {
            return new DefaultSpelEngine(properties.getSpel().getCacheSize(),
                    properties.getSpel().isEnabled());
        }

        /**
         * 载荷防护策略：大字段截断 + 忽略类型过滤。
         */
        @Bean
        @ConditionalOnMissingBean(PayloadPolicy.class)
        public PayloadPolicy operateLogPayloadPolicy(OperateLogProperties properties) {
            OperateLogProperties.Payload payload = properties.getPayload();
            return new PayloadPolicy(payload.getMaxRequestLength(),
                    payload.getMaxResponseLength(),
                    payload.getMaxErrorLength(),
                    payload.getMaxExtraLength(),
                    payload.getIgnoreTypes());
        }

        /**
         * 默认处理器：单行 JSON 输出到 SLF4J；宿主 mapper 的获取方式同序列化器（三 bean 共享同一副本）。
         */
        @Bean
        @ConditionalOnMissingBean(OperateLogHandler.class)
        public OperateLogHandler operateLogHandler() {
            return new DefaultOperateLogHandler(safeTimeMapper());
        }

        /**
         * 操作日志切面：组装上述全部组件。
         *
         * <p>AspectJ 类名用字符串形式（理由见类注释）。宿主关闭 AOP 自动装配
         * （{@code spring.aop.auto=false}）时本 bean 仍会创建但不会被织入，
         * Spring 不报错、日志静默缺失，需宿主自行确认。</p>
         */
        @Bean
        @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
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
                    properties.getTraceIdMdcKey(),
                    properties.getSpel().getCacheSize() * 4);
        }

        /**
         * 日志侧共享 ObjectMapper：首次调用取宿主 bean 的防御性副本（保留宿主全部配置，补注册
         * {@link JavaTimeModule} 保证时间类型可序列化），宿主完全没有 mapper 时自建兜底实例。
         * 三个消费 bean 共享同一实例、{@code copy()} 只做一次；惰性取值把解析时点留在消费 bean 创建期。
         */
        private ObjectMapper safeTimeMapper() {
            ObjectMapper cached = this.sharedSafeTimeMapper;
            if (cached != null) {
                return cached;
            }
            ObjectMapper hostMapper = this.objectMapperProvider.getIfAvailable();
            ObjectMapper mapper;
            if (hostMapper != null) {
                mapper = hostMapper.copy().registerModule(new JavaTimeModule());
            } else {
                mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());
                mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            }
            this.sharedSafeTimeMapper = mapper;
            return mapper;
        }
    }

    /**
     * jakarta 栈（Boot 3.x）装配：classpath 存在 {@code jakarta.servlet} 时生效。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    static class JakartaServletConfiguration {
        /**
         * jakarta 栈客户端 IP 解析器（返回类型必须是 core 接口，理由见类注释）。
         */
        @Bean
        @ConditionalOnMissingBean(ClientIpResolver.class)
        public ClientIpResolver operateLogJakartaClientIpResolver(OperateLogProperties properties) {
            return new OperateLogJakartaClientIpResolver(properties.getHttp().isTrustProxy());
        }

        /**
         * jakarta 栈 HTTP 上下文解析器。
         */
        @Bean
        @ConditionalOnMissingBean(HttpContextResolver.class)
        public HttpContextResolver operateLogJakartaHttpContextResolver(ClientIpResolver clientIpResolver,
                                                                        OperateLogProperties properties) {
            return new OperateLogJakartaHttpContextResolver(clientIpResolver,
                    properties.getHttp().isCaptureHeaders());
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
        /**
         * javax 栈客户端 IP 解析器（返回类型必须是 core 接口，理由见类注释）。
         */
        @Bean
        @ConditionalOnMissingBean(ClientIpResolver.class)
        public ClientIpResolver operateLogJavaxClientIpResolver(OperateLogProperties properties) {
            return new OperateLogJavaxClientIpResolver(properties.getHttp().isTrustProxy());
        }

        /**
         * javax 栈 HTTP 上下文解析器。
         */
        @Bean
        @ConditionalOnMissingBean(HttpContextResolver.class)
        public HttpContextResolver operateLogJavaxHttpContextResolver(ClientIpResolver clientIpResolver,
                                                                      OperateLogProperties properties) {
            return new OperateLogJavaxHttpContextResolver(clientIpResolver,
                    properties.getHttp().isCaptureHeaders());
        }
    }

    /**
     * 非 Web 环境兜底：两种 Servlet API 都不存在时生效，
     * 空实现保证 {@link OperateLogAspect} 的构造注入永远成立。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass({"jakarta.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletRequest"})
    static class FallbackConfiguration {
        /**
         * 兜底客户端 IP 解析器：恒返回 {@code null}。
         */
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

        /**
         * 兜底 HTTP 上下文解析器：恒返回 {@code null}，日志中 HTTP 字段一律为空。
         */
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
