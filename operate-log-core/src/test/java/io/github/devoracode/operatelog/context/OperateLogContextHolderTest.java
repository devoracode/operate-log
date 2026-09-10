package io.github.devoracode.operatelog.context;

import io.github.devoracode.operatelog.testsupport.Samples;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link OperateLogContextHolder} 单测：安全 no-op、写入、解绑。
 *
 * @author devoracode
 */
class OperateLogContextHolderTest {

    @Test
    void outsideOfAspectScopeIsSafeNoOp() throws Exception {
        OperateLogContextHolder.unbind();
        assertNull(OperateLogContextHolder.current());

        OperateLogContextHolder.putExtra("k", "v");
        OperateLogContextHolder.putExtras(null);
        OperateLogContextHolder.putExtras(Collections.<String, Object>emptyMap());
        // 不抛异常即为通过；unbound 状态下无任何副作用
        assertNull(OperateLogContextHolder.current());
    }

    @Test
    void writesIntoBoundContext() throws Exception {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        OperateLogContext ctx = Samples.contextOf(m, new Samples.Demo(), new Object[]{"1"});

        OperateLogContextHolder.bind(ctx);
        try {
            assertSame(ctx, OperateLogContextHolder.current());
            OperateLogContextHolder.putExtra("k1", "v1");
            Map<String, Object> more = new LinkedHashMap<String, Object>();
            more.put("k2", 2);
            OperateLogContextHolder.putExtras(more);

            assertEquals("v1", ctx.getExtra().get("k1"));
            assertEquals(2, ctx.getExtra().get("k2"));
        } finally {
            OperateLogContextHolder.unbind();
        }
        assertNull(OperateLogContextHolder.current());
    }

    @Test
    void blankKeysIgnored() throws Exception {
        Method m = Samples.method(Samples.Demo.class, "query", String.class);
        OperateLogContext ctx = Samples.contextOf(m, new Samples.Demo(), new Object[]{"1"});

        OperateLogContextHolder.bind(ctx);
        try {
            OperateLogContextHolder.putExtra(null, "v");
            OperateLogContextHolder.putExtra("", "v");
            assertEquals(0, ctx.getExtra().size());
        } finally {
            OperateLogContextHolder.unbind();
        }
    }
}
