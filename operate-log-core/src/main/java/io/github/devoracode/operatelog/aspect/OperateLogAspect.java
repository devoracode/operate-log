package io.github.devoracode.operatelog.aspect;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import io.github.devoracode.operatelog.handler.OperateLogHandler;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import io.github.devoracode.operatelog.model.OperateType;
import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.model.RecordOn;
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
import java.lang.annotation.Annotation;
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
 * <p><b>类级注解与字段级合并</b>：切点同时接受 {@code @within}（类上标注），
 * 生效注解 = 方法级（含接口查找）与类级逐字段合并（见 {@link MergedOperateLog}）；
 * 无方法级注解的方法按类级配置直接记录。</p>
 *
 * <p><b>记录时机过滤</b>：{@code recordOn}（ALWAYS / SUCCESS / ERROR）在 SpEL
 * 条件之前先行短路，低成本实现「仅成功 / 仅失败」审计。</p>
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
    /**
     * traceId 的默认 MDC key（可被 {@code operate-log.trace-id-mdc-key} 覆盖）。
     */
    private static final String DEFAULT_TRACE_ID_MDC_KEY = "traceId";
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
    private final String traceIdMdcKey;

    @Around("@annotation(io.github.devoracode.operatelog.annotation.OperateLog)" + " || @within(io.github.devoracode.operatelog.annotation.OperateLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method invocationMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?> targetClass = joinPoint.getTarget() == null ? invocationMethod.getDeclaringClass() : joinPoint.getTarget()
                                                                                                      .getClass();
        final Method targetMethod;
        final OperateLog annotation;
        try {
            targetMethod = AopUtils.getMostSpecificMethod(invocationMethod, targetClass);
            annotation = resolveOperateLog(invocationMethod, targetMethod, targetClass);
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: annotation lookup failed, logging skipped.", ex);
            return joinPoint.proceed();
        }
        if (annotation == null) {
            return joinPoint.proceed();
        }
        final OperateLogContext context;
        try {
            context = new OperateLogContext(annotation,
                    joinPoint,
                    targetMethod,
                    joinPoint.getTarget(),
                    joinPoint.getArgs());
            context.setStartTime(Instant.now());
            context.setTraceId(resolveTraceId());
            context.setOperator(resolveOperator());
            context.setHttp(resolveHttpContext());
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: context initialization failed, logging skipped.", ex);
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
        } catch (Throwable ex) {
            context.setError(ex);
            context.setSuccess(false);
            throw ex;
        } finally {
            try {
                context.setEndTime(Instant.now());
                context.setCostTime(Duration.between(context.getStartTime(), context.getEndTime())
                        .toMillis());
                handleSafely(context);
            } finally {
                // 嵌套标注方法场景：恢复外层上下文而非直接清空，防止 ThreadLocal 泄漏与外层丢数据
                if (previous == null) {
                    OperateLogContextHolder.unbind();
                } else {
                    OperateLogContextHolder.bind(previous);
                }
            }
        }
    }

    /**
     * 生效注解解析：方法级（含接口查找）与类级默认值逐字段合并。
     *
     * <p>仅类级注解在场时直接按类级配置记录；仅方法级在场时原样返回。</p>
     */
    private OperateLog resolveOperateLog(Method invocationMethod,
                                         Method targetMethod,
                                         Class<?> targetClass) {
        OperateLog methodLevel = findOperateLog(invocationMethod, targetMethod, targetClass);
        OperateLog classLevel = targetClass == null ? null : AnnotationUtils.findAnnotation(
                targetClass,
                OperateLog.class);
        if (methodLevel == null) {
            return classLevel;
        }
        if (classLevel == null) {
            return methodLevel;
        }
        return new MergedOperateLog(classLevel, methodLevel);
    }

    /**
     * {@code recordOn} 记录时机过滤：SUCCESS 仅在正常返回时记录，ERROR 仅在抛出异常时记录。
     */
    private boolean shouldRecord(OperateLogContext context) {
        RecordOn recordOn = context.getAnnotation().recordOn();
        if (recordOn == null) {
            // 防御自定义实现返回 null：视为 ALWAYS，不丢日志
            return true;
        }
        switch (recordOn) {
            case SUCCESS:
                return context.isSuccess();
            case ERROR:
                return context.hasError();
            default:
                return true;
        }
    }

    /**
     * 注解查找链：目标类 most-specific 方法 → 调用方法 → 目标类实现的接口。
     */
    private OperateLog findOperateLog(Method invocationMethod,
                                      Method targetMethod,
                                      Class<?> targetClass) {
        if (targetMethod != null) {
            OperateLog annotation = AnnotationUtils.findAnnotation(targetMethod, OperateLog.class);
            if (annotation != null) {
                return annotation;
            }
        }
        if (invocationMethod != null && invocationMethod != targetMethod) {
            OperateLog annotation = AnnotationUtils.findAnnotation(invocationMethod,
                    OperateLog.class);
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
                Method interfaceMethod = interfaceClass.getMethod(method.getName(),
                        method.getParameterTypes());
                OperateLog annotation = AnnotationUtils.findAnnotation(interfaceMethod,
                        OperateLog.class);
                if (annotation != null) {
                    return annotation;
                }
            } catch (NoSuchMethodException ignored) {
                // 该接口无同签名方法，继续查找下一个接口
            }
        }
        return null;
    }

    private String resolveTraceId() {
        String mdcKey = StringUtils.defaultIfBlank(this.traceIdMdcKey, DEFAULT_TRACE_ID_MDC_KEY);
        try {
            return MDC.get(mdcKey);
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: traceId resolution failed, degraded to null.", ex);
            return null;
        }

    }

    private Operator resolveOperator() {
        try {
            return this.operatorResolver.resolve();
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: operator resolution failed, degraded to null.", ex);
            return null;
        }
    }

    private HttpContext resolveHttpContext() {
        try {
            return this.httpContextResolver.resolve();
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: http context resolution failed, degraded to null.", ex);
            return null;
        }
    }

    /**
     * 日志组装与落地全程隔离：任何故障只损失这条日志本身，
     * 并在 debug 级别暴露原因（组件自身可观测），绝不触碰业务执行。
     */
    private void handleSafely(OperateLogContext context) {
        try {
            // recordOn 先行短路过滤（无 SpEL 求值成本），再进 condition 交集判定
            if (!shouldRecord(context)) {
                return;
            }
            if (!this.spelEngine.evaluateBoolean(context.getAnnotation().condition(), context)) {
                return;
            }
            OperateLogRecord record = buildRecord(context);
            this.handler.handle(record);
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: failed to build/handle log record for module=[{}], " + "operation=[{}].",
                    context.getAnnotation().module(),
                    context.getAnnotation().operation(),
                    ex);
        }
    }

    private OperateLogRecord buildRecord(OperateLogContext context) {
        OperateLog annotation = context.getAnnotation();
        Operator operator = context.getOperator();
        HttpContext httpContext = context.getHttp();
        Throwable error = context.getError();
        String requestHeaders = httpContext == null ? null : this.serializer.serialize(httpContext.getHeaders());
        String requestBody = annotation.recordRequest() ? this.serializer.serializeArguments(this.payloadPolicy.filterArguments(
                context.getArguments())) : null;
        String responseBody = annotation.recordResponse() ? this.serializer.serialize(context.getResult()) : null;
        if (this.maskEnabled) {
            requestHeaders = this.sensitiveDataMasker.mask(requestHeaders);
            requestBody = this.sensitiveDataMasker.mask(requestBody);
            responseBody = this.sensitiveDataMasker.mask(responseBody);
        }
        // 截断在脱敏之后执行：无论脱敏使内容变长还是变短，落地的最终体积都不越界
        requestHeaders = this.payloadPolicy.truncateRequest(requestHeaders);
        requestBody = this.payloadPolicy.truncateRequest(requestBody);
        responseBody = this.payloadPolicy.truncateResponse(responseBody);
        Map<String, Object> extra = context.getExtra()
                .isEmpty() ? null : new LinkedHashMap<String, Object>(context.getExtra());
        return OperateLogRecord.builder()
                .id(UUID.randomUUID().toString())
                .traceId(context.getTraceId())
                .application(this.application)
                .environment(this.environment)
                .version(this.version)
                .module(annotation.module())
                .operation(annotation.operation())
                .operationType(annotation.type())
                .description(this.spelEngine.evaluateTemplate(annotation.description(), context))
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
                .errorStack(error == null ? null : this.payloadPolicy.truncateErrorStack(
                        getStackTrace(error)))
                .extra(extra)
                .build();
    }

    private String resolveBusinessId(OperateLog annotation, OperateLogContext context) {
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

    /**
     * 类级默认值与方法级显式值的合并视图（普通实现类，非 JDK 注解代理）。
     *
     * <p>合并规则：方法级字段为「未设置」时继承类级——字符串按空串、type 按
     * {@link OperateType#OTHER}、recordOn 按 {@link RecordOn#ALWAYS} 判定未设置；
     * {@code recordRequest} / {@code recordResponse} 布尔字段无未设置态，不参与合并。</p>
     *
     * <p>SpEL 中的 {@code #annotation} 变量暴露的即本合并视图，表达式读到的
     * 属性值与最终落地的日志一致。</p>
     */
    private static final class MergedOperateLog implements OperateLog {
        private final String module;
        private final String operation;
        private final OperateType type;
        private final String description;
        private final String businessId;
        private final String condition;
        private final RecordOn recordOn;
        private final boolean recordRequest;
        private final boolean recordResponse;

        private MergedOperateLog(OperateLog classLevel, OperateLog methodLevel) {
            this.module = pickText(methodLevel.module(), classLevel.module());
            this.operation = pickText(methodLevel.operation(), classLevel.operation());
            this.type = methodLevel.type() == OperateType.OTHER ? classLevel.type() : methodLevel.type();
            this.description = pickText(methodLevel.description(), classLevel.description());
            this.businessId = pickText(methodLevel.businessId(), classLevel.businessId());
            this.condition = pickText(methodLevel.condition(), classLevel.condition());
            this.recordOn = methodLevel.recordOn() == RecordOn.ALWAYS ? classLevel.recordOn() : methodLevel.recordOn();
            this.recordRequest = methodLevel.recordRequest();
            this.recordResponse = methodLevel.recordResponse();
        }

        private static String pickText(String override, String fallback) {
            return StringUtils.isEmpty(override) ? fallback : override;
        }

        @Override
        public String module() {
            return this.module;
        }

        @Override
        public String operation() {
            return this.operation;
        }

        @Override
        public OperateType type() {
            return this.type;
        }

        @Override
        public String description() {
            return this.description;
        }

        @Override
        public String businessId() {
            return this.businessId;
        }

        @Override
        public String condition() {
            return this.condition;
        }

        @Override
        public RecordOn recordOn() {
            return this.recordOn;
        }

        @Override
        public boolean recordRequest() {
            return this.recordRequest;
        }

        @Override
        public boolean recordResponse() {
            return this.recordResponse;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return OperateLog.class;
        }
    }
}
