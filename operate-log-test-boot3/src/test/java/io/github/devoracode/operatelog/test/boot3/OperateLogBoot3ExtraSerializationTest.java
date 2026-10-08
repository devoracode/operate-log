package io.github.devoracode.operatelog.test.boot3;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
import io.github.devoracode.operatelog.serializer.DefaultOperateLogSerializer;
import io.github.devoracode.operatelog.serializer.OperateLogSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperateLogBoot3ExtraSerializationTest {

    @Test
    void limitedExtraDoesNotProbeComplexValueBeforeMeasurement() throws Exception {
        CountingSerializer serializer = new CountingSerializer();
        PayloadPolicy policy = new PayloadPolicy(0, 0, 0, 2048, Collections.<String>emptySet());
        OperateLogAspect aspect = new OperateLogAspect(null, null, null, serializer, null, null,
                "", "", "", false, policy, null, 4096);
        Map<String, Object> complex = new LinkedHashMap<String, Object>();
        complex.put("key", "value");
        Method normalize = OperateLogAspect.class.getDeclaredMethod("normalizeExtra", Map.class);
        normalize.setAccessible(true);

        Object normalized = normalize.invoke(aspect, Collections.singletonMap("complex", complex));

        assertSame(complex, ((Map<?, ?>) normalized).get("complex"));
        assertEquals(2, serializer.calls,
                "启用 extra 限长时不应在逐条测量前重复探测复杂值");
    }

    /**
 * 整表清空必须留痕。清空之后记录里的 extra 与「业务方法本来就没写 extra」完全同形，
 * 审计方无从分辨；没有日志就等于这段数据被静默丢弃。
 */
    @Test
    void extraClearedOnUnserializableValueIsLogged() throws Exception {
        String appenderName = "test-operate-log-extra-drop";
        ch.qos.logback.classic.Logger aspectLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OperateLogAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.setName(appenderName);
        appender.start();
        aspectLogger.addAppender(appender);
        try {
            PayloadPolicy policy = new PayloadPolicy(0, 0, 0, 2048, Collections.<String>emptySet());
            OperateLogAspect aspect = new OperateLogAspect(null, null, null, new NullSerializer(),
                    null, null, "", "", "", false, policy, null, 4096);
            Method enforce = OperateLogAspect.class.getDeclaredMethod("enforceExtraLimit", Map.class, int.class);
            enforce.setAccessible(true);
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put("plain", "value");
            extra.put("other", "value2");

            enforce.invoke(aspect, extra, 2048);

            assertTrue(extra.isEmpty(), "序列化不可用时整表应被清空，实际 " + extra);
            assertEquals(1, appender.list.size(), "整表清空必须恰好留一条日志: " + appender.list);
            String message = appender.list.get(0).getFormattedMessage();
            assertTrue(message.contains("extra dropped entirely"), message);
            assertTrue(message.contains("2 key(s) lost"), message);
        } finally {
            aspectLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private static final class NullSerializer implements OperateLogSerializer {
        @Override
        public String serialize(Object value) {
            return null;
        }
    }

    private static final class CountingSerializer implements OperateLogSerializer {
        private final DefaultOperateLogSerializer delegate =
                new DefaultOperateLogSerializer(new ObjectMapper());
        private int calls;

        @Override
        public String serialize(Object value) {
            this.calls++;
            return this.delegate.serialize(value);
        }
    }
}
