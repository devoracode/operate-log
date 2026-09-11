package io.github.devoracode.operatelog.serializer;

import io.github.devoracode.operatelog.json.ForyJsons;
import org.apache.fory.json.ForyJson;

/**
 * 基于 Fory JSON 的操作日志序列化器。失败语义：任何序列化异常（getter 抛错、循环引用、
 * 栈溢出等）都被吞掉并降级为占位符——只损失对应字段，不损失整条记录，更不影响业务方法。
 */
public class ForyOperateLogSerializer implements OperateLogSerializer {
    private static final String UNSERIALIZABLE = "<UNSERIALIZABLE>";

    private final ForyJson json;

    public ForyOperateLogSerializer() {
        this(ForyJsons.defaultJson());
    }

    public ForyOperateLogSerializer(ForyJson json) {
        this.json = json;
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        String serialized = writeQuietly(value);
        return serialized == null ? UNSERIALIZABLE : serialized;
    }

    /** 优先整体序列化；失败则逐元素降级，坏元素换成 {@code <UNSERIALIZABLE:类型名>}。 */
    @Override
    public String serializeArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return serialize(arguments);
        }
        String whole = writeQuietly(arguments);
        if (whole != null) {
            return whole;
        }
        StringBuilder json = new StringBuilder(arguments.length * 32);
        json.append('[');
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            String element = writeQuietly(arguments[i]);
            if (element == null) {
                // 占位符必须是合法 JSON 字符串字面量，否则整个 requestBody 无法被下游解析
                json.append("\"<UNSERIALIZABLE:")
                        .append(arguments[i].getClass().getSimpleName())
                        .append(">\"");
            } else {
                json.append(element);
            }
        }
        json.append(']');
        return json.toString();
    }

    /** 静默序列化：成功返回 JSON，任何失败返回 {@code null} 交由调用方降级。 */
    private String writeQuietly(Object value) {
        try {
            return this.json.toJson(value);
        } catch (Exception | StackOverflowError ex) {
            return null;
        }
    }
}
