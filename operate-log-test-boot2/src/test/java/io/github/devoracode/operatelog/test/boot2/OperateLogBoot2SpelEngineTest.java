package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.spel.DefaultSpelEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * {@code enabled=false} 时三个入口全部直通。
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

    @Test
    void disabledEnginePassesThroughWithoutEvaluation() {
        DefaultSpelEngine engine = new DefaultSpelEngine(false);
        OperateLogContext context = emptyContext();
        assertNull(engine.evaluate("'v'", context));
        assertEquals("查询用户 #{#userId}", engine.evaluateTemplate("查询用户 #{#userId}", context));
        assertTrue(engine.evaluateBoolean("1 +", context));
    }
}
