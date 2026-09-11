package io.github.devoracode.operatelog.test.boot3;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static io.github.devoracode.operatelog.test.boot3.LogRecords.text;
import static io.github.devoracode.operatelog.test.boot3.LogRecords.flag;
import static io.github.devoracode.operatelog.test.boot3.LogRecords.isNull;
import static io.github.devoracode.operatelog.test.boot3.LogRecords.child;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 配置项在真实容器里的生效语义（只覆盖默认值，不改配置键）：{@code http.capture-headers} /
 * {@code http.trust-proxy} / {@code mask.enabled} / {@code payload.max-response-length} /
 * {@code spel.enabled}。这些属性会整体切换语义，故用独立上下文跑，避免与默认配置用例互串。
 */
@SpringBootTest(properties = {
        "operate-log.http.capture-headers=true",
        "operate-log.http.trust-proxy=true",
        "operate-log.mask.enabled=false",
        "operate-log.payload.max-response-length=64",
        "operate-log.spel.enabled=false"})
@AutoConfigureMockMvc
class OperateLogBoot3ConfigurationOverrideTest {

    private static final String LOG_PREFIX = "operate-log=";
    private static final String APPENDER_NAME = "test-operate-log-override";

    private static ListAppender<ILoggingEvent> appender;

    @Autowired
    private MockMvc mockMvc;

    /** 同 MockMvc 用例的约定：必须 @BeforeEach 挂载，命名 + 判重保证幂等。 */
    @BeforeEach
    void attachAppenderAndClear() {
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
    void headersCapturedAndProxyHeadersHonored() throws Exception {
        this.mockMvc.perform(get("/demo/5")
                .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                .header("User-Agent", "override-it/1.0"));

        Map<String, Object> record = lastRecord();
        // trust-proxy=true：取 X-Forwarded-For 链第一个（最原始客户端）
        assertEquals("203.0.113.7", text(record, "clientIp"));
        // capture-headers=true：请求头以 JSON 入日志（本身也走脱敏管道，此处 mask.enabled=false）
        String headers = text(record, "requestHeaders");
        assertTrue(headers.contains("override-it/1.0"), headers);
        assertTrue(headers.contains("203.0.113.7"), headers);
    }

    @Test
    void maskDisabledKeepsRawValues() throws Exception {
        this.mockMvc.perform(post("/demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"plain-when-mask-off\"}"));

        assertTrue(text(lastRecord(), "requestBody").contains("plain-when-mask-off"),
                "mask.enabled=false 时不得改写内容");
    }

    @Test
    void responsePayloadTruncatedToOverrideLimit() throws Exception {
        StringBuilder blob = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            blob.append('b');
        }
        this.mockMvc.perform(post("/demo/huge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blob\":\"" + blob + "\"}"));

        String responseBody = text(lastRecord(), "responseBody");
        // max-response-length=64：上限含 ...[truncated] 标记，结果长度恰好 64
        assertEquals(64, responseBody.length(), "实际长度 " + responseBody.length());
        assertTrue(responseBody.endsWith("...[truncated]"), responseBody);
    }

    @Test
    void spelDisabledFallsBackToPassthrough() throws Exception {
        this.mockMvc.perform(get("/demo/42"));

        Map<String, Object> record = lastRecord();
        // 引擎直通：模板输出原文、businessId 为 null、condition 恒通过（记录照常产出）
        assertEquals("查询用户 #{#userId}", text(record, "description"));
        assertTrue(isNull(record, "businessId"), String.valueOf(record.get("businessId")));
        assertTrue(flag(record, "success"));
    }

    @Test
    void extraChannelStillWorksWhenSpelDisabled() throws Exception {
        this.mockMvc.perform(get("/demo/42"));

        assertEquals("extra-channel", text(child(lastRecord(), "extra"), "demo"));
        assertFalse(appender.list.isEmpty());
    }

    private Map<String, Object> lastRecord() {
        Map<String, Object> last = null;
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            if (message.startsWith(LOG_PREFIX)) {
                last = LogRecords.parse(message.substring(LOG_PREFIX.length()));
            }
        }
        assertTrue(last != null, "未捕获到日志记录，appender 看到 " + appender.list.size() + " 条事件");
        return last;
    }
}
