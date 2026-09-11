package io.github.devoracode.operatelog.test.boot2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 序列化必坏的测试载体：getter 抛异常让 Jackson 写不出去，用来验证
 * {@code serializeArguments} 的逐元素降级——坏参数换成占位符，同批其他参数照常入日志，
 * 业务方法不受影响。
 */
public class UnserializableHolder {
    private String note = "ignored";

    public String getNote() {
        return this.note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    /** 任何序列化尝试都会走到这里抛异常。 */
    public Map<String, Object> getBoom() {
        throw new IllegalStateException("intentionally unserializable");
    }
}
