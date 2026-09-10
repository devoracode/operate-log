package io.github.devoracode.operatelog.test.boot3;

import io.github.devoracode.operatelog.annotation.OperateLog;
import io.github.devoracode.operatelog.context.OperateLogContextHolder;
import io.github.devoracode.operatelog.model.OperateType;
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
 * @author devoracode
 */
@RestController
@RequestMapping("/demo")
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
         condition = "#success",
         description = "创建用户")
 @PostMapping
 public Map<String, Object> create(@RequestBody Map<String, Object> body) {
     OperateLogContextHolder.putExtra("payloadKeys", body.keySet().toString());
     return body;
 }
}
 