package io.github.devoracode.operatelog.boot;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.util.Iterator;
import java.util.stream.BaseStream;

final class SingleUseValueModule extends SimpleModule {
    private static final long serialVersionUID = 1L;

    SingleUseValueModule() {
        super("operate-log-single-use-values");
        addSerializer(Iterator.class, placeholder(Iterator.class));
        addSerializer(BaseStream.class, placeholder(BaseStream.class));
    }

    private static JsonSerializer<Object> placeholder(Class<?> ignoredType) {
        String marker = "<IGNORED:" + ignoredType.getSimpleName() + ">";
        return new JsonSerializer<Object>() {
            @Override
            public void serialize(Object value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeString(marker);
            }
        };
    }
}
