# Changelog

本项目所有值得注意的变更都记录在此文件。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)（`MAJOR.MINOR.PATCH`）。
发布时由 release 流水线将 `[Unreleased]` 小节固化为 `[x.y.z] - YYYY-MM-DD`。

## [Unreleased]

### Added

- **Spring Boot 2.x / 3.x 双栈单 Starter**：单一坐标
  `io.github.devoracode:operate-log-spring-boot-starter`，运行时按宿主
  classpath 自动选择 Servlet API 实现，装配优先级 `jakarta` → `javax` →
  无 Servlet API 时的空实现兜底（非 Web 场景可用）。
- `recordOn`（`ALWAYS` / `SUCCESS` / `ERROR`）记录时机过滤，先于 SpEL
  `condition` 短路，低成本实现「仅成功 / 仅失败」审计。
- 注解查找链：目标类 most-specific 方法 → 调用方法 → 目标类实现的接口
  （含父接口）——覆盖「注解只标在接口方法 + JDK 动态代理」场景。
- `traceId` 与链路追踪体系打通：从 MDC 读取（key 可配
  `operate-log.trace-id-mdc-key`，默认 `traceId`），取不到时自动生成 UUID，
  MDC 访问异常不影响业务（降级为生成的 UUID）。
- 业务侧自定义字段通道：`OperateLogContextHolder#putExtra / putExtras`，
  随日志 `extra` 字段落地；嵌套调用恢复外层上下文，不泄漏 ThreadLocal。
- 载荷防护 `PayloadPolicy`：`requestBody` / `responseBody` / `errorStack` /
  `requestHeaders` 长度上限（`...[truncated]` 标记，`<=0` 不截断）与
  `ignoreTypes` 参数类型过滤（`<IGNORED:类型简名>` 占位；文件流 / Servlet
  容器对象 / `BindingResult` 等内置默认列表）。
- 敏感数据脱敏：Jackson 树模型递归命中字段名（忽略大小写，精确匹配非子串），
  `operate-log.mask.*` 配置字段集与替换文本；截断在脱敏之后执行，保证最终
  体积不越界。
- SpEL 表达式引擎：`#result` / `#error` / `#success` / `#costTime` /
  `#traceId` / `#operator` / `#http` / `#annotation` / `#context` / `#pN` /
  `#aN` / 参数名 变量，模板语法 `#{...}` 混排字面量；LRU 解析缓存（默认
  1024，下限 64）；表达式失败一律降级（evaluate 出 `null`、模板出原文、
  condition 放行），绝不阻塞日志与业务。
- 序列化坏元素逐个降级：`<UNSERIALIZABLE:类型>` 占位，其余参数照常记录。
- `operate-log.*` 全部配置项经 `spring-configuration-metadata.json`
  提供 IDE 自动补全与文档提示。
- CI 与发布工程：GitHub Actions 构建矩阵（JDK 8 / 17 / 21）、Maven Central
  发布流水线、japicmp 二进制兼容门禁、`sources` / `javadoc` 随构建产物发布。
- CI 依赖污染门禁 `dependency-hygiene`（`.github/scripts/dependency-hygiene.sh`）：
  对 Boot 2 / Boot 3 / Starter 三棵**真实依赖树**校验版本边界——测试工程 import BOM
  会掩盖库侧污染，「测试通过」不等于产物干净，故单独设门禁并双向自检
  （既拦「库把版本推给宿主」，也拦「把库的硬依赖裁没了」）。
- 测试集中在 `operate-log-test-boot2` / `operate-log-test-boot3` 两个真实 Boot 应用模块，
  两侧用例逐条对称（`core` / `starter` 不再保留单元测试）：`...MockMvcTest`（落地 JSON
  逐字段：SpEL 变量面与失败降级、`condition` / `recordOn` 短路、嵌套与大小写脱敏、
  `<IGNORED:>`、`<UNSERIALIZABLE:>` 逐元素降级、截断恰等于上限、嵌套 `extra` 隔离与
  上下文恢复、traceId 命中与兜底、`clientIp` / `userAgent`）、
  `...ConfigurationOverrideTest`（配置项覆盖后的生效语义）、
  `...BusinessZeroImpactTest`（`RANDOM_PORT` + 故障开关注入 resolver / serializer / handler
  异常）、`...NonWebContextTest`（`web-application-type=none`）、
  `...StarterAssemblyTest`（`ApplicationContextRunner` + `FilteredClassLoader` 验证
  「任一 classpath 组合只装配一套实现」「无 Servlet API 走兜底」「用户 Bean 让位」
  「`enabled=false` 整体不装配」，并以反射固化本轮收缩的公共 API）。

### Changed

