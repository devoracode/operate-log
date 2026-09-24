package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OperateLogBoot2AnnotationCacheTest {

    private static final int CACHE_CAPACITY = 80;
    private static final int OVERFLOW_LOOKUP_COUNT = 160;

    @Test
    void annotationCacheUsesCaffeineAndEvictsToConfiguredSize() throws Exception {
        OperateLogAspect aspect = newAspect();
        Method lookup = lookupMethod();
        fillDistinctLookups(aspect, lookup, CACHE_CAPACITY);

        Object cache = annotationCache(aspect);
        Class<?> caffeineCacheType = caffeineCacheType();
        assertTrue(caffeineCacheType.isInstance(cache));
        caffeineCacheType.getMethod("cleanUp").invoke(cache);
        int configuredSize = ((Number) caffeineCacheType.getMethod("estimatedSize").invoke(cache)).intValue();
        assertTrue(configuredSize > 64,
                "配置容量应高于构造器下限 64: " + configuredSize);

        fillDistinctLookups(aspect, lookup, OVERFLOW_LOOKUP_COUNT);
        caffeineCacheType.getMethod("cleanUp").invoke(cache);
        int size = ((Number) caffeineCacheType.getMethod("estimatedSize").invoke(cache)).intValue();
        assertTrue(size <= CACHE_CAPACITY, "Caffeine 缓存超过配置容量: " + size);
    }

    @Test
    void missingAnnotationIsResolvedAgain() throws Exception {
        OperateLogAspect aspect = newAspect();
        Method lookup = lookupMethod();
        Method annotated = getClass().getDeclaredMethod("annotatedMethod");
        assertNotNull(lookup.invoke(aspect, annotated, annotated, getClass()));

        Object cache = annotationCache(aspect);
        Class<?> caffeineCacheType = caffeineCacheType();
        Map<?, ?> entries = (Map<?, ?>) caffeineCacheType.getMethod("asMap").invoke(cache);
        Object key = cacheKey(annotated, getClass());
        caffeineCacheType.getMethod("invalidate", Object.class).invoke(cache, key);

        assertFalse(entries.containsKey(key));
        assertNotNull(lookup.invoke(aspect, annotated, annotated, getClass()));
        assertTrue(entries.containsKey(key));
    }

    @OperateLog(module = "cache", operation = "eviction")
    private void annotatedMethod() {
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

    private static void fillDistinctLookups(OperateLogAspect aspect, Method lookup, int count) throws Exception {
        Method[] methods = String.class.getMethods();
        Class<?>[] targetClasses = {String.class, Integer.class, Object.class};
        assertTrue(methods.length * targetClasses.length >= count,
                "需要足够多的不同 Method / Class 组合作为冷缓存键");
        for (int i = 0; i < count; i++) {
            Method method = methods[i % methods.length];
            Class<?> targetClass = targetClasses[i / methods.length];
            lookup.invoke(aspect, method, method, targetClass);
        }
    }

    private static Object annotationCache(OperateLogAspect aspect) throws Exception {
        Field field = OperateLogAspect.class.getDeclaredField("annotationCache");
        field.setAccessible(true);
        return field.get(aspect);
    }

    private static Object cacheKey(Method method, Class<?> targetClass) throws Exception {
        Class<?> keyType = Class.forName(
                "io.github.devoracode.operatelog.aspect.OperateLogAspect$AnnotationCacheKey");
        Constructor<?> constructor = keyType.getDeclaredConstructor(Method.class, Class.class);
        constructor.setAccessible(true);
        return constructor.newInstance(method, targetClass);
    }

    private static Class<?> caffeineCacheType() throws Exception {
        try {
            return Class.forName("com.github.benmanes.caffeine.cache.Cache");
        } catch (ClassNotFoundException ex) {
            fail("OperateLogAspect 必须使用 Caffeine Cache");
            return null;
        }
    }
}
