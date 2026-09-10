package io.github.devoracode.operatelog.serializer;

/**
 * 操作日志序列化器。
 *
 * @author devoracode
 */
public interface OperateLogSerializer {

    /**
     * 序列化对象。
     *
     * @param value 对象
     * @return 序列化结果
     */
    String serialize(Object value);

    /**
     * 序列化方法参数数组。
     *
     * <p>默认实现直接委托 {@link #serialize(Object)}；实现方可覆写本方法，
     * 提供「整数组失败 → 逐元素降级」的能力，避免单个坏参数拖垮整条日志。</p>
     *
     * @param arguments 方法参数数组
     * @return 序列化结果
     */
    default String serializeArguments(Object[] arguments) {
        return serialize(arguments);
    }
}
