package io.github.devoracode.operatelog.test.boot3;

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

/**
 * SpEL 缓存回归：定容 {@link java.util.concurrent.ConcurrentHashMap} 在写满与并发下仍求值正确。
 */
class OperateLogBoot3SpelCacheTest {

    private static OperateLogContext emptyContext() {
        return new OperateLogContext(null, null, null, null, new Object[0]);
    }

    @Test
    void evaluationStaysCorrectAfterCacheSaturates() {
        DefaultSpelEngine engine = new DefaultSpelEngine(64, true);
        OperateLogContext context = emptyContext();
        // 写满容量后再求值：缓存满后不再放入，但重复解析不应影响结果
        for (int i = 0; i < 200; i++) {
            assertEquals("v" + i, engine.evaluate("'v" + i + "'", context));
        }
        for (int i = 0; i < 200; i++) {
            assertEquals("v" + i, engine.evaluate("'v" + i + "'", context));
        }
    }

    @Test
    void concurrentEvaluationReturnsCorrectResults() throws Exception {
        DefaultSpelEngine engine = new DefaultSpelEngine(64, true);
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
        DefaultSpelEngine engine = new DefaultSpelEngine(64, true);
        OperateLogContext context = emptyContext();
        assertNotNull(engine.evaluateTemplate("no-placeholder", context));
        assertEquals("no-placeholder", engine.evaluateTemplate("no-placeholder", context));
        // 语法错误按方向降级：不抛异常，业务链路不受影响
        engine.evaluate("1 +", context);
    }
}
