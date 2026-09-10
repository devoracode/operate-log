package io.github.devoracode.operatelog.sanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Jackson JSON 树的敏感数据脱敏器。
 *
 * @author devoracode
 */
public class JacksonSensitiveDataMasker implements SensitiveDataMasker {

    private final ObjectMapper objectMapper;

    private final Set<String> sensitiveFields;

    private final String maskText;

    public JacksonSensitiveDataMasker(
            ObjectMapper objectMapper,
            Set<String> sensitiveFields,
            String maskText) {
        this.objectMapper = objectMapper;
        this.sensitiveFields = normalizeFields(sensitiveFields);
        this.maskText = StringUtils.defaultIfEmpty(maskText, "******");
    }

    @Override
    public String mask(String json) {
        if (StringUtils.isBlank(json) || this.sensitiveFields.isEmpty()) {
            return json;
        }

        try {
            JsonNode root = this.objectMapper.readTree(json);
            maskNode(root);
            return this.objectMapper.writeValueAsString(root);
        }
        catch (Exception ex) {
            return json;
        }
    }

    private void maskNode(JsonNode node) {
        if (node == null) {
            return;
        }

        if (node.isObject()) {
            maskObjectNode((ObjectNode) node);
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                maskNode(child);
            }
        }
    }

    private void maskObjectNode(ObjectNode objectNode) {
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode fieldValue = entry.getValue();

            if (this.sensitiveFields.contains(fieldName.toLowerCase(Locale.ROOT))) {
                objectNode.put(fieldName, this.maskText);
                continue;
            }

            maskNode(fieldValue);
        }
    }

    private Set<String> normalizeFields(Set<String> fields) {
        Set<String> normalizedFields = new HashSet<String>();

        if (fields == null) {
            return normalizedFields;
        }

        for (String field : fields) {
            if (StringUtils.isNotBlank(field)) {
                normalizedFields.add(field.toLowerCase(Locale.ROOT));
            }
        }

        return normalizedFields;
    }
}
