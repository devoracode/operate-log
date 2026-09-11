package io.github.devoracode.operatelog.boot.servlet.javax;

import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * javax 栈（Spring Boot 2.x / Servlet 4-）HTTP 上下文解析器：从 {@code RequestContextHolder} 取宿主 request，
 * 采集请求侧快照。通道选择与「一类一栈」的原因见同包 {@code OperateLogJavaxClientIpResolver}
 * 的类注释——同一个 request 对象，两处入口。
 *
 * <p>只读 request：状态码不在采集范围内（取舍见 {@link HttpContextResolver}），
 * 因此本类不依赖任何 response 侧 API。</p>
 */
public class OperateLogJavaxHttpContextResolver implements HttpContextResolver {
    /** 本栈 IP 解析器，可与本类共享同一条 request 通道。 */
    private final ClientIpResolver clientIpResolver;
    private final boolean captureHeaders;

    public OperateLogJavaxHttpContextResolver(ClientIpResolver clientIpResolver, boolean captureHeaders) {
        this.clientIpResolver = clientIpResolver;
        this.captureHeaders = captureHeaders;
    }

    @Override
    public HttpContext resolve() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        // 通过二进制兼容通道获取宿主真实 request，再收窄到 javax 栈类型
        Object requestObject = attributes.resolveReference(RequestAttributes.REFERENCE_REQUEST);
        if (!(requestObject instanceof HttpServletRequest)) {
            return null;
        }
        HttpServletRequest request = (HttpServletRequest) requestObject;
        Map<String, String> headers = this.captureHeaders ? resolveHeaders(request) : null;
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

    /** 采集请求头：所用 API 在 Servlet 4 / 5 / 6 中签名一致。 */
    private Map<String, String> resolveHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return headers;
        }
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        return headers;
    }
}
