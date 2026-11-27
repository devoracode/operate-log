package io.github.devoracode.operatelog.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/** 默认处理器：以单行 JSON 输出到 SLF4J。 */
public class DefaultOperateLogHandler implements OperateLogHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOperateLogHandler.class);

    private final ObjectMapper objectMapper;
    private final AtomicLong serializationFailures = new AtomicLong();

    public DefaultOperateLogHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(OperateLogRecord record) {
        try {
            LOGGER.info("operate-log={}", this.objectMapper.writeValueAsString(record));
        } catch (Exception | StackOverflowError ex) {
            long failures = this.serializationFailures.incrementAndGet();
            if (failures == 1L) {
                LOGGER.warn("Failed to serialize operation log (exceptionType={}); later failures are reported as a count.",
                        ex.getClass().getName());
            } else {
                LOGGER.warn("Failed to serialize operation log ({} failures so far).", failures);
            }
        }
    }
}
