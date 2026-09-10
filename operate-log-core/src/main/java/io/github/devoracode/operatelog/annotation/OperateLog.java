package io.github.devoracode.operatelog.annotation;

import io.github.devoracode.operatelog.model.OperateType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记录操作日志的方法。
 *
 * @author devoracode
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperateLog {

    String module() default "";

    String operation() default "";

    OperateType type() default OperateType.OTHER;

    String description() default "";

    String businessId() default "";

    String condition() default "";

    boolean recordRequest() default true;

    boolean recordResponse() default false;
}
