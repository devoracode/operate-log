package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.spel.DefaultSpelEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpEL 引擎回归：默认开启时每次调用重新解析表达式，并发共用一个解析器仍求值正确；
 * 越界表达式求值失败并降级；{@code enabled=false} 时三个入口全部直通。
 */
class OperateLogBoot2SpelEngineTest {

    private static OperateLogContext emptyContext() {
        return new OperateLogContext(null, null, null, null, new Object[0]);
    }

    @Test
    void concurrentEvaluationReturnsCorrectResults() throws Exception {
        DefaultSpelEngine engine = new DefaultSpelEngine(true);
        int threads = 8;
        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    OperateLogContext context = emptyContext();
                    for (int i = 0; i < iterations; i++) {
                        String value = "c" + (i % 32);
                        assertEquals(value, engine.evaluate("'" + value + "'", context));
                    }
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void templatesAndDegradationUnaffected() {
        DefaultSpelEngine engine = new DefaultSpelEngine(true);
        OperateLogContext context = emptyContext();
        assertNotNull(engine.evaluateTemplate("no-placeholder", context));
        assertEquals("no-placeholder", engine.evaluateTemplate("no-placeholder", context));
        // 语法错误按方向降级：不抛异常，业务链路不受影响
        engine.evaluate("1 +", context);
    }

    /**
     * 越界表达式必须求值失败并降级，不能执行成功。钉的是 README 与 {@link DefaultSpelEngine}
     * javadoc 描述的边界：{@code T(...)} / {@code new} / {@code @bean} 没有对应解析器，而
     * {@code #p0.class} 这条反射链走不通靠的是 Spring 过滤掉 {@link Object} 声明的方法。
     * 本组件没有任何 AST 守卫——宿主升级 Spring 若放宽那条过滤，这里就会变红。
     */
    @Test
    void sandboxBlocksTypeAndReflectionEscapes() {
        DefaultSpelEngine engine = new DefaultSpelEngine(true);
        OperateLogContext context = new OperateLogContext(null, null, null, null,
                new Object[]{"a-value"});
        List<String> escapes = Arrays.asList(
                "T(java.lang.Runtime)",
                "T(java.lang.Runtime).getRuntime()",
                "@java.lang.System",
                "new java.lang.String('x')",
                "#p0.class",
                "#p0.getClass()",
                "#p0.class.forName('java.lang.Runtime')",
                "#p0.class.classLoader");
        for (String escape : escapes) {
            assertNull(engine.evaluate(escape, context), "越界表达式不得求值成功: " + escape);
        }
        // 条件入口按同一方向降级：失败视为通过（宁可多记不可漏记），不抛异常
        assertTrue(engine.evaluateBoolean("#p0.class != null", context));
    }

    @Test
    void disabledEnginePassesThroughWithoutEvaluation() {
        DefaultSpelEngine engine = new DefaultSpelEngine(false);
        OperateLogContext context = emptyContext();
        assertNull(engine.evaluate("'v'", context));
        assertEquals("查询用户 #{#userId}", engine.evaluateTemplate("查询用户 #{#userId}", context));
        assertTrue(engine.evaluateBoolean("1 +", context));
    }
}
