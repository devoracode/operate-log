package io.github.devoracode.operatelog.sanitizer;

/**
 * 敏感数据脱敏器：作用于序列化后的整段文本。
 *
 * <p>{@link #mask(String)} 处理 JSON 格式内容；{@link #maskQuery(String)} 处理
 * URL query string（{@code name=value&...}）格式内容。默认实现为原样返回，
 * 具体脱敏器应覆写以提供 query 参数级掩码。</p>
 */
public interface SensitiveDataMasker {

    /** 对 JSON 格式内容进行敏感字段脱敏。 */
    String mask(String content);

    /**
     * 对 URL query string（{@code name=value&...}）中的敏感参数值进行掩码。
     * 默认实现原样返回；{@link DefaultSensitiveDataMasker} 覆写后按参数名匹配并替换。
     *
     * @param query 原始 query string，可能为 {@code null} 或空
     * @return 脱敏后的 query string
     */
    default String maskQuery(String query) {
        return query;
    }
}
