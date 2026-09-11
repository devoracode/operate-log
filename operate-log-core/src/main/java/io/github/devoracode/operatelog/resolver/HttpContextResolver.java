package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.HttpContext;

/**
 * HTTP 上下文解析器。
 *
 * <p>由切面在业务方法执行前调用一次，采集请求侧信息
 * （method / url / uri / query / headers / clientIp / userAgent）。</p>
 *
 * <p><b>不提供响应状态码</b>：环绕通知位于 Controller 方法体外层，其 {@code finally} 早于
 * Spring MVC 的返回值处理与异常解析，{@code response.getStatus()} 在此既可能读不到业务
 * 即将写入的值、也无法反映 {@code ResponseEntity} / {@code @ResponseStatus} /
 * {@code @ControllerAdvice} 的最终改写——记录一个容易被误读成「客户端实际收到的状态码」的
 * 字段比不记录更糟，因此本组件不采集 HTTP 状态码。审计判据请用
 * {@code OperateLogRecord#isSuccess()} 与 {@code errorType} / {@code errorMessage}。</p>
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
