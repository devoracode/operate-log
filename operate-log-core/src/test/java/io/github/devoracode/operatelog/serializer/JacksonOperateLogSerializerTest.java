package io.github.devoracode.operatelog.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.testsupport.Samples;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JacksonOperateLogSerializer} 单测：降级语义与参数逐元素回退。
 *
 * @author devoracode
 */
class JacksonOperateLogSerializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final JacksonOperateLogSerializer serializer =
            new JacksonOperateLogSerializer(mapper);

    @Test
    void nullValueSerializesToNull() {
        assertNull(serializer.serialize(null));
    }

    @Test
    void serializesSimpleValues() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("k", "v");
        assertEquals("{\"k\":\"v\"}", serializer.serialize(map));
        assertEquals("\"s\"", serializer.serialize("s"));
    }

    @Test
    void failureDegradesToMarkerInsteadOfThrowing() {
        assertEquals("<UNSERIALIZABLE>",
                serializer.serialize(new Samples.Boom()));
    }

    @Test
    void argumentsHappyPathEqualsArraySerialization() {
        assertEquals("[1,\"x\"]",
                serializer.serializeArguments(new Object[]{1, "x"}));
        assertEquals("[]", serializer.serializeArguments(new Object[0]));
        assertNull(serializer.serializeArguments(null));
    }

    @Test
    void badElementFallsBackWithoutSinkingOthers() throws Exception {
        Object[] args = new Object[]{"ok", new Samples.Boom(), 42};
        String json = serializer.serializeArguments(args);

        assertNotNull(json);
        // 坏元素被替换为占位符（合法 JSON 字符串字面量），其余元素照常
        assertTrue(json.contains("\"<UNSERIALIZABLE:Boom>\""), json);
        assertTrue(json.contains("\"ok\""), json);
        assertTrue(json.contains("42"), json);
        // 结果必须是可解析的合法 JSON
        assertTrue(mapper.readTree(json).isArray());
    }

    @Test
    void defaultInterfaceMethodDelegatesToSerialize() {
        OperateLogSerializer minimal = value -> serializer.serialize(value);
        assertEquals("[1,\"x\"]",
                minimal.serializeArguments(new Object[]{1, "x"}));
    }
}
