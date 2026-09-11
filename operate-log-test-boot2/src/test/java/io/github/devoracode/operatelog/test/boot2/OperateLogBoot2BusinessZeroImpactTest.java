package io.github.devoracode.operatelog.test.boot2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import io.github.devoracode.operatelog.handler.OperateLogHandler;
import io.github.devoracode.operatelog.serializer.JacksonOperateLogSerializer;
import io.github.devoracode.operatelog.serializer.OperateLogSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Business Zero Impact：真实 Servlet 容器下，日志链路任何一环故障都不得影响业务。
 *
 * <p>故障注入点（经 {@link ChaosFlags} 逐个点亮，互不牵连）：
 * {@code OperatorResolver}（业务执行前）、{@code OperateLogSerializer} 与
 * {@code OperateLogHandler}（业务执行后的收尾阶段，即切面 {@code finally}）——
 * 最后这两个正是「日志异常容易顶替业务异常」的位置。</p>
 *
 * <p>用 {@code RANDOM_PORT} + {@link TestRestTemplate} 而不是 MockMvc：
 * 需要真实容器的返回值处理与 {@code /error} 路径来判定「客户端实际拿到了什么」。</p>
 *
 * @author devoracode
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperateLogBoot2BusinessZeroImpactTest {

    private static final String LOG_PREFIX = "operate-log=";
    private static final String APPENDER_NAME = "test-operate-log-zero-impact";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ListAppender<ILoggingEvent> appender;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 故障版序列化器与 Handler：仅替换自动配置的默认实现，
     * 未开启故障时完全委托原实现，保证「控制组」用例仍是真实落地路径。
     */
    @TestConfiguration
    static class ChaosConfiguration {

        @Bean
        OperateLogSerializer operateLogSerializer(ObjectMapper objectMapper) {
            final OperateLogSerializer delegate = new JacksonOperateLogSerializer(objectMapper);
            return new OperateLogSerializer() {
                @Override
                public String serialize(Object value) {
                    ChaosFlags.throwIfActive(ChaosFlags.SERIALIZER, "chaos: serializer down");
                    return delegate.serialize(value);
                }

                @Override
                public String serializeArguments(Object[] arguments) {
                    ChaosFlags.throwIfActive(ChaosFlags.SERIALIZER, "chaos: serializer down");
                    return delegate.serializeArguments(arguments);
                }
            };
        }

        @Bean
        OperateLogHandler operateLogHandler(ObjectMapper objectMapper) {
            final OperateLogHandler delegate = new DefaultOperateLogHandler(objectMapper);
            return record -> {
                ChaosFlags.throwIfActive(ChaosFlags.HANDLER, "chaos: handler down");
                delegate.handle(record);
            };
        }
    }

    @BeforeEach
    void attachAppenderAndClearFlags() {
        ChaosFlags.disableAll();
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

    @AfterEach
    void clearFlags() {
        // 故障开关是静态的，必须在本类范围内复位，避免影响复用同一主上下文的其他用例
        ChaosFlags.disableAll();
    }

    @Test
    void controlGroupLogsNormallyWhenNoChaos() {
        ResponseEntity<String> response = this.restTemplate.getForEntity("/demo/42", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"message\":\"ok\""), String.valueOf(response.getBody()));
        JsonNode record = lastRecord();
        assertNotNull(record, "控制组必须照常落地日志");
        assertEquals("10001", record.get("operatorUserId").asText());
    }

    @Test
    void operatorResolverFailureDegradesOnlyOperatorFields() {
        ChaosFlags.enable(ChaosFlags.OPERATOR);

        ResponseEntity<String> response = this.restTemplate.getForEntity("/demo/42", String.class);

        assertEquals(200, response.getStatusCode().value(), "解析器异常绝不允许影响业务响应");
        assertTrue(response.getBody().contains("\"message\":\"ok\""), String.valueOf(response.getBody()));
        // 解析降级只损失 operator 三个字段，其余字段照常（各自独立 try-catch 的契约）
        JsonNode record = lastRecord();
        assertNotNull(record);
        assertTrue(record.get("operatorUserId").isNull(),
                "解析失败时 operator 字段应为空: " + record.get("operatorUserId"));
        assertEquals("/demo/42", record.get("requestUri").asText());
        assertEquals("query", record.get("operation").asText());
    }

    @Test
    void serializerFailureLosesRecordNotBusiness() {
        ChaosFlags.enable(ChaosFlags.SERIALIZER);

        ResponseEntity<String> response = this.restTemplate.getForEntity("/demo/42", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"message\":\"ok\""), String.valueOf(response.getBody()));
        assertNull(lastRecord(), "序列化故障只允许丢这条日志，不得抛出到业务");
    }

    @Test
    void handlerFailureDoesNotTouchBusiness() {
        ChaosFlags.enable(ChaosFlags.HANDLER);

        ResponseEntity<String> response = this.restTemplate.postForEntity(
                "/demo", java.util.Collections.singletonMap("password", "p"), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("p"), String.valueOf(response.getBody()));
    }

    @Test
    void businessExceptionNotDisplacedByLogFailures() {
        // 收尾阶段（切面 finally）全线故障：业务异常必须原样冒到容器错误处理，
        // 不能被日志异常顶替——否则 500 的成因会被误判为「审计系统故障」
        ChaosFlags.enable(ChaosFlags.SERIALIZER, ChaosFlags.HANDLER);

        ResponseEntity<String> response = this.restTemplate.getForEntity("/demo/fail", String.class);

        assertEquals(500, response.getStatusCode().value());
        String body = String.valueOf(response.getBody());
        assertTrue(!body.contains("chaos:"), "日志侧异常顶替了业务异常: " + body);
    }

    @Test
    void recordNotEmittedWhenHandlerDownAndNestingUnaffected() {
        ChaosFlags.enable(ChaosFlags.HANDLER);

        ResponseEntity<String> response = this.restTemplate.getForEntity("/demo/nested/9", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("context-restored"), String.valueOf(response.getBody()));
        // 内层落地失败不影响外层业务返回，也不得把 ThreadLocal 留成脏值
        assertTrue(appender.list.isEmpty(), "Handler 故障时不应有成功落地的记录");
    }

    private JsonNode lastRecord() {
        JsonNode last = null;
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            if (message.startsWith(LOG_PREFIX)) {
                try {
                    last = MAPPER.readTree(message.substring(LOG_PREFIX.length())
                            .getBytes(StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    throw new IllegalStateException("operate-log line is not valid JSON", ex);
                }
            }
        }
        return last;
    }
}
