package io.github.devoracode.operatelog.test.boot2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Boot 2.x（javax 栈）端到端主用例：经完整 Spring MVC + AOP 链路，
 * 断言 {@link DefaultOperateLogHandler} 落地的 JSON 日志字段。
 *
 * <p>core / starter 不再保留单元测试，本类与同模块其余用例共同承担：
 * 注解语义、SpEL（含降级）、脱敏（嵌套 + 大小写）、载荷防护（截断 + 忽略类型 + 坏元素）、
 * 嵌套上下文隔离、traceId 兜底、HTTP 请求侧采集与 {@code trust-proxy} 语义。</p>
 *
 * <p>断言通道：向 handler 的 logback logger 挂 {@link ListAppender}，解析
 * {@code operate-log=} 前缀行——比抓 stdout 稳定，且不引入额外依赖。</p>
 *
 * <p>用例与 Boot 3 测试工程逐条对称：同一份 Starter 产物在两栈下的行为必须一致。</p>
 *
 * @author devoracode
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperateLogBoot2MockMvcTest {

    private static final String LOG_PREFIX = "operate-log=";
    private static final String APPENDER_NAME = "test-operate-log-capture";
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    private static final String MASKED = "******";
    private static final int MAX_PAYLOAD_LENGTH = 2048;
    private static final int MAX_ERROR_STACK_LENGTH = 4096;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ListAppender<ILoggingEvent> appender;

    @Autowired
    private MockMvc mockMvc;

    /**
     * 必须挂在 @BeforeEach：@BeforeAll 早于 Spring 上下文启动，而 Boot 日志系统
     * 初始化会重置 logback LoggerContext，把提前挂上的 appender 从 logger 树上摘掉。
     * 命名 + 判重保证同一 LoggerContext 内幂等（上下文跨用例缓存复用）。
     */
    @BeforeEach
    void attachAppenderAndReset() {
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

    // ==================== 记录基础字段 ====================

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
        assertEquals("GET", record.get("requestMethod").asText());
        assertEquals("/demo/42", record.get("requestUri").asText());
        // TestOperatorResolver 定制操作人
        assertEquals("10001", record.get("operatorUserId").asText());
        assertEquals("demo", record.get("operatorUserAccount").asText());
        // extra 自定义字段通道
        assertEquals("extra-channel", record.get("extra").get("demo").asText());
        assertEquals("42", record.get("extra").get("userId").asText());
        // recordRequest 默认 true：参数入日志；recordResponse 默认 false：响应体不记录
        assertTrue(record.get("requestBody").asText().contains("42"));
        assertTrue(record.get("responseBody").isNull() || record.get("responseBody").asText().isEmpty());
        assertNotNull(record.get("traceId").asText());
        assertEquals("operate-log-test", record.get("application").asText());
        assertEquals("test", record.get("environment").asText());
        // 本组件不提供 HTTP 状态码（见 HttpContextResolver 的取舍说明）：字段必须整体缺席，
        // 而不是「存在但为 null」——留着易被误读成最终状态码的空字段比没有更糟
        assertFalse(record.has("httpStatus"), "httpStatus 已从日志模型中移除: " + record);
    }

    @Test
    void requestSideHttpFieldsCollectedWithoutStatus() throws Exception {
        this.mockMvc.perform(get("/demo/8")
                .header("User-Agent", "operate-log-it/2.0")
                .header("X-Forwarded-For", "203.0.113.7"));

        JsonNode record = lastRecord();
        assertEquals("operate-log-it/2.0", record.get("userAgent").asText());
        // trust-proxy=false：X-Forwarded-For 必须被忽略，取容器给出的直连地址
        assertEquals("127.0.0.1", record.get("clientIp").asText());
        assertTrue(record.get("requestUrl").asText().endsWith("/demo/8"), record.get("requestUrl").asText());
        // capture-headers=false：请求头默认不采集
        assertTrue(record.get("requestHeaders").isNull(), String.valueOf(record.get("requestHeaders")));
    }

    @Test
    void queryParametersRecorded() throws Exception {
        this.mockMvc.perform(get("/demo/conditional?tag=go&page=3"));

        JsonNode record = lastRecord();
        assertEquals("tag=go&page=3", record.get("requestQuery").asText());
    }

    // ==================== 脱敏 ====================

    @Test
    void sensitiveFieldsMaskedInRequestAndResponseRecursively() throws Exception {
        String payload = "{\"Name\":\"n1\",\"password\":\"s3cr3t\",\"keep\":\"visible\","
                + "\"nested\":{\"PASSWORD\":\"deep-secret\",\"token\":\"tok-1\","
                + "\"deeper\":{\"clientSecret\":\"shhh\"}}}";
        this.mockMvc.perform(post("/demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));

        JsonNode record = lastRecord();
        String requestBody = record.get("requestBody").asText();
        String responseBody = record.get("responseBody").asText();
        for (String raw : new String[]{requestBody, responseBody}) {
            assertFalse(raw.contains("s3cr3t"), "明文密码进入日志: " + raw);
            assertFalse(raw.contains("deep-secret"), "嵌套明文进入日志: " + raw);
            assertFalse(raw.contains("tok-1"), "token 明文进入日志: " + raw);
            assertFalse(raw.contains("shhh"), "多层嵌套明文进入日志: " + raw);
            assertTrue(raw.contains(MASKED), "应看到替换文本: " + raw);
            // 非敏感字段必须保持原样，脱敏不得连带改写
            assertTrue(raw.contains("visible"), "误伤非敏感字段: " + raw);
        }
        // 精确匹配字段名（非子串）：Name / keep 不应被脱敏
        assertTrue(requestBody.contains("\"n1\""), requestBody);
    }

    // ==================== 异常路径与记录时机 ====================

    @Test
    void errorPathRecordedWithStack() throws Exception {
        // MockMvc 下未捕获的业务异常可能穿透 perform(...)，也可能以 500 收口，
        // 两种形态都接受：日志在切面 finally 中先行落地，与此无关
        try {
            this.mockMvc.perform(get("/demo/fail"));
        } catch (Exception expected) {
            // IllegalStateException 经 Servlet 容器包装上抛——预期行为，
            // 日志侧收尾绝不允许顶替它（Business Zero Impact 的断言见 ZeroImpact 用例）
        }

        JsonNode record = lastRecord();
        assertEquals("fail", record.get("operation").asText());
        assertEquals(1, countRecords(), "recordOn=ERROR 必须恰好产出一条记录");
        assertTrue(record.get("errorType").asText().contains("IllegalStateException"));
        assertEquals("demo failure", record.get("errorMessage").asText());
        assertTrue(record.get("errorStack").asText().contains("demo failure"));
        assertTrue(record.get("errorStack").asText().length() <= MAX_ERROR_STACK_LENGTH,
                "errorStack 不得越界: " + record.get("errorStack").asText().length());
        assertFalse(record.get("success").asBoolean());
    }

    @Test
    void successPathWithRecordOnSuccessEmitsSingleRecord() throws Exception {
        // /demo（recordOn=SUCCESS）正常返回时记录；此处仅断言「一次调用一条记录」，
        // 与 errorPathRecordedWithStack 的「恰好一条」共同钉死记录时机过滤
        this.mockMvc.perform(post("/demo").contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}"));
        assertEquals(1, countRecords());
    }

    // ==================== traceId ====================

    @Test
    void traceIdPickedUpFromMdc() throws Exception {
        MDC.put("traceId", "it-trace-2718");
        this.mockMvc.perform(get("/demo/7"));
        assertEquals("it-trace-2718", lastRecord().get("traceId").asText());
    }

    @Test
    void traceIdGeneratedWhenMdcAbsent() throws Exception {
        // MDC 无值时必须自动生成，而不是留空：否则无链路追踪体系的宿主无法关联记录
        this.mockMvc.perform(get("/demo/7"));
        String traceId = lastRecord().get("traceId").asText();
        assertTrue(traceId.length() >= 16, "traceId 疑似未生成: " + traceId);
    }

    // ==================== SpEL ====================

    @Test
    void spelVariablesResolveAgainstResultAndAnnotation() throws Exception {
        this.mockMvc.perform(get("/demo/spel/7"));

        JsonNode record = lastRecord();
        // #result（业务返回值）/ #p0（位置参数）/ #annotation（生效注解）与字面量混排
        assertEquals("spel:value-7|7|spel", record.get("description").asText());
        // #costTime 在收尾阶段已写入，恒 >= 0
        assertEquals("measured", record.get("businessId").asText());
        assertTrue(record.get("costTime").asLong() >= 0);
    }

    @Test
    void brokenSpelDegradesInsteadOfFailing() throws Exception {
        this.mockMvc.perform(get("/demo/spel-broken"));

        JsonNode record = lastRecord();
        // 模板求值失败 → 输出原文；表达式求值失败 → null；两者都不得影响业务与其余字段
        assertEquals("broken #{#noSuchVar + }", record.get("description").asText());
        assertTrue(record.get("businessId").isNull(), String.valueOf(record.get("businessId")));
        assertTrue(record.get("success").asBoolean());
    }

    @Test
    void conditionFalseSuppressesRecord() throws Exception {
        this.mockMvc.perform(get("/demo/conditional?tag=skip"));
        assertEquals(0, countRecords(), "condition 求值为 false 时不应产出记录");

        this.mockMvc.perform(get("/demo/conditional?tag=go"));
        assertEquals(1, countRecords(), "condition 求值为 true 时应产出一条记录");
    }

    // ==================== 载荷防护 ====================

    @Test
    void ignoredArgumentTypesReplacedWithPlaceholder() throws Exception {
        this.mockMvc.perform(get("/demo/ignore-args?tag=s"));

        JsonNode record = lastRecord();
        String requestBody = record.get("requestBody").asText();
        assertTrue(requestBody.contains("<IGNORED:"), "Servlet 请求对象应被忽略类型命中: " + requestBody);
        assertFalse(requestBody.contains("MockHttpServletRequest"), "容器对象不得被序列化: " + requestBody);
        // 同批次的普通参数照常记录
        assertTrue(requestBody.contains("\"s\""), requestBody);
        assertTrue(record.get("success").asBoolean());
    }

    @Test
    void unserializableArgumentDegradesPerElement() throws Exception {
        this.mockMvc.perform(post("/demo/bad-arg?note=ok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}"));

        JsonNode record = lastRecord();
        String requestBody = record.get("requestBody").asText();
        assertTrue(requestBody.contains("<UNSERIALIZABLE:UnserializableHolder>"),
                "坏参数应按元素降级: " + requestBody);
        assertTrue(requestBody.contains("\"ok\""), "其余参数不得受坏元素牵连: " + requestBody);
        assertTrue(record.get("success").asBoolean(), "坏元素不得影响业务");
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

        JsonNode record = lastRecord();
        String requestBody = record.get("requestBody").asText();
        String responseBody = record.get("responseBody").asText();
        assertTrue(requestBody.length() <= MAX_PAYLOAD_LENGTH,
                "requestBody 长度 " + requestBody.length() + " 超过上限 " + MAX_PAYLOAD_LENGTH);
        // 上限含截断标记：结果长度恰好等于上限并带标记（修复前为「上限 + 标记长度」）
        assertEquals(MAX_PAYLOAD_LENGTH, responseBody.length());
        assertTrue(responseBody.endsWith(TRUNCATED_SUFFIX), responseBody);
    }

    // ==================== 嵌套调用与上下文 ====================

    @Test
    void nestedCallsIsolateExtraAndRestoreOuterContext() throws Exception {
        this.mockMvc.perform(get("/demo/nested/9"));

        List<JsonNode> records = records();
        assertEquals(2, records.size(), "内外层各产一条记录，实际 " + records.size());
        JsonNode inner = pick(records, "inner");
        JsonNode outer = pick(records, "outer");

        assertEquals("9", inner.get("businessId").asText());
        assertEquals("inner", inner.get("extra").get("layer").asText());
        assertEquals("inner-value", inner.get("extra").get("innerOnly").asText());

        // 外层：恢复的上下文仍在，且不被内层 extra 污染
        assertEquals("outer", outer.get("extra").get("layer").asText());
        assertNull(outer.get("extra").get("innerOnly"), "内层 extra 泄漏到外层: " + outer.get("extra"));
        assertTrue(outer.get("extra").size() == 1, "外层 extra 应只含自身键: " + outer.get("extra"));
        assertTrue(outer.get("responseBody").asText().contains("context-restored"),
                "内层结束后必须恢复外层上下文: " + outer.get("responseBody").asText());
    }

    // ==================== helpers ====================

    private int countRecords() {
        return records().size();
    }

    private JsonNode lastRecord() {
        List<JsonNode> parsed = records();
        assertTrue(!parsed.isEmpty(), "no operate-log record captured; appender saw "
                + appender.list.size() + " event(s)");
        return parsed.get(parsed.size() - 1);
    }

    private JsonNode pick(List<JsonNode> records, String operation) {
        for (JsonNode record : records) {
            if (operation.equals(record.get("operation").asText())) {
                return record;
            }
        }
        throw new AssertionError("未捕获到 operation=" + operation + " 的记录，实际 " + records);
    }

    private List<JsonNode> records() {
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
        return parsed;
    }
}
