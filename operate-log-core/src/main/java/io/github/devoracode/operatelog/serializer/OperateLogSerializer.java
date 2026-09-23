package io.github.devoracode.operatelog.serializer;

/** 操作日志序列化器：把入参 / 返回值等对象转成日志中的字符串。 */
public interface OperateLogSerializer {

    /**
     * 序列化单个对象为日志字段所用的文本。
     *
     * @param value 待序列化对象，如返回值、请求头 Map
     * @return 序列化后的文本；{@code value} 为 {@code null} 时返回 {@code null}
     */
    String serialize(Object value);

    /**
     * 序列化方法参数数组。默认委托 {@link #serialize(Object)}；
     * 覆写可实现「整数组失败 → 逐元素降级」，避免单个坏参数拖垮整条日志。
     *
     * @param arguments 业务方法的实参数组
     * @return 参数数组的序列化文本；默认实现即整个数组交给 {@link #serialize(Object)} 的结果
     */
    default String serializeArguments(Object[] arguments) {
        return serialize(arguments);
    }
}
