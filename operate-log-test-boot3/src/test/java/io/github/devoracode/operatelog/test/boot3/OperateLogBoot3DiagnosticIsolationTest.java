package io.github.devoracode.operatelog.test.boot3;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import io.github.devoracode.operatelog.handler.OperateLogHandler;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
import io.github.devoracode.operatelog.resolver.OperatorResolver;
import io.github.devoracode.operatelog.spel.DefaultSpelEngine;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperateLogBoot3DiagnosticIsolationTest {

    @AfterEach
    void clearContext() {
        OperateLogContextHolder.unbind();
    }

    @Test
    void annotationDiagnosticFailureStillProceedsOnce() throws Throwable {
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        when(point.getSignature()).thenThrow(new IllegalStateException("no signature"));
        when(point.proceed()).thenReturn("ok");
        OperateLogAspect aspect = aspect(record -> { }, () -> null);

        try (BrokenWarnBackend backend = new BrokenWarnBackend()) {
            assertEquals("ok", aspect.around(point));
            verify(point).proceed();
            assertEquals(1, backend.attempts);
            assertNull(OperateLogContextHolder.current());
        }
    }

    @Test
    void resolverDiagnosticFailureDoesNotSkipBusinessOrLeakContext() {
        CallTarget target = new CallTarget();
        List<OperateLogRecord> records = new ArrayList<>();
        CallTarget service = proxy(target, aspect(records::add, () -> {
            throw new IllegalStateException("operator resolver failed");
        }));

        try (BrokenWarnBackend backend = new BrokenWarnBackend()) {
            assertEquals("ok", service.call("first", null));
            assertEquals(1, target.calls);
            assertNull(OperateLogContextHolder.current());
            assertEquals("ok", service.call(null, null));
            assertNull(OperateLogContextHolder.current());
            assertEquals(2, target.calls);
            assertEquals(2, backend.attempts);
            assertEquals(2, records.size());
            assertEquals("first", records.get(0).getExtra().get("call"));
            assertNull(records.get(1).getExtra());
        }
    }

    @Test
    void handlerDiagnosticFailurePreservesBusinessExceptionAndOuterContext() {
        CallTarget target = new CallTarget();
        CallTarget service = proxy(target, aspect(record -> {
            if (!record.isSuccess()) {
                throw new IllegalStateException("handler failed");
            }
        }, () -> null));
        IllegalStateException failure = new IllegalStateException("original business failure");
        OperateLogContext outer = new OperateLogContext(null, null, null, null, new Object[0]);
        outer.getExtra().put("call", "outer");
        OperateLogContextHolder.bind(outer);

        try (BrokenWarnBackend backend = new BrokenWarnBackend()) {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.call("inner", failure)));
            assertEquals(1, target.calls);
            assertEquals(1, backend.attempts);
            assertSame(outer, OperateLogContextHolder.current());
            assertEquals("outer", outer.getExtra().get("call"));
        }
    }

    private static OperateLogAspect aspect(OperateLogHandler handler, OperatorResolver resolver) {
        return new OperateLogAspect(handler, resolver, () -> null, null, null, new DefaultSpelEngine(),
                "test", "test", null, false, new PayloadPolicy(0, 0, 128, 0, Collections.emptyList()), null, 64);
    }

    private static CallTarget proxy(CallTarget target, OperateLogAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    static class CallTarget {
        private int calls;

        @OperateLog(recordRequest = false)
        public String call(String extra, IllegalStateException failure) {
            this.calls++;
            if (extra != null) {
                OperateLogContextHolder.putExtra("call", extra);
            }
            if (failure != null) {
                throw failure;
            }
            return "ok";
        }
    }

    private static final class BrokenWarnBackend extends TurboFilter implements AutoCloseable {
        private final LoggerContext loggerContext;
        private final Thread testThread = Thread.currentThread();
        private int attempts;

        private BrokenWarnBackend() {
            Logger logger = (Logger) LoggerFactory.getLogger(OperateLogAspect.class);
            this.loggerContext = logger.getLoggerContext();
            setContext(this.loggerContext);
            start();
            this.loggerContext.addTurboFilter(this);
        }

        @Override
        public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                                  Object[] params, Throwable error) {
            if (Thread.currentThread() == this.testThread && level == Level.WARN
                    && OperateLogAspect.class.getName().equals(logger.getName())) {
                this.attempts++;
                throw new IllegalStateException("WARN backend failed");
            }
            return FilterReply.NEUTRAL;
        }

        @Override
        public void close() {
            this.loggerContext.getTurboFilterList().remove(this);
            stop();
        }
    }
}
