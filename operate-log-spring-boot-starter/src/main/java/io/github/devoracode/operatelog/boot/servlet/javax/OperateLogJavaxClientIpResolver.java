package io.github.devoracode.operatelog.boot.servlet.javax;

import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import javax.servlet.http.HttpServletRequest;

/**
 * javax 栈（Spring Boot 2.x / Servlet 4-）客户端 IP 解析器。
 *
 * <p><b>双栈兼容设计说明（与 jakarta 栈实现互为镜像，以下四点是本类存在的原因）：</b></p>
 * <ol>
 *   <li><b>为什么一个类只 import 一种 Servlet API？</b>
 *       如果同一个类里同时出现 javax 和 jakarta 两种 {@code instanceof} 判断，
 *       在只有单一 Servlet API 的运行环境（Boot 2 或 Boot 3 宿主）执行到另一栈的
 *       {@code instanceof} 指令时会触发 {@link NoClassDefFoundError}。
 *       因此双栈必须拆成两个类，由自动配置按 classpath 条件装配选择。</li>
 *   <li><b>为什么不调用 {@code ServletRequestAttributes.getRequest()}？</b>
 *       该方法在 Spring 5.3（Boot 2）中返回 {@code javax.servlet.http.HttpServletRequest}，
 *       在 Spring 6（Boot 3）中返回 {@code jakarta.servlet.http.HttpServletRequest}，
 *       两者的方法描述符不同，JVM 按描述符精确解析方法，
 *       跨版本调用会抛 {@code NoSuchMethodError}。</li>
 *   <li><b>为什么用 {@code RequestAttributes.resolveReference(REFERENCE_REQUEST)}？</b>
 *       {@code RequestAttributes} 接口本身不依赖 Servlet 类型，
 *       其 {@code resolveReference(String)} 方法签名在 Spring 5.3 / 6 中完全一致（二进制兼容），
 *       返回值 {@code Object} 在运行时就是宿主真实的 request 对象
 *       （javax 还是 jakarta 由宿主决定），再用 {@code instanceof} 收窄到本栈类型。</li>
 * </ol>
 *
 * @author devoracode
 */
public class OperateLogJavaxClientIpResolver implements ClientIpResolver {
    /**
     * 反向代理透传的客户端 IP 列表请求头。
     */
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    /**
     * 反向代理透传的真实客户端 IP 请求头。
     */
    private static final String REAL_IP = "X-Real-IP";
    /**
     * 是否信任反向代理头（仅可信网络环境下开启，防止客户端伪造 IP）。
     */
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

    /**
     * 获取当前线程绑定的 javax HttpServletRequest。
     *
     * <p>这是本栈获取 request 的唯一合法通道：
     * {@code RequestContextHolder.getRequestAttributes()} 与
     * {@code resolveReference(REFERENCE_REQUEST)} 的方法签名在 Spring 5.3 / 6 中
     * 二进制兼容，不会出现跨版本 {@code NoSuchMethodError}。</p>
     *
     * @return 当前请求，非 Web 场景或非本栈环境返回 {@code null}
     */
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
