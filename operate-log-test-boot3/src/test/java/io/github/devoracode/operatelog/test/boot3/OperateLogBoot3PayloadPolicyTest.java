package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.payload.PayloadPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

class OperateLogBoot3PayloadPolicyTest {

    @Test
    void diamondInterfaceGraphVisitsEachInterfaceOnce() throws Exception {
        PayloadPolicy policy = new PayloadPolicy(0, 0, 0, 0,
                Collections.singleton("not.a.real.Type"));
        Method finder;
        try {
            finder = PayloadPolicy.class.getDeclaredMethod("findIgnoredInterface", Class.class, Set.class);
        } catch (NoSuchMethodException ex) {
            fail("PayloadPolicy 必须使用单次调用共享的接口访问集");
            return;
        }
        finder.setAccessible(true);
        CountingSet visited = new CountingSet();

        assertNull(finder.invoke(policy, Diamond.class, visited));
        assertEquals(5, visited.addCalls);
        assertEquals(4, visited.size());
    }

    private interface Root {
    }

    private interface Left extends Root {
    }

    private interface Right extends Root {
    }

    private interface Top extends Left, Right {
    }

    private static final class Diamond implements Top {
    }

    private static final class CountingSet extends HashSet<Class<?>> {
        private int addCalls;

        @Override
        public boolean add(Class<?> value) {
            this.addCalls++;
            return super.add(value);
        }
    }
}
