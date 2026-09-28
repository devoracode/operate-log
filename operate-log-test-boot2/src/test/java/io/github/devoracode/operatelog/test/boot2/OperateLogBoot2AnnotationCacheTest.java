package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OperateLogBoot2AnnotationCacheTest {

    private static final int CACHE_CAPACITY = 80;
    private static final int OVERFLOW_LOOKUP_COUNT = CACHE_CAPACITY + 1;

    @Test
    void annotationCachesAreAssociatedWithTargetClass() throws Exception {
        OperateLogAspect aspect = newAspect();
        ClassValue<?> classValues = annotationCaches(aspect);

        Object first = classValues.get(String.class);
        Object sameClass = classValues.get(String.class);
        Object otherClass = classValues.get(Integer.class);

        assertTrue(first instanceof Map, "单类注解缓存应为 Map: " + first);
        assertSame(first, sameClass);
        assertNotSame(first, otherClass);
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
                "需要足够多的 Method 作为单类冷缓存键");
        fillDistinctLookups(aspect, lookup, CACHE_CAPACITY, Class.class);

        Map<?, ?> cache = annotationCache(aspect, Class.class);
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

        Map<?, ?> cache = annotationCache(aspect, getClass());
        Object key = cacheKey(unannotated, getClass());
        Object cached = cache.get(key);
        assertNotNull(cached, "未标注方法也应缓存，否则每次调用都要重新反射查找");
        assertTrue(cached instanceof Optional);
        assertFalse(((Optional<?>) cached).isPresent());
        cache.remove(key);

        assertFalse(cache.containsKey(key));
        assertNull(lookup.invoke(aspect, unannotated, unannotated, getClass()));
        assertTrue(cache.containsKey(key));
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

    private static ClassValue<?> annotationCaches(OperateLogAspect aspect) throws Exception {
        Field field;
        try {
            field = OperateLogAspect.class.getDeclaredField("annotationCaches");
        } catch (NoSuchFieldException ex) {
            fail("OperateLogAspect 必须按目标 Class 关联注解缓存");
            return null;
        }
        field.setAccessible(true);
        return (ClassValue<?>) field.get(aspect);
    }

    private static Map<?, ?> annotationCache(OperateLogAspect aspect, Class<?> targetClass) throws Exception {
        return (Map<?, ?>) annotationCaches(aspect).get(targetClass);
    }

    private static Object cacheKey(Method method, Class<?> targetClass) throws Exception {
        Class<?> keyType = Class.forName(
                "io.github.devoracode.operatelog.aspect.OperateLogAspect$AnnotationCacheKey");
        Constructor<?> constructor = keyType.getDeclaredConstructor(Method.class, Class.class);
        constructor.setAccessible(true);
        return constructor.newInstance(method, targetClass);
    }
}
