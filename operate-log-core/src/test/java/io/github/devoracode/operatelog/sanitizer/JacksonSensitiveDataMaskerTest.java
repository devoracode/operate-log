package io.github.devoracode.operatelog.sanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JacksonSensitiveDataMasker} 单测：递归命中、大小写、降级与配置。
 *
 * @author devoracode
 */
class JacksonSensitiveDataMaskerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JacksonSensitiveDataMasker masker(String... fields) {
        return new JacksonSensitiveDataMasker(
                mapper, new HashSet<String>(Arrays.asList(fields)), null);
    }

    @Test
    void masksTopLevelField() throws Exception {
        JsonNode node = read(masker("password")
                .mask("{\"name\":\"a\",\"password\":\"secret\"}"));
        assertEquals("******", node.get("password").asText());
        assertEquals("a", node.get("name").asText());
    }

    @Test
    void masksNestedObjectAndArrayRecursively() throws Exception {
        String json = "{\"outer\":{\"password\":\"p1\"},"
                + "\"list\":[{\"accessToken\":\"t1\"},{\"keep\":1},"
                + "[{\"token\":\"deep\"}]]}";
        JsonNode node = read(masker("password", "accessToken", "token").mask(json));
        assertEquals("******", node.get("outer").get("password").asText());
        assertEquals("******", node.get("list").get(0).get("accessToken").asText());
        assertEquals(1, node.get("list").get(1).get("keep").asInt());
        assertEquals("******",
                node.get("list").get(2).get(0).get("token").asText());
    }

    @Test
    void fieldMatchIsCaseInsensitive() throws Exception {
        JsonNode node = read(masker("password")
                .mask("{\"PaSsWoRd\":\"x\",\"PASSWORD\":\"y\"}"));
        assertEquals("******", node.get("PaSsWoRd").asText());
        assertEquals("******", node.get("PASSWORD").asText());
    }

    @Test
    void numericSensitiveValueReplacedByMaskText() throws Exception {
        JsonNode node = read(masker("token").mask("{\"token\":123}"));
        assertTrue(node.get("token").isTextual());
        assertEquals("******", node.get("token").asText());
    }

    @Test
    void exactFieldNameMatchNotSubstring() throws Exception {
        JsonNode node = read(masker("password")
                .mask("{\"userPassword\":\"keepme\"}"));
        assertEquals("keepme", node.get("userPassword").asText());
    }

    @Test
    void nonJsonContentReturnedAsIs() {
        String raw = "not a json {";
        assertEquals(raw, masker("password").mask(raw));
    }

    @Test
    void nullBlankAndEmptyFieldsShortCircuit() {
        JacksonSensitiveDataMasker m = masker("password");
        assertNull(m.mask(null));
        assertEquals("", m.mask(""));
        assertEquals("  ", m.mask("  "));
        // 无敏感字段配置：原样返回
        JacksonSensitiveDataMasker none =
                new JacksonSensitiveDataMasker(mapper, Collections.<String>emptySet(), null);
        assertEquals("{\"password\":\"x\"}", none.mask("{\"password\":\"x\"}"));
    }

    @Test
    void customMaskText() throws Exception {
        JacksonSensitiveDataMasker m = new JacksonSensitiveDataMasker(
                mapper, new HashSet<String>(Arrays.asList("pwd")), "###");
        JsonNode node = read(m.mask("{\"pwd\":\"secret\"}"));
        assertEquals("###", node.get("pwd").asText());
    }

    @Test
    void blankMaskTextFallsBackToDefault() throws Exception {
        JacksonSensitiveDataMasker m = new JacksonSensitiveDataMasker(
                mapper, new HashSet<String>(Arrays.asList("pwd")), "");
        JsonNode node = read(m.mask("{\"pwd\":\"secret\"}"));
        assertEquals("******", node.get("pwd").asText());
    }

    private JsonNode read(String json) throws Exception {
        assertFalse(json.contains("secret\""), "raw secret must not survive");
        return mapper.readTree(json);
    }
}
