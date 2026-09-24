package io.github.devoracode.operatelog.aspect;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import io.github.devoracode.operatelog.handler.OperateLogHandler;
import io.github.devoracode.operatelog.model.HttpContext;
import io.github.devoracode.operatelog.model.OperateLogRecord;
import io.github.devoracode.operatelog.model.Operator;
import io.github.devoracode.operatelog.model.RecordOn;
import io.github.devoracode.operatelog.payload.PayloadPolicy;
import io.github.devoracode.operatelog.resolver.HttpContextResolver;
import io.github.devoracode.operatelog.resolver.OperatorResolver;
import io.github.devoracode.operatelog.sanitizer.SensitiveDataMasker;
import io.github.devoracode.operatelog.serializer.OperateLogSerializer;
import io.github.devoracode.operatelog.spel.SpelEngine;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作日志切面。
 *
 * <p>注解查找链（兼容各类代理形态）：目标类 most-specific 方法 → 调用方法 → 目标类接口的同签名方法。
 * CGLIB 代理下注解只标在接口方法上不会织入，需放到实现类方法。</p>
 *
 * <p>业务零影响：日志侧故障只以 warn / debug 暴露，业务异常原样透传，{@code finally} 收尾不外抛。</p>
 *
 * <p>{@code success} 仅表示「业务方法未抛异常」，<b>不含</b>事务提交结果：若事务通知位于本切面内层，
 * 回滚时日志仍记为成功。要求审计一致需让事务通知位于外层，或改用延后落地的 {@code OperateLogHandler}。</p>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class OperateLogAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(OperateLogAspect.class);
    private static final String DEFAULT_TRACE_ID_MDC_KEY = "traceId";
    private static final String UNSERIALIZABLE_SENTINEL = "<UNSERIALIZABLE>";
    private static final Comparator<ExtraEntry> EXTRA_ENTRY_LENGTH_DESC =
            (left, right) -> Integer.compare(right.serializedLength, left.serializedLength);
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
    private final Map<AnnotationCacheKey, Optional<OperateLog>> annotationCache;
    private final int maxAnnotationCacheSize;

    // 参数顺序即二进制签名：README 指引宿主自行 new 本切面以扩大切点，新增字段只能追加到末尾、
    // 禁止重排——改序会让已编译的宿主按位置静默错位传参
    public OperateLogAspect(OperateLogHandler handler,
                            OperatorResolver operatorResolver,
                            HttpContextResolver httpContextResolver,
                            OperateLogSerializer serializer,
                            SensitiveDataMasker sensitiveDataMasker,
                            SpelEngine spelEngine,
                            String application,
                            String environment,
                            String version,
                            boolean maskEnabled,
                            PayloadPolicy payloadPolicy,
                            String traceIdMdcKey,
                            int annotationCacheSize) {
        this.handler = handler;
        this.operatorResolver = operatorResolver;
        this.httpContextResolver = httpContextResolver;
        this.serializer = serializer;
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.spelEngine = spelEngine;
        this.application = application;
        this.environment = environment;
        this.version = version;
        this.maskEnabled = maskEnabled;
        this.payloadPolicy = payloadPolicy;
        this.traceIdMdcKey = traceIdMdcKey;
        this.maxAnnotationCacheSize = Math.max(annotationCacheSize, 64);
        this.annotationCache = new ConcurrentHashMap<>();
    }

    /**
     * 环绕通知：解析注解 → 绑定线程上下文 → 执行业务 → 静默收尾落地。
     * 未标注 {@link OperateLog} 或日志侧初始化失败时直接放行，不留痕也不影响业务。
     *
     * @param joinPoint 被拦截的业务方法连接点
     * @return 业务方法的原始返回值
     * @throws Throwable 业务方法自身抛出的异常，原样透传不包装
     */
    @Around("@annotation(io.github.devoracode.operatelog.annotation.OperateLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        final Method invocationMethod;
        final Class<?> targetClass;
        final Method targetMethod;
        final OperateLog annotation;
        try {
            // 签名/目标类解析也属日志侧工作，异常不得穿透到业务
            invocationMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
            Object target = joinPoint.getTarget();
            targetClass = target == null ? invocationMethod.getDeclaringClass() : target.getClass();
            targetMethod = AopUtils.getMostSpecificMethod(invocationMethod, targetClass);
            annotation = findOperateLogCached(invocationMethod, targetMethod, targetClass);
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: annotation lookup failed, logging skipped.", ex);
            return joinPoint.proceed();
        }
        if (annotation == null) {
            return joinPoint.proceed();
        }
        final OperateLogContext context;
        final TraceId traceId;
        try {
            context = new OperateLogContext(annotation,
                    joinPoint,
                    targetMethod,
                    joinPoint.getTarget(),
                    joinPoint.getArgs());
            context.setStartTime(Instant.now());
            traceId = resolveTraceId();
            context.setTraceId(traceId.value());
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: context initialization failed, logging skipped.", ex);
            return joinPoint.proceed();
        }
        // 绑定线程上下文后再跑解析器：Resolver 与业务方法都能用 OperateLogContextHolder#putExtra 追加字段
        OperateLogContext previous = OperateLogContextHolder.current();
        OperateLogContextHolder.bind(context);
        context.setOperator(resolveOperator());
        context.setHttp(resolveHttpContext());
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
                finishQuietly(context);
            } finally {
                // 嵌套场景恢复外层上下文，避免 ThreadLocal 泄漏与外层丢数据
                restorePreviousContext(previous);
                // 只清理本组件生成的 traceId，宿主 MDC 值不动
                if (traceId.generated()) {
                    clearTraceIdQuietly(traceId.mdcKey());
                }
            }
        }
    }

    private void finishQuietly(OperateLogContext context) {
        try {
            context.setEndTime(Instant.now());
            context.setCostTime(Duration.between(context.getStartTime(), context.getEndTime())
                    .toMillis());
            handleSafely(context);
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: log finalization failed, this record may be lost; " + "business execution is unaffected.",
                    ex);
        }
    }

    private void restorePreviousContext(OperateLogContext previous) {
        if (previous == null) {
            OperateLogContextHolder.unbind();
        } else {
            OperateLogContextHolder.bind(previous);
        }
    }

    private boolean shouldRecord(OperateLogContext context) {
        switch (context.getAnnotation().recordOn()) {
            case SUCCESS:
                return context.isSuccess();
            case ERROR:
                return context.hasError();
            default:
                return true;
        }
    }

    private OperateLog findOperateLogCached(Method invocationMethod,
                                            Method targetMethod,
                                            Class<?> targetClass) {
        AnnotationCacheKey cacheKey = new AnnotationCacheKey(invocationMethod, targetClass);
        Optional<OperateLog> cached = this.annotationCache.get(cacheKey);
        if (cached != null) {
            return cached.orElse(null);
        }
        OperateLog resolved = findOperateLog(invocationMethod, targetMethod, targetClass);
        if (this.annotationCache.size() < this.maxAnnotationCacheSize) {
            this.annotationCache.putIfAbsent(cacheKey, Optional.ofNullable(resolved));
        }
        return resolved;
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    @EqualsAndHashCode
    private static final class AnnotationCacheKey {
        private final Method method;
        private final Class<?> targetClass;
    }

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

    private TraceId resolveTraceId() {
        String mdcKey = StringUtils.defaultIfBlank(this.traceIdMdcKey, DEFAULT_TRACE_ID_MDC_KEY);
        boolean mayHaveWritten = false;
        try {
            String existing = MDC.get(mdcKey);
            if (StringUtils.isNotBlank(existing)) {
                return new TraceId(existing, false, mdcKey);
            }
            String generated = UUID.randomUUID().toString();
            mayHaveWritten = true;
            MDC.put(mdcKey, generated);
            return new TraceId(generated, true, mdcKey);
        } catch (Throwable ex) {
            // 走到写入分支才清理：此时已确认原值为空，不会误删宿主 MDC。MDC.put 可能写入后才抛，
            // 降级值不能谎报 generated=false，否则半写的 traceId 会留在复用线程上。
            if (mayHaveWritten) {
                clearTraceIdQuietly(mdcKey);
            }
            LOGGER.warn("operate-log: traceId resolution failed, degraded to generated UUID.", ex);
            return new TraceId(UUID.randomUUID().toString(), false, mdcKey);
        }
    }

    private void clearTraceIdQuietly(String mdcKey) {
        try {
            MDC.remove(mdcKey);
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: traceId cleanup failed, MDC entry may remain on this thread.",
                    ex);
        }
    }

    private static final class TraceId {
        private final String value;
        private final boolean generated;
        private final String mdcKey;

        private TraceId(String value, boolean generated, String mdcKey) {
            this.value = value;
            this.generated = generated;
            this.mdcKey = mdcKey;
        }

        private String value() {
            return this.value;
        }

        private boolean generated() {
            return this.generated;
        }

        private String mdcKey() {
            return this.mdcKey;
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
        String requestBody = null;
        if (annotation.recordRequest()) {
            Object[] requestArguments = this.payloadPolicy.filterArguments(context.getArguments());
            requestBody = this.serializer.serializeArguments(requestArguments);
        }
        String responseBody = annotation.recordResponse() ? this.serializer.serialize(context.getResult()) : null;
        if (this.maskEnabled) {
            requestHeaders = this.sensitiveDataMasker.mask(requestHeaders);
            requestBody = this.sensitiveDataMasker.mask(requestBody);
            responseBody = this.sensitiveDataMasker.mask(responseBody);
        }
        // query string 掩码：复用敏感字段集合按参数名匹配，与 JSON 脱敏并列执行
        String requestQuery = httpContext == null ? null : httpContext.getQuery();
        if (this.maskEnabled && requestQuery != null) {
            requestQuery = this.sensitiveDataMasker.maskQuery(requestQuery);
        }
        // 异常 message 是纯文本（非 JSON），走 plainText 脱敏路径
        String errorMessage = error == null ? null : error.getMessage();
        String errorStack = error == null ? null : getStackTrace(error);
        if (this.maskEnabled) {
            errorMessage = this.sensitiveDataMasker.maskPlainText(errorMessage);
            errorStack = this.sensitiveDataMasker.maskPlainText(errorStack);
        }
        String userAgent = httpContext == null ? null : httpContext.getUserAgent();
        // 截断在脱敏之后：脱敏可能改变长度，先截断会越界；query/userAgent 同为客户端可控输入，一并受 request 上限保护
        requestHeaders = this.payloadPolicy.truncateRequest(requestHeaders);
        requestBody = this.payloadPolicy.truncateRequest(requestBody);
        responseBody = this.payloadPolicy.truncateResponse(responseBody);
        requestQuery = this.payloadPolicy.truncateRequest(requestQuery);
        userAgent = this.payloadPolicy.truncateRequest(userAgent);
        Map<String, Object> extra = normalizeExtra(context.getExtra());
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
                .requestQuery(requestQuery)
                .requestHeaders(requestHeaders)
                .requestBody(requestBody)
                .responseBody(responseBody)
                .clientIp(httpContext == null ? null : httpContext.getIp())
                .userAgent(userAgent)
                .success(context.isSuccess())
                .costTime(context.getCostTime())
                .startTime(context.getStartTime())
                .endTime(context.getEndTime())
                .errorType(error == null ? null : error.getClass().getName())
                .errorMessage(this.payloadPolicy.truncateErrorMessage(errorMessage))
                .errorStack(this.payloadPolicy.truncateErrorStack(errorStack))
                .extra(extra)
                .build();
    }

    private String resolveBusinessId(OperateLog annotation, OperateLogContext context) {
        Object value = this.spelEngine.evaluate(annotation.businessId(), context);
        return value == null ? null : String.valueOf(value);
    }

    private String getStackTrace(Throwable throwable) {
        StringWriter writer = new LimitedStringWriter(this.payloadPolicy.getMaxErrorLength());
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }

    private Map<String, Object> normalizeExtra(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), toSerializableValue(entry.getValue()));
            }
        }
        int limit = this.payloadPolicy.getMaxExtraLength();
        if (limit > 0) {
            enforceExtraLimit(result, limit);
        }
        return result.isEmpty() ? null : result;
    }

    private Object toSerializableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        String probe = this.serializer.serialize(value);
        if (probe != null && !UNSERIALIZABLE_SENTINEL.equals(probe)) {
            return value;
        }
        return "<UNSERIALIZABLE:" + value.getClass().getSimpleName() + ">";
    }

    private void enforceExtraLimit(Map<String, Object> extra, int limit) {
        PriorityQueue<ExtraEntry> entries = new PriorityQueue<>(
                Math.max(1, extra.size()), EXTRA_ENTRY_LENGTH_DESC);
        int serializedLength = 2;
        int entryCount = 0;
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            ExtraEntry measured = measureExtraEntry(entry.getKey(), entry.getValue());
            if (measured == null) {
                extra.clear();
                return;
            }
            entries.offer(measured);
            serializedLength += measured.serializedLength - 2;
            entryCount++;
        }
        if (entryCount > 1) {
            serializedLength += entryCount - 1;
        }

        while (serializedLength > limit && !entries.isEmpty()) {
            ExtraEntry largest = entries.poll();
            if (!extra.containsKey(largest.key)) {
                continue;
            }
            int excess = serializedLength - limit;
            if (largest.value instanceof String && largest.serializedValueLength - excess >= 2) {
                String shortened = shortenSerializedString((String) largest.value,
                        largest.serializedValueLength - excess);
                if (shortened != null) {
                    extra.put(largest.key, shortened);
                    String serializedValue = this.serializer.serialize(shortened);
                    int shortenedLength = serializedValue.length();
                    int previousLength = largest.serializedLength;
                    largest.update(shortened, shortenedLength);
                    serializedLength += largest.serializedLength - previousLength;
                    entries.offer(largest);
                    continue;
                }
            }
            extra.remove(largest.key);
            serializedLength = extra.isEmpty() ? 0 : serializedLength - largest.serializedLength + 1;
        }

        if (!extra.isEmpty()) {
            String serialized = this.serializer.serialize(extra);
            if (serialized == null || UNSERIALIZABLE_SENTINEL.equals(serialized) || serialized.length() > limit) {
                extra.clear();
            }
        }
    }

    private ExtraEntry measureExtraEntry(String key, Object value) {
        String serializedEntry = this.serializer.serialize(Collections.singletonMap(key, value));
        if (serializedEntry == null || UNSERIALIZABLE_SENTINEL.equals(serializedEntry)) {
            return null;
        }
        int serializedValueLength = 0;
        if (value instanceof String) {
            String serializedValue = this.serializer.serialize(value);
            if (serializedValue == null || UNSERIALIZABLE_SENTINEL.equals(serializedValue)) {
                return null;
            }
            serializedValueLength = serializedValue.length();
        }
        return new ExtraEntry(key, value, serializedEntry.length(), serializedValueLength);
    }

    private String shortenSerializedString(String value, int maxSerializedLength) {
        int low = 0;
        int high = value.length();
        String shortened = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String candidate = PayloadPolicy.truncate(value, middle);
            String serialized = this.serializer.serialize(candidate);
            if (serialized != null && serialized.length() <= maxSerializedLength) {
                shortened = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return shortened;
    }

    private static final class ExtraEntry {
        private final String key;
        private Object value;
        private int serializedLength;
        private int serializedValueLength;

        private ExtraEntry(String key, Object value, int serializedLength, int serializedValueLength) {
            this.key = key;
            this.value = value;
            this.serializedLength = serializedLength;
            this.serializedValueLength = serializedValueLength;
        }

        private void update(Object value, int serializedValueLength) {
            this.serializedLength += serializedValueLength - this.serializedValueLength;
            this.value = value;
            this.serializedValueLength = serializedValueLength;
        }
    }

    private static final class LimitedStringWriter extends StringWriter {
        private final int limit;

        private LimitedStringWriter(int limit) {
            // limit<=0（不截断）同样按 8192 预分配：初始 256 会让大堆栈多次扩容拷贝，高频异常场景放大 GC 压力
            super(limit > 0 ? Math.min(limit, 8192) : 8192);
            this.limit = limit;
        }

        @Override
        public void write(int c) {
            if (this.limit <= 0 || getBuffer().length() < this.limit) {
                super.write(c);
            }
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            if (this.limit <= 0) {
                super.write(cbuf, off, len);
                return;
            }
            int remaining = this.limit - getBuffer().length();
            if (remaining > 0) {
                super.write(cbuf, off, Math.min(len, remaining));
            }
        }

        @Override
        public void write(String str, int off, int len) {
            if (this.limit <= 0) {
                super.write(str, off, len);
                return;
            }
            int remaining = this.limit - getBuffer().length();
            if (remaining > 0) {
                super.write(str, off, Math.min(len, remaining));
            }
        }
    }
}
