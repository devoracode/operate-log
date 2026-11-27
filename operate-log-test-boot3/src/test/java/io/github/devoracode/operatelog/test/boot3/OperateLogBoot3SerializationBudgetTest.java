package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.boot.OperateLogAutoConfiguration;
import io.github.devoracode.operatelog.serializer.OperateLogSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperateLogBoot3SerializationBudgetTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OperateLogAutoConfiguration.class))
            .withPropertyValues("operate-log.payload.max-serialization-length=128");

    @Test
    void nestedLargeValueStopsSerializationBeforeTheNextGetter() {
        this.runner.run(context -> {
            OperateLogSerializer serializer = context.getBean(OperateLogSerializer.class);
            AtomicInteger laterReads = new AtomicInteger();
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("bytes", new byte[32 * 1024]);
            nested.put("later", new GetterProbe(laterReads));

            assertEquals("<UNSERIALIZABLE>", serializer.serialize(nested));
            assertEquals(0, laterReads.get(), "不继续读取超预算值之后的属性");
            assertEquals("\"ok\"", serializer.serialize("ok"));

            AtomicInteger priorReads = new AtomicInteger();
            assertEquals("[\"<SERIALIZATION_LIMIT>\"]", serializer.serializeArguments(
                    new Object[]{new GetterProbe(priorReads), nested}));
            assertEquals(1, priorReads.get(), "超预算不逐元素重试");
            assertEquals(0, laterReads.get());
        });
    }

    @Test
    void perElementFallbackKeepsTheBudgetForTheWholeArray() {
        this.runner.run(context -> {
            OperateLogSerializer serializer = context.getBean(OperateLogSerializer.class);
            UnserializableHolder broken = new UnserializableHolder();
            assertEquals("[\"<UNSERIALIZABLE:UnserializableHolder>\",\"ok\"]",
                    serializer.serializeArguments(new Object[]{broken, "ok"}));
            String medium = new String(new char[64]).replace('\0', 'x');
            assertEquals("[\"<SERIALIZATION_LIMIT>\"]",
                    serializer.serializeArguments(new Object[]{broken, medium, medium}));
        });
    }

    private static final class GetterProbe {
        private final AtomicInteger reads;

        private GetterProbe(AtomicInteger reads) {
            this.reads = reads;
        }

        public int getValue() {
            return this.reads.incrementAndGet();
        }
    }
}
