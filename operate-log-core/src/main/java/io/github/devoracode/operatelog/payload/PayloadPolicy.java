package io.github.devoracode.operatelog.payload;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.ClassUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 载荷防护策略：大字段截断 + 序列化忽略类型过滤。
 *
 * <p>设计目标：任何单条操作日志的体积都有上界，且流 / 文件 / Servlet 容器对象等
 * 不适合序列化的参数自动替换为占位符，避免序列化产出巨量垃圾或直接失败。</p>
 *
 * <ul>
 *   <li>长度上限 {@code <= 0} 表示不截断；截断后以 {@code ...[truncated]} 标记。</li>
 *   <li>忽略类型按 <b>全限定类名</b> 匹配，命中父类或任意接口（含传递接口）即算匹配，
 *       因此配置接口名（如 {@code org.springframework.web.multipart.MultipartFile}）
 *       即可覆盖全部实现类。</li>
 * </ul>
 *
 * @author devoracode
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
     * 将参数数组中命中「忽略类型」的元素替换为占位符字符串。
     *
     * <p>无命中时返回原数组（零拷贝）；有命中时克隆数组后替换，绝不修改调用方入参。</p>
     *
     * @param arguments 方法参数数组
     * @return 过滤后的参数数组
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

    /**
     * 截断 requestBody / requestHeaders（headers 复用请求侧上限）。
     */
    public String truncateRequest(String value) {
        return truncate(value, this.maxRequestLength);
    }

    /**
     * 截断 responseBody。
     */
    public String truncateResponse(String value) {
        return truncate(value, this.maxResponseLength);
    }

    /**
     * 截断 errorStack。
     */
    public String truncateErrorStack(String value) {
        return truncate(value, this.maxErrorStackLength);
    }

    /**
     * 按最大长度截断，超长时保留前段并追加截断标记。
     *
     * @param value     原值，可为 {@code null}
     * @param maxLength 最大长度，{@code <= 0} 表示不截断
     * @return 截断结果
     */
    public static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + TRUNCATED_SUFFIX;
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
