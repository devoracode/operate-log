package io.github.devoracode.operatelog.annotation;

import io.github.devoracode.operatelog.model.OperateType;
import io.github.devoracode.operatelog.model.RecordOn;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记录操作日志的方法。只识别方法级标注：类上标注会连带拦截该类全部方法
 * （含无需审计的 getter / 内部复用方法），噪声与开销都不可控。
 *
 * <p>需要「整类统一配置」时，用 {@code @OperateLog} 作元注解自定义注解，或逐方法标注。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperateLog {
    /** 模块名，如 {@code user} / {@code order}。 */
    String module() default "";

    /** 操作名，如 {@code create} / {@code cancel}。 */
    String operation() default "";

    /** 操作类型。 */
    OperateType type() default OperateType.OTHER;

    /** 操作描述，支持 {@code #{...}} SpEL 模板。 */
    String description() default "";

    /** 业务 ID，纯 SpEL 表达式。 */
    String businessId() default "";

    /** 记录条件，SpEL 布尔表达式；为空或求值非 true 时不记录（与 {@link #recordOn()} 取交集）。 */
    String condition() default "";

    /** 记录时机。 */
    RecordOn recordOn() default RecordOn.ALWAYS;

    /** 是否记录方法参数（序列化进 {@code requestBody}）。 */
    boolean recordRequest() default true;

    /** 是否记录返回值（序列化进 {@code responseBody}），需显式开启。 */
    boolean recordResponse() default false;
}
