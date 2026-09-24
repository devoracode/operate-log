package io.github.devoracode.operatelog.payload;

import lombok.Getter;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 载荷防护策略：大字段截断 + 序列化忽略类型过滤。
 *
 * <p>忽略类型按<b>全限定类名</b>匹配父类链与全部接口（含传递接口），
 * 配置接口名即可覆盖所有实现类。直接构造时按传入集合精确生效；
 * 需要内置安全基线时使用 {@link #createWithSafetyDefaults}。</p>
 */
public class PayloadPolicy {
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    private static final List<String> SAFETY_DEFAULT_IGNORED_TYPES = Collections.unmodifiableList(Arrays.asList(
            "javax.servlet.ServletRequest",
            "javax.servlet.ServletResponse",
            "javax.servlet.http.HttpSession",
            "jakarta.servlet.ServletRequest",
            "jakarta.servlet.ServletResponse",
            "jakarta.servlet.http.HttpSession",
            "org.springframework.web.multipart.MultipartFile",
            "org.springframework.validation.BindingResult",
            "org.springframework.web.servlet.ModelAndView",
            "java.security.Principal",
            "org.springframework.security.core.Authentication",
            "org.springframework.security.core.context.SecurityContext",
            "java.io.InputStream",
            "java.io.OutputStream",
            "java.io.Reader",
            "java.io.Writer",
            "[B"));
    private static final int IGNORED_TYPE_CACHE_SIZE = 512;
    private final int maxRequestLength;
    private final int maxResponseLength;
    @Getter
    private final int maxErrorLength;
    @Getter
    private final int maxExtraLength;
    private final Set<String> ignoredTypes;
    private final Map<Class<?>, Optional<String>> ignoredTypeCache = new ConcurrentHashMap<>();

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
     * 创建包含内置序列化防护类型与调用方追加类型的载荷策略。内置防护类型始终生效，
     * 追加项为空或含重复值时仍保留完整安全基线。
     *
     * @param maxRequestLength request 载荷最大字符数
     * @param maxResponseLength response 载荷最大字符数
     * @param maxErrorLength 错误载荷最大字符数
     * @param maxExtraLength extra 载荷最大字符数
     * @param additionalIgnoredTypes 调用方追加的忽略类型，可为 {@code null}
     * @return 合并内置与追加类型后的策略
     */
    public static PayloadPolicy createWithSafetyDefaults(int maxRequestLength,
                                                         int maxResponseLength,
                                                         int maxErrorLength,
                                                         int maxExtraLength,
                                                         Collection<String> additionalIgnoredTypes) {
        Set<String> ignoredTypes = new LinkedHashSet<>(SAFETY_DEFAULT_IGNORED_TYPES);
        if (additionalIgnoredTypes != null) {
            ignoredTypes.addAll(additionalIgnoredTypes);
        }
        return new PayloadPolicy(maxRequestLength, maxResponseLength, maxErrorLength, maxExtraLength, ignoredTypes);
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

    public String truncateRequest(String value) {
        return truncate(value, this.maxRequestLength);
    }

    public String truncateResponse(String value) {
        return truncate(value, this.maxResponseLength);
    }

    public String truncateErrorStack(String value) {
        return truncate(value, this.maxErrorLength);
    }

    public String truncateErrorMessage(String value) {
        return truncate(value, this.maxErrorLength);
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

    // 截断点若落在代理对中间（前一个 code unit 是 high surrogate）回退一位，
    // 否则孤立 surrogate 经 UTF-8 编码会成 U+FFFD 乱码甚至抛异常
    private static int safeCutIndex(String value, int cutIndex) {
        if (cutIndex <= 0) {
            return 0;
        }
        if (cutIndex < value.length() && Character.isHighSurrogate(value.charAt(cutIndex - 1))) {
            return cutIndex - 1;
        }
        return cutIndex;
    }

    private String findIgnoredType(Class<?> type) {
        Optional<String> cached = this.ignoredTypeCache.get(type);
        if (cached != null) {
            return cached.orElse(null);
        }
        String matched = resolveIgnoredType(type);
        if (this.ignoredTypeCache.size() < IGNORED_TYPE_CACHE_SIZE) {
            this.ignoredTypeCache.putIfAbsent(type, Optional.ofNullable(matched));
        }
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
        Set<String> normalized = new HashSet<>();
        for (String type : types) {
            if (StringUtils.isNotBlank(type)) {
                normalized.add(StringUtils.trim(type));
            }
        }
        return normalized.isEmpty() ? Collections.<String>emptySet() : normalized;
    }
}
