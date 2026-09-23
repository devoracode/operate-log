package io.github.devoracode.operatelog.annotation;

import io.github.devoracode.operatelog.model.OperateType;
import io.github.devoracode.operatelog.model.RecordOn;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记录操作日志的方法。只识别方法级标注：
 * 类上标注会连带拦截整类方法，噪声与开销不可控。
 *
 * <p>注解查找不解析元注解：把 {@code @OperateLog} 标注在自定义注解上，
 * 再把自定义注解贴到方法上<b>不会生效</b>，必须直接标注 {@code @OperateLog}。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperateLog {
    /**
     * 模块名，如 {@code user} / {@code order}。
     *
     * @return 记入 {@code module} 字段的模块名；默认空串表示不归入任何模块
     */
    String module() default "";

    /**
     * 操作名，如 {@code create} / {@code cancel}。
     *
     * @return 记入 {@code operation} 字段的操作名；默认空串表示未命名
     */
    String operation() default "";

    /**
     * 操作类型。
     *
     * @return 记入 {@code operationType} 字段的类型；默认 {@link OperateType#OTHER}
     */
    OperateType type() default OperateType.OTHER;

    /**
     * 操作描述，支持 {@code #{...}} SpEL 模板。
     *
     * @return 模板原文，由切面渲染后写入 {@code description} 字段；默认空串表示无描述
     */
    String description() default "";

    /**
     * 业务 ID，纯 SpEL 表达式。
     *
     * @return 表达式原文，求值结果字符串化后写入 {@code businessId} 字段；默认空串表示不记录
     */
    String businessId() default "";

    /**
     * 记录条件，SpEL 布尔表达式；求值非 {@code true} 时不记录（与 {@link #recordOn()} 取交集）。
     *
     * @return SpEL 条件表达式原文；默认空串不附加条件，即恒通过
     */
    String condition() default "";

    /**
     * 记录时机。
     *
     * @return 生效的时机；默认 {@link RecordOn#ALWAYS} 即成败都记录
     */
    RecordOn recordOn() default RecordOn.ALWAYS;

    /**
     * 是否记录方法参数（序列化进 {@code requestBody}）。
     *
     * @return {@code true} 表示序列化入参写入 {@code requestBody}；默认 {@code true}
     */
    boolean recordRequest() default true;

    /**
     * 是否记录返回值（序列化进 {@code responseBody}），需显式开启。
     *
     * @return {@code true} 表示序列化返回值写入 {@code responseBody}；默认 {@code false} 即不记响应体
     */
    boolean recordResponse() default false;
}
