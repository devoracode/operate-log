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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    /** traceId 默认 MDC key，可由 {@code operate-log.trace-id-mdc-key} 覆盖。 */
    private static final String DEFAULT_TRACE_ID_MDC_KEY = "traceId";
    /** {@code DefaultOperateLogSerializer} 序列化失败哨兵，用于探测 extra 单值能否写出。 */
    private static final String UNSERIALIZABLE_SENTINEL = "<UNSERIALIZABLE>";
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
    /** 注解查找缓存：定容 LRU，热部署下旧 ClassLoader 引用随淘汰释放。 */
    private final Map<AnnotationCacheKey, Optional<OperateLog>> annotationCache;

    /**
     * 供宿主直接 {@code new} 以自定义切点。参数顺序即二进制签名，
     * <b>新增字段只能追加到末尾、禁止重排</b>——切面是可覆盖的扩展点，改序会让已编译宿主静默错位。
     *
     * @param handler             日志落地出口，组装完成的记录交由它写出
     * @param operatorResolver    业务方法执行前解析当前操作人，失败降级为 {@code null}
     * @param httpContextResolver 采集请求侧 HTTP 快照（method / url / headers / query 等）
     * @param serializer          把入参、返回值、请求头等对象转成 JSON 文本
     * @param sensitiveDataMasker 脱敏器，仅在 {@code maskEnabled} 为 {@code true} 时被调用
     * @param spelEngine          渲染 {@code description}、求值 {@code businessId} 与 {@code condition}
     * @param application         写入记录 {@code application} 字段的应用名，用于多应用共库时区分
     * @param environment         写入记录 {@code environment} 字段的运行环境
     * @param version             写入记录 {@code version} 字段的应用版本号
     * @param maskEnabled         脱敏总开关；{@code false} 时跳过 {@code sensitiveDataMasker}，各字段原样落地
     * @param payloadPolicy       载荷防护策略：参数过滤与各字段的长度截断
     * @param traceIdMdcKey       读取宿主 traceId 的 MDC key，空白时回落到默认的 {@code traceId}
     * @param annotationCacheSize 注解查找 LRU 缓存的条目上限（缓存「方法 + 目标类 → {@link OperateLog}」解析结果），
     *                            小于 64 时按 64 生效
     */
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
        final int maxCacheSize = Math.max(annotationCacheSize, 64);
        this.annotationCache = Collections.synchronizedMap(new LinkedHashMap<AnnotationCacheKey, Optional<OperateLog>>(
                16,
                0.75f,
                true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<AnnotationCacheKey, Optional<OperateLog>> eldest) {
                return size() > maxCacheSize;
            }
        });
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
            context.setOperator(resolveOperator());
            context.setHttp(resolveHttpContext());
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: context initialization failed, logging skipped.", ex);
            return joinPoint.proceed();
        }
        // 绑定线程上下文：业务方法内可通过 OperateLogContextHolder#putExtra 追加字段
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

    /**
     * 日志收尾：计时 → 组装落地。运行于切面 {@code finally}，逃逸异常会顶替业务异常，故不外抛。
     */
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

    /** 嵌套场景下恢复外层上下文，否则解绑当前线程。 */
    private void restorePreviousContext(OperateLogContext previous) {
        if (previous == null) {
            OperateLogContextHolder.unbind();
        } else {
            OperateLogContextHolder.bind(previous);
        }
    }

    /** {@code recordOn} 过滤：SUCCESS 只记正常返回，ERROR 只记抛异常，先于 SpEL 短路。 */
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

    /**
     * 带缓存的注解查找。{@code findOperateLog} 含多轮反射与接口扫描，而切入点注解运行期不变，
     * 故按「调用方法 + 目标类」缓存；targetClass 必须入 key——同一接口方法可对应多个实现类。
     */
    private OperateLog findOperateLogCached(Method invocationMethod,
                                            Method targetMethod,
                                            Class<?> targetClass) {
        AnnotationCacheKey cacheKey = new AnnotationCacheKey(invocationMethod, targetClass);
        Optional<OperateLog> cached = this.annotationCache.get(cacheKey);
        if (cached != null) {
            return cached.orElse(null);
        }
        OperateLog resolved = findOperateLog(invocationMethod, targetMethod, targetClass);
        this.annotationCache.putIfAbsent(cacheKey, Optional.ofNullable(resolved));
        return resolved;
    }

    /**
     * 注解缓存 key：{@link Method} 按签名比较、{@link Class} 按身份比较，组合后即唯一标识一个切入点。
     */
    private static final class AnnotationCacheKey {
        private final Method method;
        private final Class<?> targetClass;

        private AnnotationCacheKey(Method method, Class<?> targetClass) {
            this.method = method;
            this.targetClass = targetClass;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof AnnotationCacheKey)) {
                return false;
            }
            AnnotationCacheKey that = (AnnotationCacheKey) other;
            return this.method.equals(that.method) && this.targetClass.equals(that.targetClass);
        }

        @Override
        public int hashCode() {
            return 31 * this.method.hashCode() + this.targetClass.hashCode();
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
     * 在目标类的全部接口（含父接口）上找同签名方法的注解，覆盖「只标接口 + JDK 代理」。
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

    /**
     * 解析 traceId：优先取宿主 MDC 值，缺失时生成 UUID 并回写 MDC，使同一请求内多个
     * {@code @OperateLog} 方法（含嵌套）共享同一 traceId。返回值标记是否本组件生成，据此决定收尾是否清理。
     */
    private TraceId resolveTraceId() {
        String mdcKey = StringUtils.defaultIfBlank(this.traceIdMdcKey, DEFAULT_TRACE_ID_MDC_KEY);
        try {
            String existing = MDC.get(mdcKey);
            if (StringUtils.isNotBlank(existing)) {
                return new TraceId(existing, false, mdcKey);
            }
            String generated = UUID.randomUUID().toString();
            MDC.put(mdcKey, generated);
            return new TraceId(generated, true, mdcKey);
        } catch (Throwable ex) {
            // MDC 不可用时退化为「本条记录内唯一」的 ID，不回写也无需清理
            LOGGER.warn("operate-log: traceId resolution failed, degraded to generated UUID.", ex);
            return new TraceId(UUID.randomUUID().toString(), false, mdcKey);
        }
    }

    /**
     * 清理本组件写入的 traceId；清理失败只影响本线程 MDC，不牵连业务与日志收尾。
     */
    private void clearTraceIdQuietly(String mdcKey) {
        try {
            MDC.remove(mdcKey);
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: traceId cleanup failed, MDC entry may remain on this thread.",
                    ex);
        }
    }

    /**
     * traceId 取值结果：值、是否由本组件生成（决定收尾是否清理 MDC）、所用 MDC key。
     */
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

    /**
     * 采集请求侧 HTTP 快照；解析失败只让相关字段为 {@code null}。
     */
    private HttpContext resolveHttpContext() {
        try {
            return this.httpContextResolver.resolve();
        } catch (Throwable ex) {
            LOGGER.warn("operate-log: http context resolution failed, degraded to null.", ex);
            return null;
        }
    }

    /**
     * 组装与落地全程隔离：故障只损失本条日志，原因在 debug 级别暴露。
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

    /**
     * 打印堆栈到限长缓冲：深递归异常堆栈可达数十万行，先构造完整串再截断会先撑起远超上限的内存副本。
     * 上限（含 {@code <=0 不截断}）由 {@link LimitedStringWriter} 处理，此处直接透传。
     */
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
        Map<String, Object> result = new LinkedHashMap<String, Object>();
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

    /**
     * extra 单值降级：只保证「Jackson 能写出」。extra 由开发者主动写入，本组件不脱敏
     * （见 README「不要往 extra 塞敏感数据」）；但一个循环引用 / getter 抛错的坏值会让整条记录
     * 在 handler 侧序列化失败被丢，故逐值探测：标量直返，非标量写不出就换占位符，其余字段照常落地。
     */
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

    /**
     * 逐值收缩最长的值直至整体 JSON 不超上限，一次至少缩 1 字符保证收敛。
     * 相比整表一次截断，逐值收缩能保留排在下方的键（如占位符），不丢诊断线索。
     */
    private void enforceExtraLimit(Map<String, Object> extra, int limit) {
        String serialized = this.serializer.serialize(extra);
        while (serialized != null && serialized.length() > limit && !extra.isEmpty()) {
            Map.Entry<String, Object> longest = null;
            int longestLength = -1;
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                int length = String.valueOf(entry.getValue()).length();
                if (length > longestLength) {
                    longest = entry;
                    longestLength = length;
                }
            }
            if (longest == null || longestLength <= 0) {
                break;
            }
            int target = longestLength - (serialized.length() - limit);
            if (target <= 0) {
                // 缩无可缩（单值本身就超上限）：整表降级到 _truncated，至少保证不超过 limit
                extra.clear();
                extra.put("_truncated", PayloadPolicy.truncate(serialized, limit));
                return;
            }
            extra.put(longest.getKey(),
                    PayloadPolicy.truncate(String.valueOf(longest.getValue()), Math.min(target, longestLength - 1)));
            serialized = this.serializer.serialize(extra);
        }
    }

    /**
     * 写入超过上限后丢弃后续内容（含换行），使内存占用与最终落地长度同阶。
     */
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
