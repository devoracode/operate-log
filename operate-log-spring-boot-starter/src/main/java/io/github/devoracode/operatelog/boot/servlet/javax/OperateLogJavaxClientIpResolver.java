package io.github.devoracode.operatelog.boot.servlet.javax;

import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import javax.servlet.http.HttpServletRequest;

/**
 * javax 栈（Boot 2.x）客户端 IP 解析器。
 *
 * <p>一个类只 import 一种 Servlet API（javax/jakarta 混用会在单栈宿主触发
 * {@link NoClassDefFoundError}），两套实现分开，由自动配置按 classpath 装配。</p>
 *
 * <p>取 request 走 {@code RequestContextHolder} + {@code resolveReference(REFERENCE_REQUEST)}：
 * {@code getRequest()} 的返回类型在 Spring 5.3（javax）与 6（jakarta）描述符不同，
 * 跨版本调用会抛 {@code NoSuchMethodError}；{@code RequestAttributes} 签名两代一致。</p>
 */
public class OperateLogJavaxClientIpResolver implements ClientIpResolver {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";
    /** 是否信任反向代理头；代理不可信时开启会被客户端伪造 IP。 */
    private final boolean trustProxy;

    public OperateLogJavaxClientIpResolver(boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    @Override
    public String resolve() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        // 信任代理时取代理透传头；否则取容器直连地址
        if (this.trustProxy) {
            String forwardedFor = request.getHeader(FORWARDED_FOR);
            if (StringUtils.isNotBlank(forwardedFor)) {
                // 注意：链首为客户端自报值，伪造风险由可信网络边界承担；
                // 严格审计场景应自右向左跳过 N 个可信代理节点
                return StringUtils.substringBefore(forwardedFor, ",").trim();
            }
            String realIp = request.getHeader(REAL_IP);
            if (StringUtils.isNotBlank(realIp)) {
                return realIp.trim();
            }
        }
        // 兜底：容器直连地址
        return request.getRemoteAddr();
    }

    /** 当前线程绑定的 javax request；非 Web 场景或非本栈宿主返回 {@code null}。 */
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
