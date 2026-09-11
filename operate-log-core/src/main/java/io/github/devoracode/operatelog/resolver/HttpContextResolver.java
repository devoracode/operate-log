package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.HttpContext;

/**
 * HTTP 上下文解析器：由切面在业务方法执行前调用一次，采集请求侧快照
 * （method / url / uri / query / headers / clientIp / userAgent）。
 *
 * <p>接口只提供请求侧、没有取状态码的方法：环绕通知在 Controller 方法体外层，其
 * {@code finally} 早于 Spring MVC 的返回值处理与异常解析，此刻读到的状态既不含业务即将
 * 写入的值、也不反映 {@code ResponseEntity} / {@code @ResponseStatus} 的改写，记录它比不记录
 * 更容易被误读。成败判据请用 {@code OperateLogRecord#isSuccess()} 与 {@code errorType}。</p>
 */
public interface HttpContextResolver {

    /** 当前 HTTP 请求上下文；非 HTTP 场景返回 {@code null}。 */
    HttpContext resolve();
}
