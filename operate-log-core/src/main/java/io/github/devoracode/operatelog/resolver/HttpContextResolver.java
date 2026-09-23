package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.HttpContext;

/**
 * HTTP 上下文解析器：由切面在业务方法执行前调用一次，采集请求侧快照
 * （method / url / uri / query / headers / clientIp / userAgent）。
 *
 * <p>不提供状态码：切面 {@code finally} 早于 Spring MVC 的返回值处理与异常解析，
 * 此刻读到的状态码是过程快照，比不记录更容易被误读。
 * 成败判据请用 {@code OperateLogRecord#isSuccess()} 与 {@code errorType}。</p>
 */
public interface HttpContextResolver {

    /**
     * 当前 HTTP 请求上下文；非 HTTP 场景返回 {@code null}。
     *
     * @return 请求侧快照 {@link HttpContext}（method / url / uri / query / headers / ip / userAgent），
     *         线程未绑定请求时为 {@code null}
     */
    HttpContext resolve();
}
