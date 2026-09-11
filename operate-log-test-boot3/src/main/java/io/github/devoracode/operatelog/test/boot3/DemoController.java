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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作日志测试 Controller（Boot 3.x / jakarta 栈）。每个端点只为一条待断言的行为服务，
 * 与 boot2 模块的同名类逐条对称——同一份 Starter 产物在两栈下行为必须一致。
 * core / starter 的可观测行为都在这里用真实 Spring MVC + AOP 链路验证，不另写单元测试。
 */
@RestController
@RequestMapping("/demo")
public class DemoController {
    private final DemoNestedService nestedService;

    public DemoController(DemoNestedService nestedService) {
        this.nestedService = nestedService;
    }

    /** 基础路径：SpEL 模板混排字面量、{@code businessId} 按参数名取值、{@code extra} 通道、只记请求不记响应。 */
    @OperateLog(
            module = "demo",
            operation = "query",
            type = OperateType.QUERY,
            description = "查询用户 #{#userId}",
            businessId = "#userId")
    @GetMapping("/{userId}")
    public Map<String, Object> query(@PathVariable String userId) {
        OperateLogContextHolder.putExtra("demo", "extra-channel");
        OperateLogContextHolder.putExtra("userId", userId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("userId", userId);
        result.put("message", "ok");
        return result;
    }

    /** 请求 / 响应双侧脱敏（嵌套对象、大小写变体见用例）；{@code recordOn=SUCCESS} 正常返回必须记录。 */
    @OperateLog(
            module = "demo",
            operation = "create",
            type = OperateType.CREATE,
            recordOn = RecordOn.SUCCESS,
            description = "创建用户",
            recordResponse = true)
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        OperateLogContextHolder.putExtra("payloadKeys", body.keySet().toString());
        return body;
    }

    /** 异常路径：{@code recordOn=ERROR} 只在抛异常时记录，产出 errorType / errorMessage / errorStack。 */
    @OperateLog(module = "demo", operation = "fail", type = OperateType.OTHER, recordOn = RecordOn.ERROR)
    @GetMapping("/fail")
    public Map<String, Object> fail() {
        throw new IllegalStateException("demo failure");
    }

    /**
     * {@code HttpServletRequest} 参数命中 {@code payload.ignore-types}（接口命中即算），
     * 必须以 {@code <IGNORED:类型简名>} 占位，绝不参与序列化。
     */
    @OperateLog(module = "demo", operation = "ignore-args")
    @GetMapping("/ignore-args")
    public Map<String, Object> ignoreArgs(@RequestParam(defaultValue = "x") String tag,
                                         HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tag", tag);
        return result;
    }

    /** SpEL 变量面：{@code #result}、{@code #p0}、{@code #annotation}、{@code #costTime}。 */
    @OperateLog(
            module = "demo",
            operation = "spel",
            description = "spel:#{#result.get('message')}|#{#p0}|#{#annotation.operation()}",
            businessId = "#costTime >= 0 ? 'measured' : 'unmeasured'",
            recordResponse = true)
    @GetMapping("/spel/{userId}")
    public Map<String, Object> spel(@PathVariable String userId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("message", "value-" + userId);
        return result;
    }

    /** 表达式失败只降级不抛：{@code businessId} 记 {@code null}，{@code description} 输出原文。 */
    @OperateLog(
            module = "demo",
            operation = "spel-broken",
            description = "broken #{#noSuchVar + }",
            businessId = "#noSuchVar")
    @GetMapping("/spel-broken")
    public Map<String, Object> spelBroken() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("message", "broken-spel-ok");
        return result;
    }

    /** {@code condition} 短路：{@code #tag == 'skip'} 时整条日志不产出。 */
    @OperateLog(module = "demo", operation = "conditional", condition = "#tag != 'skip'")
    @GetMapping("/conditional")
    public Map<String, Object> conditional(@RequestParam(defaultValue = "go") String tag) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tag", tag);
        return result;
    }

    /** 嵌套标注：内外层各产一条记录，{@code extra} 互不污染，内层结束恢复外层上下文。 */
    @OperateLog(module = "demo", operation = "outer", recordResponse = true)
    @GetMapping("/nested/{userId}")
    public Map<String, Object> nested(@PathVariable String userId) {
        OperateLogContextHolder.putExtra("layer", "outer");
        Map<String, Object> innerResult = this.nestedService.inner(userId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll(innerResult);
        result.put("afterInner", OperateLogContextHolder.current() == null
                ? "context-lost"
                : "context-restored");
        return result;
    }

    /** 坏参数逐个降级为 {@code <UNSERIALIZABLE:类型简名>}，同批其他参数照常记录。 */
    @OperateLog(module = "demo", operation = "bad-arg")
    @PostMapping("/bad-arg")
    public Map<String, Object> badArg(@RequestParam String note,
                                      @RequestBody UnserializableHolder holder) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("note", note);
        return result;
    }

    /** 超长载荷按 {@code payload.max-*-length} 截断：结果长度恰好等于上限（标记计入上限）。 */
    @OperateLog(module = "demo", operation = "huge-payload", recordResponse = true)
    @PostMapping("/huge")
    public Map<String, Object> huge(@RequestBody Map<String, Object> body) {
        return body;
    }
}
