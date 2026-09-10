package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import io.github.devoracode.operatelog.model.OperateType;
import io.github.devoracode.operatelog.model.RecordOn;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作日志测试 Controller。
 *
 * <p>类级 {@code @OperateLog} 为类内方法提供 module / operation / type 默认值，
 * 方法级注解按字段覆盖（见 {@link #query}）。{@link #classOnly()} 无方法级注解，
 * 按类级配置直接记录。</p>
 *
 * @author devoracode
 */
@RestController
@RequestMapping("/demo")
@OperateLog(module = "demo", operation = "class-default", type = OperateType.QUERY)
public class DemoController {

    @OperateLog(
            module = "demo",
            operation = "query",
            type = OperateType.QUERY,
            description = "查询用户 #{#userId}",
            businessId = "#userId")
    @GetMapping("/{userId}")
    public Map<String, Object> query(@PathVariable String userId) {
        // 冒烟：extra 自定义字段通道（应随日志输出 "extra":{"demo":"extra-channel",...}）
        OperateLogContextHolder.putExtra("demo", "extra-channel");
        OperateLogContextHolder.putExtra("userId", userId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("userId", userId);
        result.put("message", "ok");
        return result;
    }

    @OperateLog(
            module = "demo",
            operation = "create",
            type = OperateType.CREATE,
            recordOn = RecordOn.SUCCESS,
            description = "创建用户")
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        OperateLogContextHolder.putExtra("payloadKeys", body.keySet().toString());
        return body;
    }

    // 冒烟：类级注解默认值——无方法级注解也应记录 operation=class-default
    @GetMapping("/class-only")
    public Map<String, Object> classOnly() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("source", "class-level-annotation");
        return result;
    }

    // 冒烟：recordOn=ERROR——抛异常时记录；正常返回的 SUCCESS 场景应无日志
    @GetMapping("/fail")
    @OperateLog(module = "demo", operation = "fail", recordOn = RecordOn.ERROR)
    public Map<String, Object> fail() {
        throw new IllegalStateException("demo failure");
    }
}
