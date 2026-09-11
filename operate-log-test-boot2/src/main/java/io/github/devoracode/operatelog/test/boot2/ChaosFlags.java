package io.github.devoracode.operatelog.test.boot2;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志侧组件的「按需故障」开关，供 Business Zero Impact 用例逐个点亮。
 *
 * <p>之所以用静态开关而不是为每个故障场景起一个 Spring 上下文：这些故障注入点
 * （{@link TestOperatorResolver}、测试内的 serializer / handler Bean）需要与真实容器
 * 组合验证，而一个上下文 + 用例内开关注入既能覆盖全部场景，又让用例之间互不串扰。
 * 默认全关，因此 {@code mvn spring-boot:run} 冒烟流程行为不变。</p>
 *
 * <p>JUnit 默认单线程执行同一测试类，用例内 {@code enable} / {@code disableAll} 成对出现即安全；
 * 集合本身用并发容器兜底，避免将来开启并行执行时静默失效。</p>
 *
 * @author devoracode
 */
public final class ChaosFlags {
    /**
     * {@code OperatorResolver} 抛异常。
     */
    public static final String OPERATOR = "operator";
    /**
     * {@code OperateLogSerializer} 抛异常。
     */
    public static final String SERIALIZER = "serializer";
    /**
     * {@code OperateLogHandler} 抛异常。
     */
    public static final String HANDLER = "handler";

    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();

    private ChaosFlags() {
    }

    public static void enable(String... flags) {
        ACTIVE.addAll(Arrays.asList(flags));
    }

    public static void disableAll() {
        ACTIVE.clear();
    }

    public static boolean isActive(String flag) {
        return ACTIVE.contains(flag);
    }

    /**
     * 命中开关即抛出异常，用于注入「日志侧组件故障」。
     *
     * @param flag 开关名
     * @param message 异常文案（必须与业务异常文案明显不同，便于断言「业务异常未被顶替」）
     */
    public static void throwIfActive(String flag, String message) {
        if (isActive(flag)) {
            throw new IllegalStateException(message);
        }
    }
}
