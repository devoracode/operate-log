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
- `@OperateLog` 支持类级标注（`@Target({METHOD, TYPE})`）：方法级注解按字段
  与类级默认值合并（空串 / `OTHER` / `ALWAYS` 视为未设置继承类级）。
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

### Changed

- 异常隔离日志级别由 `debug` 提升为 `warn`：日志侧故障必须默认可观测，
  否则静默丢审计无从排障。
- 序列化降级捕获范围从 `JsonProcessingException` 扩大到
  `Exception | StackOverflowError`（getter 抛任意异常、Bean 循环引用均降级）。

### Fixed

- `resolveTraceId` 在重构加入 MDC 访问防护时丢失了「取不到自动生成 UUID」
  的兜底语义，导致无链路追踪体系的宿主（定时任务、非 Web 服务）`traceId`
  恒为 `null`——已恢复兜底并保留防护（c2a597e）。
