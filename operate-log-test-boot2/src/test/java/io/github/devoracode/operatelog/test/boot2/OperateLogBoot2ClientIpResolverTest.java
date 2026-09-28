package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.boot.servlet.javax.JavaxClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperateLogBoot2ClientIpResolverTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void usesProxyAppendedForwardedChainTail() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.12");
        request.addHeader("X-Forwarded-For", "8.8.8.8, 203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("203.0.113.7",
                new JavaxClientIpResolver(true).resolve());
    }

    @Test
    void doesNotFallBackToEarlierEntryWhenForwardedChainTailIsInvalid() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.12");
        request.addHeader("X-Forwarded-For", "8.8.8.8, not-an-ip");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("10.0.0.12",
                new JavaxClientIpResolver(true).resolve());
    }

    @Test
    void fallsBackToRealIpWhenForwardedChainTailIsInvalid() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.12");
        request.addHeader("X-Forwarded-For", "8.8.8.8, not-an-ip");
        request.addHeader("X-Real-IP", "203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("203.0.113.7",
                new JavaxClientIpResolver(true).resolve());
    }

    @Test
    void rejectsStructurallyInvalidIpv6Values() {
        String[] candidates = {
                ":", ":::", "1:::2",
                "1:2:3:4:5:6:7:8:9:a:b:c:d:e:f:1:2",
                "1:2:3:4:5:6:7", "1::2:3:4:5:6:7:8",
                "192.0.2.1::", "192.0.2.1::1", "::192.0.2.1:1",
                "1::2::3", ":1", "1:", "::ffff:192.0.2.999", "::1%eth0"
        };
        for (String candidate : candidates) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.12");
            request.addHeader("X-Forwarded-For", candidate);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            assertEquals("10.0.0.12",
                    new JavaxClientIpResolver(true).resolve(), candidate);
        }
    }

    @Test
    void acceptsStructurallyValidIpv6Values() {
        String[] candidates = {
                "2001:db8::1", "::1", "1::", "::",
                "2001:db8:0:1:1:1:1:1", "::ffff:192.0.2.128",
                "1:2:3:4:5::6:7", "1:2:3:4:5:6:192.0.2.1",
                "[2001:db8::1]"
        };
        for (String candidate : candidates) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.12");
            request.addHeader("X-Forwarded-For", candidate);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            assertEquals(candidate,
                    new JavaxClientIpResolver(true).resolve());
        }
    }

    @Test
    void rejectsUntrustedProxyMalformedAndOversizedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "not-an-ip");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals("192.0.2.10",
                new JavaxClientIpResolver(true).resolve());

        request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.12");
        request.addHeader("X-Forwarded-For", new String(new char[300]).replace('\0', '1'));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals("10.0.0.12",
                new JavaxClientIpResolver(true).resolve());
    }

    @Test
    void sharedIpValidatorExposesStrictLiteralValidation() throws Exception {
        Class<?> type;
        try {
            type = Class.forName("io.github.devoracode.operatelog.boot.servlet.ServletIpUtils");
        } catch (ClassNotFoundException ex) {
            throw new AssertionError("ServletIpUtils 公共工具类必须存在", ex);
        }
        Method method = type.getMethod("isValidIp", String.class);
        assertEquals(Boolean.TRUE, method.invoke(null, "2001:db8::1"));
        assertEquals(Boolean.FALSE, method.invoke(null, "1:::2"));
    }

    @Test
    void trustProxyFalseKeepsRemoteAddressCompatibility() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals("192.0.2.10", new JavaxClientIpResolver(false).resolve());
    }
}
