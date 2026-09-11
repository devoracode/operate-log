package io.github.devoracode.operatelog.boot.servlet.jakarta;

import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import jakarta.servlet.http.HttpServletRequest;

/**
 * jakarta 栈（Spring Boot 3.x / Servlet 5+）客户端 IP 解析器。
 *
 * <p>一个类只 import 一种 Servlet API：同一个类里同时出现 javax 与 jakarta 的
 * {@code instanceof}，在单栈宿主执行到另一侧指令时会抛 {@link NoClassDefFoundError}，
 * 因此两套实现分开，由自动配置按 classpath 条件装配。</p>
 *
 * <p>取 request 只走 {@code RequestContextHolder} + {@code resolveReference(REFERENCE_REQUEST)}：
 * {@code ServletRequestAttributes#getRequest()} 的返回类型在 Spring 5.3（javax）与
 * Spring 6（jakarta）中描述符不同，跨版本调用会抛 {@code NoSuchMethodError}；而
 * {@code RequestAttributes} 的签名两代一致，返回的 Object 就是宿主真实 request，
 * 再收窄到本栈类型使用。</p>
 */
public class OperateLogJakartaClientIpResolver implements ClientIpResolver {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";
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
        // 信任代理时优先取代理透传头：X-Forwarded-For 可能是逗号分隔的 IP 链，取第一个（最原始客户端）
        if (this.trustProxy) {
            String forwardedFor = request.getHeader(FORWARDED_FOR);
            if (StringUtils.isNotBlank(forwardedFor)) {
                return StringUtils.substringBefore(forwardedFor, ",").trim();
            }
            String realIp = request.getHeader(REAL_IP);
            if (StringUtils.isNotBlank(realIp)) {
                return realIp.trim();
            }
        }
        // 兜底：Servlet 容器给出的直连地址（该方法签名在 Servlet 4/5/6 中一致，二进制兼容）
        return request.getRemoteAddr();
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
