package io.github.devoracode.operatelog.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import io.github.devoracode.operatelog.handler.OperateLogHandler;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import io.github.devoracode.operatelog.model.OperateType;
import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
import io.github.devoracode.operatelog.sanitizer.JacksonSensitiveDataMasker;
import io.github.devoracode.operatelog.serializer.JacksonOperateLogSerializer;
import io.github.devoracode.operatelog.spel.DefaultSpelEngine;
import io.github.devoracode.operatelog.spel.SpelEngine;
import io.github.devoracode.operatelog.testsupport.Samples;
import io.github.devoracode.operatelog.testsupport.StubJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OperateLogAspect} 单测：字段组装、recordOn/condition 过滤、注解查找链、
 * 异常隔离、脱敏/截断/忽略类型管线、extra 通道与 traceId 解析。
 *
 * <p>不经过 Spring 代理，直接以 {@link StubJoinPoint} 驱动 {@code around(...)}，
 * 隔离 AOP 织入因素，专注切面自身逻辑。</p>
 *
 * @author devoracode
 */
class OperateLogAspectTest {

    private final List<OperateLogRecord> records = new ArrayList<OperateLogRecord>();
    private final ObjectMapper mapper = new ObjectMapper();
    private RuntimeException handlerFailure;
    private final OperateLogHandler capture = record -> {
        this.records.add(record);
        if (this.handlerFailure != null) {
            throw this.handlerFailure;
        }
    };

    @AfterEach
    void cleanUp() {
        MDC.clear();
        OperateLogContextHolder.unbind();
    }

    private OperateLogAspect aspect() {
        return aspect("traceId");
    }

    private OperateLogAspect aspect(String mdcKey) {
        return new OperateLogAspect(this.capture,
                () -> Operator.builder().userId("u9").userAccount("acct").userName("Nick").build(),
                () -> HttpContext.builder()
                        .method("GET").url("http://h/api").uri("/api").query("q=1")
                        .ip("1.2.3.4").userAgent("junit").status(200)
                        .headers(Collections.singletonMap("X-Test", "1"))
                        .build(),
                new JacksonOperateLogSerializer(this.mapper),
                new JacksonSensitiveDataMasker(this.mapper,
                        new HashSet<String>(Arrays.asList("password")), null),
                new DefaultSpelEngine(64),
                "demo-app", "junit", "9.9.9", true,
                new PayloadPolicy(0, 0, 0, Collections.<String>emptySet()),
                mdcKey);
    }

    // ==================== 成功记录全字段 ====================

    @Test
    void successRecordPopulatesAllFields() throws Throwable {
        Samples.Demo demo = new Samples.Demo();
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        Object result = aspect().around(StubJoinPoint.returning(m, demo, new Object[]{"42"}, "ok-42"));

        assertEquals("ok-42", result);
        assertEquals(1, this.records.size());
        OperateLogRecord r = this.records.get(0);
        assertEquals("user", r.getModule());
        assertEquals("query", r.getOperation());
        assertEquals(OperateType.QUERY, r.getOperationType());
        assertEquals("查询用户 42", r.getDescription());
        assertEquals("42", r.getBusinessId());
        assertTrue(r.isSuccess());
        assertTrue(r.getCostTime() >= 0);
        assertNotNull(r.getStartTime());
        assertNotNull(r.getEndTime());
        assertEquals("demo-app", r.getApplication());
        assertEquals("junit", r.getEnvironment());
        assertEquals("9.9.9", r.getVersion());
        assertNotNull(r.getId());
        // operator
        assertEquals("u9", r.getOperatorUserId());
        assertEquals("acct", r.getOperatorUserAccount());
        assertEquals("Nick", r.getOperatorUserName());
        // http（resolver 提供）
        assertEquals("GET", r.getRequestMethod());
        assertEquals("http://h/api", r.getRequestUrl());
        assertEquals("/api", r.getRequestUri());
        assertEquals("q=1", r.getRequestQuery());
        assertEquals(Integer.valueOf(200), r.getHttpStatus());
        assertEquals("1.2.3.4", r.getClientIp());
        assertEquals("junit", r.getUserAgent());
        assertTrue(r.getRequestHeaders().contains("X-Test"), r.getRequestHeaders());
        // payload：recordRequest 默认 true、recordResponse 默认 false
        assertEquals("[\"42\"]", r.getRequestBody());
        assertNull(r.getResponseBody());
        // 无错、无 extra
        assertNull(r.getErrorType());
        assertNull(r.getErrorMessage());
        assertNull(r.getErrorStack());
        assertNull(r.getExtra());
    }

