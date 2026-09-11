package io.github.devoracode.operatelog.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认处理器：以单行 JSON 输出到 SLF4J。 */
@RequiredArgsConstructor
public class DefaultOperateLogHandler implements OperateLogHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOperateLogHandler.class);
    private final ObjectMapper objectMapper;

    @Override
    public void handle(OperateLogRecord record) {
        try {
            String json = this.objectMapper.writeValueAsString(record);
            LOGGER.info("operate-log={}", json);
        } catch (JsonProcessingException ex) {
            LOGGER.warn("Failed to serialize operation log.", ex);
        }
    }
}
