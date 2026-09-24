package io.github.devoracode.operatelog.context;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 当前线程的操作日志上下文持有器：业务方法内与各 Resolver 实现内用
 * {@link #putExtra(String, Object)} 追加自定义字段，随 {@code OperateLogRecord#extra} 落地。
 *
 * <p>绑定与清理由 {@code OperateLogAspect} 负责，业务只写不读；切面管辖范围之外
 * （如业务另起的异步线程）调用是安全 no-op；嵌套标注方法各自独立上下文；
 * 值直接进序列化，勿放入未脱敏的敏感数据。</p>
 */
public final class OperateLogContextHolder {
    private static final ThreadLocal<OperateLogContext> HOLDER = new ThreadLocal<>();

    private OperateLogContextHolder() {
    }

    public static OperateLogContext current() {
        return HOLDER.get();
    }

    public static void putExtra(String key, Object value) {
        if (StringUtils.isEmpty(key)) {
            return;
        }
        OperateLogContext context = HOLDER.get();
        if (context == null) {
            return;
        }
        context.getExtra().put(key, value);
    }

    public static void putExtras(Map<String, ?> extras) {
        if (extras == null || extras.isEmpty()) {
            return;
        }
        OperateLogContext context = HOLDER.get();
        if (context == null) {
            return;
        }
        context.getExtra().putAll(extras);
    }

    public static void bind(OperateLogContext context) {
        HOLDER.set(context);
    }

    public static void unbind() {
        HOLDER.remove();
    }
}
