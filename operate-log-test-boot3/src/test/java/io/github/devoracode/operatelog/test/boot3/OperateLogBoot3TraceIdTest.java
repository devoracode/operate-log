package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * traceId 只透传 MDC：{@code resolveTraceId} 只读不写，MDC 无值时返回 null。
 *
 * <p>MDC 读取异常的降级路径需要替换 SLF4J 全局 MDCAdapter 才能复现，因此本用例依赖 SLF4J
 * 内部结构：1.7.x 字段名 {@code mdcAdapter} 且直接读取，2.0.x 为 {@code MDC_ADAPTER}，且首次
 * {@code LoggerFactory} 初始化时 {@code bind()} 会用 provider 的 adapter 覆写该字段——
 * 注入必须排在初始化之后，否则会被静默冲掉，故障根本触发不了（断言照样红，但测的不是降级路径）。
 * 按名探测 + 装后回读，装不上就跳过（而非失败），SLF4J 结构再变时用例不会变成假红。</p>
 */
class OperateLogBoot3TraceIdTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String[] ADAPTER_FIELD_NAMES = {"MDC_ADAPTER", "mdcAdapter"};

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void passesThroughExistingMdcValue() throws Exception {
        MDC.put(TRACE_ID_KEY, "upstream-trace");
        assertEquals("upstream-trace", resolve());
    }

    @Test
    void returnsNullAndWritesNothingWhenMdcAbsent() throws Exception {
        assertNull(resolve());
        assertNull(MDC.get(TRACE_ID_KEY), "只读不写：MDC 无值时不得回写自造值");
    }

    @Test
    void blankMdcValueIsTreatedAsAbsent() throws Exception {
        MDC.put(TRACE_ID_KEY, "   ");
        assertNull(resolve());
    }

    @Test
    void mdcReadFailureDegradesToNullWithoutEscaping() throws Exception {
        LoggerFactory.getILoggerFactory();
        Field adapterField = locateAdapterField();
        Assumptions.assumeTrue(adapterField != null, "SLF4J MDC adapter field not found; skip");
        Object original = adapterField.get(null);
        ThrowingGetAdapter probe = new ThrowingGetAdapter((MDCAdapter) original);
        adapterField.set(null, probe);
        boolean installed = MDC.getMDCAdapter() == probe;
        adapterField.set(null, original);
        Assumptions.assumeTrue(installed, "SLF4J MDC adapter cannot be overridden on this version; skip");
        adapterField.set(null, probe);
        try {
            // 日志侧故障绝不允许逃逸到业务调用栈
            assertNull(resolve(), "MDC 读取抛异常时必须降级为 null");
        } finally {
            adapterField.set(null, original);
            MDC.clear();
        }
    }

    private static String resolve() throws Exception {
        OperateLogAspect aspect = new OperateLogAspect(null, null, null, null, null, null,
                null, null, null, false, null, TRACE_ID_KEY, 64);
        Method method = OperateLogAspect.class.getDeclaredMethod("resolveTraceId");
        method.setAccessible(true);
        return (String) method.invoke(aspect);
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
     * 只让 traceId 的读取抛异常，其余 key 透传原 adapter。
     */
    private static final class ThrowingGetAdapter implements MDCAdapter {
        private final MDCAdapter delegate;

        private ThrowingGetAdapter(MDCAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String key, String val) {
            this.delegate.put(key, val);
        }

        @Override
        public String get(String key) {
            if (TRACE_ID_KEY.equals(key)) {
                throw new IllegalStateException("simulated MDC read failure");
            }
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
