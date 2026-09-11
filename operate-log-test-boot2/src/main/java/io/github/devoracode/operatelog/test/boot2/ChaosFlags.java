package io.github.devoracode.operatelog.test.boot2;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志侧组件的「按需故障」开关，供 Business Zero Impact 用例逐个点亮。
 *
 * <p>用静态开关而不是为每个故障场景另起 Spring 上下文：注入点（{@link TestOperatorResolver}
 * 与测试里的 serializer / handler bean）要和真实容器组合验证，一个上下文即可覆盖全部场景。
 * 默认全关，冒烟运行行为不变；用例内 {@code enable} / {@code disableAll} 成对出现，
 * 集合用并发容器兜底。</p>
 */
public final class ChaosFlags {
    public static final String OPERATOR = "operator";
    public static final String SERIALIZER = "serializer";
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

    /** 命中开关即抛异常；文案要与业务异常明显不同，便于断言业务异常未被顶替。 */
    public static void throwIfActive(String flag, String message) {
        if (isActive(flag)) {
            throw new IllegalStateException(message);
        }
    }
}
