package io.github.devoracode.operatelog.boot.servlet;

import org.apache.commons.lang3.StringUtils;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * javax / jakarta 两栈共用的 Servlet 工具方法。
 *
 * <p>只接收两栈都具备的 JDK 类型（{@link Enumeration} 等），不引用任何 Servlet API，
 * 以免在单栈宿主触发 {@code NoClassDefFoundError}。</p>
 */
public final class ServletRequestUtils {

    private ServletRequestUtils() {
    }

    /**
     * 把请求头快照收集成映射。
     *
     * <p>同一个头出现多个值时以 {@code ", "} 拼接，结果保持 {@code headerNames} 的枚举顺序。
     * {@code headerValues} 对不存在的头返回 {@code null} 时，该头会被记为空串而不是被跳过——
     * 跳过会让人误读成容器没返回这个头。</p>
     *
     * <pre>
     * resolveHeaders(Collections.enumeration(Arrays.asList("Accept", "Host")), request::getHeaders)
     *     = {Accept=text/html, application/json, Host=example.com}
     * </pre>
     *
     * @param headerNames  请求头名枚举，为 {@code null} 时返回空映射
     * @param headerValues 按头名取出多值的函数，允许对未知头返回 {@code null}
     * @return 头名到拼接值的映射，按 {@code headerNames} 的枚举顺序排列
     */
    public static Map<String, String> resolveHeaders(Enumeration<String> headerNames,
                                                     Function<String, Enumeration<String>> headerValues) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headerNames == null) {
            return headers;
        }
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            Enumeration<String> values = headerValues.apply(headerName);
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            while (values != null && values.hasMoreElements()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(values.nextElement());
                first = false;
            }
            headers.put(headerName, sb.toString());
        }
        return headers;
    }

    /**
     * 校验一个字符串是否为合法的 IPv4 / IPv6 字面量。
     *
     * <p>只做字面量语法校验，不做 DNS 反查：能解析的主机名一律判为非法，形似字面量但结构
     * 不合法的值也不会被放过。方括号包裹的 IPv6（{@code [2001:db8::1]}）视为合法。</p>
     *
     * <pre>
     * isValidIp("192.0.2.1")     = true
     * isValidIp("2001:db8::1")   = true
     * isValidIp("[2001:db8::1]") = true
     * isValidIp("1:::2")         = false
     * isValidIp("192.0.2.999")   = false
     * isValidIp("example.com")   = false
     * </pre>
     *
     * @param value 待校验的字面量，可为 {@code null}
     * @return 是否为合法的 IPv4 / IPv6 字面量
     */
    public static boolean isValidIp(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        if (value.indexOf(':') < 0) {
            return isValidIpv4(value);
        }
        String literal = value;
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        if (literal.isEmpty() || literal.indexOf('%') >= 0) {
            return false;
        }
        int compression = literal.indexOf("::");
        if (compression >= 0 && compression != literal.lastIndexOf("::")) {
            return false;
        }
        if (compression < 0 && (literal.startsWith(":") || literal.endsWith(":"))) {
            return false;
        }
        String left = compression < 0 ? literal : literal.substring(0, compression);
        String right = compression < 0 ? "" : literal.substring(compression + 2);
        if (compression >= 0 && (left.endsWith(":") || right.startsWith(":"))) {
            return false;
        }
        int leftUnits = countIpv6Units(left, compression < 0);
        int rightUnits = countIpv6Units(right, compression >= 0);
        if (leftUnits < 0 || rightUnits < 0) {
            return false;
        }
        int units = leftUnits + rightUnits;
        return compression < 0 ? units == 8 : units <= 7;
    }

    /**
     * 统计 {@code ::} 某一侧包含的 16 位组数量。
     *
     * <p>该侧整体省略（{@code ::} 的一端为空串）时返回 0。</p>
     *
     * <pre>
     * countIpv6Units("1:2:3", true)   = 3
     * countIpv6Units("::ffff:192.0.2.1", true) = 3
     * countIpv6Units("192.0.2.1", false)       = -1
     * </pre>
     *
     * @param part          {@code ::} 的一侧，不含分隔符
     * @param allowIpv4Tail 该侧是否允许以点分四段收尾，只有最后一侧允许
     * @return 16 位组数量，结构非法时为 {@code -1}
     */
    private static int countIpv6Units(String part, boolean allowIpv4Tail) {
        if (part.isEmpty()) {
            return 0;
        }
        int units = 0;
        int start = 0;
        while (start <= part.length()) {
            int separator = part.indexOf(':', start);
            int end = separator < 0 ? part.length() : separator;
            String group = part.substring(start, end);
            if (group.isEmpty()) {
                return -1;
            }
            if (group.indexOf('.') >= 0) {
                if (!allowIpv4Tail || separator >= 0 || !isValidIpv4(group)) {
                    return -1;
                }
                units += 2;
            } else {
                if (!isIpv6Group(group)) {
                    return -1;
                }
                units++;
            }
            if (separator < 0) {
                break;
            }
            start = separator + 1;
        }
        return units;
    }

    /**
     * 校验单个 16 位组。
     *
     * <p>只接受 1 到 4 个十六进制字符，大小写皆可；{@code 0x} 前缀与分隔符都判为非法。</p>
     *
     * <pre>
     * isIpv6Group("db8")  = true
     * isIpv6Group("DB8")  = true
     * isIpv6Group("0x1")  = false
     * isIpv6Group("12345") = false
     * </pre>
     *
     * @param value 待校验的组，不含 {@code :}
     * @return 是否为合法的 16 位组
     */
    private static boolean isIpv6Group(String value) {
        if (value.isEmpty() || value.length() > 4) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验点分十进制 IPv4 字面量。
     *
     * <p>要求恰好四段，每段为 1 到 3 位十进制数字且取值不超过 255；空段、非数字字符、
     * 超过三位的段一律非法。</p>
     *
     * <pre>
     * isValidIpv4("192.0.2.1")   = true
     * isValidIpv4("192.0.2.256") = false
     * isValidIpv4("192.0.2")     = false
     * isValidIpv4("192.0.2.")    = false
     * </pre>
     *
     * @param value 待校验的字面量
     * @return 是否为合法的 IPv4 字面量
     */
    private static boolean isValidIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            int number = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                number = number * 10 + c - '0';
            }
            if (number > 255) {
                return false;
            }
        }
        return true;
    }
}
