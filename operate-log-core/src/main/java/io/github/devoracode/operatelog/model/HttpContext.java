package io.github.devoracode.operatelog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 当前 HTTP 请求上下文（请求侧快照，不含响应状态码；原因见
 * {@link io.github.devoracode.operatelog.resolver.HttpContextResolver}）。
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
