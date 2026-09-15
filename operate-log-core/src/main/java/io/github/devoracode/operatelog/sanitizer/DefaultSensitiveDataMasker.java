package io.github.devoracode.operatelog.sanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.devoracode.operatelog.sanitizer.SensitiveDataMasker;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 默认脱敏器：将 JSON 文本解析为 Jackson {@link JsonNode} 动态树，
 * 递归遍历只替换命中字段名的值，不改结构；一个字段都没命中时原样返回。
 *
 * <p>非 JSON 文本无法定位字段名，解析失败即原样落地；
 * 需要别的脱敏策略（如手机号部分掩码）时注册自定义 {@link SensitiveDataMasker} bean 覆盖。</p>
 */
public class DefaultSensitiveDataMasker implements SensitiveDataMasker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSensitiveDataMasker.class);
    private static final String DEFAULT_MASK_TEXT = "******";

    private final ObjectMapper objectMapper;
    private final Set<String> sensitiveFields;
    private final String maskText;

    public DefaultSensitiveDataMasker(ObjectMapper objectMapper,
                                      Set<String> sensitiveFields,
                                      String maskText) {
        this.objectMapper = objectMapper;
        this.sensitiveFields = normalizeFields(sensitiveFields);
        this.maskText = StringUtils.defaultIfEmpty(maskText, DEFAULT_MASK_TEXT);
    }

    @Override
    public String mask(String content) {
        if (StringUtils.isBlank(content) || this.sensitiveFields.isEmpty()) {
            return content;
        }
        try {
            JsonNode root = this.objectMapper.readTree(content);
            if (!maskNode(root)) {
                return content;
            }
            return this.objectMapper.writeValueAsString(root);
        } catch (Exception | StackOverflowError ex) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("operate-log: masking skipped — content is not valid JSON, "
                        + "sensitive fields (if any) will NOT be masked. "
                        + "If using a custom OperateLogSerializer, ensure it produces JSON output.", ex);
            }
            return content;
        }
    }

    /**
     * 递归遍历 {@link JsonNode} 树，命中字段名时将值替换为 maskText。
     *
     * @return 是否发生过替换，据此决定要不要重写整段 JSON
     */
    private boolean maskNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isObject()) {
            return maskObject((ObjectNode) node);
        }
        if (node.isArray()) {
            boolean changed = false;
            for (JsonNode element : node) {
                changed |= maskNode(element);
            }
            return changed;
        }
        return false;
    }

    private boolean maskObject(ObjectNode object) {
        boolean changed = false;
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            if (name != null && this.sensitiveFields.contains(name.toLowerCase(Locale.ROOT))) {
                object.set(name, TextNode.valueOf(this.maskText));
                changed = true;
            } else {
                changed |= maskNode(entry.getValue());
            }
        }
        return changed;
    }

    /** 字段名精确匹配（非子串），大小写不敏感。 */
    private static Set<String> normalizeFields(Set<String> fields) {
        Set<String> normalized = new HashSet<String>();
        if (fields == null) {
            return normalized;
        }
        for (String field : fields) {
            if (StringUtils.isNotBlank(field)) {
                normalized.add(field.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }
}
