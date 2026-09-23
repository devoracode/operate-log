package io.github.devoracode.operatelog.handler;

import io.github.devoracode.operatelog.model.OperateLogRecord;

/** 操作日志处理器：日志落地出口（数据库 / MQ / ES / 审计系统在此实现）。 */
public interface OperateLogHandler {

    /**
     * 处理一条组装完成的操作日志记录。
     *
     * @param record 经切面组装、载荷防护后的完整日志记录，非 null
     */
    void handle(OperateLogRecord record);
}
