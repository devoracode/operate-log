package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

        assertTrue(first instanceof com.github.benmanes.caffeine.cache.Cache);
        assertSame(first, sameClass);
        assertNotSame(first, otherClass);
    }

    @Test
    void annotationCacheUsesCaffeineAndEvictsToConfiguredSize() throws Exception {
        OperateLogAspect aspect = newAspect();
        Method lookup = lookupMethod();
        Method[] methods = candidateMethods(Class.class);
        assertTrue(methods.length > CACHE_CAPACITY,
                "需要足够多的 Method 作为单类冷缓存键");
        fillDistinctLookups(aspect, lookup, CACHE_CAPACITY, Class.class);

        Object cache = annotationCache(aspect, Class.class);
        Class<?> caffeineCacheType = caffeineCacheType();
        caffeineCacheType.getMethod("cleanUp").invoke(cache);
        int configuredSize = ((Number) caffeineCacheType.getMethod("estimatedSize").invoke(cache)).intValue();
        assertTrue(configuredSize > 64 && configuredSize <= CACHE_CAPACITY,
                "单类缓存应遵循高于下限的配置容量: " + configuredSize);

        fillDistinctLookups(aspect, lookup, OVERFLOW_LOOKUP_COUNT, Class.class);
        caffeineCacheType.getMethod("cleanUp").invoke(cache);
        int size = ((Number) caffeineCacheType.getMethod("estimatedSize").invoke(cache)).intValue();
        assertTrue(size <= CACHE_CAPACITY, "Caffeine 缓存超过配置容量: " + size);
    }

    @Test
    void negativeAnnotationIsCachedAndResolvedAgain() throws Exception {
        OperateLogAspect aspect = newAspect();
        Method lookup = lookupMethod();
        Method unannotated = getClass().getDeclaredMethod("unannotatedMethod");
        assertNull(lookup.invoke(aspect, unannotated, unannotated, getClass()));

        Object cache = annotationCache(aspect, getClass());
        Class<?> caffeineCacheType = caffeineCacheType();
        Map<?, ?> entries = (Map<?, ?>) caffeineCacheType.getMethod("asMap").invoke(cache);
        Object key = cacheKey(unannotated, getClass());
        Object cached = entries.get(key);
        assertTrue(cached instanceof Optional);
        assertFalse(((Optional<?>) cached).isPresent());
        caffeineCacheType.getMethod("invalidate", Object.class).invoke(cache, key);

        assertFalse(entries.containsKey(key));
        assertNull(lookup.invoke(aspect, unannotated, unannotated, getClass()));
        assertTrue(entries.containsKey(key));
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

    private static Object annotationCache(OperateLogAspect aspect, Class<?> targetClass) throws Exception {
        return annotationCaches(aspect).get(targetClass);
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
