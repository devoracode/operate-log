package io.github.devoracode.operatelog.handler;

import io.github.devoracode.operatelog.model.OperateLogRecord;

/** 操作日志处理器：日志落地出口（数据库 / MQ / ES / 审计系统在此实现）。 */
public interface OperateLogHandler {

    void handle(OperateLogRecord record);
}
