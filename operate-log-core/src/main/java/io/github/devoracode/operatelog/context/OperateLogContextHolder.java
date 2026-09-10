package io.github.devoracode.operatelog.context;

import java.util.Map;

/**
 * 当前线程的操作日志上下文持有器。
 *
 * <p>业务方法内可通过 {@link #putExtra(String, Object)} 向当前日志记录追加自定义字段，
 * 这些字段随 {@code OperateLogRecord#extra} 一起序列化落地，无需为每个业务字段扩展记录模型。</p>
 *
 * <pre>{@code
 * @OperateLog(module = "order", operation = "cancel", type = OperateType.UPDATE)
 * public void cancel(String orderNo) {
 *     Order order = orderService.get(orderNo);
 *     OperateLogContextHolder.putExtra("channel", order.getChannel());
 *     OperateLogContextHolder.putExtra("amount", order.getAmount());
 *     ...
 * }
 * }</pre>
 *
 * <p>行为约定：</p>
 * <ul>
 *   <li>绑定与清理由 {@code OperateLogAspect} 负责，业务代码只写不读；</li>
 *   <li>切面管辖范围之外（如业务内部另起的异步线程）调用为安全 no-op，不会抛异常；
 *       异步线程中写入的值不会进入日志记录；</li>
 *   <li>嵌套标注方法各自持有独立上下文，内层写入只影响内层记录；</li>
 *   <li>值会被序列化器直接写入日志，请勿放入未脱敏的敏感数据。</li>
 * </ul>
 *
 * @author devoracode
 */
public final class OperateLogContextHolder {

    private static final ThreadLocal<OperateLogContext> HOLDER =
            new ThreadLocal<OperateLogContext>();

    private OperateLogContextHolder() {
    }

    /**
     * 获取当前线程绑定的操作日志上下文。
     *
     * @return 当前上下文，切面管辖范围之外返回 {@code null}
     */
    public static OperateLogContext current() {
        return HOLDER.get();
    }

    /**
     * 追加单个自定义字段。
     *
     * @param key   字段名，空键忽略
     * @param value 字段值，由序列化器负责落地
     */
    public static void putExtra(String key, Object value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        OperateLogContext context = HOLDER.get();
        if (context == null) {
            return;
        }
        context.getExtra().put(key, value);
    }

    /**
     * 批量追加自定义字段。
     *
     * @param extras 字段集合，{@code null} 或空集合忽略
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
     * 绑定上下文。<b>仅供切面框架调用</b>，业务代码请勿使用。
     *
     * @param context 待绑定上下文
     */
    public static void bind(OperateLogContext context) {
        HOLDER.set(context);
    }

    /**
     * 解绑当前线程上下文。<b>仅供切面框架调用</b>，业务代码请勿使用。
     */
    public static void unbind() {
        HOLDER.remove();
    }
}
