package io.github.devoracode.operatelog.boot.servlet.jakarta;

import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * jakarta 栈（Spring Boot 3.x / Servlet 5+）HTTP 上下文解析器。
 *
 * <p><b>双栈兼容设计说明（与 javax 栈实现互为镜像）：</b></p>
 * <ol>
 *   <li><b>为什么一个类只 import 一种 Servlet API？</b>
 *       同类中两种栈的 {@code instanceof} 在单栈环境会触发
 *       {@link NoClassDefFoundError}，因此双栈拆成两个类，由自动配置按 classpath 条件装配。</li>
 *   <li><b>为什么不调用 {@code ServletRequestAttributes.getRequest()/getResponse()}？</b>
 *       两个方法在 Spring 5.3（返回 javax 类型）与 Spring 6（返回 jakarta 类型）中
 *       方法描述符不同，编译期绑定后跨版本运行会抛 {@code NoSuchMethodError}。</li>
 *   <li><b>为什么用 {@code RequestAttributes.resolveReference(REFERENCE_REQUEST)}？</b>
 *       接口方法签名在两代 Spring 中二进制兼容，返回 {@code Object} 即宿主真实 request，
 *       再用 {@code instanceof} 收窄到本栈类型。</li>
 *   <li><b>为什么 response 用反射 {@code getMethod("getResponse")}？</b>
 *       {@code RequestAttributes} 接口没有 response 引用，只能从
 *       {@code ServletRequestAttributes} 子类获取；而其 {@code getResponse()} 方法
 *       在两代 Spring 中方法名相同仅返回类型不同——反射按方法名匹配，
 *       不受编译期描述符限制，跨版本安全；反射失败时 status 降级为 {@code null}，不阻断采集。</li>
 * </ol>
 *
 * @author devoracode
 */
public class OperateLogJakartaHttpContextResolver implements HttpContextResolver {
    /**
     * 客户端 IP 解析器（由自动配置注入，可能是本栈实现）。
     */
    private final ClientIpResolver clientIpResolver;
    /**
     * 是否采集完整请求头。
     */
    private final boolean captureHeaders;

    public OperateLogJakartaHttpContextResolver(ClientIpResolver clientIpResolver,
                                                boolean captureHeaders) {
        this.clientIpResolver = clientIpResolver;
        this.captureHeaders = captureHeaders;
    }

    @Override
    public HttpContext resolve() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        // 通过二进制兼容通道获取宿主真实 request，再收窄到 jakarta 栈类型
        Object requestObject = attributes.resolveReference(RequestAttributes.REFERENCE_REQUEST);
        if (!(requestObject instanceof HttpServletRequest)) {
            return null;
        }
        HttpServletRequest request = (HttpServletRequest) requestObject;
        Map<String, String> headers = this.captureHeaders ? resolveHeaders(request) : null;
        Integer status = resolveStatus(attributes);
        return HttpContext.builder()
                .method(request.getMethod())
                .url(request.getRequestURL().toString())
                .uri(request.getRequestURI())
                .query(request.getQueryString())
                .ip(this.clientIpResolver.resolve())
                .userAgent(request.getHeader("User-Agent"))
                .status(status)
                .headers(headers)
                .build();
    }

    /**
     * 反射获取当前响应的 HTTP 状态码。
     *
     * <p>运行时 {@code attributes} 的实际类型是宿主 Spring 版本的
     * {@code ServletRequestAttributes}，其 {@code getResponse()} 方法名在
     * Spring 5.3 / 6 中相同，反射调用不受返回类型描述符差异影响；
     * 返回对象再收窄到本栈的 {@link HttpServletResponse}。
     * 任何反射失败（无方法 / 类型不匹配 / 调用异常）都降级为 {@code null}，
     * 不影响其余字段采集。</p>
     *
     * @param attributes 当前请求属性
     * @return HTTP 状态码，获取失败返回 {@code null}
     */
    private Integer resolveStatus(RequestAttributes attributes) {
        try {
            Method getResponse = attributes.getClass().getMethod("getResponse");
            Object responseObject = getResponse.invoke(attributes);
            if (responseObject instanceof HttpServletResponse) {
                return ((HttpServletResponse) responseObject).getStatus();
            }
        } catch (ReflectiveOperationException ex) {
            // 反射失败时优雅降级：状态码记为 null，其余字段照常采集
        }
        return null;
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
