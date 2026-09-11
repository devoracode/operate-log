package io.github.devoracode.operatelog.test.boot2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 序列化必坏的测试载体：getter 抛异常，令 Jackson 无法写出该对象。
 *
 * <p>用于验证 {@code OperateLogSerializer#serializeArguments} 的逐元素降级
 * ——单个坏参数替换为 {@code <UNSERIALIZABLE:UnserializableHolder>}，同批次其他参数照常入日志，
 * 业务方法本身更不受影响。故意只暴露 getter，不参与 Spring MVC 的请求体反序列化之外的逻辑。</p>
 *
 * @author devoracode
 */
public class UnserializableHolder {
    private String note = "ignored";

    public String getNote() {
        return this.note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    /**
     * 任何序列化尝试都会走到这里抛出异常。
     */
    public Map<String, Object> getBoom() {
        throw new IllegalStateException("intentionally unserializable");
    }
}
