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
 * javax 栈（Spring Boot 2.x / Servlet 4-）HTTP 上下文解析器。
 *
 * <p><b>双栈兼容设计说明（与 jakarta 栈实现互为镜像）：</b></p>
 * <ol>
 *   <li><b>为什么一个类只 import 一种 Servlet API？</b>
 *       同类中两种栈的 {@code instanceof} 在单栈环境会触发
 *       {@link NoClassDefFoundError}，因此双栈拆成两个类，由自动配置按 classpath 条件装配。</li>
 *   <li><b>为什么不调用 {@code ServletRequestAttributes.getRequest()}？</b>
 *       该方法在 Spring 5.3（返回 javax 类型）与 Spring 6（返回 jakarta 类型）中
 *       方法描述符不同，编译期绑定后跨版本运行会抛 {@code NoSuchMethodError}。</li>
 *   <li><b>为什么用 {@code RequestAttributes.resolveReference(REFERENCE_REQUEST)}？</b>
 *       接口方法签名在两代 Spring 中二进制兼容，返回 {@code Object} 即宿主真实 request，
 *       再用 {@code instanceof} 收窄到本栈类型。</li>
 *   <li><b>为什么只读 request、不读 response？</b>
 *       状态码已从本组件的采集范围移除（切面 {@code finally} 早于返回值与异常处理阶段，
 *       取到的值容易被误读成客户端实际收到的状态码，取舍见
 *       {@link HttpContextResolver}）；因此本类无需反射
 *       {@code ServletRequestAttributes#getResponse()}，也不依赖任何 response 侧 API。</li>
 * </ol>
 *
 * @author devoracode
 */
public class OperateLogJavaxHttpContextResolver implements HttpContextResolver {
    /**
     * 客户端 IP 解析器（由自动配置注入，可能是本栈实现）。
     */
    private final ClientIpResolver clientIpResolver;
    /**
     * 是否采集完整请求头。
     */
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

    /**
     * 采集请求头（以下 API 在 Servlet 4/5/6 中签名一致，二进制兼容）。
     */
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
