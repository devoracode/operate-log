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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 操作日志自动配置（Spring Boot 2.x / 3.x 双栈通用）。
 *
 * <p><b>双栈装配机制：</b></p>
 * <ul>
 *   <li>Servlet 相关实现按 classpath 自动条件装配：
 *       存在 {@code jakarta.servlet.http.HttpServletRequest}（Boot 3.x）时装配 jakarta 栈，
 *       否则存在 {@code javax.servlet.http.HttpServletRequest}（Boot 2.x）时装配 javax 栈，
 *       两者都不存在（非 Web 环境）时装配返回 {@code null} 的兜底实现，
 *       保证 {@link OperateLogAspect} 构造注入永远成立。</li>
 *   <li><b>bean 声明顺序是正确性的组成部分，绝不可重排</b>：
 *       Spring 对同一配置类内的 {@code @Bean} 方法按声明顺序评估条件，
 *       必须保证 jakarta → javax → 兜底 的串联顺序，每种类型恰好装配一个实现。</li>
 *   <li><b>硬性纪律</b>：
 *       条件 bean 方法的返回类型 / 参数类型只能使用 core 接口、
 *       {@link OperateLogProperties}、{@link ObjectMapper} 等跨栈兼容类型——
 *       Spring 用 ASM 解析方法签名，若出现具体栈实现类会在条件未命中时触发类加载失败；
 *       具体栈类只能在方法体内 {@code new}（条件未命中时方法体不执行，不会加载）。</li>
 *   <li>{@code @ConditionalOnClass} 必须使用 {@code name =} 字符串形式，
 *       避免注解解析阶段加载不存在的 Servlet 类。</li>
 * </ul>
 *
 * <p>本类同时注册在 {@code META-INF/spring.factories}（Boot 2.x 读取）
 * 与 {@code META-INF/spring/...AutoConfiguration.imports}（Boot 3.x 读取）。</p>
 *
 * @author devoracode
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperateLogProperties.class)
@ConditionalOnProperty(
        prefix = "operate-log",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OperateLogAutoConfiguration {

    /**
     * 默认操作人解析器：匿名（返回 {@code null}），业务方自定义 bean 后自动让位。
     */
    @Bean
    @ConditionalOnMissingBean
    public OperatorResolver operateLogOperatorResolver() {
        return new AnonymousOperatorResolver();
    }

    // ==================== jakarta 栈（Spring Boot 3.x，优先装配） ====================

    /**
     * jakarta 栈客户端 IP 解析器。
     *
     * <p>返回类型必须是 core 接口 {@link ClientIpResolver}：
     * Spring ASM 解析方法签名时若引用具体栈类，
     * 在 Boot 2 宿主（classpath 无 jakarta）上会直接类加载失败。</p>
     */
    @Bean
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    @ConditionalOnMissingBean
    public ClientIpResolver operateLogJakartaClientIpResolver(
            OperateLogProperties properties) {
        return new OperateLogJakartaClientIpResolver(
                properties.getHttp().isTrustProxy());
    }

    /**
     * jakarta 栈 HTTP 上下文解析器。
     */
    @Bean
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    @ConditionalOnMissingBean
    public HttpContextResolver operateLogJakartaHttpContextResolver(
            ClientIpResolver clientIpResolver,
            OperateLogProperties properties) {
        return new OperateLogJakartaHttpContextResolver(
                clientIpResolver,
                properties.getHttp().isCaptureHeaders());
    }

    // ==================== javax 栈（Spring Boot 2.x，jakarta 未命中时装配） ====================

    /**
     * javax 栈客户端 IP 解析器。
     */
    @Bean
    @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
    @ConditionalOnMissingBean
    public ClientIpResolver operateLogJavaxClientIpResolver(
            OperateLogProperties properties) {
        return new OperateLogJavaxClientIpResolver(
                properties.getHttp().isTrustProxy());
    }

    /**
     * javax 栈 HTTP 上下文解析器。
     */
    @Bean
    @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
    @ConditionalOnMissingBean
    public HttpContextResolver operateLogJavaxHttpContextResolver(
            ClientIpResolver clientIpResolver,
            OperateLogProperties properties) {
        return new OperateLogJavaxHttpContextResolver(
                clientIpResolver,
                properties.getHttp().isCaptureHeaders());
    }

    // ==================== 非 Web 兜底（classpath 无任何 Servlet API） ====================

    /**
     * 兜底客户端 IP 解析器：非 Web 环境返回 {@code null}，
     * 保证 {@link OperateLogAspect} 构造注入不因缺少实现而启动失败。
     * 必须声明在两个栈实现之后（依赖条件评估顺序）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ClientIpResolver operateLogFallbackClientIpResolver() {
        return new ClientIpResolver() {
            @Override
            public String resolve() {
                return null;
            }
        };
    }

    /**
     * 兜底 HTTP 上下文解析器：非 Web 环境返回 {@code null}。
     * 必须声明在两个栈实现之后（依赖条件评估顺序）。
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpContextResolver operateLogFallbackHttpContextResolver() {
        return new HttpContextResolver() {
            @Override
            public HttpContext resolve() {
                return null;
            }
        };
    }

    // ==================== 通用组件（与栈无关） ====================

    /**
     * 基于 Jackson 的序列化器（复用宿主 ObjectMapper）。
     */
    @Bean
    @ConditionalOnMissingBean
    public OperateLogSerializer operateLogSerializer(
            ObjectMapper objectMapper) {
        return new JacksonOperateLogSerializer(objectMapper);
    }

    /**
     * 基于 Jackson JSON 树的敏感数据脱敏器。
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveDataMasker operateLogSensitiveDataMasker(
            ObjectMapper objectMapper,
            OperateLogProperties properties) {
        return new JacksonSensitiveDataMasker(
                objectMapper,
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
    @ConditionalOnMissingBean
    public SpelEngine operateLogSpelEngine(OperateLogProperties properties) {
        return new DefaultSpelEngine(
                properties.getSpel().getCacheSize(),
                properties.getSpel().isEnabled());
    }

    /**
     * 载荷防护策略：requestBody / responseBody / errorStack 等字段长度截断，
     * 以及序列化时按类型忽略文件 / 流 / Servlet 容器等不适合入日志的参数。
     */
    @Bean
    @ConditionalOnMissingBean
    public PayloadPolicy operateLogPayloadPolicy(OperateLogProperties properties) {
        OperateLogProperties.Payload payload = properties.getPayload();
        return new PayloadPolicy(
                payload.getMaxRequestLength(),
                payload.getMaxResponseLength(),
                payload.getMaxErrorStackLength(),
                payload.getIgnoreTypes());
    }

    /**
     * 默认处理器：将日志 JSON 输出到 SLF4J。
     */
    @Bean
    @ConditionalOnMissingBean
    public OperateLogHandler operateLogHandler(
            ObjectMapper objectMapper) {
        return new DefaultOperateLogHandler(objectMapper);
    }

    /**
     * 操作日志切面（组装全部组件）。
     */
    @Bean
    @ConditionalOnMissingBean
    public OperateLogAspect operateLogAspect(
            OperateLogHandler handler,
            OperatorResolver operatorResolver,
            HttpContextResolver httpContextResolver,
            OperateLogSerializer serializer,
            SensitiveDataMasker sensitiveDataMasker,
            SpelEngine spelEngine,
            PayloadPolicy payloadPolicy,
            OperateLogProperties properties) {
        return new OperateLogAspect(
                handler,
                operatorResolver,
                httpContextResolver,
                serializer,
                sensitiveDataMasker,
                spelEngine,
                properties.getApplication(),
                properties.getEnvironment(),
                properties.getVersion(),
                properties.getMask().isEnabled(),
                payloadPolicy);
    }
}
