package io.github.devoracode.operatelog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 当前 HTTP 请求上下文（请求侧快照）。
 *
 * <p>由 {@link io.github.devoracode.operatelog.resolver.HttpContextResolver} 在业务方法执行前采集。
 * 本模型<b>不含响应状态码</b>：切面形态下取不到客户端实际收到的最终状态，
 * 取舍说明见 {@link io.github.devoracode.operatelog.resolver.HttpContextResolver}。</p>
 *
 * @author devoracode
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HttpContext {
    private String method;
    private String url;
    private String uri;
    private String query;
    private String ip;
    private String userAgent;
    private Map<String, String> headers;
}
