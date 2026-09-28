package io.github.devoracode.operatelog.boot.servlet;

import org.apache.commons.lang3.StringUtils;

/**
 * Servlet resolver 共用的 IP 字面量校验工具。
 *
 * <p>只做本地语法校验，不触发 DNS 反查。</p>
 */
public final class ServletIpUtils {

    private ServletIpUtils() {
    }

    /**
     * 严格校验 IPv4 / IPv6 字面量。
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
