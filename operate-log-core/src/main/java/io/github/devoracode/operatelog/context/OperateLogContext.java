package io.github.devoracode.operatelog.context;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.model.Operator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 操作日志执行上下文：单次方法调用的全部中间状态，供 SpEL 求值与记录组装使用。 */
@Getter
@Setter
@RequiredArgsConstructor
public class OperateLogContext {
    private final OperateLog annotation;
    private final ProceedingJoinPoint joinPoint;
    private final Method method;
    private final Object target;
    private final Object[] arguments;
    private Object result;
    private Throwable error;
    private HttpContext http;
    private Operator operator;
    private String traceId;
    private boolean success;
    private long costTime;
    private Instant startTime;
    private Instant endTime;
    /** 业务自定义字段，经 {@link OperateLogContextHolder} 写入，随记录一起落地。 */
    private final Map<String, Object> extra = new LinkedHashMap<String, Object>();

    public boolean hasError() {
        return this.error != null;
    }
}
