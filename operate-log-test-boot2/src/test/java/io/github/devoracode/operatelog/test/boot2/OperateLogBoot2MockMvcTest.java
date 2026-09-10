package io.github.devoracode.operatelog.test.boot2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Boot 2.x（javax 栈）端到端冒烟：经完整 Spring MVC + AOP 链路，
 * 断言 {@link DefaultOperateLogHandler} 落地的 JSON 日志字段。
 *
 * <p>断言通道：向 handler 的 logback logger 挂 {@link ListAppender}，解析
 * {@code operate-log=} 前缀行——比抓 stdout 稳定，且不引入额外依赖。</p>
 *
 * @author devoracode
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperateLogBoot2MockMvcTest {

    private static final String LOG_PREFIX = "operate-log=";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static ListAppender<ILoggingEvent> appender;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultOperateLogHandler.class);
        appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterAll
    static void detachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultOperateLogHandler.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    @BeforeEach
    void reset() {
        appender.list.clear();
        MDC.clear();
    }

    // ==================== 用例 ====================

    @Test
    void successRecordEndToEnd() throws Exception {
        this.mockMvc.perform(get("/demo/42"));

        JsonNode record = lastRecord();
        assertEquals("demo", record.get("module").asText());
        assertEquals("query", record.get("operation").asText());
        assertEquals("QUERY", record.get("operationType").asText());
        assertEquals("查询用户 42", record.get("description").asText());
        assertEquals("42", record.get("businessId").asText());
        assertTrue(record.get("success").asBoolean());
        assertEquals(200, record.get("httpStatus").asInt());
        assertEquals("GET", record.get("requestMethod").asText());
        assertEquals("/demo/42", record.get("requestUri").asText());
        // TestOperatorResolver 定制操作人
        assertEquals("10001", record.get("operatorUserId").asText());
        assertEquals("demo", record.get("operatorUserAccount").asText());
        // extra 自定义字段通道
        assertEquals("extra-channel", record.get("extra").get("demo").asText());
        // recordRequest 默认 true：参数入日志
        assertTrue(record.get("requestBody").asText().contains("42"));
        // recordResponse 默认 false：响应体不记录
        assertTrue(record.get("responseBody").isNull()
                || record.get("responseBody").asText().isEmpty());
        assertNotNull(record.get("traceId").asText());
        assertEquals("operate-log-test", record.get("application").asText());
        assertEquals("test", record.get("environment").asText());
    }

    @Test
    void classLevelAnnotationAppliesToUnannotatedMethod() throws Exception {
        this.mockMvc.perform(get("/demo/class-only"));
        JsonNode record = lastRecord();
        assertEquals("demo", record.get("module").asText());
        assertEquals("class-default", record.get("operation").asText());
        assertTrue(record.get("success").asBoolean());
    }

    @Test
    void sensitiveFieldMaskedInRequestBody() throws Exception {
        this.mockMvc.perform(post("/demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"n1\",\"password\":\"s3cr3t\"}"));

        JsonNode record = lastRecord();
        assertEquals("create", record.get("operation").asText());
        String body = record.get("requestBody").asText();
        assertTrue(body.contains("******"), body);
        assertTrue(!body.contains("s3cr3t"), "raw secret must not reach log: " + body);
    }

    @Test
    void errorPathRecordedWithStack() throws Exception {
        // MockMvc 下未捕获的业务异常可能穿透 perform(...)，也可能以 500 收口，
        // 两种形态都接受：日志在切面 finally 中先行落地，与此无关
        try {
            this.mockMvc.perform(get("/demo/fail"));
        } catch (Exception expected) {
            // IllegalStateException 经 Servlet 容器包装上抛——预期行为
        }

        JsonNode record = lastRecord();
        assertEquals("fail", record.get("operation").asText());
        assertEquals(1, countRecords(), "recordOn=ERROR must exactly emit one record");
        assertTrue(record.get("errorType").asText().contains("IllegalStateException"));
        assertEquals("demo failure", record.get("errorMessage").asText());
        assertTrue(record.get("errorStack").asText().contains("demo failure"));
        assertTrue(!record.get("success").asBoolean());
    }

    @Test
    void traceIdPickedUpFromMdc() throws Exception {
        MDC.put("traceId", "it-trace-2718");
        this.mockMvc.perform(get("/demo/7"));
        assertEquals("it-trace-2718", lastRecord().get("traceId").asText());
    }

    // ==================== helpers ====================

    private int countRecords() {
        int count = 0;
        for (ILoggingEvent event : appender.list) {
            if (event.getFormattedMessage().startsWith(LOG_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    private JsonNode lastRecord() {
        List<JsonNode> parsed = new ArrayList<JsonNode>();
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            if (message.startsWith(LOG_PREFIX)) {
                try {
                    parsed.add(MAPPER.readTree(
                            message.substring(LOG_PREFIX.length()).getBytes(StandardCharsets.UTF_8)));
                } catch (Exception ex) {
                    throw new IllegalStateException("operate-log line is not valid JSON", ex);
                }
            }
        }
        assertTrue(!parsed.isEmpty(), "no operate-log record captured; appender saw "
                + appender.list.size() + " event(s)");
        return parsed.get(parsed.size() - 1);
    }
}
