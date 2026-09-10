package io.github.devoracode.operatelog.model;

/**
 * 操作日志记录时机。
 *
 * <p>比 {@code condition} 的 SpEL 布尔表达式更直观的高频过滤（零求值开销），
 * 二者同时配置时取交集（AND）。</p>
 *
 * @author devoracode
 */
public enum RecordOn {

    /**
     * 无论成功失败都记录（默认）。
     */
    ALWAYS,

    /**
     * 仅方法正常返回时记录（如：登录成功才记，失败不刷日志）。
     */
    SUCCESS,

    /**
     * 仅方法抛出异常时记录（如：退款排障场景只关心失败）。
     */
    ERROR
}
