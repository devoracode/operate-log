package io.github.devoracode.operatelog.test.boot2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.handler.DefaultOperateLogHandler;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.resolver.ClientIpResolver;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非 Web 形态（{@code spring.main.web-application-type=none}）下的真实启动验证：无请求上下文时
 * Starter 照常装配、正常启动，HTTP 字段一律 {@code null}，切面对普通 bean 方法仍然生效。
 * 「classpath 完全没有 Servlet API 时兜底装配」由 {@code OperateLogBoot2StarterAssemblyTest}
 * 用 {@code FilteredClassLoader} 覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.web-application-type=none")
class OperateLogBoot2NonWebContextTest {

    private static final String LOG_PREFIX = "operate-log=";
    private static final String APPENDER_NAME = "test-operate-log-non-web";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ListAppender<ILoggingEvent> appender;

    @Autowired
    private ApplicationContext context;

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
    void contextStartsWithAllComponentsWired() {
        assertNotNull(this.context.getBean(OperateLogAspect.class));
        assertNotNull(this.context.getBean(HttpContextResolver.class));
        assertNotNull(this.context.getBean(ClientIpResolver.class));
        assertNotNull(this.context.getBean(DefaultOperateLogHandler.class));
    }

    @Test
    void aspectStillAppliesToPlainBeanCalls() {
        DemoController controller = this.context.getBean(DemoController.class);

        assertEquals("ok", controller.query("9").get("message"));

        JsonNode record = lastRecord();
        assertNotNull(record, "非 Web 场景仍应产出日志记录");
        assertEquals("query", record.get("operation").asText());
        assertTrue(record.get("success").asBoolean());
        // 无请求上下文：HTTP 字段一律为 null，且不得因此报错
        assertTrue(record.get("requestUri").isNull(), String.valueOf(record.get("requestUri")));
        assertTrue(record.get("requestMethod").isNull(), String.valueOf(record.get("requestMethod")));
        assertTrue(record.get("clientIp").isNull(), String.valueOf(record.get("clientIp")));
        assertTrue(record.get("userAgent").isNull(), String.valueOf(record.get("userAgent")));
        assertFalse(record.has("httpStatus"), "httpStatus 已从日志模型中移除: " + record);
        // 业务方法返回值不受影响
        assertEquals("extra-channel", record.get("extra").get("demo").asText());
    }

    @Test
    void resolversReturnNullWithoutRequestContext() {
        HttpContextResolver resolver = this.context.getBean(HttpContextResolver.class);

        HttpContext resolved = resolver.resolve();
        assertNull(resolved, "非 Web 环境 HTTP 上下文必须为 null，而不是抛异常");
        assertNull(this.context.getBean(ClientIpResolver.class).resolve());
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
