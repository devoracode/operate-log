package io.github.devoracode.operatelog.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

/**
 * 基于 Jackson 的操作日志序列化器。
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

        try {
            return this.objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            return UNSERIALIZABLE;
        }
    }
}
