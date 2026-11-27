package io.github.devoracode.operatelog.test.boot2;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperateLogBoot2HandlerFailureLogTest {

    private static final String APPENDER_NAME = "test-operate-log-handler-failure";

    @Test
    void failureDiagnosticsKeepTypeAndCountWithoutRawExceptions() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultOperateLogHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.setName(APPENDER_NAME);
        appender.start();
        logger.addAppender(appender);
        try {
            DefaultOperateLogHandler handler = new DefaultOperateLogHandler(new HostileObjectMapper());
            OperateLogRecord record = new OperateLogRecord();

            handler.handle(record);
            handler.handle(record);
            handler.handle(record);

            assertEquals(3, appender.list.size(), "每次失败都应留一条 warn: " + appender.list);
            ILoggingEvent first = appender.list.get(0);
            assertEquals(Level.WARN, first.getLevel());
            assertTrue(first.getFormattedMessage().contains(IllegalStateException.class.getName()));
            for (ILoggingEvent event : appender.list) {
                assertNull(event.getThrowableProxy());
                assertFalse(event.getFormattedMessage().contains("test-serializer-detail"));
            }
            assertTrue(appender.list.get(1).getFormattedMessage().contains("(2 failures so far)"));
            assertTrue(appender.list.get(2).getFormattedMessage().contains("(3 failures so far)"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 宿主 Jackson 配置下某类值一律写不出，是最典型的持续失败形态。 */
    private static final class HostileObjectMapper extends ObjectMapper {
        private static final long serialVersionUID = 1L;

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            throw new IllegalStateException("test-serializer-detail");
        }
    }
}
