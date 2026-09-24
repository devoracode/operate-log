package io.github.devoracode.operatelog.test.boot2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
import io.github.devoracode.operatelog.serializer.DefaultOperateLogSerializer;
import io.github.devoracode.operatelog.serializer.OperateLogSerializer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OperateLogBoot2ExtraSerializationTest {

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
