package io.github.devoracode.operatelog.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.github.devoracode.operatelog.sanitizer.JacksonSensitiveDataMasker;
import io.github.devoracode.operatelog.sanitizer.SensitiveDataMasker;
import io.github.devoracode.operatelog.serializer.JacksonOperateLogSerializer;
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
 * 操作日志自动配置（Spring Boot 2.x / 3.x 双栈通用）。
 *
 * <p><b>装配结构</b>：本类只负责总开关与属性绑定，具体 bean 按职责分入四个内部配置类：</p>
 * <pre>{@code
 * OperateLogAutoConfiguration                  operate-log.enabled 总开关
 * ├── CommonConfiguration                       与 Servlet 栈无关的通用组件
 * ├── JakartaServletConfiguration               Boot 3.x（jakarta.servlet）
 * ├── JavaxServletConfiguration                 Boot 2.x（javax.servlet）
 * └── FallbackConfiguration                     非 Web 环境兜底
 * }</pre>
 *
 * <p><b>双栈装配机制（条件互斥，不依赖声明顺序）：</b></p>
 * <ul>
 *   <li>三个 Servlet 相关配置类的条件<b>两两互斥</b>，由 classpath 上的 Servlet API 唯一决定：
 *       jakarta 存在 → 装配 jakarta 栈；jakarta 不存在且 javax 存在 → 装配 javax 栈；
 *       两者都不存在 → 装配兜底实现。
 *       「{@code jakarta} 优先」由 {@link ConditionalOnMissingClass} 显式表达，
 *       而不是靠「javax 配置排在 jakarta 配置之后」。</li>
 *   <li><b>正确性与 bean 方法 / 内部类的声明顺序无关</b>（旧实现依赖同一配置类内
 *       {@code jakarta → javax → 兜底} 的串联顺序，把 {@code @ConditionalOnMissingBean}
 *       的评估次序当成装配契约：Spring 并不承诺跨类 / 跨方法的求值顺序，
 *       重排方法或 IDE 整理代码即可能静默产生重复 bean 或缺失 bean）。
 *       现在每个 bean 的取舍只由「classpath 条件 + 用户是否已提供同类型 bean」决定，
 *       可任意重排。</li>
 *   <li>各栈 bean 方法上的 {@link ConditionalOnMissingBean} 只用于「让位给用户自定义 Bean」
 *     （README「扩展点」契约），不再承担栈间互斥职责。</li>
 *   <li><b>硬性纪律</b>：条件 bean 方法的返回类型 / 参数类型只能使用 core 接口、
 *       {@link OperateLogProperties}、{@link ObjectMapper} 等跨栈兼容类型——
 *       Spring 用 ASM 解析方法签名，若出现具体栈实现类会在条件未命中时触发类加载失败；
 *       具体栈类只能在方法体内 {@code new}（条件未命中时方法体不执行，不会加载）。
 *       各栈专属实现因此被收进各自的嵌套配置类，其类级
 *       {@code @ConditionalOnClass(name = "...")} 保证条件未命中时整个类不被加载。</li>
 *   <li>{@code @ConditionalOnClass} / {@code @ConditionalOnMissingClass} 必须使用
 *       字符串形式，避免注解解析阶段加载不存在的 Servlet 类。</li>
 * </ul>
 *
 * <p>本类同时注册在 {@code META-INF/spring.factories}（Boot 2.x 读取）
 * 与 {@code META-INF/spring/...AutoConfiguration.imports}（Boot 3.x 读取）。</p>
 *
 * @author devoracode
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperateLogProperties.class)
@ConditionalOnProperty(prefix = "operate-log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OperateLogAutoConfiguration {

    /**
     * 与 Servlet 栈无关的通用组件：序列化、脱敏、SpEL、载荷防护、Handler 与切面本体。
     *
     * <p>切面通过 {@link HttpContextResolver} / {@link ClientIpResolver} 接口协作，
     * 具体实现由下面三个栈配置之一提供，因此本类不需要任何 Servlet 相关条件。</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class CommonConfiguration {

        /**
         * 默认操作人解析器：匿名（返回 {@code null}），业务方自定义 bean 后自动让位。
         */
        @Bean
        @ConditionalOnMissingBean(OperatorResolver.class)
        public OperatorResolver operateLogOperatorResolver() {
            return new AnonymousOperatorResolver();
        }

        /**
         * 基于 Jackson 的序列化器（复用宿主 ObjectMapper）。
         */
        @Bean
        @ConditionalOnMissingBean(OperateLogSerializer.class)
        public OperateLogSerializer operateLogSerializer(ObjectMapper objectMapper) {
            return new JacksonOperateLogSerializer(objectMapper);
        }

        /**
         * 基于 Jackson JSON 树的敏感数据脱敏器。
         */
        @Bean
        @ConditionalOnMissingBean(SensitiveDataMasker.class)
        public SensitiveDataMasker operateLogSensitiveDataMasker(ObjectMapper objectMapper,
                                                                 OperateLogProperties properties) {
            return new JacksonSensitiveDataMasker(objectMapper,
                    properties.getMask().getFields(),
                    properties.getMask().getMaskText());
        }

        /**
         * 默认 SpEL 引擎（带表达式解析缓存）。
         *
         * <p>{@code operate-log.spel.enabled=false} 时引擎整体降级为直通：
         * condition 恒通过、description 输出模板原文、businessId 记 {@code null}，
         * 不产生任何表达式求值开销。</p>
         */
        @Bean
        @ConditionalOnMissingBean(SpelEngine.class)
        public SpelEngine operateLogSpelEngine(OperateLogProperties properties) {
            return new DefaultSpelEngine(properties.getSpel().getCacheSize(), properties.getSpel().isEnabled());
        }

        /**
         * 载荷防护策略：requestBody / responseBody / errorStack 等字段长度截断，
         * 以及序列化时按类型忽略文件 / 流 / Servlet 容器等不适合入日志的参数。
         */
        @Bean
        @ConditionalOnMissingBean(PayloadPolicy.class)
        public PayloadPolicy operateLogPayloadPolicy(OperateLogProperties properties) {
            OperateLogProperties.Payload payload = properties.getPayload();
            return new PayloadPolicy(payload.getMaxRequestLength(),
                    payload.getMaxResponseLength(),
                    payload.getMaxErrorStackLength(),
                    payload.getIgnoreTypes());
        }

        /**
         * 默认处理器：将日志 JSON 输出到 SLF4J。
         */
        @Bean
        @ConditionalOnMissingBean(OperateLogHandler.class)
        public OperateLogHandler operateLogHandler(ObjectMapper objectMapper) {
            return new DefaultOperateLogHandler(objectMapper);
        }

        /**
         * 操作日志切面（组装全部组件）。
         */
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

    /**
     * jakarta 栈（Spring Boot 3.x）装配：classpath 存在 {@code jakarta.servlet} 时生效，
     * 与 javax 栈、兜底实现互斥。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    static class JakartaServletConfiguration {

        /**
         * jakarta 栈客户端 IP 解析器。
         *
         * <p>返回类型必须是 core 接口 {@link ClientIpResolver}：
         * Spring ASM 解析方法签名时若引用具体栈类，
         * 在 Boot 2 宿主（classpath 无 jakarta）上会直接类加载失败。</p>
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
            return new OperateLogJakartaHttpContextResolver(clientIpResolver, properties.getHttp().isCaptureHeaders());
        }
    }

    /**
     * javax 栈（Spring Boot 2.x）装配：classpath 存在 {@code javax.servlet} 且
     * <b>不存在</b> {@code jakarta.servlet} 时生效——后半个条件即「jakarta 优先」的显式表达，
     * 两栈共存（少见，如 Boot 2 + Servlet 5 容器）时不会与 jakarta 栈重复装配。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
    @ConditionalOnMissingClass("jakarta.servlet.http.HttpServletRequest")
    static class JavaxServletConfiguration {

        /**
         * javax 栈客户端 IP 解析器。
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
            return new OperateLogJavaxHttpContextResolver(clientIpResolver, properties.getHttp().isCaptureHeaders());
        }
    }

    /**
     * 非 Web 环境兜底装配：classpath 上两种 Servlet API 都不存在时生效
     * （定时任务、后台服务等），返回 {@code null} 的空实现保证
     * {@link OperateLogAspect} 的构造注入永远成立、应用正常启动。
     *
     * <p>与两个栈配置类的互斥由类级 {@link ConditionalOnMissingClass} 保证，
     * 方法级 {@link ConditionalOnMissingBean} 再让用户自定义实现优先（用户已提供
     * {@link HttpContextResolver} 而未提供 {@link ClientIpResolver} 时，
     * 兜底 IP 解析器仍会注册，供用户实现注入使用）。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass({
            "jakarta.servlet.http.HttpServletRequest",
            "javax.servlet.http.HttpServletRequest"})
    static class FallbackConfiguration {

        /**
         * 兜底客户端 IP 解析器：非 Web 环境返回 {@code null}。
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
         * 兜底 HTTP 上下文解析器：非 Web 环境返回 {@code null}，
         * 日志中 HTTP 相关字段（method / url / uri / query / headers / clientIp / userAgent）
         * 一律为 {@code null}，切面其余字段照常记录。
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
