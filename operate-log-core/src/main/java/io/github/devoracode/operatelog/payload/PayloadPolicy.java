package io.github.devoracode.operatelog.payload;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.ClassUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 载荷防护策略：大字段截断 + 序列化忽略类型过滤。
 *
 * <p>忽略类型按<b>全限定类名</b>匹配父类链与全部接口（含传递接口），
 * 配置接口名即可覆盖所有实现类。</p>
 */
public class PayloadPolicy {
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    /** 参数类型解析缓存上限：单应用类型数天然有界，定容 LRU 防热部署旧 Class 滞留。 */
    private static final int IGNORED_TYPE_CACHE_SIZE = 512;
    private final int maxRequestLength;
    private final int maxResponseLength;
    /** errorStack 与 errorMessage 共用同一上限。 */
    private final int maxErrorLength;
    private final int maxExtraLength;
    private final Set<String> ignoredTypes;
    /**
     * 参数类型 → 命中忽略项的缓存（{@link Optional#empty()} 表示无命中）：
     * 未命中也要扫描父类链与接口，缓存后降为一次 Map 查询。
     */
    private final Map<Class<?>, Optional<String>> ignoredTypeCache = Collections.synchronizedMap(new LinkedHashMap<Class<?>, Optional<String>>(
            16,
            0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Class<?>, Optional<String>> eldest) {
            return size() > IGNORED_TYPE_CACHE_SIZE;
        }
    });

    public PayloadPolicy(int maxRequestLength,
                         int maxResponseLength,
                         int maxErrorLength,
                         int maxExtraLength,
                         Collection<String> ignoredTypes) {
        this.maxRequestLength = maxRequestLength;
        this.maxResponseLength = maxResponseLength;
        this.maxErrorLength = maxErrorLength;
        this.maxExtraLength = maxExtraLength;
        this.ignoredTypes = normalize(ignoredTypes);
    }

    /**
     * 命中「忽略类型」的参数替换为 {@code <IGNORED:命中类型短名>}（短名取配置命中的接口/父类，非运行时实现类）。
     * 无命中返回原数组；有命中先克隆再替换，不改调用方入参。
     *
     * @param arguments 被拦截方法的实参数组
     * @return 过滤后的实参数组
     */
    public Object[] filterArguments(Object[] arguments) {
        if (ArrayUtils.isEmpty(arguments) || this.ignoredTypes.isEmpty()) {
            return arguments;
        }
        Object[] filtered = arguments;
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            if (argument == null) {
                continue;
            }
            String ignoredType = findIgnoredType(argument.getClass());
            if (ignoredType == null) {
                continue;
            }
            if (filtered == arguments) {
                filtered = arguments.clone();
            }
            filtered[i] = "<IGNORED:" + ClassUtils.getShortName(ignoredType) + ">";
        }
        return filtered;
    }

    /**
     * 截断 requestBody（requestHeaders / requestQuery / userAgent 复用本上限）。
     *
     * @param value 序列化后的请求体文本
     * @return 截断后的文本，未超长时原样返回
     */
    public String truncateRequest(String value) {
        return truncate(value, this.maxRequestLength);
    }

    /**
     * 截断 responseBody。
     *
     * @param value 序列化后的返回值文本
     * @return 截断后的文本，未超长时原样返回
     */
    public String truncateResponse(String value) {
        return truncate(value, this.maxResponseLength);
    }

    /**
     * 截断 errorStack。
     *
     * @param value 完整异常堆栈文本
     * @return 截断后的文本，未超长时原样返回
     */
    public String truncateErrorStack(String value) {
        return truncate(value, this.maxErrorLength);
    }

    /**
     * 截断 errorMessage，与 errorStack 共用上限。
     *
     * @param value 异常 message 文本
     * @return 截断后的文本，未超长时原样返回
     */
    public String truncateErrorMessage(String value) {
        return truncate(value, this.maxErrorLength);
    }

    /**
     * errorStack 上限；供堆栈打印侧按上限限长写入，避免先撑起完整字符串。
     *
     * @return errorStack 与 errorMessage 共用的长度上限，小于等于 0 表示不截断
     */
    public int getMaxErrorLength() {
        return this.maxErrorLength;
    }

    public int getMaxExtraLength() {
        return this.maxExtraLength;
    }

    /**
     * 按最大长度截断。{@code maxLength} 是最终落地字符串的长度上限，
     * 截断标记 {@code ...[truncated]} 计入该额度：{@code maxLength > 0} 时
     * 恒有 {@code result.length() <= maxLength}。
     *
     * <p>边界：{@code null} 原样返回；{@code maxLength <= 0} 不截断；
     * 额度装不下标记时只截不标记。</p>
     *
     * @param value     待截断文本
     * @param maxLength 含截断标记在内的最终长度上限
     * @return 截断结果；无需截断时原样返回 {@code value}
     */
    public static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        int suffixLength = TRUNCATED_SUFFIX.length();
        if (maxLength < suffixLength) {
            // 额度装不下截断标记（严格小于）：守住长度上限，放弃标记
            return value.substring(0, safeCutIndex(value, maxLength));
        }
        return value.substring(0, safeCutIndex(value, maxLength - suffixLength)) + TRUNCATED_SUFFIX;
    }

    /**
     * 截断点落在代理对中间时回退一位：按 UTF-16 code unit 截断可能切裂 high/low surrogate，
     * 产生孤立 surrogate，下游按 UTF-8 编码时替换为 U+FFFD（乱码）甚至抛异常。
     */
    private static int safeCutIndex(String value, int cutIndex) {
        if (cutIndex <= 0) {
            return 0;
        }
        if (cutIndex < value.length() && Character.isHighSurrogate(value.charAt(cutIndex - 1))) {
            return cutIndex - 1;
        }
        return cutIndex;
    }

    /**
     * 查找参数类型在忽略列表中的命中项（类自身 → 父类链 → 全部接口含父接口），
     * 返回命中的配置类型名，未命中返回 {@code null}。结果按类型缓存（含未命中哨兵），
     * 同一参数类型的扫描只做一次。
     */
    private String findIgnoredType(Class<?> type) {
        Optional<String> cached = this.ignoredTypeCache.get(type);
        if (cached != null) {
            return cached.orElse(null);
        }
        String matched = resolveIgnoredType(type);
        this.ignoredTypeCache.putIfAbsent(type, Optional.ofNullable(matched));
        return matched;
    }

    private String resolveIgnoredType(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (this.ignoredTypes.contains(current.getName())) {
                return current.getName();
            }
            String matched = findIgnoredInterface(current);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    /**
     * 递归当前类声明的接口及其父接口，返回命中的接口全限定名，未命中返回 {@code null}。
     */
    private String findIgnoredInterface(Class<?> type) {
        Class<?>[] interfaces = type.getInterfaces();
        for (Class<?> interfaceClass : interfaces) {
            if (this.ignoredTypes.contains(interfaceClass.getName())) {
                return interfaceClass.getName();
            }
            String matched = findIgnoredInterface(interfaceClass);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    private static Set<String> normalize(Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<String>();
        for (String type : types) {
            if (StringUtils.isNotBlank(type)) {
                normalized.add(StringUtils.trim(type));
            }
        }
        return normalized.isEmpty() ? Collections.<String>emptySet() : normalized;
    }
}
