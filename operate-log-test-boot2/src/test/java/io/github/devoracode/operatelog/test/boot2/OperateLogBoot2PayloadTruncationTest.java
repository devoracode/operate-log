package io.github.devoracode.operatelog.test.boot2;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import static io.github.devoracode.operatelog.test.boot2.LogRecords.child;
import static io.github.devoracode.operatelog.test.boot2.LogRecords.text;
import static io.github.devoracode.operatelog.test.boot2.LogRecords.flag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 载荷截断测试：需显式配置 max-request/response-length。
 */
@SpringBootTest(properties = {
        "operate-log.payload.max-request-length=2048",
        "operate-log.payload.max-response-length=2048",
        "operate-log.payload.max-extra-length=2048",
        "operate-log.mask.enabled=true"
})
@AutoConfigureMockMvc
class OperateLogBoot2PayloadTruncationTest {

    private static final String LOG_PREFIX = "operate-log=";
    private static final String APPENDER_NAME = "test-operate-log-payload";
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    private static final int MAX_PAYLOAD_LENGTH = 2048;
    private static ListAppender<ILoggingEvent> appender;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void oversizedPayloadTruncatedWithinConfiguredLimit() throws Exception {
        StringBuilder blob = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            blob.append('a');
        }
        this.mockMvc.perform(post("/demo/huge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blob\":\"" + blob + "\"}"));

        Map<String, Object> record = lastRecord();
        String requestBody = text(record, "requestBody");
        String responseBody = text(record, "responseBody");
        assertTrue(requestBody.length() <= MAX_PAYLOAD_LENGTH,
                "requestBody 长度 " + requestBody.length() + " 超过上限 " + MAX_PAYLOAD_LENGTH);
        // 上限含截断标记：结果长度恰好等于上限并带标记
        assertEquals(MAX_PAYLOAD_LENGTH, responseBody.length());
        assertTrue(responseBody.endsWith(TRUNCATED_SUFFIX), responseBody);
    }

    /**
     * query string 与 User-Agent 同属客户端可控输入，必须与 JSON 字段一样受
     * {@code payload.max-request-length} 约束，否则单条日志可被撑到任意大小。
     */
    @Test
    void oversizedQueryAndUserAgentTruncated() throws Exception {
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longValue.append('q');
        }
        this.mockMvc.perform(get("/demo/1").queryParam("blob", longValue.toString())
                .header("User-Agent", longValue.toString()));

        Map<String, Object> record = lastRecord();
        String requestQuery = text(record, "requestQuery");
        String userAgent = text(record, "userAgent");
        assertEquals(MAX_PAYLOAD_LENGTH, requestQuery.length(), "requestQuery 应恰好截到上限");
        assertTrue(requestQuery.endsWith(TRUNCATED_SUFFIX), requestQuery);
        assertEquals(MAX_PAYLOAD_LENGTH, userAgent.length(), "userAgent 应恰好截到上限");
        assertTrue(userAgent.endsWith(TRUNCATED_SUFFIX), userAgent);
        assertTrue(flag(record, "success"));
    }

    /**
     * extra 由开发者主动写入，本组件不脱敏（README 明确提醒不要塞敏感数据）。
     * 但坏值（循环引用 / getter 抛错）不得拖垮整条记录，超限必须收缩到窗口内。
     */
    @Test
    void extraIsBoundedAndSerializationFailureIsControlled() throws Exception {
        this.mockMvc.perform(get("/demo/extra-security"));

        Map<String, Object> record = lastRecord();
        Object extraValue = record.get("extra");
        assertTrue(extraValue != null, "extra 应随记录落地，不因单个坏值整条丢失: " + record);
        String extra = String.valueOf(extraValue);
        assertTrue(extra.length() <= 2048, "extra payload exceeds configured limit: " + extra.length());
        assertTrue(extra.contains("UNSERIALIZABLE"), "坏值应降级为占位符: " + extra);
        Map<String, Object> extraMap = child(record, "extra");
        assertFalse(extraMap.containsKey("nested"), "超大非字符串值应移除，不得字符串化: " + extraMap);
        assertFalse(extraMap.containsKey("_truncated"), "不应回退为改变类型的 _truncated 字符串: " + extraMap);
    }

    @Test
    void escapedStringExtraUsesSerializedLengthBudget() throws Exception {
        this.mockMvc.perform(get("/demo/extra-escaped"));

        Map<String, Object> extra = child(lastRecord(), "extra");
        String serialized = this.objectMapper.writeValueAsString(extra);
        assertTrue(serialized.length() <= MAX_PAYLOAD_LENGTH,
                "extra JSON 长度 " + serialized.length() + " 超过上限");
        assertTrue(serialized.length() >= MAX_PAYLOAD_LENGTH - 128,
                "转义值应按实际 JSON 长度充分使用预算: " + serialized.length());
        String escaped = text(extra, "escaped");
        assertTrue(escaped.endsWith(TRUNCATED_SUFFIX), escaped);
        assertTrue(escaped.length() > 900,
                "转义字符不应导致过度收缩: json=" + serialized + ", value=" + escaped);
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