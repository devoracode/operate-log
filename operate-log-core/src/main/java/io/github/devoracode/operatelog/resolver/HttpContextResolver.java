package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.HttpContext;

/**
 * HTTP 上下文解析器。
 *
 * @author devoracode
 */
public interface HttpContextResolver {

    /**
     * 获取当前 HTTP 请求上下文。
     *
     * @return HTTP 上下文，非 HTTP 场景返回 {@code null}
     */
    HttpContext resolve();
}
