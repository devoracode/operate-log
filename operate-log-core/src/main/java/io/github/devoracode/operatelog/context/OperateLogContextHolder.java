package io.github.devoracode.operatelog.context;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 当前线程的操作日志上下文持有器：业务方法内用 {@link #putExtra(String, Object)} 追加自定义字段，
 * 随 {@code OperateLogRecord#extra} 落地。
 *
 * <p>绑定与清理由 {@code OperateLogAspect} 负责，业务只写不读；切面管辖范围之外
 * （如业务另起的异步线程）调用是安全 no-op；嵌套标注方法各自独立上下文；
 * 值直接进序列化，勿放入未脱敏的敏感数据。</p>
 */
public final class OperateLogContextHolder {
    private static final ThreadLocal<OperateLogContext> HOLDER = new ThreadLocal<OperateLogContext>();

    private OperateLogContextHolder() {
    }

    /**
     * 当前线程上下文。
     *
     * @return 已绑定的执行上下文；切面管辖范围之外（未绑定）为 {@code null}
     */
    public static OperateLogContext current() {
        return HOLDER.get();
    }

    /**
     * 追加单个自定义字段；空键忽略。
     *
     * @param key   自定义字段名
     * @param value 字段值，未经脱敏直接序列化落地
     */
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

    /**
     * 批量追加自定义字段；{@code null} 或空集合忽略。
     *
     * @param extras 待合并的字段集合，同名键覆盖已有值
     */
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

    /**
     * 绑定上下文。<b>框架内部使用</b>，业务代码请勿调用。
     *
     * @param context 切面为本次调用创建的上下文
     */
    public static void bind(OperateLogContext context) {
        HOLDER.set(context);
    }

    /**
     * 解绑当前线程上下文。<b>框架内部使用</b>，业务代码请勿调用。
     */
    public static void unbind() {
        HOLDER.remove();
    }
}
