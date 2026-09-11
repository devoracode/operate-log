package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.Operator;

/** 默认实现：恒返回 {@code null}，未接入登录态时的占位。 */
public class AnonymousOperatorResolver implements OperatorResolver {

    @Override
    public Operator resolve() {
        return null;
    }
}
