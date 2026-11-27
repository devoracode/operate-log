package io.github.devoracode.operatelog.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/** 默认处理器：以单行 JSON 输出到 SLF4J。 */
@Slf4j
public class DefaultOperateLogHandler implements OperateLogHandler {
    private final ObjectMapper objectMapper;
    private final AtomicLong serializationFailures = new AtomicLong();

    public DefaultOperateLogHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(OperateLogRecord record) {
        try {
            log.info("operate-log={}", this.objectMapper.writeValueAsString(record));
        } catch (Exception | StackOverflowError ex) {
            long failures = this.serializationFailures.incrementAndGet();
            if (failures == 1L) {
                log.warn("Failed to serialize operation log (exceptionType={}); later failures are reported as a count.",
                        ex.getClass().getName());
            } else {
                log.warn("Failed to serialize operation log ({} failures so far).", failures);
            }
        }
    }
}
