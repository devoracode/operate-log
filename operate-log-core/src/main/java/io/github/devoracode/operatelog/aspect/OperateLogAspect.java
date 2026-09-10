package io.github.devoracode.operatelog.aspect;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.handler.OperateLogHandler;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import io.github.devoracode.operatelog.resolver.OperatorResolver;
import io.github.devoracode.operatelog.sanitizer.SensitiveDataMasker;
import io.github.devoracode.operatelog.serializer.OperateLogSerializer;
import io.github.devoracode.operatelog.spel.SpelEngine;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 操作日志切面。
 *
 * @author devoracode
 */
@Aspect
@RequiredArgsConstructor
public class OperateLogAspect {

    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final OperateLogHandler handler;

    private final OperatorResolver operatorResolver;

    private final HttpContextResolver httpContextResolver;

    private final OperateLogSerializer serializer;

    private final SensitiveDataMasker sensitiveDataMasker;

    private final SpelEngine spelEngine;

    private final String application;

    private final String environment;

    private final String version;

    private final boolean maskEnabled;

    @Around("@annotation(io.github.devoracode.operatelog.annotation.OperateLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Method targetMethod = AopUtils.getMostSpecificMethod(
                method, joinPoint.getTarget().getClass());
        OperateLog annotation = AnnotationUtils.findAnnotation(
                targetMethod, OperateLog.class);

        if (annotation == null) {
            annotation = AnnotationUtils.findAnnotation(method, OperateLog.class);
        }

        if (annotation == null) {
            return joinPoint.proceed();
        }

        OperateLogContext context = new OperateLogContext(
                annotation,
                joinPoint,
                targetMethod,
                joinPoint.getTarget(),
                joinPoint.getArgs());

        context.setStartTime(Instant.now());
        context.setTraceId(resolveTraceId());
        context.setOperator(resolveOperator());
        context.setHttp(resolveHttpContext());

        try {
            Object result = joinPoint.proceed();
            context.setResult(result);
            context.setSuccess(true);
            return result;
        }
        catch (Throwable ex) {
            context.setError(ex);
            context.setSuccess(false);
            throw ex;
        }
        finally {
            context.setEndTime(Instant.now());
            context.setCostTime(Duration.between(
                    context.getStartTime(), context.getEndTime()).toMillis());
            handleSafely(context);
        }
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return StringUtils.defaultIfBlank(traceId, UUID.randomUUID().toString());
    }

    private Operator resolveOperator() {
        try {
            return this.operatorResolver.resolve();
        }
        catch (Throwable ex) {
            return null;
        }
    }

    private HttpContext resolveHttpContext() {
        try {
            return this.httpContextResolver.resolve();
        }
        catch (Throwable ex) {
            return null;
        }
    }

    private void handleSafely(OperateLogContext context) {
        try {
            if (!this.spelEngine.evaluateBoolean(
                    context.getAnnotation().condition(), context)) {
                return;
            }

            OperateLogRecord record = buildRecord(context);
            this.handler.handle(record);
        }
        catch (Throwable ex) {
            // Operation logging must never affect business execution.
        }
    }

    private OperateLogRecord buildRecord(OperateLogContext context) {
        OperateLog annotation = context.getAnnotation();
        Operator operator = context.getOperator();
        HttpContext httpContext = context.getHttp();
        Throwable error = context.getError();

        String requestHeaders = httpContext == null
                ? null
                : this.serializer.serialize(httpContext.getHeaders());
        String requestBody = annotation.recordRequest()
                ? this.serializer.serialize(context.getArguments())
                : null;
        String responseBody = annotation.recordResponse()
                ? this.serializer.serialize(context.getResult())
                : null;

        if (this.maskEnabled) {
            requestHeaders = this.sensitiveDataMasker.mask(requestHeaders);
            requestBody = this.sensitiveDataMasker.mask(requestBody);
            responseBody = this.sensitiveDataMasker.mask(responseBody);
        }

        return OperateLogRecord.builder()
                .id(UUID.randomUUID().toString())
                .traceId(context.getTraceId())
                .application(this.application)
                .environment(this.environment)
                .version(this.version)
                .module(annotation.module())
                .operation(annotation.operation())
                .operationType(annotation.type())
                .description(this.spelEngine.evaluateTemplate(
                        annotation.description(), context))
                .businessId(resolveBusinessId(annotation, context))
                .operatorUserId(operator == null ? null : operator.getUserId())
                .operatorUserAccount(operator == null ? null : operator.getUserAccount())
                .operatorUserName(operator == null ? null : operator.getUserName())
                .requestMethod(httpContext == null ? null : httpContext.getMethod())
                .requestUrl(httpContext == null ? null : httpContext.getUrl())
                .requestUri(httpContext == null ? null : httpContext.getUri())
                .requestQuery(httpContext == null ? null : httpContext.getQuery())
                .requestHeaders(requestHeaders)
                .requestBody(requestBody)
                .responseBody(responseBody)
                .httpStatus(httpContext == null ? null : httpContext.getStatus())
                .clientIp(httpContext == null ? null : httpContext.getIp())
                .userAgent(httpContext == null ? null : httpContext.getUserAgent())
                .success(context.isSuccess())
                .costTime(context.getCostTime())
                .startTime(context.getStartTime())
                .endTime(context.getEndTime())
                .errorType(error == null ? null : error.getClass().getName())
                .errorMessage(error == null ? null : error.getMessage())
                .errorStack(error == null ? null : getStackTrace(error))
                .build();
    }

    private String resolveBusinessId(
            OperateLog annotation,
            OperateLogContext context) {
        Object value = this.spelEngine.evaluate(annotation.businessId(), context);
        return value == null ? null : String.valueOf(value);
    }

    private String getStackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }
}