- **core 的依赖边界**：`spring-core` / `spring-aop` / `spring-context` / `spring-expression`
  与 `slf4j-api` 由 `compile` 改为 `optional`（编译期定版仅供 core 自身，不再进入消费者
  依赖图）；`jackson-databind` / `aspectjweaver` / `commons-lang3` 保留 `compile`
  （宿主不必然提供，裁掉会破坏「只加一个 Starter 就能用」）。同时把这三者的版本下界
  与 Boot 2.7.18 基线对齐（Jackson `2.15.4→2.13.5`、`commons-lang3 3.14.0→3.12.0`）：
  库的下界高于宿主时会赢得调解，造成 `jackson-databind` 新、`jackson-core` /
  `annotations` 旧的混版。Boot 3 宿主由自身 BOM 上调，不受影响。
- **自动配置结构**：单一 `OperateLogAutoConfiguration` 拆为 `CommonConfiguration` /
  `JakartaServletConfiguration` / `JavaxServletConfiguration` / `FallbackConfiguration`
  四个内部配置类，栈间互斥由 `@ConditionalOnClass` + `@ConditionalOnMissingClass`
  显式表达；`@ConditionalOnMissingBean` 回归「让位给用户 Bean」的单一职责。
  装配结果不再与 bean 方法 / 内部类的声明顺序耦合（BeanDefinition 注册顺序仍按
  类名字典序，故 `Common` 排在两套 servlet 配置之前——顺序不再承载正确性）。
- **载荷截断语义**：`...[truncated]` 标记计入长度上限，截断结果长度恒
  `<= max*Length`（此前是「保留 maxLength 个字符再外挂 14 字符」）。
  `maxLength <= 0` 仍表示不截断。
- 异常隔离日志级别由 `debug` 提升为 `warn`：日志侧故障必须默认可观测，
  否则静默丢审计无从排障。
- 序列化降级捕获范围从 `JsonProcessingException` 扩大到
  `Exception | StackOverflowError`（getter 抛任意异常、Bean 循环引用均降级）。

### Removed

发布前收敛功能面，两项能力在 1.0.0 之前的开发历史中存在过，现已彻底移除（无兼容过渡期）：

- **`@OperateLog` 类级标注**：`@Target` 收敛为仅 `METHOD`，切点去掉 `@within`，
  类级 / 方法级逐字段合并（`MergedOperateLog`）与接口类级注解查找一并删除。
  理由：类级标注会连带拦截整类方法（getter、内部复用方法全进日志），噪声与开销都压不住，
  而它带来的「少写几个属性」收益很低。
- **HTTP 状态码采集**：`OperateLogRecord#httpStatus`、`HttpContext#status` 与
  `HttpContextResolver#resolveResponseStatus()` 全部删除，两个 servlet 解析器不再反射
  `ServletRequestAttributes#getResponse()`。理由：环绕通知的 `finally` 早于返回值处理与
  全局异常处理，此刻读到的 `response.getStatus()` 不是客户端实际收到的状态；
  保留一个「看着像最终值、其实是过程快照」的字段比不提供更危险（见 README
  「为什么没有 `httpStatus`」）。审计判据用 `success` + `errorType` / `errorMessage`。
- 相应地，`operate-log-core` 与 `operate-log-spring-boot-starter` 的单元测试目录删除
  （含 `junit-jupiter` / `spring-boot-starter-test` 的 test 依赖），断言迁移到两个
  boot 测试模块（见 Added）。

### Fixed

- `PayloadPolicy#truncate` 的结果长度越过配置上限：`substring(0, maxLength)` 之后又
  追加 14 字符的 `...[truncated]`，实际可达 `maxLength + 14`，使 `payload.max-*`
  不再是硬上限（`errorStack` 场景最明显）。改为预留标记空间：
  `substring(0, maxLength - 14) + 标记`，上限不足标记长度时退化为定长前缀（仍可辨识截断）。
- 切面 `finally` 收尾（计时、`endTime`、上下文采集、日志落地）无异常边界：日志侧任何
  异常都会从 `finally` 逃逸并**顶替业务异常**——异常路径下 `recordOn=ERROR` 的审计
  恰好不落地，且违反 Business Zero Impact。现整体包在 `finishQuietly` 的
  `catch (Throwable)` 中，上下文恢复置于其自身 `finally`。
- `@ConditionalOnMissingBean(HttpContextResolver.class)` 被两套栈实现共用时，
  「后声明的那套」会被前者满足，正确性完全依赖 bean 方法的声明次序，重排即坏；
  且非 Web 环境下 javax / jakarta 两套解析器会同时装配、由 `@Primary` + 顺序决定生效者。
  现以显式条件互斥取代（见 Changed）。
- `resolveTraceId` 在重构加入 MDC 访问防护时丢失了「取不到自动生成 UUID」
  的兜底语义，导致无链路追踪体系的宿主（定时任务、非 Web 服务）`traceId`
  恒为 `null`——已恢复兜底并保留防护（c2a597e）。
