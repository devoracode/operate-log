package io.github.devoracode.operatelog.sanitizer;

import io.github.devoracode.operatelog.json.ForyJsons;
import org.apache.commons.lang3.StringUtils;
import org.apache.fory.json.ForyJson;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 敏感数据脱敏器的<b>默认实现</b>：在组件自带 JSON 实现的动态树（{@code JsonObject} / {@code JsonArray}）上
 * 只替换命中字段名的值，不改结构；一个字段都没命中时原样返回，避免重写带来数字与格式漂移。
 *
 * <p>JSON 库只是这里的一种解析手段，类名因此不绑定具体实现；需要别的脱敏策略（如手机号部分掩码）时
 * 注册自己的 {@link SensitiveDataMasker} bean 覆盖即可。
 *
 * <p>非 JSON 文本（如纯字符串参数）无法定位字段名，解析失败即原样落地；需要覆盖这种形态时
 * 由业务方提供自定义 {@link SensitiveDataMasker} bean。</p>
 */
public class DefaultSensitiveDataMasker implements SensitiveDataMasker {
    private static final String DEFAULT_MASK_TEXT = "******";

    private final ForyJson json;

    private final Set<String> sensitiveFields;

    private final String maskText;

    public DefaultSensitiveDataMasker(Set<String> sensitiveFields, String maskText) {
        this(ForyJsons.defaultJson(), sensitiveFields, maskText);
    }

    public DefaultSensitiveDataMasker(ForyJson json, Set<String> sensitiveFields, String maskText) {
        this.json = json;
        this.sensitiveFields = normalizeFields(sensitiveFields);
        this.maskText = StringUtils.defaultIfEmpty(maskText, DEFAULT_MASK_TEXT);
    }

    @Override
    public String mask(String content) {
        if (StringUtils.isBlank(content) || this.sensitiveFields.isEmpty()) {
            return content;
        }
        try {
            Object root = this.json.fromJson(content, Object.class);
            if (!maskValue(root)) {
                return content;
            }
            return this.json.toJson(root);
        } catch (Exception | StackOverflowError ex) {
            return content;
        }
    }

    /** 命中即替换；返回是否发生过替换，据此决定要不要重写整段 JSON。 */
    @SuppressWarnings("unchecked")
    private boolean maskValue(Object value) {
        if (value instanceof Map) {
            return maskObject((Map<String, Object>) value);
        }
        if (value instanceof List) {
            boolean changed = false;
            for (Object element : (List<Object>) value) {
                changed |= maskValue(element);
            }
            return changed;
        }
        return false;
    }

    private boolean maskObject(Map<String, Object> object) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            String name = entry.getKey();
            if (name != null && this.sensitiveFields.contains(name.toLowerCase(Locale.ROOT))) {
                entry.setValue(this.maskText);
                changed = true;
                continue;
            }
            changed |= maskValue(entry.getValue());
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
