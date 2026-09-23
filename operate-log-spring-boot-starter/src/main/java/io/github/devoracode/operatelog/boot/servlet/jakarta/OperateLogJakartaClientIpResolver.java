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
public class OperateLogJakartaClientIpResolver implements ClientIpResolver {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";
    /** 代理头长度上限：超长头几乎必为伪造 / 溢出攻击载荷，直接判为不可信。 */
    private static final int MAX_HEADER_LENGTH = 256;
    /** 是否信任反向代理头；代理不可信时开启会被客户端伪造 IP。 */
    private final boolean trustProxy;

    public OperateLogJakartaClientIpResolver(boolean trustProxy) {
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
                // 链首为最接近客户端的一跳，长度超限（伪造 / 溢出载荷）或格式非法则跳过
                if (forwardedFor.length() <= MAX_HEADER_LENGTH) {
                    String candidate = StringUtils.trim(StringUtils.substringBefore(forwardedFor, ","));
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

    /** 校验 IP 字面量：无 {@code ':'} 按 IPv4（四段十进制 0–255），含 {@code ':'} 按 IPv6（十六进制与冒号，至多一处 {@code ::}，可带方括号）。 */
    private static boolean isValidIp(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        if (value.indexOf(':') < 0) {
            String[] parts = value.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) {
                    return false;
                }
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (c < '0' || c > '9') {
                        return false;
                    }
                }
                int v = Integer.parseInt(part);
                if (v < 0 || v > 255) {
                    return false;
                }
            }
            return true;
        }
        String s = value;
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty()) {
            return false;
        }
        int doubleColonCount = 0;
        int idx = 0;
        while (idx < s.length()) {
            int next = s.indexOf("::", idx);
            if (next >= 0) {
                doubleColonCount++;
                idx = next + 2;
            } else {
                break;
            }
        }
        if (doubleColonCount > 1) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':' || c == '.')) {
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
