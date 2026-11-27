package io.github.devoracode.operatelog.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Writer;

/**
 * 默认序列化器：用 Jackson {@link ObjectMapper} 把对象写成 JSON 文本。
 * 需要自定义日期格式、命名策略等时，注册自己的 {@link OperateLogSerializer} bean 覆盖。
 *
 * <p>失败语义：任何序列化异常（getter 抛错、循环引用、栈溢出等）都被吞掉并降级为占位符——
 * 只损失对应字段，不影响业务方法。</p>
 */
public class DefaultOperateLogSerializer implements OperateLogSerializer {
    private static final String UNSERIALIZABLE = "<UNSERIALIZABLE>";
    private static final String LIMIT_REACHED = "<SERIALIZATION_LIMIT>";
    private static final String LIMITED_ARGUMENTS = "[\"<SERIALIZATION_LIMIT>\"]";

    private final ObjectMapper objectMapper;
    private final int maxSerializationLength;

    public DefaultOperateLogSerializer(ObjectMapper objectMapper) {
        this(objectMapper, 0);
    }

    public DefaultOperateLogSerializer(ObjectMapper objectMapper, int maxSerializationLength) {
        this.objectMapper = objectMapper;
        this.maxSerializationLength = maxSerializationLength;
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        String serialized = writeQuietly(value, this.maxSerializationLength);
        return serialized == null || LIMIT_REACHED.equals(serialized) ? UNSERIALIZABLE : serialized;
    }

    @Override
    public String serializeArguments(Object[] arguments) {
        if (arguments == null) {
            return null;
        }
        String whole = writeQuietly(arguments, this.maxSerializationLength);
        if (LIMIT_REACHED.equals(whole)) {
            return LIMITED_ARGUMENTS;
        }
        if (whole != null) {
            return whole;
        }
        if (arguments.length == 0) {
            return UNSERIALIZABLE;
        }
        StringBuilder sb = new StringBuilder(Math.min(arguments.length, 8) * 32);
        sb.append('[');
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object argument = arguments[i];
            // 逐元素降级仍按整组计费；预留结尾的 ]。
            int remaining = this.maxSerializationLength > 0
                    ? this.maxSerializationLength - sb.length() - 1 : 0;
            if (this.maxSerializationLength > 0 && remaining <= 0) {
                return LIMITED_ARGUMENTS;
            }
            String element = argument == null ? "null" : writeQuietly(argument, remaining);
            if (LIMIT_REACHED.equals(element)) {
                return LIMITED_ARGUMENTS;
            }
            if (element == null) {
                element = "\"<UNSERIALIZABLE:" + argument.getClass().getSimpleName() + ">\"";
            }
            if (remaining > 0 && element.length() > remaining) {
                return LIMITED_ARGUMENTS;
            }
            sb.append(element);
        }
        sb.append(']');
        return sb.toString();
    }

    private String writeQuietly(Object value, int limit) {
        BoundedWriter writer = limit > 0 ? new BoundedWriter(limit) : null;
        try {
            if (writer == null) {
                return this.objectMapper.writeValueAsString(value);
            }
            this.objectMapper.writeValue(writer, value);
            return writer.exceeded ? LIMIT_REACHED : writer.buffer.toString();
        } catch (Exception | StackOverflowError ex) {
            return writer != null && writer.exceeded ? LIMIT_REACHED : null;
        }
    }

    private static final class BoundedWriter extends Writer {
        private final int limit;
        private final StringBuilder buffer;
        private boolean exceeded;

        private BoundedWriter(int limit) {
            this.limit = limit;
            this.buffer = new StringBuilder(Math.min(limit, 256));
        }

        @Override
        public void write(int character) throws IOException {
            requireCapacity(1);
            this.buffer.append((char) character);
        }

        @Override
        public void write(char[] characters, int offset, int length) throws IOException {
            requireCapacity(length);
            this.buffer.append(characters, offset, length);
        }

        @Override
        public void write(String value, int offset, int length) throws IOException {
            requireCapacity(length);
            this.buffer.append(value, offset, offset + length);
        }

        private void requireCapacity(int length) throws IOException {
            if (this.exceeded || length > this.limit - this.buffer.length()) {
                this.exceeded = true;
                throw new IOException("Operation log serialization budget exhausted");
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
