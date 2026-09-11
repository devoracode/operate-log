package io.github.devoracode.operatelog.test.boot2;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 嵌套调用链的内层服务：由 {@link DemoController#nested(String)} 经 Spring 代理调用，
 * 用于验证内层记录落地后外层上下文被恢复、{@code extra} 互不污染。
 */
@Service
public class DemoNestedService {

    @OperateLog(module = "demo", operation = "inner", businessId = "#userId")
    public Map<String, Object> inner(String userId) {
        OperateLogContextHolder.putExtra("layer", "inner");
        OperateLogContextHolder.putExtra("innerOnly", "inner-value");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("inner", "done");
        result.put("userId", userId);
        return result;
    }
}
