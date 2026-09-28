package io.github.devoracode.operatelog.boot.servlet.jakarta;

import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import jakarta.servlet.http.HttpServletRequest;

/**
 * jakarta 栈（Boot 3.x）客户端 IP 解析器。
 *
 * <p>一个类只 import 一种 Servlet API（javax/jakarta 混用会在单栈宿主触发
 * {@link NoClassDefFoundError}），两套实现分开，由自动配置按 classpath 装配。</p>
 *
 * <p>取 request 走 {@code RequestContextHolder} + {@code resolveReference(REFERENCE_REQUEST)}：
 * {@code getRequest()} 的返回类型在 Spring 5.3（javax）与 6（jakarta）描述符不同，
 * 跨版本调用会抛 {@code NoSuchMethodError}；{@code RequestAttributes} 签名两代一致。</p>
 */
public class JakartaClientIpResolver implements ClientIpResolver {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";
    /** 代理头长度上限：超长头几乎必为伪造 / 溢出攻击载荷，直接判为不可信。 */
    private static final int MAX_HEADER_LENGTH = 256;
    private final boolean trustProxy;

    public JakartaClientIpResolver(boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    @Override
    public String resolve() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        // 信任代理时取代理透传头，但必须过 IP 格式 + 长度校验，非法即回落到直连地址
        if (this.trustProxy) {
            String forwardedFor = request.getHeader(FORWARDED_FOR);
            if (StringUtils.isNotBlank(forwardedFor)) {
                // XFF 左侧可由客户端预置，只接受当前可信代理追加的链尾
                if (forwardedFor.length() <= MAX_HEADER_LENGTH) {
                    int separator = forwardedFor.lastIndexOf(',');
                    String candidate = StringUtils.trim(forwardedFor.substring(separator + 1));
                    if (isValidIp(candidate)) {
                        return candidate;
                    }
                }
            }
            String realIp = request.getHeader(REAL_IP);
            if (StringUtils.isNotBlank(realIp)) {
                String trimmed = StringUtils.trim(realIp);
                if (trimmed.length() <= MAX_HEADER_LENGTH && isValidIp(trimmed)) {
                    return trimmed;
                }
            }
        }
        // 兜底：容器直连地址（不可被客户端头伪造）
        return request.getRemoteAddr();
    }

    /** 严格校验 IPv4 / IPv6 字面量，不触发 DNS 解析。 */
    private static boolean isValidIp(String value) {
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

    /** 当前线程绑定的 jakarta request；非 Web 场景或非本栈宿主返回 {@code null}。 */
    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object requestObject = attributes.resolveReference(RequestAttributes.REFERENCE_REQUEST);
        if (!(requestObject instanceof HttpServletRequest)) {
            return null;
        }
        return (HttpServletRequest) requestObject;
    }
}
