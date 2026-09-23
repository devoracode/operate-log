package io.github.devoracode.operatelog.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认处理器：以单行 JSON 输出到 SLF4J。 */
public class DefaultOperateLogHandler implements OperateLogHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOperateLogHandler.class);

    private final ObjectMapper objectMapper;

    public DefaultOperateLogHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(OperateLogRecord record) {
        try {
            LOGGER.info("operate-log={}", this.objectMapper.writeValueAsString(record));
        } catch (Exception | StackOverflowError ex) {
            LOGGER.warn("Failed to serialize operation log.", ex);
        }
    }
}
