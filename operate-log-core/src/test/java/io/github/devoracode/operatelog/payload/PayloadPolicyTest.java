package io.github.devoracode.operatelog.payload;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayloadPolicy} 单测：截断边界与忽略类型过滤。
 *
 * @author devoracode
 */
class PayloadPolicyTest {

    private static PayloadPolicy unlimited(Set<String> ignored) {
        return new PayloadPolicy(0, 0, 0, ignored);
    }

    // ==================== 截断 ====================

    @Test
    void truncateBoundaries() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            builder.append('x');
        }
        String text = builder.toString();

        // 恰好等于上限：原样返回（同一实例）
        assertSame(text, PayloadPolicy.truncate(text, 100));
        // 超上限：截断并打标记
        assertEquals(text.substring(0, 99) + "...[truncated]",
                PayloadPolicy.truncate(text, 99));
        // <=0 表示不截断
        assertSame(text, PayloadPolicy.truncate(text, 0));
        assertSame(text, PayloadPolicy.truncate(text, -5));
        assertEquals(null, PayloadPolicy.truncate(null, 10));
    }

    @Test
    void fieldTruncationUsesConfiguredLimits() {
        PayloadPolicy policy = new PayloadPolicy(3, 5, 2, Collections.<String>emptySet());
        assertEquals("abc...[truncated]", policy.truncateRequest("abcdefg"));
        assertEquals("abcde...[truncated]", policy.truncateResponse("abcdefg"));
        assertEquals("ab...[truncated]", policy.truncateErrorStack("abcdefg"));
    }

    // ==================== 忽略类型过滤 ====================

    @Test
    void noIgnoreTypesReturnsSameArrayInstance() {
        Object[] args = new Object[]{"a", 1};
        assertSame(args, unlimited(Collections.<String>emptySet())
                .filterArguments(args));
        assertSame(args, new PayloadPolicy(0, 0, 0, null).filterArguments(args));
    }

    @Test
    void matchesByInterfaceNameCoversAllImplementations() {
        PayloadPolicy policy = unlimited(
                Collections.singleton("java.io.Serializable"));
        Object anon = new Object();
        Object[] args = new Object[]{"string-is-serializable", 42, anon};

        Object[] filtered = policy.filterArguments(args);
        assertEquals("<IGNORED:String>", filtered[0]);
        assertEquals("<IGNORED:Integer>", filtered[1]);
        // 匿名 Object 非 Serializable，原样保留
        assertSame(anon, filtered[2]);
    }

    @Test
    void matchesAlongSuperclassChain() {
        PayloadPolicy policy = unlimited(Collections.singleton(
                "java.io.InputStream"));
        Object[] args = new Object[]{new ByteArrayInputStream(new byte[0])};
        Object[] filtered = policy.filterArguments(args);
        assertEquals("<IGNORED:ByteArrayInputStream>", filtered[0]);
    }

    @Test
    void byteArraysMatchByInternalName() {
        PayloadPolicy policy = unlimited(Collections.<String>singleton("[B"));
        Object[] args = new Object[]{new byte[1024]};
        Object[] filtered = policy.filterArguments(args);
        assertEquals("<IGNORED:byte[]>", filtered[0]);
    }

    @Test
    void nullAndEmptyArgumentsSafe() {
        PayloadPolicy policy = unlimited(Collections.singleton("java.io.Serializable"));
        assertEquals(null, policy.filterArguments(null));
        assertEquals(0, policy.filterArguments(new Object[0]).length);
        // null 元素原样保留（不会 NPE）
        Object[] args = new Object[]{null};
        assertSame(args, policy.filterArguments(args));
    }

    @Test
    void originalArgumentsNeverMutatedOnHit() {
        PayloadPolicy policy = unlimited(Collections.singleton("java.io.Serializable"));
        Object[] args = new Object[]{"x"};
        Object[] filtered = policy.filterArguments(args);
        assertEquals("x", args[0]);
        assertTrue(filtered != args);
        // 空忽略列表：零拷贝直通
        assertSame(args, new PayloadPolicy(0, 0, 0,
                new HashSet<String>()).filterArguments(args));
    }
}
