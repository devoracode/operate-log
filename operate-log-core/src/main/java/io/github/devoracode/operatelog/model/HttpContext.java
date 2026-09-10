package io.github.devoracode.operatelog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 当前 HTTP 请求上下文。
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

    private Integer status;

    private Map<String, String> headers;
}
