package io.github.devoracode.operatelog.handler;

import io.github.devoracode.operatelog.model.OperateLogRecord;

/**
 * 操作日志处理器。
 *
 * @author devoracode
 */
public interface OperateLogHandler {

    /**
     * 处理操作日志。
     *
     * @param record 操作日志
     */
    void handle(OperateLogRecord record);
}
