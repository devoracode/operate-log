package io.github.devoracode.operatelog.model;

/**
 * 操作日志记录时机。比 {@code condition} 更直观的高频过滤（零求值开销），
 * 二者同时配置时取交集（AND）。
 */
public enum RecordOn {
    /** 无论成功失败都记录（默认）。 */
    ALWAYS,
    /** 仅正常返回时记录：如登录成功才记，失败不刷日志。 */
    SUCCESS,
    /** 仅抛异常时记录：如排障只关心失败。 */
    ERROR
}
