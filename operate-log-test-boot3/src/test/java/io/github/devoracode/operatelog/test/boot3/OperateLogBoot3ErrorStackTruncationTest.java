package io.github.devoracode.operatelog.test.boot3;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import static io.github.devoracode.operatelog.test.boot3.LogRecords.text;
import static io.github.devoracode.operatelog.test.boot3.LogRecords.flag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 错误堆栈截断测试：需显式配置 max-error-length。
 */
@SpringBootTest(properties = "operate-log.payload.max-error-length=4096")
@AutoConfigureMockMvc
class OperateLogBoot3ErrorStackTruncationTest {

    private static final String LOG_PREFIX = "operate-log=";
    private static final String APPENDER_NAME = "test-operate-log-error-stack";
    private static final int MAX_ERROR_LENGTH = 4096;
    private static ListAppender<ILoggingEvent> appender;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultOperateLogHandler.class);
        if (logger.getAppender(APPENDER_NAME) == null) {
            appender = new ListAppender<ILoggingEvent>();
            appender.setName(APPENDER_NAME);
            appender.start();
            logger.addAppender(appender);
        } else {
            appender = (ListAppender<ILoggingEvent>) logger.getAppender(APPENDER_NAME);
        }
        appender.list.clear();
        MDC.clear();
    }

    @AfterAll
    static void detachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultOperateLogHandler.class);
        ch.qos.logback.core.Appender<ILoggingEvent> existing = logger.getAppender(APPENDER_NAME);
        if (existing != null) {
            logger.detachAppender(existing);
            existing.stop();
        }
    }

    @Test
    void errorPathRecordedWithStack() throws Exception {
        try {
            this.mockMvc.perform(get("/demo/fail"));
        } catch (Exception expected) {
        }

        Map<String, Object> record = lastRecord();
        assertEquals("fail", text(record, "operation"));
        assertEquals(1, countRecords(), "recordOn=ERROR 必须恰好产出一条记录");
        assertTrue(text(record, "errorType").contains("IllegalStateException"));
        assertEquals("demo failure", text(record, "errorMessage"));
        assertTrue(text(record, "errorStack").contains("demo failure"));
        assertTrue(text(record, "errorStack").length() <= MAX_ERROR_LENGTH,
                "errorStack 不得越界: " + text(record, "errorStack").length());
        assertFalse(flag(record, "success"));
    }

    private int countRecords() {
        return records().size();
    }

    private Map<String, Object> lastRecord() {
        List<Map<String, Object>> parsed = records();
        assertTrue(!parsed.isEmpty(), "no operate-log record captured; appender saw "
                + appender.list.size() + " event(s)");
        return parsed.get(parsed.size() - 1);
    }

    private List<Map<String, Object>> records() {
        List<String> lines = new ArrayList<String>();
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            if (message.startsWith(LOG_PREFIX)) {
                lines.add(message.substring(LOG_PREFIX.length()));
            }
        }
        return LogRecords.parseAll(lines);
    }
}