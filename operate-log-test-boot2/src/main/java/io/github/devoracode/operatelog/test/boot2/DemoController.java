package io.github.devoracode.operatelog.test.boot2;

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

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作日志测试 Controller（Boot 2.x / javax 栈）。
 *
 * <p>{@code @OperateLog} 仅支持方法级标注，因此这里逐个端点显式标注；
 * 每个端点只为一件待断言的行为服务，注释写明「锁住什么」，
 * 与 boot3 模块的同名类逐条对称——同一份 Starter 产物在两栈下行为必须一致。</p>
 *
 * <p>本模块不写「单元测试」：core / starter 的可观测行为一律在这里用真实
 * Spring MVC + AOP 链路验证（见 README「构建」中的测试分层）。</p>
 *
 * @author devoracode
 */
@RestController
@RequestMapping("/demo")
public class DemoController {
    private final DemoNestedService nestedService;

    public DemoController(DemoNestedService nestedService) {
        this.nestedService = nestedService;
    }

    /**
     * 基础路径：SpEL 模板 {@code #{...}} 混排字面量、{@code businessId} 取参数名、
     * {@code extra} 自定义字段通道、{@code recordRequest=true} / {@code recordResponse=false}。
     */
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

    /**
     * 请求体与响应体双侧脱敏（含嵌套对象、大小写变体由测试用例的 JSON 覆盖）；
     * {@code recordOn=SUCCESS}：本方法正常返回必须记录。
     */
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

    /**
     * 异常路径：{@code recordOn=ERROR} 只在抛异常时记录，并产出 errorType / errorMessage / errorStack。
     */
    @OperateLog(module = "demo", operation = "fail", type = OperateType.OTHER, recordOn = RecordOn.ERROR)
    @GetMapping("/fail")
    public Map<String, Object> fail() {
        throw new IllegalStateException("demo failure");
    }

    /**
     * 载荷忽略类型：{@code HttpServletRequest} 参数命中 {@code payload.ignore-types}
     * （接口命中即算，见内置默认列表），必须以 {@code <IGNORED:类型简名>} 占位入日志，
     * 绝不被序列化（否则要么巨量内容、要么直接失败）。
     */
    @OperateLog(module = "demo", operation = "ignore-args")
    @GetMapping("/ignore-args")
    public Map<String, Object> ignoreArgs(@RequestParam(defaultValue = "x") String tag,
                                         HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tag", tag);
        return result;
    }

    /**
     * SpEL 变量面与降级：{@code #result}（业务返回值）、{@code #p0}（位置参数）、
     * {@code #annotation}（生效注解）、{@code #costTime}（切面在收尾前已写入）；
     * 同时用不存在的变量名验证「表达式失败只降级不抛」。
     */
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

    /**
     * SpEL 求值失败降级：{@code businessId} 指向不存在的变量 → 记 {@code null}；
     * {@code description} 模板坏语法 → 输出原文；业务与日志都不得因此失败。
     */
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

    /**
     * {@code condition} 短路：{@code #tag == 'skip'} 时整条日志不产出（成本最低的过滤位）。
     */
    @OperateLog(module = "demo", operation = "conditional", condition = "#tag != 'skip'")
    @GetMapping("/conditional")
    public Map<String, Object> conditional(@RequestParam(defaultValue = "go") String tag) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tag", tag);
        return result;
    }

    /**
     * 嵌套标注调用：外层与内层各产一条记录，{@code extra} 互不污染，
     * 内层结束后 ThreadLocal 恢复外层上下文（而不是被清空）。
     */
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

    /**
     * 序列化坏元素逐个降级：坏参数替换为 {@code <UNSERIALIZABLE:类型简名>}，
     * 同批次的其他参数照常记录（单个坏参数不拖垮整条日志）。
     */
    @OperateLog(module = "demo", operation = "bad-arg")
    @PostMapping("/bad-arg")
    public Map<String, Object> badArg(@RequestParam String note,
                                      @RequestBody UnserializableHolder holder) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("note", note);
        return result;
    }

    /**
     * 超长载荷：按 {@code payload.max-request-length} / {@code max-response-length} 截断，
     * 结果长度恰好等于上限（{@code ...[truncated]} 标记计入上限，不外挂）。
     */
    @OperateLog(module = "demo", operation = "huge-payload", recordResponse = true)
    @PostMapping("/huge")
    public Map<String, Object> huge(@RequestBody Map<String, Object> body) {
        return body;
    }
}
