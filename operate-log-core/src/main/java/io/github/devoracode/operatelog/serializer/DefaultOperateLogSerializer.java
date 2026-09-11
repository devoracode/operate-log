package io.github.devoracode.operatelog.serializer;

import io.github.devoracode.operatelog.json.ForyJsons;
import org.apache.fory.json.ForyJson;

/**
 * 操作日志序列化器的<b>默认实现</b>：用组件自带的 JSON 实现（Apache Fory）把对象写成日志文本。
 * JSON 库只是日志的一种呈现方式，因此类名不绑定具体实现——需要 Jackson / Gson 或自定义日期格式时，
 * 注册自己的 {@link OperateLogSerializer} bean 覆盖即可。
 *
 * <p>失败语义：任何序列化异常（getter 抛错、循环引用、栈溢出等）都被吞掉并降级为占位符——
 * 只损失对应字段，不损失整条记录，更不影响业务方法。
 */
public class DefaultOperateLogSerializer implements OperateLogSerializer {
    private static final String UNSERIALIZABLE = "<UNSERIALIZABLE>";

    private final ForyJson json;

    public DefaultOperateLogSerializer() {
        this(ForyJsons.defaultJson());
    }

    public DefaultOperateLogSerializer(ForyJson json) {
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
