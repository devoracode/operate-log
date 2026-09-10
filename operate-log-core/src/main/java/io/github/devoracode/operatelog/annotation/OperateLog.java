package io.github.devoracode.operatelog.annotation;

import io.github.devoracode.operatelog.model.OperateType;
import io.github.devoracode.operatelog.model.RecordOn;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记录操作日志的方法或类。
 *
 * <p><b>方法级</b>（常规用法）：精确标注单个业务方法。</p>
 *
 * <p><b>类级</b>（默认值模式）：标注在类上为其全部方法提供 module / operation / type
 * 等默认值。方法级注解按字段覆盖——字符串字段以空串、type 以 {@code OTHER}、
 * recordOn 以 {@code ALWAYS} 为「未设置」，未设置时继承类级；
 * {@code recordRequest} / {@code recordResponse} 布尔字段不参与合并，方法级注解在场时以方法级为准。</p>
 *
 * <p>注意：类级标注会使该类<b>全部</b>方法被拦截记录（含无方法级注解的方法），
 * 高频核心类请优先使用方法级精确标注。</p>
 *
 * @author devoracode
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperateLog {

    /**
     * 模块名（如 {@code user} / {@code order}），支持类级默认值。
     */
    String module() default "";

    /**
     * 操作名（如 {@code create} / {@code cancel}），支持类级默认值。
     */
    String operation() default "";

    /**
     * 操作类型，支持类级默认值（方法级为 OTHER 时视为未设置）。
     */
    OperateType type() default OperateType.OTHER;

    /**
     * 操作描述，SpEL 模板（{@code #{...}}）语法，支持类级默认值。
     */
    String description() default "";

    /**
     * 业务 ID，纯 SpEL 表达式，支持类级默认值。
     */
    String businessId() default "";

    /**
     * 记录条件，纯 SpEL 布尔表达式；为空或求值非 true 时不记录。与 {@link #recordOn()} 取交集。
     */
    String condition() default "";

    /**
     * 记录时机（ALWAYS / SUCCESS / ERROR），支持类级默认值（方法级为 ALWAYS 时视为未设置）。
     */
    RecordOn recordOn() default RecordOn.ALWAYS;

    /**
     * 是否记录方法参数（序列化进 requestBody）。不参与类级合并。
     */
    boolean recordRequest() default true;

    /**
     * 是否记录返回值（序列化进 responseBody），需显式开启。不参与类级合并。
     */
    boolean recordResponse() default false;
}
