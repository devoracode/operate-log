package io.github.devoracode.operatelog.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

/**
 * 基于 Jackson 的操作日志序列化器。
 *
 * <p>失败语义：任何序列化异常（getter 抛异常、循环引用、无法识别的类型、
 * 栈溢出等）都被吞掉并降级为占位符，绝不向调用方传播——日志序列化失败
 * 只损失对应字段，不损失整条记录，更不影响业务方法。</p>
 *
 * @author devoracode
 */
@RequiredArgsConstructor
public class JacksonOperateLogSerializer implements OperateLogSerializer {
    private static final String UNSERIALIZABLE = "<UNSERIALIZABLE>";
    private final ObjectMapper objectMapper;

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        String serialized = writeQuietly(value);
        return serialized == null ? UNSERIALIZABLE : serialized;
    }

    /**
     * 参数数组专用：优先整体序列化；失败时逐元素降级序列化，
     * 单个坏元素替换为 {@code <UNSERIALIZABLE:类型名>} 占位，其余元素照常记录。
     */
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

    /**
     * 静默序列化：成功返回 JSON，任何失败返回 {@code null} 由调用方降级。
     *
     * <p>捕获范围从 {@code JsonProcessingException} 扩大到 {@code Exception} 与
     * {@code StackOverflowError}：业务 getter 抛出的任意异常、Bean 循环引用
     * 都不应穿透到日志主流程。</p>
     */
    private String writeQuietly(Object value) {
        try {
            return this.objectMapper.writeValueAsString(value);
        } catch (Exception | StackOverflowError ex) {
            return null;
        }
    }
}
