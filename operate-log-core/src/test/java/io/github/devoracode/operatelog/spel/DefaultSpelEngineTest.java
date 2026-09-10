package io.github.devoracode.operatelog.spel;

import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.testsupport.Samples;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultSpelEngine} 单测：变量求值、模板语法、降级语义、开关直通、LRU 缓存。
 *
 * @author devoracode
 */
class DefaultSpelEngineTest {

    private final DefaultSpelEngine engine = new DefaultSpelEngine(64);

    private OperateLogContext successContext() throws Exception {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        OperateLogContext ctx = Samples.contextOf(m, new Samples.Demo(), new Object[]{"42"});
        ctx.setResult("ok-42");
        ctx.setSuccess(true);
        ctx.setCostTime(7L);
        ctx.setTraceId("tid-1");
        ctx.setOperator(Operator.builder().userId("u1").build());
        return ctx;
    }

    // ==================== 变量求值 ====================

    @Test
    void evaluatesPositionalAndNamedArgs() throws Exception {
        OperateLogContext ctx = successContext();
        assertEquals("42", engine.evaluate("#p0", ctx));
        assertEquals("42", engine.evaluate("#a0", ctx));
        assertEquals("42", engine.evaluate("#userId", ctx));
    }

    @Test
    void evaluatesRuntimeVariables() throws Exception {
        OperateLogContext ctx = successContext();
        assertEquals("ok-42", engine.evaluate("#result", ctx));
        assertEquals(Boolean.TRUE, engine.evaluate("#success", ctx));
        assertEquals(7L, engine.evaluate("#costTime", ctx));
        assertEquals("tid-1", engine.evaluate("#traceId", ctx));
        assertEquals("u1", engine.evaluate("#operator.userId", ctx));
        assertNull(engine.evaluate("#error", ctx));
        assertNull(engine.evaluate("#http", ctx));
    }

    @Test
    void evaluatesAnnotationAndContextVariables() throws Exception {
        OperateLogContext ctx = successContext();
        assertEquals("user", engine.evaluate("#annotation.module()", ctx));
        assertEquals(ctx, engine.evaluate("#context", ctx));
    }

    // ==================== 模板语法 ====================

    @Test
    void evaluatesTemplateMixingLiteralAndSpel() throws Exception {
        OperateLogContext ctx = successContext();
        assertEquals("查询用户 42",
                engine.evaluateTemplate("查询用户 #{#userId}", ctx));
        assertEquals("plain text", engine.evaluateTemplate("plain text", ctx));
        assertEquals("", engine.evaluateTemplate("", ctx));
        assertNull(engine.evaluateTemplate(null, ctx));
    }

    // ==================== 布尔条件 ====================

    @Test
    void blankConditionPasses() throws Exception {
        assertTrue(engine.evaluateBoolean("", successContext()));
        assertTrue(engine.evaluateBoolean(null, successContext()));
    }

    @Test
    void evaluatesBooleanCondition() throws Exception {
        OperateLogContext ctx = successContext();
        assertTrue(engine.evaluateBoolean("#success", ctx));
        assertFalse(engine.evaluateBoolean("#costTime > 1000", ctx));

        ctx.setError(new RuntimeException("x"));
        ctx.setSuccess(false);
        assertTrue(engine.evaluateBoolean("#error != null", ctx));
        assertFalse(engine.evaluateBoolean("#success", ctx));
    }

    // ==================== 降级语义（不抛异常） ====================

    @Test
    void brokenExpressionsDegradeInsteadOfThrowing() throws Exception {
        OperateLogContext ctx = successContext();
        // 语法错误
        assertNull(engine.evaluate("1 +", ctx));
        assertEquals("模板 #{#n +}", engine.evaluateTemplate("模板 #{#n +}", ctx));
        assertTrue(engine.evaluateBoolean("(((", ctx));
        // 空指针属性访问
        assertNull(engine.evaluate("#userId.nope.nope", ctx));
        assertTrue(engine.evaluateBoolean("#missing.deep", ctx));
    }

    @Test
    void disabledEnginePassesThrough() throws Exception {
        DefaultSpelEngine off = new DefaultSpelEngine(64, false);
        OperateLogContext ctx = successContext();
        assertNull(off.evaluate("#userId", ctx));
        assertEquals("查询用户 #{#userId}", off.evaluateTemplate("查询用户 #{#userId}", ctx));
        assertTrue(off.evaluateBoolean("#success == false", ctx));
    }

    // ==================== LRU 缓存 ====================

    @Test
    void cacheIsBoundedAndEvicts() throws Exception {
        OperateLogContext ctx = successContext();
        for (int i = 0; i < 300; i++) {
            assertEquals("v" + i, engine.evaluate("'v' + " + i, ctx));
        }
        java.util.Map<?, ?> cache = cacheOf(engine);
        synchronized (cache) {
            assertTrue(cache.size() <= 64,
                    "cache must stay bounded by configured size, was " + cache.size());
        }
        // 被淘汰的表达式重新求值仍正确（miss 后重解析）
        assertEquals("42", engine.evaluate("#userId", ctx));
    }

    @Test
    void cacheSizeFloorsAt64() throws Exception {
        DefaultSpelEngine tiny = new DefaultSpelEngine(1);
        OperateLogContext ctx = successContext();
        for (int i = 0; i < 100; i++) {
            tiny.evaluate("#userId + " + i, ctx);
        }
        Map<?, ?> cache = cacheOf(tiny);
        synchronized (cache) {
            assertEquals(64, cache.size());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cacheOf(DefaultSpelEngine engine) throws Exception {
        Field field = DefaultSpelEngine.class.getDeclaredField("expressionCache");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(engine);
    }
}
