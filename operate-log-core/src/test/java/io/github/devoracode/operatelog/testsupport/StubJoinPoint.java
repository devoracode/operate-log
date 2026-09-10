package io.github.devoracode.operatelog.testsupport;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignatureImpl;
import org.aspectj.lang.reflect.SourceLocation;

import java.lang.reflect.Method;

/**
 * 手写 ProceedingJoinPoint 桩（不依赖 Mockito：规避 Byte Buddy 在不同 JDK 上的兼容波动）。
 *
 * <p>可编排：被拦截的方法签名、目标对象、参数、返回值 / 抛出异常、
 * 以及「方法体执行期间」的回调（用于冒烟 {@code OperateLogContextHolder#putExtra}）。</p>
 *
 * @author devoracode
 */
public class StubJoinPoint implements ProceedingJoinPoint {

    private final Method method;

    private final Object target;

    private final Object[] args;

    private final Object result;

    private final Throwable thrown;

    private final Runnable duringProceed;

    private StubJoinPoint(Method method, Object target, Object[] args,
            Object result, Throwable thrown, Runnable duringProceed) {
        this.method = method;
        this.target = target;
        this.args = args;
        this.result = result;
        this.thrown = thrown;
        this.duringProceed = duringProceed;
    }

    /** 方法正常返回的桩。 */
    public static StubJoinPoint returning(Method method, Object target, Object[] args,
            Object result) {
        return new StubJoinPoint(method, target, args, result, null, null);
    }

    /** 方法正常返回、执行期间触发回调的桩（extra 通道冒烟）。 */
    public static StubJoinPoint returningWithBody(Method method, Object target, Object[] args,
            Object result, Runnable body) {
        return new StubJoinPoint(method, target, args, result, null, body);
    }

    /** 方法抛出异常的桩。 */
    public static StubJoinPoint throwing(Method method, Object target, Object[] args,
            Throwable thrown) {
        return new StubJoinPoint(method, target, args, null, thrown, null);
    }

    public Object proceed() throws Throwable {
        if (duringProceed != null) {
            duringProceed.run();
        }
        if (thrown != null) {
            throw thrown;
        }
        return result;
    }

    public Object proceed(Object[] newArgs) throws Throwable {
        return proceed();
    }

    public Object[] getArgs() {
        return args;
    }

    public Signature getSignature() {
        return new MethodSignatureImpl(method);
    }

    public Object getTarget() {
        return target;
    }

    public Object getThis() {
        return target;
    }

    public SourceLocation getSourceLocation() {
        return null;
    }

    public StaticPart getStaticPart() {
        return null;
    }

    public String toShortString() {
        return "StubJoinPoint." + method.getName();
    }

    public String toLongString() {
        return toShortString();
    }

    public String toFullString() {
        return toShortString();
    }

    public void setThis(Object obj) {
    }

    public void setArgs(Object[] newArgs) {
    }
}
