package io.github.devoracode.operatelog.boot;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 操作日志配置属性（Spring Boot 2.x / 3.x 通用）。
 *
 * <p>属性与合并前 Boot2 / Boot3 两个 starter 的属性类保持兼容，
 * 仅合并为单一实现；本版本新增 {@code payload} 载荷防护段。</p>
 *
 * @author devoracode
 */
@Data
@ConfigurationProperties(prefix = "operate-log")
public class OperateLogProperties {

    /**
     * 是否启用操作日志组件。
     */
    private boolean enabled = true;

    /**
     * 应用名称，写入日志记录便于多应用区分。
     */
    private String application = "";

    /**
     * 运行环境（如 dev / test / prod）。
     */
    private String environment = "";

    /**
     * 应用版本号。
     */
    private String version = "";

    /**
     * traceId 所在的 MDC key，用于与链路追踪体系打通。
     *
     * <p>Micrometer Tracing（Brave/Sleuth 同）默认写入 {@code traceId}；
     * OpenTelemetry logback-mdc 桥接常见为 {@code trace_id}。
     * 取不到该 key 的值时自动生成 UUID；配置为空白时回退默认 key {@code traceId}。</p>
     */
    private String traceIdMdcKey = "traceId";

    /**
     * HTTP 相关配置。
     */
    private Http http = new Http();

    /**
     * 敏感数据脱敏配置。
     */
    private Mask mask = new Mask();

    /**
     * SpEL 表达式引擎配置。
     */
    private Spel spel = new Spel();

    /**
     * 载荷防护配置（大字段截断 + 序列化忽略类型）。
     */
    private Payload payload = new Payload();

    /**
     * HTTP 请求采集配置。
     */
    @Data
    public static class Http {

        /**
         * 是否信任反向代理头（X-Forwarded-For / X-Real-IP）。
         */
        private boolean trustProxy;

        /**
         * 是否采集完整请求头。
         */
        private boolean captureHeaders;
    }

    /**
     * 敏感数据脱敏配置。
     */
    @Data
    public static class Mask {

        /**
         * 是否启用脱敏。
         */
        private boolean enabled = true;

        /**
         * 脱敏替换文本。
         */
        private String maskText = "******";

        /**
         * 敏感字段名集合（匹配时忽略大小写）。
         */
        private Set<String> fields = new LinkedHashSet<String>(Arrays.asList(
                "password",
                "passwd",
                "pwd",
                "token",
                "accessToken",
                "refreshToken",
                "authorization",
                "cookie",
                "set-cookie",
                "secret",
                "clientSecret"));
    }

    /**
     * SpEL 表达式引擎配置。
     */
    @Data
    public static class Spel {

        /**
         * 是否启用 SpEL。
         */
        private boolean enabled = true;

        /**
         * 表达式解析缓存大小。
         */
        private int cacheSize = 1024;
    }

    /**
     * 载荷防护配置：限制单条日志各载荷字段的最大长度，
     * 并在序列化参数时跳过不适合入日志的类型（文件 / 流 / Servlet 容器对象等）。
     */
    @Data
    public static class Payload {

        /**
         * requestBody / requestHeaders 最大字符数，&lt;=0 表示不截断。
         */
        private int maxRequestLength = 2048;

        /**
         * responseBody 最大字符数，&lt;=0 表示不截断。
         */
        private int maxResponseLength = 2048;

        /**
         * errorStack 最大字符数，&lt;=0 表示不截断。
         */
        private int maxErrorStackLength = 4096;

        /**
         * 序列化时跳过的方法参数类型（全限定类名，命中父类或任意接口即算），
         * 命中参数以 {@code <IGNORED:类型简名>} 占位入日志。
         * 配置后将整体替换内置默认列表（而非追加）。
         */
        private List<String> ignoreTypes = new ArrayList<String>(Arrays.asList(
                "javax.servlet.ServletRequest",
                "javax.servlet.ServletResponse",
                "jakarta.servlet.ServletRequest",
                "jakarta.servlet.ServletResponse",
                "org.springframework.web.multipart.MultipartFile",
                "org.springframework.validation.BindingResult",
                "org.springframework.web.servlet.ModelAndView",
                "java.io.InputStream",
                "java.io.OutputStream",
                "java.io.Reader",
                "java.io.Writer",
                "[B"));
    }
}
