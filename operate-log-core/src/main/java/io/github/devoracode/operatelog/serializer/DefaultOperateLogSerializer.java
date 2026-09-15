package io.github.devoracode.operatelog.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 默认序列化器：用 Jackson {@link ObjectMapper} 把对象写成 JSON 文本。
 * 需要自定义日期格式、命名策略等时，注册自己的 {@link OperateLogSerializer} bean 覆盖。
 *
 * <p>失败语义：任何序列化异常（getter 抛错、循环引用、栈溢出等）都被吞掉并降级为占位符——
 * 只损失对应字段，不影响业务方法。</p>
 */
public class DefaultOperateLogSerializer implements OperateLogSerializer {
    private static final String UNSERIALIZABLE = "<UNSERIALIZABLE>";

    private final ObjectMapper objectMapper;

    public DefaultOperateLogSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        String serialized = writeQuietly(value);
        return serialized == null ? UNSERIALIZABLE : serialized;
    }

    /** 优先整体序列化；失败则逐元素降级，坏元素换成占位符。 */
    @Override
    public String serializeArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return serialize(arguments);
        }
        String whole = writeQuietly(arguments);
        if (whole != null) {
            return whole;
        }
        StringBuilder sb = new StringBuilder(arguments.length * 32);
        sb.append('[');
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String element = writeQuietly(arguments[i]);
            if (element == null) {
                sb.append("\"<UNSERIALIZABLE:")
                        .append(arguments[i].getClass().getSimpleName())
                        .append(">\"");
            } else {
                sb.append(element);
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /** 静默序列化：成功返回 JSON，任何失败返回 {@code null} 交由调用方降级。 */
    private String writeQuietly(Object value) {
        try {
            return this.objectMapper.writeValueAsString(value);
        } catch (Exception | StackOverflowError ex) {
            return null;
        }
    }
}
