package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OperateLogBoot3AnnotationCacheTest {

    private static final int CACHE_CAPACITY = 80;
    private static final int OVERFLOW_LOOKUP_COUNT = CACHE_CAPACITY + 1;

    @Test
    void annotationCacheKeysCarryTargetClassAndMethodSignature() throws Exception {
        OperateLogAspect aspect = newAspect();
        Method lookup = lookupMethod();
        Method method = String.class.getDeclaredMethods()[0];

        lookup.invoke(aspect, method, method, String.class);
        lookup.invoke(aspect, method, method, Integer.class);

        // 同一个方法配不同目标类是两个不同键：注解解析会遍历目标类实现的接口，结果不只取决于方法
        Map<?, ?> cache = annotationCaches(aspect);
        assertEquals(2, cache.size(), cache.keySet().toString());
        for (Object key : cache.keySet()) {
            String text = String.valueOf(key);
            assertTrue(text.contains("#") && text.contains("("),
                    "键应形如 目标类#方法签名: " + text);
        }
    }

    /**
     * 缓存是单层 {@code String} 键的普通 map：既非 {@link ClassValue}，也不再按目标类分嵌套。
     * 键只存类名与签名字符串，不持有 {@link Class} 引用，因此旧类可被卸载。
     */
    @Test
    void annotationCachesAreASingleFlatStringKeyedMap() throws Exception {
        String declared = annotationCachesField().getGenericType().getTypeName();

        assertTrue(declared.startsWith("java.util.concurrent.ConcurrentHashMap<java.lang.String"),
                declared);
        assertFalse(declared.contains("ClassValue"), declared);
        assertFalse(declared.contains("java.lang.Class"), declared);
    }

    @Test
    void aspectNoLongerReferencesCaffeine() {
        for (Field field : OperateLogAspect.class.getDeclaredFields()) {
            String declared = field.getGenericType().getTypeName();
            assertFalse(declared.contains("caffeine"),
                    "切面不应再依赖 Caffeine: " + field.getName() + " -> " + declared);
        }
    }

    @Test
    void annotationCacheNeverExceedsConfiguredSize() throws Exception {
        OperateLogAspect aspect = newAspect();
        Method lookup = lookupMethod();
        assertTrue(candidateMethods(Class.class).length > CACHE_CAPACITY,
                "需要足够多的 Method 作为冷缓存键");
        fillDistinctLookups(aspect, lookup, CACHE_CAPACITY, Class.class);

        Map<?, ?> cache = annotationCaches(aspect);
        // size() 是精确值，不存在 Caffeine 的 estimatedSize 与 cleanUp
        assertEquals(CACHE_CAPACITY, cache.size(), "缓存已填满但未达到配置容量");

        fillDistinctLookups(aspect, lookup, OVERFLOW_LOOKUP_COUNT, Class.class);
        assertTrue(cache.size() <= CACHE_CAPACITY, "缓存超过配置容量: " + cache.size());
    }

    @Test
    void negativeAnnotationIsCachedAndResolvedAgain() throws Exception {
        OperateLogAspect aspect = newAspect();
        Method lookup = lookupMethod();
        Method unannotated = getClass().getDeclaredMethod("unannotatedMethod");
        assertNull(lookup.invoke(aspect, unannotated, unannotated, getClass()));

        Map<?, ?> cache = annotationCaches(aspect);
        assertEquals(1, cache.size(), cache.keySet().toString());
        Object key = cache.keySet().iterator().next();
        Object cached = cache.get(key);
        assertNotNull(cached, "未标注方法也应缓存，否则每次调用都要重新反射查找");
        assertTrue(cached instanceof Optional);
        assertFalse(((Optional<?>) cached).isPresent());
        cache.remove(key);

        assertEquals(0, cache.size());
        assertNull(lookup.invoke(aspect, unannotated, unannotated, getClass()));
        assertEquals(1, cache.size());
    }

    /**
     * 缓存键是字符串而非 {@link Method}：历史上为携带 targetClass 而存在的包装类已是纯样板。
     * 本断言防止它作为「更清晰」的空壳被重新引入。
     */
    @Test
    void vestigialCacheKeyWrapperIsNotRevived() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "io.github.devoracode.operatelog.aspect.OperateLogAspect$AnnotationCacheKey"));
    }

    private void unannotatedMethod() {
    }

    private static OperateLogAspect newAspect() {
        return new OperateLogAspect(null, null, null, null, null, null,
                "", "", "", false, null, null, CACHE_CAPACITY);
    }

    private static Method lookupMethod() throws Exception {
        Method lookup = OperateLogAspect.class.getDeclaredMethod("findOperateLogCached",
                Method.class, Method.class, Class.class);
        lookup.setAccessible(true);
        return lookup;
    }

    private static Method[] candidateMethods(Class<?> targetClass) {
        Method[] primary = targetClass.getMethods();
        Method[] secondary = String.class.getMethods();
        Method[] candidates = new Method[primary.length + secondary.length];
        System.arraycopy(primary, 0, candidates, 0, primary.length);
        System.arraycopy(secondary, 0, candidates, primary.length, secondary.length);
        return candidates;
    }

    private static void fillDistinctLookups(OperateLogAspect aspect, Method lookup, int count,
                                            Class<?> targetClass) throws Exception {
        Method[] methods = candidateMethods(targetClass);
        for (int i = 0; i < count; i++) {
            Method method = methods[i % methods.length];
            lookup.invoke(aspect, method, method, targetClass);
        }
    }

    private static Field annotationCachesField() throws Exception {
        try {
            Field field = OperateLogAspect.class.getDeclaredField("annotationCaches");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ex) {
            fail("OperateLogAspect 必须按目标 Class 关联注解缓存");
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Optional<OperateLog>> annotationCaches(OperateLogAspect aspect) throws Exception {
        return (Map<String, Optional<OperateLog>>) annotationCachesField().get(aspect);
    }
}
