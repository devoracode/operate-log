package io.github.devoracode.operatelog.sanitizer;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 敏感数据脱敏器：作用于序列化后的整段文本。
 *
 * <p>{@link #mask(String)} 处理 JSON 格式内容；{@link #maskQuery(String)} 处理
 * URL query string（{@code name=value&...}）格式内容。默认实现为原样返回，
 * 具体脱敏器应覆写以提供 query 参数级掩码。</p>
 */
public interface SensitiveDataMasker {
    Logger LOGGER = LoggerFactory.getLogger(SensitiveDataMasker.class);
    /**
     * 默认 maskQuery 被调用即说明实现类未覆写，只提示一次避免刷屏。
     */
    AtomicBoolean UNMASKED_QUERY_WARNED = new AtomicBoolean(false);

    /**
     * 对 JSON 格式内容进行敏感字段脱敏。
     *
     * @param content 序列化后的 JSON 文本
     * @return 脱敏后的文本；无法安全处理的内容应替换成占位符
     */
    String mask(String content);

    /**
     * 在序列化前按参数名或显式 MVC 绑定名隐藏参数。默认保护内置敏感字段，
     * 自定义脱敏器可覆写以追加字段或使用自己的替换文本。
     *
     * @param method 被拦截的方法，用于读取参数元数据
     * @param arguments 被拦截方法的实参，不得就地修改
     * @return 保持参数顺序的脱敏副本；无匹配时可返回原数组
     */
    default Object[] maskArguments(Method method, Object[] arguments) {
        return NamedArgumentMasking.mask(method, arguments,
                name -> DefaultSensitiveDataMasker.DEFAULT_FIELDS.stream().anyMatch(name::equalsIgnoreCase),
                DefaultSensitiveDataMasker.DEFAULT_MASK_TEXT);
    }

    /**
     * 对纯文本（非 JSON）内容进行敏感数据脱敏。
     * 异常 message 常拼接原始参数（含密码、token），但格式不是 JSON，
     * {@link #mask(String)} 无法处理，需走独立的纯文本掩码路径。
     * 默认实现原样返回；{@link DefaultSensitiveDataMasker} 覆写为按字段名子串匹配替换。
     *
     * @param text 待脱敏的纯文本
     * @return 脱敏后的文本；未命中敏感字段时原样返回 {@code text}
     */
    default String maskPlainText(String text) {
        return text;
    }

    /**
     * 对 URL query string（{@code name=value&...}）中的敏感参数值进行掩码。
     * 默认实现原样返回；{@link DefaultSensitiveDataMasker} 覆写后按参数名匹配并替换。
     *
     * <p>自定义 {@link SensitiveDataMasker} 若只覆写 {@link #mask(String)} 而忘记覆写本方法，
     * query 脱敏会静默失效——这属于安全控制的静默降级，故此处一次性 warn 提示（全局只提示一次）。</p>
     *
     * @param query 原始 query string
     * @return 脱敏后的 query string
     */
    default String maskQuery(String query) {
        if (StringUtils.isNotEmpty(query) && UNMASKED_QUERY_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("operate-log: maskQuery is NOT overridden by {}; URL query parameters " + "will NOT be masked. Override maskQuery(...) to enable query masking.",
                    getClass().getName());
        }
        return query;
    }
}
