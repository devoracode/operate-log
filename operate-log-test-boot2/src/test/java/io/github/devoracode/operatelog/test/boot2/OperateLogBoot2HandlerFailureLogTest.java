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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认处理器序列化失败时的日志形态：记录本就丢了，重试无意义，而失败往往是持续性的，
 * 逐次带全栈会把日志冲垮。因此只在首次给出堆栈，之后只报累计次数。
 */
class OperateLogBoot2HandlerFailureLogTest {

    private static final String APPENDER_NAME = "test-operate-log-handler-failure";

    @Test
    void onlyTheFirstFailureCarriesAStackTrace() {
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
            assertNotNull(first.getThrowableProxy(), "首次失败必须带堆栈，否则无从诊断");
            assertRepeatedFailureWithoutStack(appender.list.get(1), 2);
            assertRepeatedFailureWithoutStack(appender.list.get(2), 3);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void assertRepeatedFailureWithoutStack(ILoggingEvent event, int expectedCount) {
        assertNull(event.getThrowableProxy(),
                "重复失败不应再带堆栈: " + event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains("(" + expectedCount + " failures so far)"),
                event.getFormattedMessage());
    }

    /** 宿主 Jackson 配置下某类值一律写不出，是最典型的持续失败形态。 */
    private static final class HostileObjectMapper extends ObjectMapper {
        private static final long serialVersionUID = 1L;

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            throw new IllegalStateException("serializer is hostile");
        }
    }
}