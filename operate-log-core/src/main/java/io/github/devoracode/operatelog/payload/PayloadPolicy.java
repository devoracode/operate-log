package io.github.devoracode.operatelog.payload;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.ClassUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 载荷防护策略：大字段截断 + 序列化忽略类型过滤。目标是任何一条日志的体积都有上界，
 * 且流 / 文件 / Servlet 容器对象不会被序列化成一堆垃圾或直接失败。
 *
 * <p>忽略类型按<b>全限定类名</b>匹配父类链与全部接口（含传递接口），因此配置接口名
 * （如 {@code org.springframework.web.multipart.MultipartFile}）即可覆盖所有实现类。</p>
 */
public class PayloadPolicy {
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    private final int maxRequestLength;
    private final int maxResponseLength;
    private final int maxErrorStackLength;
    private final Set<String> ignoredTypes;

    public PayloadPolicy(int maxRequestLength,
                         int maxResponseLength,
                         int maxErrorStackLength,
                         Collection<String> ignoredTypes) {
        this.maxRequestLength = maxRequestLength;
        this.maxResponseLength = maxResponseLength;
        this.maxErrorStackLength = maxErrorStackLength;
        this.ignoredTypes = normalize(ignoredTypes);
    }

    /**
     * 命中「忽略类型」的参数替换为 {@code <IGNORED:短类名>}。
     * 无命中返回原数组（零拷贝），有命中先克隆再替换，不改调用方入参。
     */
    public Object[] filterArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0 || this.ignoredTypes.isEmpty()) {
            return arguments;
        }
        Object[] filtered = arguments;
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            if (argument == null || !isIgnored(argument.getClass())) {
                continue;
            }
            if (filtered == arguments) {
                filtered = arguments.clone();
            }
            filtered[i] = "<IGNORED:" + ClassUtils.getShortName(argument.getClass()) + ">";
        }
        return filtered;
    }

    /** 截断 requestBody（requestHeaders 复用本上限）。 */
    public String truncateRequest(String value) {
        return truncate(value, this.maxRequestLength);
    }

    /** 截断 responseBody。 */
    public String truncateResponse(String value) {
        return truncate(value, this.maxResponseLength);
    }

    /** 截断 errorStack。 */
    public String truncateErrorStack(String value) {
        return truncate(value, this.maxErrorStackLength);
    }

    /**
     * 按最大长度截断。<b>{@code maxLength} 是最终落地字符串的长度上限</b>，
     * 故截断标记 {@code ...[truncated]} 计入这份额度：{@code maxLength > 0} 时
     * 恒有 {@code result.length() <= maxLength}。
     *
     * <p>边界：{@code null} 原样返回；{@code maxLength <= 0} 表示不截断；未超限返回同一实例；
     * 额度装不下标记（{@code maxLength <= 14}）时长度上限优先，只截不标记。</p>
     */
    public static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        int suffixLength = TRUNCATED_SUFFIX.length();
        if (maxLength <= suffixLength) {
            // 额度容不下截断标记：守住长度上限，放弃标记
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - suffixLength) + TRUNCATED_SUFFIX;
    }

    private boolean isIgnored(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (this.ignoredTypes.contains(current.getName())) {
                return true;
            }
        }
        for (Class<?> interfaceClass : ClassUtils.getAllInterfacesForClass(type)) {
            if (this.ignoredTypes.contains(interfaceClass.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalize(Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<String>();
        for (String type : types) {
            if (StringUtils.isNotBlank(type)) {
                normalized.add(type.trim());
            }
        }
        return normalized.isEmpty() ? Collections.<String>emptySet() : normalized;
    }
}
