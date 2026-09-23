package io.github.devoracode.operatelog.test.boot2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.devoracode.operatelog.test.boot2.LogRecords.child;
import static io.github.devoracode.operatelog.test.boot2.LogRecords.flag;
import static io.github.devoracode.operatelog.test.boot2.LogRecords.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 解析器写 {@code extra}：操作人角色这类扩展属性由宿主在 {@code OperatorResolver} 内追加，
 * 复用记录级 extra 通道，不动 {@code Operator} 与记录的字段形态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperateLogBoot2OperatorExtraTest {

    private static final String LOG_PREFIX = "operate-log=";
    private static final String APPENDER_NAME = "test-operate-log-operator-extra";
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

    @AfterEach
    void clearFlags() {
        ChaosFlags.disableAll();
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
    void roleAttributesFromResolverLandInExtra() throws Exception {
        ChaosFlags.enable(ChaosFlags.OPERATOR_EXTRA);
        this.mockMvc.perform(get("/demo/42"));

        Map<String, Object> extra = child(lastRecord(), "extra");
        assertEquals("20001", text(extra, "roleId"));
        assertEquals("ADMIN", text(extra, "roleCode"));
        assertEquals("超级管理员", text(extra, "roleName"));
        // 业务方法内写入的键与解析器写入的键共存，互不覆盖
        assertEquals("extra-channel", text(extra, "demo"));
        assertEquals("42", text(extra, "userId"));
    }

    @Test
    void resolverWritesNothingByDefault() throws Exception {
        this.mockMvc.perform(get("/demo/42"));

        Map<String, Object> extra = child(lastRecord(), "extra");
        assertNull(extra.get("roleCode"), "未开启开关时解析器不得写属性: " + extra);
        assertEquals(2, extra.size(), "extra 应只含业务侧键: " + extra);
    }

    @Test
    void resolverFailureWritesNoPartialAttributes() throws Exception {
        ChaosFlags.enable(ChaosFlags.OPERATOR_EXTRA, ChaosFlags.OPERATOR);
        this.mockMvc.perform(get("/demo/42"));

        Map<String, Object> record = lastRecord();
        assertTrue(flag(record, "success"), "解析器故障不得影响业务");
        assertNull(record.get("operatorUserId"), "operator 应降级为 null: " + record);
        assertNull(child(record, "extra").get("roleCode"), "故障前不得留下半写属性: " + record.get("extra"));
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
