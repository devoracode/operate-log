package io.github.devoracode.operatelog.sanitizer;

import org.springframework.core.annotation.AnnotationUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

final class NamedArgumentMasking {
    private static final Set<String> NAMED_BINDINGS = new HashSet<>(Arrays.asList(
            "org.springframework.web.bind.annotation.RequestParam",
            "org.springframework.web.bind.annotation.RequestHeader",
            "org.springframework.web.bind.annotation.CookieValue",
            "org.springframework.web.bind.annotation.PathVariable",
            "org.springframework.web.bind.annotation.MatrixVariable",
            "org.springframework.web.bind.annotation.RequestPart"));

    private NamedArgumentMasking() {
    }

    static Object[] mask(Method method, Object[] arguments, Predicate<String> isSensitive, String replacement) {
        if (method == null || arguments == null || arguments.length == 0) {
            return arguments;
        }
        Parameter[] parameters = method.getParameters();
        Object[] masked = arguments;
        for (int index = 0; index < parameters.length && index < arguments.length; index++) {
            if (arguments[index] != null && matches(parameters[index], isSensitive)) {
                if (masked == arguments) {
                    masked = arguments.clone();
                }
                masked[index] = replacement;
            }
        }
        return masked;
    }

    private static boolean matches(Parameter parameter, Predicate<String> isSensitive) {
        if (parameter.isNamePresent() && isSensitive.test(parameter.getName())) {
            return true;
        }
        for (Annotation annotation : parameter.getAnnotations()) {
            if (NAMED_BINDINGS.contains(annotation.annotationType().getName())
                    && (matchesAlias(annotation, "name", isSensitive)
                    || matchesAlias(annotation, "value", isSensitive))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAlias(Annotation annotation, String attribute, Predicate<String> isSensitive) {
        Object binding = AnnotationUtils.getValue(annotation, attribute);
        return binding instanceof String && !((String) binding).isEmpty() && isSensitive.test((String) binding);
    }
}
