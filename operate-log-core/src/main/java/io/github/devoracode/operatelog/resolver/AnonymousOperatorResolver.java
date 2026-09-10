package io.github.devoracode.operatelog.resolver;

import io.github.devoracode.operatelog.model.Operator;

/**
 * 默认匿名操作人解析器。
 *
 * @author devoracode
 */
public class AnonymousOperatorResolver implements OperatorResolver {

    @Override
    public Operator resolve() {
        return null;
    }
}
