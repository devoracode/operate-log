package io.github.devoracode.operatelog.sanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认脱敏器：将 JSON 文本解析为 Jackson {@link JsonNode} 动态树，
 * 递归遍历只替换命中字段名的值，不改结构；一个字段都没命中时原样返回。
 *
 * <p>非 JSON 文本无法定位字段名，解析失败即原样落地；
 * 需要别的脱敏策略（如手机号部分掩码）时注册自定义 {@link SensitiveDataMasker} bean 覆盖。</p>
 *
 * <p>同时覆写 {@link #maskQuery(String)}：对 URL query string 按参数名匹配并替换敏感值，
 * 复用同一份敏感字段集合与替换文本。</p>
 */
public class DefaultSensitiveDataMasker implements SensitiveDataMasker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSensitiveDataMasker.class);
    private static final String DEFAULT_MASK_TEXT = "******";
    /**
     * 内置默认敏感字段，<b>始终生效</b>：构造时与传入集合取并集，配置无法移除。
     * 免得「只想追加一个字段」的动作静默关掉既有脱敏项——整体替换默认集的代价远大于无法精简。
     */
    public static final Set<String> DEFAULT_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("password",
                    "passwd",
                    "pwd",
                    "token",
                    "accessToken",
                    "refreshToken",
                    "authorization",
                    "cookie",
                    "set-cookie",
                    "secret",
                    "clientSecret",
                    "apiKey",
                    "privateKey",
                    "accessKey",
                    "secretKey",
                    "creditCard")));
    private final ObjectMapper objectMapper;
    private final Set<String> sensitiveFields;
    private final Pattern plainTextFieldPattern;
    private final String maskText;
    private final String queryMaskText;

    public DefaultSensitiveDataMasker(ObjectMapper objectMapper,
                                      Set<String> sensitiveFields,
                                      String maskText) {
        this.objectMapper = objectMapper;
        this.sensitiveFields = normalizeFields(sensitiveFields);
        this.plainTextFieldPattern = buildPlainTextFieldPattern(this.sensitiveFields);
        this.maskText = StringUtils.defaultIfEmpty(maskText, DEFAULT_MASK_TEXT);
        this.queryMaskText = urlEncode(this.maskText);
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
            // 降 debug：序列化降级产生的 <UNSERIALIZABLE:...> 占位符本就不是合法 JSON，
            // 正常业务路径也会命中这里，warn 会按调用次数刷屏。排查「脱敏为什么没生效」时开 debug 对照。
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("operate-log: masking skipped — content is not valid JSON, " + "sensitive fields (if any) will NOT be masked. " + "If using a custom OperateLogSerializer, ensure it produces JSON output.",
                        ex);
            }
            return content;
        }
    }

    /**
     * 按参数名（忽略大小写）掩码 URL query string 的敏感值。参数名先按 form 解码再匹配，
     * 避免 {@code pass%77ord} 之类编码绕过；输出保留原始参数名写法，仅替换值。
     */
    @Override
    public String maskQuery(String query) {
        if (StringUtils.isBlank(query) || this.sensitiveFields.isEmpty()) {
            return query;
        }
        StringBuilder result = new StringBuilder(query.length());
        boolean changed = false;
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                result.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            // eq <= 0：无名参数（如 "=value"）或无值参数（无 '='），不参与脱敏，原样保留
            if (eq > 0) {
                String name = pair.substring(0, eq);
                String decodedName = urlDecode(name);
                if (this.sensitiveFields.contains(StringUtils.lowerCase(decodedName, Locale.ROOT))) {
                    result.append(name).append('=').append(this.queryMaskText);
                    changed = true;
                    continue;
                }
            }
            result.append(pair);
        }
        return changed ? result.toString() : query;
    }

    /**
     * 按 {@code application/x-www-form-urlencoded} 解码参数名，使编码形式与明文同名命中。
     * 非法编码序列按原样返回，只影响该参数的匹配，不中断整段 query 的脱敏。
     */
    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException ex) {
            return value;
        }
    }

    /**
     * 按 form 编码替换值，使 maskText 含 {@code &} / {@code =} 时仍不破坏 query 结构。
     */
    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            return value;
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
            if (name != null && this.sensitiveFields.contains(StringUtils.lowerCase(name, Locale.ROOT))) {
                object.set(name, TextNode.valueOf(this.maskText));
                changed = true;
            } else {
                changed |= maskNode(entry.getValue());
            }
        }
        return changed;
    }

    /**
     * 纯文本脱敏：按敏感字段名做大小写不敏感的子串搜索，命中后将其后连续的非空白值替换为 maskText。
     * 匹配模式覆盖常见异常 message 格式：{@code password=abc123}、{@code token: xyz}、{@code "secret":"val"}。
     */
    @Override
    public String maskPlainText(String text) {
        if (StringUtils.isBlank(text) || this.sensitiveFields.isEmpty()) {
            return text;
        }
        StringBuilder result = null;
        int lastEnd = 0;
        Matcher matcher = this.plainTextFieldPattern.matcher(text);
        while (matcher.find(lastEnd)) {
            if (result == null) {
                result = new StringBuilder(text.length() + 16);
            }
            int valueStart = matcher.end();
            char quote = 0;
            boolean hasSeparator = false;
            while (valueStart < text.length()) {
                char separator = text.charAt(valueStart);
                if (!hasSeparator && isQuote(separator)) {
                    valueStart++;
                } else if (separator == '=' || separator == ':' || Character.isWhitespace(separator)) {
                    hasSeparator = true;
                    valueStart++;
                } else {
                    if (hasSeparator && isQuote(separator)) {
                        quote = separator;
                        valueStart++;
                    }
                    break;
                }
            }
            int valueEnd = findValueEnd(text, valueStart, quote);
            result.append(text, lastEnd, valueStart);
            if (valueEnd > valueStart) {
                result.append(this.maskText);
            }
            lastEnd = Math.max(valueEnd, matcher.end());
        }
        if (result == null) {
            return text;
        }
        result.append(text, lastEnd, text.length());
        return result.toString();
    }

    private static Pattern buildPlainTextFieldPattern(Set<String> fields) {
        List<String> orderedFields = new ArrayList<>(fields);
        orderedFields.sort((left, right) -> {
            int byLength = Integer.compare(right.length(), left.length());
            return byLength != 0 ? byLength : left.compareTo(right);
        });
        StringBuilder expression = new StringBuilder();
        for (String field : orderedFields) {
            if (expression.length() > 0) {
                expression.append('|');
            }
            expression.append(Pattern.quote(field));
        }
        return Pattern.compile(expression.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private int findValueEnd(String text, int valueStart, char quote) {
        if (quote != 0) {
            for (int i = valueStart; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == quote && (i + 1 == text.length() || isValueTerminator(text.charAt(i + 1)))) {
                    Matcher fieldMatcher = this.plainTextFieldPattern.matcher(text);
                    fieldMatcher.region(valueStart, i);
                    if (!fieldMatcher.find()) {
                        return i;
                    }
                }
            }
            return text.length();
        }
        int valueEnd = valueStart;
        while (valueEnd < text.length() && !isValueTerminator(text.charAt(valueEnd))) {
            valueEnd++;
        }
        return valueEnd;
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'';
    }

    private static boolean isValueTerminator(char c) {
        return Character.isWhitespace(c)
                || c == ',' || c == ';' || c == '&' || c == '}' || c == ']' || c == ')';
    }

    /**
     * {@link #DEFAULT_FIELDS} 与追加字段取并集；字段名精确匹配（非子串），大小写不敏感。
     */
    private static Set<String> normalizeFields(Set<String> fields) {
        Set<String> normalized = new HashSet<>();
        for (String field : DEFAULT_FIELDS) {
            normalized.add(StringUtils.lowerCase(field, Locale.ROOT));
        }
        if (fields == null) {
            return normalized;
        }
        for (String field : fields) {
            if (StringUtils.isNotBlank(field)) {
                normalized.add(StringUtils.lowerCase(field, Locale.ROOT));
            }
        }
        return normalized;
    }
}
