package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.boot.servlet.ServletRequestUtils;
import io.github.devoracode.operatelog.boot.servlet.jakarta.JakartaClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperateLogBoot3ClientIpResolverTest {

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
                new JakartaClientIpResolver(true).resolve());
    }

    @Test
    void doesNotFallBackToEarlierEntryWhenForwardedChainTailIsInvalid() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.12");
        request.addHeader("X-Forwarded-For", "8.8.8.8, not-an-ip");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("10.0.0.12",
                new JakartaClientIpResolver(true).resolve());
    }

    @Test
    void fallsBackToRealIpWhenForwardedChainTailIsInvalid() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.12");
        request.addHeader("X-Forwarded-For", "8.8.8.8, not-an-ip");
        request.addHeader("X-Real-IP", "203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("203.0.113.7",
                new JakartaClientIpResolver(true).resolve());
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
                    new JakartaClientIpResolver(true).resolve(), candidate);
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
                    new JakartaClientIpResolver(true).resolve());
        }
    }

    @Test
    void rejectsUntrustedProxyMalformedAndOversizedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "not-an-ip");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals("192.0.2.10",
                new JakartaClientIpResolver(true).resolve());

        request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.12");
        request.addHeader("X-Forwarded-For", new String(new char[300]).replace('\0', '1'));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals("10.0.0.12",
                new JakartaClientIpResolver(true).resolve());
    }

    @Test
    void sharedIpValidatorExposesStrictLiteralValidation() {
        assertTrue(ServletRequestUtils.isValidIp("192.0.2.1"));
        assertTrue(ServletRequestUtils.isValidIp("2001:db8::1"));
        assertTrue(ServletRequestUtils.isValidIp("[2001:db8::1]"));
        assertFalse(ServletRequestUtils.isValidIp("1:::2"));
        assertFalse(ServletRequestUtils.isValidIp("192.0.2.999"));
        assertFalse(ServletRequestUtils.isValidIp("example.com"));
    }

    @Test
    void sharedHeaderCollectorJoinsMultipleValuesInRequestOrder() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("Host", Collections.singletonList("example.com"));
        raw.put("Accept", Arrays.asList("text/html", "application/json"));

        Map<String, String> headers = ServletRequestUtils.resolveHeaders(
                Collections.enumeration(raw.keySet()), valuesOf(raw));

        assertEquals(2, headers.size());
        assertEquals("example.com", headers.get("Host"));
        assertEquals("text/html, application/json", headers.get("Accept"));
        assertEquals(Arrays.asList("Host", "Accept"), new ArrayList<>(headers.keySet()));
    }

    @Test
    void sharedHeaderCollectorToleratesMissingNamesAndValues() {
        assertTrue(ServletRequestUtils.resolveHeaders(null, name -> null).isEmpty());

        Map<String, String> headers = ServletRequestUtils.resolveHeaders(
                Collections.enumeration(Arrays.asList("X-Empty", "X-Null")),
                name -> null);
        assertEquals("", headers.get("X-Empty"));
        assertEquals("", headers.get("X-Null"));
    }

    private static Function<String, Enumeration<String>> valuesOf(Map<String, List<String>> raw) {
        return name -> {
            List<String> values = raw.get(name);
            return values == null ? null : Collections.enumeration(values);
        };
    }

    @Test
    void trustProxyFalseKeepsRemoteAddressCompatibility() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals("192.0.2.10", new JakartaClientIpResolver(false).resolve());
    }
}
