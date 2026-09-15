package io.github.devoracode.operatelog.serializer;

/** 操作日志序列化器：把入参 / 返回值等对象转成日志中的字符串。 */
public interface OperateLogSerializer {

    String serialize(Object value);

    /**
     * 序列化方法参数数组。默认委托 {@link #serialize(Object)}；
     * 覆写可实现「整数组失败 → 逐元素降级」，避免单个坏参数拖垮整条日志。
     */
    default String serializeArguments(Object[] arguments) {
        return serialize(arguments);
    }
}
