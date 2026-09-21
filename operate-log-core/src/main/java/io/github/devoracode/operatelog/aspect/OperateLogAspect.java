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
 * <p>注解查找链（兼容全部代理形态）：目标类 most-specific 方法 → 调用方法（JDK 代理即接口方法）
 * → 目标类实现的全部接口上的同签名方法。CGLIB 代理（Boot 默认）下注解只标在接口方法上时
 * advice 不会织入，需把注解放到实现类方法上。</p>
 *
 * <p>业务零影响纪律：日志侧故障只以 warn / debug 暴露，业务异常原样透传；
 * {@code finally} 收尾不得抛出（{@code finishQuietly} 兜底）。</p>
 *
 * <p>切面顺序显式声明为 {@link Ordered#LOWEST_PRECEDENCE}，不再依赖默认值推断；
 * {@code success} 的语义是「业务方法未抛异常」，<b>不含</b>事务提交结果。若宿主的事务通知落在
 * 本切面内层，业务方法返回后事务才提交，提交失败并回滚时日志已写为成功。需要「日志与事务结果
 * 一致」的审计结论时，请为事务通知显式指定更低 order（使其位于本切面外层），或改用延后落地的
 * {@code OperateLogHandler}。</p>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class OperateLogAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(OperateLogAspect.class);
    /**
     * traceId 默认 MDC key，可由 {@code operate-log.trace-id-mdc-key} 覆盖。
     */
    private static final String DEFAULT_TRACE_ID_MDC_KEY = "traceId";
    /**
     * {@link io.github.devoracode.operatelog.serializer.DefaultOperateLogSerializer} 序列化失败时的哨兵串；
     * 探测 extra 单值能否被 Jackson 写出时按它判定。
     */
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
    /**
     * 注解查找结果缓存：定容 LRU，热部署时旧 ClassLoader 的 Method/Class 引用随淘汰自然释放。
     */
    private final Map<AnnotationCacheKey, Optional<OperateLog>> annotationCache;

    /**
     * 供宿主直接 {@code new} 以自定义切点（见 README「注解属性」）。参数顺序即二进制签名：
     * <b>新增字段一律追加到末尾，禁止插入或重排</b>——切面是用户可覆盖的扩展点，
     * 改序会让已编译的宿主实现静默错位（Lombok {@code @RequiredArgsConstructor} 不再使用，
     * 正是为了让该约束在源码里可见、可评审）。
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

    @Around("@annotation(io.github.devoracode.operatelog.annotation.OperateLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        final Method invocationMethod;
        final Class<?> targetClass;
        final Method targetMethod;
        final OperateLog annotation;
        try {
            // 签名与目标类解析同样是日志侧工作，异常不得穿透到业务
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
                finishQuietly(context);
            } finally {
                // 嵌套标注方法场景：恢复外层上下文而非直接清空，防止 ThreadLocal 泄漏与外层丢数据
                restorePreviousContext(previous);
                // 只清理本组件生成的 traceId：MDC 原有值属于宿主链路追踪体系，不得越权抹除
                if (traceId.generated()) {
                    clearTraceIdQuietly(traceId.mdcKey());
                }
            }
        }
    }

    /**
     * 日志收尾：计时 → 组装并落地。绝不向外抛出：运行在切面 {@code finally} 中，
     * 逃逸异常会顶替业务异常或污染返回值。
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

    /**
     * 嵌套场景恢复外层上下文，否则解绑当前线程。
     */
    private void restorePreviousContext(OperateLogContext previous) {
        if (previous == null) {
            OperateLogContextHolder.unbind();
        } else {
            OperateLogContextHolder.bind(previous);
        }
    }

    /**
     * {@code recordOn} 过滤：SUCCESS 只在正常返回时记，ERROR 只在抛异常时记；先于 SpEL 条件短路。
     */
    private boolean shouldRecord(OperateLogContext context) {
        // 注解属性不可能返回 null（无默认值也必须显式赋值），无需空值防御
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
     * 带缓存的注解查找。{@link #findOperateLog} 内含多轮反射与接口链扫描，
     * 而同一切入点的注解在运行期不会变，故按「调用方法 + 目标类」缓存；
     * targetClass 必须入 key——JDK 代理下同一接口方法可对应多个实现类，结果可能不同。
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
     * 解析 traceId：优先取 MDC（宿主链路追踪体系写入的值），缺失时生成 UUID <b>并回写 MDC</b>，
     * 使同一次请求内多个 {@code @OperateLog} 方法共享同一 traceId（嵌套调用不再各生成一个）。
     * 返回值标记该值是否由本组件生成，据此决定收尾时是否清理 MDC。
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
        // 截断在脱敏之后执行：无论脱敏使内容变长还是变短，落地的最终体积都不越界。
        // query / userAgent 与 JSON 字段同属客户端可控输入，必须一并受 request 上限保护
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
     * 打印堆栈到限长缓冲：深递归异常（如无限递归）的完整堆栈可达数十万行，
     * 先构造完整字符串再截断会在内存里先撑起远超上限的副本。
     * 上限（含 {@code <=0 不截断} 语义）由 {@link LimitedStringWriter} 自身处理，此处直接透传。
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
     * 单值降级：仅保证「Jackson 能写出」这一件事。extra 由开发者在业务方法内主动写入，
     * 本组件不做脱敏（脱敏是字段级安全控制，需要开发者明示字段的语义，工具替他们判定「哪个值是敏感」
     * 只会误伤——README 明确提醒不要往 extra 里塞敏感数据）。
     * 但一个循环引用 / getter 抛错的坏值会让整条记录在 handler 侧序列化失败被丢，所以必须逐值探测：
     * 标量直返；非标量先序列化，写不出（{@link #UNSERIALIZABLE_SENTINEL}）就换成占位符，
     * 让其他字段照常落地。
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
     * 逐值收缩直至整体 JSON 不超上限；一次收缩至少缩 1 字符以保证收敛。
     * 相比「整表转字符串一次截断」的降级，逐值收缩能保留排在下方的键（如 UNSERIALIZABLE 占位符）——
     * 整表截断会让落在窗口外的诊断线索丢失，反而更难定位问题。
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
