package io.github.devoracode.operatelog.testsupport;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.context.OperateLogContext;
import io.github.devoracode.operatelog.model.OperateType;
import io.github.devoracode.operatelog.model.RecordOn;

import java.lang.reflect.Method;

/**
 * 测试样例：覆盖方法级 / 接口级 / 类级注解形态与各类边界方法。
 *
 * @author devoracode
 */
public final class Samples {

    private Samples() {
    }

    public static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("sample method not found: " + name, ex);
        }
    }

    public static OperateLog annotationOf(Method method) {
        return method.getAnnotation(OperateLog.class);
    }

    /** 以样例方法为蓝本构造切面上下文（joinPoint 传 null，SpEL 不需要）。 */
    public static OperateLogContext contextOf(Method method, Object target, Object[] args) {
        return new OperateLogContext(annotationOf(method), null, method, target, args);
    }

    // ==================== 方法级注解样例 ====================

    public static class Demo {

        @OperateLog(
                module = "user",
                operation = "query",
                type = OperateType.QUERY,
                description = "查询用户 #{#userId}",
                businessId = "#userId")
        public String query(String userId) {
            return "ok-" + userId;
        }

        @OperateLog(
                module = "pay",
                operation = "refund",
                recordOn = RecordOn.ERROR,
                condition = "#error != null")
        public void refund(int amount) {
            throw new IllegalStateException("boom-" + amount);
        }

        @OperateLog(module = "order", operation = "cancel", condition = "#success")
        public String cancel(String orderNo) {
            return "cancelled-" + orderNo;
        }

        @OperateLog(module = "order", operation = "write", recordOn = RecordOn.SUCCESS)
        public String write(String payload) {
            return "written";
        }

        @OperateLog(module = "file", operation = "upload", recordRequest = false)
        public String upload(java.io.InputStream stream) {
            return "uploaded";
        }

        @OperateLog(module = "order", operation = "pay", recordResponse = true)
        public String pay(String orderNo) {
            return "paid-" + orderNo;
        }

        public void plain() {
        }
    }

    // ==================== 类级注解样例（默认值合并） ====================

    @OperateLog(module = "cls", operation = "clsOp", type = OperateType.UPDATE)
    public static class ClassLevel {

        /** 方法级只写 operation：module/type 应继承类级。 */
        @OperateLog(operation = "methodOp")
        public String merged(String x) {
            return x;
        }

        /** 无方法级注解：应整体按类级配置记录。 */
        public String inheritOnly(String x) {
            return x;
        }
    }

    // ==================== 接口方法注解样例（JDK 代理查找链） ====================

    public interface Greeter {

        @OperateLog(module = "iface", operation = "greet", businessId = "#name")
        String greet(String name);
    }

    public static class GreeterImpl implements Greeter {

        public String greet(String name) {
            return "hi-" + name;
        }
    }

    // ==================== 序列化异常样例 ====================

    /** getter 直接抛异常的对象：Jackson 序列化必然失败，触发降级路径。 */
    public static class Boom {

        public String getBoom() {
            throw new IllegalStateException("getter exploded");
        }
    }
}
