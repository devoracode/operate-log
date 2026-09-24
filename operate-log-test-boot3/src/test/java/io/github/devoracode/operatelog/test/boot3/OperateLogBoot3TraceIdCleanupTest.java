package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * traceId 半写降级回归：{@code MDC.put} 写入生效后才抛异常时，降级路径必须清掉半写值。
 *
 * <p>该故障只能通过替换 SLF4J 全局 MDCAdapter 复现，因此本用例依赖 SLF4J 内部结构：
 * 1.7.x 字段名 {@code mdcAdapter} 且直接读取，2.0.x 为 {@code MDC_ADAPTER}，且首次
 * {@code LoggerFactory} 初始化时 {@code bind()} 会用 provider 的 adapter 覆写该字段——
 * 注入必须排在初始化之后，否则会被静默冲掉，故障根本触发不了（断言照样红，但测的不是降级路径）。
 * 按名探测 + 装后回读，装不上就跳过（而非失败），SLF4J 结构再变时用例不会变成假红。</p>
 */
class OperateLogBoot3TraceIdCleanupTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String[] ADAPTER_FIELD_NAMES = {"MDC_ADAPTER", "mdcAdapter"};

    @Test
    void halfWrittenTraceIdIsClearedOnDegradedPath() throws Exception {
        LoggerFactory.getILoggerFactory();
        Field adapterField = locateAdapterField();
        Assumptions.assumeTrue(adapterField != null, "SLF4J MDC adapter field not found; skip");
        Object original = adapterField.get(null);
        HalfWritingAdapter probe = new HalfWritingAdapter((MDCAdapter) original);
        adapterField.set(null, probe);
        boolean installed = MDC.getMDCAdapter() == probe;
        adapterField.set(null, original);
        Assumptions.assumeTrue(installed, "SLF4J MDC adapter cannot be overridden on this version; skip");
        adapterField.set(null, probe);
        try {
            Object traceId = invokeResolveTraceId();

            assertNotNull(traceId, "降级路径必须仍产出 traceId，不能让审计关联断掉");
            assertNull(MDC.get(TRACE_ID_KEY), "traceId 半写值泄漏到 MDC");
        } finally {
            adapterField.set(null, original);
            MDC.clear();
        }
    }

    private static Object invokeResolveTraceId() throws Exception {
        OperateLogAspect aspect = new OperateLogAspect(null, null, null, null, null, null,
                null, null, null, false, null, TRACE_ID_KEY, 64);
        Method method = OperateLogAspect.class.getDeclaredMethod("resolveTraceId");
        method.setAccessible(true);
        return method.invoke(aspect);
    }

    private static Field locateAdapterField() {
        for (String name : ADAPTER_FIELD_NAMES) {
            try {
                Field field = MDC.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 试下一个名字：两栈字段名不同
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 只对 traceId 做「先写入再抛」，其余 key 透传原 adapter。
     */
    private static final class HalfWritingAdapter implements MDCAdapter {
        private final MDCAdapter delegate;

        private HalfWritingAdapter(MDCAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String key, String val) {
            this.delegate.put(key, val);
            if (TRACE_ID_KEY.equals(key)) {
                throw new IllegalStateException("simulated MDC put failure after write");
            }
        }

        @Override
        public String get(String key) {
            return this.delegate.get(key);
        }

        @Override
        public void remove(String key) {
            this.delegate.remove(key);
        }

        @Override
        public void clear() {
            this.delegate.clear();
        }

        @Override
        public Map<String, String> getCopyOfContextMap() {
            return this.delegate.getCopyOfContextMap();
        }

        @Override
        public void setContextMap(Map<String, String> contextMap) {
            this.delegate.setContextMap(contextMap);
        }

        // SLF4J 2.0 的 MDCAdapter 新增 4 个抽象方法（1.7.x 接口无），不带 @Override
        public void pushByKey(String key, String value) {
            // 本用例不覆盖双端队列语义
        }

        public String popByKey(String key) {
            return null;
        }

        public Deque<String> getCopyOfDequeByKey(String key) {
            return null;
        }

        public void clearDequeByKey(String key) {
            // 无操作
        }
    }
}
