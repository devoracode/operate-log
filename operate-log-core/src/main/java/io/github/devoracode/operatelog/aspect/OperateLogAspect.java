package io.github.devoracode.operatelog.aspect;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import io.github.devoracode.operatelog.handler.OperateLogHandler;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 操作日志切面。
 *
 * <p><b>注解查找链</b>（兼容全部代理形态）：目标类 most-specific 方法 → 调用方法
 * （JDK 动态代理时为接口方法）→ 目标类实现的全部接口上的同签名方法。
 * 注解直接标注实现方法、经父类/接口继承、仅标注接口方法（JDK 代理场景）均可识别。
 * 注意：Spring 创建 CGLIB 代理的资格判定只看目标类方法，若注解仅标注在接口方法上
 * 且宿主使用 CGLIB 代理（Spring Boot 默认 {@code proxyTargetClass=true}），
 * 该 advice 不会被织入——此时请将注解放到实现类方法上。</p>
 *
 * <p><b>业务零影响纪律</b>：注解查找、上下文构建、记录组装与 Handler 落地
 * 全部包裹独立 try-catch，任何日志侧故障仅以 debug 日志暴露，绝不向业务传播；
 * 业务异常原样透传。</p>
 *
 * @author devoracode
 */
@Aspect
@RequiredArgsConstructor
public class OperateLogAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperateLogAspect.class);

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

    private final PayloadPolicy payloadPolicy;

    @Around("@annotation(io.github.devoracode.operatelog.annotation.OperateLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method invocationMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?> targetClass = joinPoint.getTarget() == null
                ? invocationMethod.getDeclaringClass()
                : joinPoint.getTarget().getClass();

        final Method targetMethod;
        final OperateLog annotation;
        try {
            targetMethod = AopUtils.getMostSpecificMethod(invocationMethod, targetClass);
            annotation = findOperateLog(invocationMethod, targetMethod, targetClass);
        }
        catch (Throwable ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("operate-log: annotation lookup failed, logging skipped.", ex);
            }
            return joinPoint.proceed();
        }

        if (annotation == null) {
            return joinPoint.proceed();
        }

        final OperateLogContext context;
        try {
            context = new OperateLogContext(
                    annotation,
                    joinPoint,
                    targetMethod,
                    joinPoint.getTarget(),
                    joinPoint.getArgs());
            context.setStartTime(Instant.now());
            context.setTraceId(resolveTraceId());
            context.setOperator(resolveOperator());
            context.setHttp(resolveHttpContext());
        }
        catch (Throwable ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("operate-log: context initialization failed, logging skipped.", ex);
            }
            return joinPoint.proceed();
        }

        // 绑定线程上下文：业务方法内可通过 OperateLogContextHolder#putExtra 追加自定义字段
        OperateLogContext previous = OperateLogContextHolder.current();
        OperateLogContextHolder.bind(context);
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
            try {
                context.setEndTime(Instant.now());
                context.setCostTime(Duration.between(
                        context.getStartTime(), context.getEndTime()).toMillis());
                handleSafely(context);
            }
            finally {
                // 嵌套标注方法场景：恢复外层上下文而非直接清空，防止 ThreadLocal 泄漏与外层丢数据
                if (previous == null) {
                    OperateLogContextHolder.unbind();
                }
                else {
                    OperateLogContextHolder.bind(previous);
                }
            }
        }
    }

    /**
     * 注解查找链：目标类 most-specific 方法 → 调用方法 → 目标类实现的接口。
     */
    private OperateLog findOperateLog(
            Method invocationMethod,
            Method targetMethod,
            Class<?> targetClass) {
        if (targetMethod != null) {
            OperateLog annotation = AnnotationUtils.findAnnotation(
                    targetMethod, OperateLog.class);
            if (annotation != null) {
                return annotation;
            }
        }

        if (invocationMethod != null && invocationMethod != targetMethod) {
            OperateLog annotation = AnnotationUtils.findAnnotation(
                    invocationMethod, OperateLog.class);
            if (annotation != null) {
                return annotation;
            }
        }

        Method lookupMethod = targetMethod != null ? targetMethod : invocationMethod;
        return findInterfaceAnnotation(targetClass, lookupMethod);
    }

    /**
     * 在目标类实现的全部接口（含父接口）上查找同签名方法的注解。
     * 覆盖「@OperateLog 仅标注接口方法 + JDK 动态代理」场景。
     */
    private OperateLog findInterfaceAnnotation(Class<?> targetClass, Method method) {
        if (targetClass == null || method == null) {
            return null;
        }

        for (Class<?> interfaceClass : ClassUtils.getAllInterfacesForClass(targetClass)) {
            try {
                Method interfaceMethod = interfaceClass.getMethod(
                        method.getName(), method.getParameterTypes());
                OperateLog annotation = AnnotationUtils.findAnnotation(
                        interfaceMethod, OperateLog.class);
                if (annotation != null) {
                    return annotation;
                }
            }
            catch (NoSuchMethodException ignored) {
                // 该接口无同签名方法，继续查找下一个接口
            }
        }
        return null;
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
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("operate-log: operator resolution failed, degraded to null.", ex);
            }
            return null;
        }
    }

    private HttpContext resolveHttpContext() {
        try {
            return this.httpContextResolver.resolve();
        }
        catch (Throwable ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("operate-log: http context resolution failed, degraded to null.", ex);
            }
            return null;
        }
    }

    /**
     * 日志组装与落地全程隔离：任何故障只损失这条日志本身，
     * 并在 debug 级别暴露原因（组件自身可观测），绝不触碰业务执行。
     */
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
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("operate-log: failed to build/handle log record for module=[{}], "
                        + "operation=[{}].", context.getAnnotation().module(),
                        context.getAnnotation().operation(), ex);
            }
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
                ? this.serializer.serializeArguments(
                        this.payloadPolicy.filterArguments(context.getArguments()))
                : null;
        String responseBody = annotation.recordResponse()
                ? this.serializer.serialize(context.getResult())
                : null;

        if (this.maskEnabled) {
            requestHeaders = this.sensitiveDataMasker.mask(requestHeaders);
            requestBody = this.sensitiveDataMasker.mask(requestBody);
            responseBody = this.sensitiveDataMasker.mask(responseBody);
        }

        // 截断在脱敏之后执行：无论脱敏使内容变长还是变短，落地的最终体积都不越界
        requestHeaders = this.payloadPolicy.truncateRequest(requestHeaders);
        requestBody = this.payloadPolicy.truncateRequest(requestBody);
        responseBody = this.payloadPolicy.truncateResponse(responseBody);

        Map<String, Object> extra = context.getExtra().isEmpty()
                ? null
                : new LinkedHashMap<String, Object>(context.getExtra());

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
                .errorStack(error == null
                        ? null
                        : this.payloadPolicy.truncateErrorStack(getStackTrace(error)))
                .extra(extra)
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
