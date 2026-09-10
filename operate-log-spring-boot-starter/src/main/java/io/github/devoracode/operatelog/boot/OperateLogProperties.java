package io.github.devoracode.operatelog.boot;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 操作日志配置属性（Spring Boot 2.x / 3.x 通用）。
 *
 * <p>与合并前 Boot2 / Boot3 两个 starter 的属性类逐字段一致，
 * 仅合并为单一实现，避免双份维护。</p>
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
}
