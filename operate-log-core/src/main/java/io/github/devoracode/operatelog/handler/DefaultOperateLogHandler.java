package io.github.devoracode.operatelog.handler;

import io.github.devoracode.operatelog.json.ForyJsons;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import org.apache.fory.json.ForyJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认处理器：以单行 JSON 输出到 SLF4J。 */
public class DefaultOperateLogHandler implements OperateLogHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOperateLogHandler.class);

    private final ForyJson json;

    public DefaultOperateLogHandler() {
        this(ForyJsons.defaultJson());
    }

    public DefaultOperateLogHandler(ForyJson json) {
        this.json = json;
    }

    @Override
    public void handle(OperateLogRecord record) {
        try {
            LOGGER.info("operate-log={}", this.json.toJson(record));
        } catch (Exception | StackOverflowError ex) {
            LOGGER.warn("Failed to serialize operation log.", ex);
        }
    }
}
