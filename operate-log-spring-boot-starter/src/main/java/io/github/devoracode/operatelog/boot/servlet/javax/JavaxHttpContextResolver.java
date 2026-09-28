package io.github.devoracode.operatelog.boot.servlet.javax;

import io.github.devoracode.operatelog.boot.servlet.ServletRequestUtils;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * javax 栈（Boot 2.x）HTTP 上下文解析器：从 {@code RequestContextHolder} 取宿主 request，
 * 采集请求侧快照。
 *
 * <p>只读 request，不采集 response 状态码。</p>
 */
public class JavaxHttpContextResolver implements HttpContextResolver {
    /** 本栈 IP 解析器，可与本类共享同一条 request 通道。 */
    private final ClientIpResolver clientIpResolver;
    private final boolean captureHeaders;

    public JavaxHttpContextResolver(ClientIpResolver clientIpResolver, boolean captureHeaders) {
        this.clientIpResolver = clientIpResolver;
        this.captureHeaders = captureHeaders;
    }

    @Override
    public HttpContext resolve() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        // 二进制兼容通道获取宿主真实 request，再收窄到 javax 栈类型
        Object requestObject = attributes.resolveReference(RequestAttributes.REFERENCE_REQUEST);
        if (!(requestObject instanceof HttpServletRequest)) {
            return null;
        }
        HttpServletRequest request = (HttpServletRequest) requestObject;
        Map<String, String> headers = this.captureHeaders
                ? ServletRequestUtils.resolveHeaders(request.getHeaderNames(), request::getHeaders)
                : null;
        return HttpContext.builder()
                .method(request.getMethod())
                .url(request.getRequestURL().toString())
                .uri(request.getRequestURI())
                .query(request.getQueryString())
                .ip(this.clientIpResolver.resolve())
                .userAgent(request.getHeader("User-Agent"))
                .headers(headers)
                .build();
    }
}
