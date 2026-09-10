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
}
