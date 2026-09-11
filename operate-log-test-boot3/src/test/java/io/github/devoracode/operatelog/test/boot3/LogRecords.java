package io.github.devoracode.operatelog.test.boot3;

import org.apache.fory.json.ForyJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试侧的 {@code operate-log=} 行解析：把 JSON 读成 Map / List 视图后按字段断言。
 *
 * <p>刻意用 Fory 而不是宿主 Jackson：用例因此不依赖任何 Jackson 坐标，同时顺带验证
 * 「落地的那行是能被同一套编解码读回的合法 JSON」。</p>
 */
final class LogRecords {

    private static final ForyJson JSON = ForyJson.builder().build();

    private LogRecords() {
    }

    static List<Map<String, Object>> parseAll(List<String> lines) {
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        for (String line : lines) {
            records.add(parse(line));
        }
        return records;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json) {
        Object root;
        try {
            root = JSON.fromJson(json, Object.class);
        } catch (Exception ex) {
            throw new IllegalStateException("operate-log line is not valid JSON", ex);
        }
        if (!(root instanceof Map)) {
            throw new IllegalStateException("operate-log line is not a JSON object: " + json);
        }
        return (Map<String, Object>) root;
    }

    /** 字段文本值；缺失或为 null 时返回 {@code null}。 */
    static String text(Map<String, Object> record, String key) {
        Object value = record.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static boolean flag(Map<String, Object> record, String key) {
        return Boolean.TRUE.equals(record.get(key));
    }

    static long number(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        // 数字被引号包住时仍应可比对（例如 long 以字符串形态落地）
        return Long.parseLong(String.valueOf(value));
    }

    static boolean has(Map<String, Object> record, String key) {
        return record.containsKey(key);
    }

    static boolean isNull(Map<String, Object> record, String key) {
        return record.get(key) == null;
    }

    /** 嵌套对象字段；缺失时给空 Map，让断言失败信息落在字段值而不是 NPE 上。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> child(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }
}
