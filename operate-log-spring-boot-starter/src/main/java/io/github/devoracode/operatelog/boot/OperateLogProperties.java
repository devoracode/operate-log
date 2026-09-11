package io.github.devoracode.operatelog.boot;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 操作日志配置属性（前缀 {@code operate-log}）。这里的注释即 IDE 在 application.yml
 * 中的悬停提示，逐项语义与默认值另见 README「配置参考」。
 */
@Data
@ConfigurationProperties(prefix = "operate-log")
public class OperateLogProperties {

    /** 总开关；关闭后不注册任何 bean，切面也不织入。 */
    private boolean enabled = true;

    /** 应用名称，便于多应用共用日志库时区分。 */
    private String application = "";

    /** 运行环境，如 dev / test / prod。 */
    private String environment = "";

    /** 应用版本号。 */
    private String version = "";

    /** traceId 的 MDC key；取不到值时自动生成 UUID（Micrometer 常见 traceId，OTel 桥接常见 trace_id）。 */
    private String traceIdMdcKey = "traceId";

    /** HTTP 请求采集配置。 */
    private Http http = new Http();

    /** 敏感数据脱敏配置。 */
    private Mask mask = new Mask();

    /** SpEL 引擎配置。 */
    private Spel spel = new Spel();

    /** 载荷防护配置。 */
    private Payload payload = new Payload();

    @Data
    public static class Http {

        /** 是否信任反向代理头；代理不可信时开启会被客户端伪造 IP。 */
        private boolean trustProxy;

        /** 是否采集完整请求头（脱敏后写入 requestHeaders）。 */
        private boolean captureHeaders;
    }

    @Data
    public static class Mask {

        /** 是否启用脱敏。 */
        private boolean enabled = true;

        /** 命中字段的替换文本。 */
        private String maskText = "******";

        /** 敏感字段名集合，匹配忽略大小写。 */
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

    @Data
    public static class Spel {

        /** 关闭后表达式全部直通，零求值开销（见 DefaultSpelEngine）。 */
        private boolean enabled = true;

        /** 表达式解析缓存条数上限，超出按 LRU 淘汰。 */
        private int cacheSize = 1024;
    }

    @Data
    public static class Payload {

        /** requestBody / requestHeaders 最大字符数（含截断标记），<=0 不截断。 */
        private int maxRequestLength = 2048;

        /** responseBody 最大字符数（含截断标记），<=0 不截断。 */
        private int maxResponseLength = 2048;

        /** errorStack 最大字符数，<=0 不截断。 */
        private int maxErrorStackLength = 4096;

        /**
         * 序列化时跳过的参数类型（全限定类名，命中父类或任意接口即算），日志中占位为
         * {@code <IGNORED:类型简名>}。注意是整体替换内置默认列表，不是追加。
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
