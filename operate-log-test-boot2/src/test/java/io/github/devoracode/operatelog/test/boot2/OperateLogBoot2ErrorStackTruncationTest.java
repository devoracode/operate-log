package io.github.devoracode.operatelog.test.boot2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
import io.github.devoracode.operatelog.spel.DefaultSpelEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.github.devoracode.operatelog.test.boot2.LogRecords.text;
import static io.github.devoracode.operatelog.test.boot2.LogRecords.flag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 错误堆栈截断测试：需显式配置 max-error-length。
 */
@SpringBootTest(properties = {
        "operate-log.payload.max-error-length=4096",
        "operate-log.mask.enabled=true"
})
@AutoConfigureMockMvc
class OperateLogBoot2ErrorStackTruncationTest {

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
        assertTrue(text(record, "errorStack").endsWith("...[truncated]"),
                "errorStack 超限时必须显式标记截断: " + text(record, "errorStack"));
        assertFalse(flag(record, "success"));
    }

    @Test
    void masksCompleteStackBeforeTruncatingIt() throws Exception {
        try {
            this.mockMvc.perform(get("/demo/fail-sensitive"));
        } catch (Exception expected) {
        }

        String stack = text(lastRecord(), "errorStack");
        assertTrue(stack.length() <= MAX_ERROR_LENGTH, stack);
        assertFalse(stack.contains("TOP_SECRET_VALUE_1234567890"), stack);
        assertTrue(stack.contains("******"), stack);
        assertTrue(stack.endsWith("...[truncated]"), stack);
    }

    @Test
    void customPolicyProcessesBothCompleteAndBoundedStacks() {
        int limit = 32;
        List<String> inputs = new ArrayList<>();
        List<OperateLogRecord> records = new ArrayList<>();
        PayloadPolicy policy = new PayloadPolicy(0, 0, limit, 0, Collections.emptyList()) {
            @Override
            public String truncateErrorStack(String value) {
                inputs.add(value);
                return "<hidden>";
            }
        };
        AspectJProxyFactory factory = new AspectJProxyFactory(new StackFailureService());
        factory.addAspect(new OperateLogAspect(records::add, () -> null, () -> null,
                null, null, new DefaultSpelEngine(), "test", "test", null, false, policy, null, 64));
        StackFailureService service = factory.getProxy();

        String longStack = String.join("", Collections.nCopies(100, "a"));
        for (String stack : Arrays.asList("short stack", longStack)) {
            IllegalStateException failure = new IllegalStateException("business failure") {
                @Override
                public void printStackTrace(PrintWriter writer) {
                    writer.write(stack);
                }
            };
            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.fail(failure)));
        }

        assertEquals(Arrays.asList("short stack", PayloadPolicy.truncate(longStack, limit)), inputs);
        assertEquals(2, records.size());
        records.forEach(record -> assertEquals("<hidden>", record.getErrorStack()));
    }

    @Test
    void truncatedCaptureHonorsSmallBudgetsAndSurrogateBoundaries() {
        String marker = "...[truncated]";
        String pair = "\uD83D\uDE00";
        assertEquals("a", PayloadPolicy.markCapturedStackTruncated("a\uD83D", 2));
        assertEquals("a" + pair, PayloadPolicy.markCapturedStackTruncated("a" + pair, 3));
        assertEquals(marker, PayloadPolicy.markCapturedStackTruncated("abc", marker.length()));
        assertEquals("a" + marker, PayloadPolicy.markCapturedStackTruncated("a" + pair, marker.length() + 2));
        assertEquals("masked" + marker, PayloadPolicy.markCapturedStackTruncated("masked", 32));
    }

    static class StackFailureService {
        @OperateLog(recordRequest = false)
        public void fail(IllegalStateException failure) {
            throw failure;
        }
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