    // ==================== 失败路径与过滤 ====================

    @Test
    void errorRecordPopulatesErrorFieldsAndRethrows() {
        Samples.Demo demo = new Samples.Demo();
        Method m = Samples.method(Samples.Demo.class, "refund", int.class);
        IllegalStateException boom = new IllegalStateException("boom-7");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> aspect().around(StubJoinPoint.throwing(m, demo, new Object[]{7}, boom)));

        assertSame(boom, thrown, "business exception must pass through untouched");
        assertEquals(1, this.records.size());
        OperateLogRecord r = this.records.get(0);
        assertFalse(r.isSuccess());
        assertEquals("java.lang.IllegalStateException", r.getErrorType());
        assertEquals("boom-7", r.getErrorMessage());
        assertTrue(r.getErrorStack().contains("boom-7"));
    }

    @Test
    void recordOnSuccessFiltersOutFailures() throws Throwable {
        Samples.Demo demo = new Samples.Demo();
        Method m = Samples.method(Samples.Demo.class, "write", String.class);
        RuntimeException boom = new RuntimeException("nope");
        assertThrows(RuntimeException.class,
                () -> aspect().around(StubJoinPoint.throwing(m, demo, new Object[]{"p"}, boom)));
        assertTrue(this.records.isEmpty(), "recordOn=SUCCESS must skip failure");

        // 正常返回则记录
        aspect().around(StubJoinPoint.returning(m, demo, new Object[]{"p"}, "written"));
        assertEquals(1, this.records.size());
    }

    @Test
    void conditionFalseSkipsRecord() {
        Samples.Demo demo = new Samples.Demo();
        Method m = Samples.method(Samples.Demo.class, "cancel", String.class);
        RuntimeException boom = new RuntimeException("cancelled-path-fail");
        // condition="#success"：失败路径下条件为 false → 不记录，但异常照常上抛
        assertThrows(RuntimeException.class,
                () -> aspect().around(StubJoinPoint.throwing(m, demo, new Object[]{"o1"}, boom)));
        assertTrue(this.records.isEmpty());
    }

    // ==================== 注解查找链 ====================

    @Test
    void interfaceOnlyAnnotationIsFoundOnJdkProxyStyleInvocation() throws Throwable {
        Samples.GreeterImpl impl = new Samples.GreeterImpl();
        // 调用方法为实现方法（本身无注解）——走接口查找链
        Method implMethod = Samples.method(Samples.GreeterImpl.class, "greet", String.class);
        aspect().around(StubJoinPoint.returning(implMethod, impl, new Object[]{"bob"}, "hi-bob"));

        assertEquals(1, this.records.size());
        assertEquals("iface", this.records.get(0).getModule());
        assertEquals("bob", this.records.get(0).getBusinessId());
    }

    @Test
    void classLevelAnnotationMergesFieldByField() throws Throwable {
        Samples.ClassLevel target = new Samples.ClassLevel();
        Method merged = Samples.method(Samples.ClassLevel.class, "merged", String.class);
        aspect().around(StubJoinPoint.returning(merged, target, new Object[]{"x"}, "x"));
        assertEquals(1, this.records.size());
        // 方法级只给 operation，其余继承类级
        assertEquals("cls", this.records.get(0).getModule());
        assertEquals("methodOp", this.records.get(0).getOperation());
        assertEquals(OperateType.UPDATE, this.records.get(0).getOperationType());
    }

    @Test
    void classLevelOnlyMethodUsesClassConfig() throws Throwable {
        Samples.ClassLevel target = new Samples.ClassLevel();
        Method inheritOnly = Samples.method(Samples.ClassLevel.class, "inheritOnly", String.class);
        aspect().around(StubJoinPoint.returning(inheritOnly, target, new Object[]{"y"}, "y"));
        assertEquals(1, this.records.size());
        assertEquals("clsOp", this.records.get(0).getOperation());
    }

    @Test
    void methodWithoutAnnotationProceedsWithoutRecord() throws Throwable {
        Samples.Demo demo = new Samples.Demo();
        Method plain = Samples.method(Samples.Demo.class, "plain");
        assertNull(aspect().around(StubJoinPoint.returning(plain, demo, new Object[0], null)));
        assertTrue(this.records.isEmpty());
    }

    // ==================== 隔离纪律 ====================

    @Test
    void handlerFailureNeverPropagates() throws Throwable {
        this.handlerFailure = new IllegalStateException("kafka down");
        Samples.Demo demo = new Samples.Demo();
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        Object result = aspect().around(StubJoinPoint.returning(m, demo, new Object[]{"42"}, "ok-42"));
        assertEquals("ok-42", result, "handler explosion must not affect business result");
    }

    @Test
    void explodingSpelEngineOnlyLosesTheLog() throws Throwable {
        SpelEngine alwaysExplodes = new SpelEngine() {
            @Override
            public Object evaluate(String expression, OperateLogContext context) {
                throw new IllegalStateException("parser down");
            }

            @Override
            public String evaluateTemplate(String template, OperateLogContext context) {
                throw new IllegalStateException("parser down");
            }

            @Override
            public boolean evaluateBoolean(String expression, OperateLogContext context) {
                throw new IllegalStateException("parser down");
            }
        };
        OperateLogAspect a = new OperateLogAspect(this.capture, () -> null, () -> null,
                new JacksonOperateLogSerializer(this.mapper), null, alwaysExplodes,
                "app", "env", "v", false,
                new PayloadPolicy(0, 0, 0, Collections.<String>emptySet()), "traceId");
        Samples.Demo demo = new Samples.Demo();
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        assertEquals("ok-42", a.around(StubJoinPoint.returning(m, demo, new Object[]{"42"}, "ok-42")));
        assertTrue(this.records.isEmpty(), "record assembly failure only loses this log");
    }

    @Test
    void operatorResolutionFailureDegradesToNullOperator() throws Throwable {
        OperateLogAspect a = new OperateLogAspect(this.capture,
                () -> {
                    throw new IllegalStateException("auth service down");
                },
                () -> null,
                new JacksonOperateLogSerializer(this.mapper), null, new DefaultSpelEngine(64),
                "app", "env", "v", false,
                new PayloadPolicy(0, 0, 0, Collections.<String>emptySet()), "traceId");
        Samples.Demo demo = new Samples.Demo();
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        a.around(StubJoinPoint.returning(m, demo, new Object[]{"42"}, "ok-42"));
        assertEquals(1, this.records.size());
        assertNull(this.records.get(0).getOperatorUserId());
        assertNull(this.records.get(0).getRequestUrl(), "null HttpContext must degrade cleanly");
    }

    // ==================== 管线：脱敏 / 截断 / 忽略类型 ====================

    @Test
    void maskingAppliedWhenEnabledAndSkippedWhenDisabled() throws Throwable {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        Samples.Demo demo = new Samples.Demo();

        AtomicInteger calls = new AtomicInteger();
        io.github.devoracode.operatelog.sanitizer.SensitiveDataMasker countingMasker = value -> {
            calls.incrementAndGet();
            return value == null ? null : value + "|masked";
        };
        OperateLogAspect on = new OperateLogAspect(this.capture, () -> null, () -> null,
                new JacksonOperateLogSerializer(this.mapper), countingMasker, new DefaultSpelEngine(64),
                "app", "env", "v", true,
                new PayloadPolicy(0, 0, 0, Collections.<String>emptySet()), "traceId");
        on.around(StubJoinPoint.returning(m, demo, new Object[]{"42"}, "ok-42"));
        assertTrue(calls.get() > 0);
        assertTrue(this.records.get(0).getRequestBody().endsWith("|masked"));

        // maskEnabled=false：masker 一次都不该被调用（零开销）
        OperateLogAspect off = new OperateLogAspect(this.capture, () -> null, () -> null,
                new JacksonOperateLogSerializer(this.mapper), value -> {
                    throw new AssertionError("must not be invoked when masking disabled");
                }, new DefaultSpelEngine(64),
                "app", "env", "v", false,
                new PayloadPolicy(0, 0, 0, Collections.<String>emptySet()), "traceId");
        off.around(StubJoinPoint.returning(m, demo, new Object[]{"42"}, "ok-42"));
        assertEquals("[\"42\"]", this.records.get(1).getRequestBody());
    }

    @Test
    void truncationHappensAfterMaskingAndSerialization() throws Throwable {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        OperateLogAspect a = new OperateLogAspect(this.capture, () -> null, () -> null,
                new JacksonOperateLogSerializer(this.mapper), null, new DefaultSpelEngine(64),
                "app", "env", "v", false,
                new PayloadPolicy(6, 0, 0, Collections.<String>emptySet()), "traceId");
        a.around(StubJoinPoint.returning(m, new Samples.Demo(),
                new Object[]{"abcdefghij"}, "ok"));
        // 序列化后完整体应为 ["abcdefghij"]（15 字符），按 6 截断
        assertEquals("[\"abcd...[truncated]", this.records.get(0).getRequestBody());
    }

    @Test
    void ignoredTypesReplacedBeforeSerialization() throws Throwable {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        OperateLogAspect a = new OperateLogAspect(this.capture, () -> null, () -> null,
                new JacksonOperateLogSerializer(this.mapper), null, new DefaultSpelEngine(64),
                "app", "env", "v", false,
                new PayloadPolicy(0, 0, 0, Collections.singleton("java.io.Serializable")),
                "traceId");
        a.around(StubJoinPoint.returning(m, new Samples.Demo(), new Object[]{"secretive"}, "ok"));
        assertEquals("[\"<IGNORED:String>\"]", this.records.get(0).getRequestBody());
    }

    @Test
    void recordResponseControlsResponseBody() throws Throwable {
        Method m = Samples.method(Samples.Demo.class, "pay", String.class);
        aspect().around(StubJoinPoint.returning(m, new Samples.Demo(),
                new Object[]{"o9"}, "paid-o9"));
        assertEquals("\"paid-o9\"", this.records.get(0).getResponseBody());
    }

    // ==================== extra 通道 ====================

    @Test
    void extrasWrittenDuringBusinessMethodLandInRecordAndUnbindAfterwards() throws Throwable {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        StubJoinPoint jp = StubJoinPoint.returningWithBody(m, new Samples.Demo(),
                new Object[]{"42"}, "ok-42", () -> OperateLogContextHolder.putExtra("bizKey", "bizVal"));

        aspect().around(jp);
        assertEquals(1, this.records.size());
        assertEquals("bizVal", this.records.get(0).getExtra().get("bizKey"));
        assertNull(OperateLogContextHolder.current(), "context must be unbound after the call");
    }

    @Test
    void nestedCallRestoresOuterContext() throws Throwable {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        OperateLogContext outer = Samples.contextOf(m, new Samples.Demo(), new Object[]{"outer"});
        OperateLogContextHolder.bind(outer);
        try {
            aspect().around(StubJoinPoint.returning(m, new Samples.Demo(), new Object[]{"42"}, "ok"));
            assertSame(outer, OperateLogContextHolder.current(),
                    "inner invocation must restore, not clear, the outer context");
        } finally {
            OperateLogContextHolder.unbind();
        }
    }

    // ==================== traceId ====================

    @Test
    void traceIdResolutionMatrix() throws Throwable {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        Samples.Demo demo = new Samples.Demo();

        // 1. 默认 key
        MDC.put("traceId", "T-DEFAULT");
        aspect().around(StubJoinPoint.returning(m, demo, new Object[]{"1"}, "r"));
        assertEquals("T-DEFAULT", this.records.get(0).getTraceId());

        // 2. 自定义 key（默认为空时不干扰）
        MDC.clear();
        MDC.put("my-trace", "T-CUSTOM");
        aspect("my-trace").around(StubJoinPoint.returning(m, demo, new Object[]{"2"}, "r"));
        assertEquals("T-CUSTOM", this.records.get(1).getTraceId());

        // 3. 配置了自定义 key 但值为空白 → 回退 UUID（而不是读默认 key）
        MDC.put("my-trace", "   ");
        aspect("my-trace").around(StubJoinPoint.returning(m, demo, new Object[]{"3"}, "r"));
        assertEquals(36, this.records.get(2).getTraceId().length());

        // 4. 完全无 MDC 值 → UUID 兜底（回归：不得为 null）
        MDC.clear();
        aspect().around(StubJoinPoint.returning(m, demo, new Object[]{"4"}, "r"));
        String uuid = this.records.get(3).getTraceId();
        assertEquals(36, uuid.length());
        assertEquals(4, countChars(uuid, '-'));

        // 5. 自定义 key 配置为空白串 → 回落默认 key "traceId"
        MDC.put("traceId", "T-FALLBACK-KEY");
        aspect("  ").around(StubJoinPoint.returning(m, demo, new Object[]{"5"}, "r"));
        assertEquals("T-FALLBACK-KEY", this.records.get(4).getTraceId());
    }

    private static int countChars(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
