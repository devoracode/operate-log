# operate-log

[![CI](https://github.com/devoracode/operate-log/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/devoracode/operate-log/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](#支持的版本)
<!-- 首个版本发布到 Maven Central 后启用：
[![Maven Central](https://img.shields.io/maven-central/v/io.github.devoracode/operate-log-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.devoracode/operate-log-spring-boot-starter)
-->

面向 Spring Boot 2.x / 3.x 的轻量级操作日志 Starter。

在关键业务方法上标注一个 `@OperateLog` 注解，即可自动记录：

- **谁**在操作（操作人扩展点，可对接登录态 / Session / Token）
- **操作了什么**（模块、操作、操作类型、业务 ID、SpEL 描述模板）
- **怎么操作的**（HTTP 请求方法 / URL / 参数 / 响应 / 客户端 IP / User-Agent）
- **结果如何**（成功 / 失败、耗时、异常类型与堆栈、traceId 全链路关联）

默认以单行 JSON 输出到 SLF4J，业务方可通过扩展点替换为任意落地方式（数据库、MQ、ES、审计系统等）。

---

## 核心特性

| 特性 | 说明 |
| --- | --- |
| 注解驱动 | `@OperateLog` 方法级标注（`@Target(METHOD)`），Spring AOP 环绕拦截，零侵入 |
| 双栈兼容 | 单一 Starter 同时支持 Spring Boot 2.x（`javax.servlet`）与 3.x（`jakarta.servlet`），按宿主 classpath 自动条件装配；非 Web 环境自动降级为空实现 |
| 记录时机 | `recordOn = ALWAYS / SUCCESS / ERROR` 直观过滤「仅成功 / 仅失败」，零 SpEL 开销，与 `condition` 取交集 |
| SpEL 表达式 | 条件过滤、业务 ID 提取、描述模板（`#{...}` 模板语法） |
| 敏感数据脱敏 | JSON 树递归脱敏，字段名忽略大小写，脱敏字段与替换文本可配置 |
| 全链路关联 | traceId 取 MDC，key 可配（`operate-log.trace-id-mdc-key`，默认 `traceId`，对齐 Sleuth / Micrometer / OTel 等链路追踪体系），缺失时自动生成 UUID |
| 异常安全 | 日志组件内部任何异常均被隔离捕获，**绝不影响业务方法执行**；故障细节以 debug 日志暴露，不静默吞 |
| 载荷防护 | `requestBody` / `responseBody` / `errorStack` 最大长度可配，超限截断打标记（标记计入上限，结果长度恒 `<=` 配置值）；文件 / 流 / Servlet 容器等不宜序列化的参数自动替换为 `<IGNORED:类型>` 占位符；序列化失败逐元素降级，单个坏参数不拖垮整条记录 |
| 自定义字段 | 业务方法内通过 `OperateLogContextHolder#putExtra` 向当前日志记录追加任意业务字段（`extra`），无需扩展记录模型 |
| 可插拔扩展点 | Handler / 操作人解析 / HTTP 上下文解析 / 序列化 / 脱敏 / SpEL 引擎 / 载荷策略全部可替换（`@ConditionalOnMissingBean` 自动让位） |
| 异常降级 | 操作人、HTTP 上下文解析失败时降级为 `null`，SpEL 表达式求值失败按方向降级（condition 视为通过、模板输出原文），日志其余字段照常记录 |

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `operate-log-core` | 核心：注解、AOP 切面、上下文模型（含 `extra` 自定义字段通道）、SpEL 引擎、序列化、脱敏、载荷防护（PayloadPolicy）、Handler / Resolver 扩展点。Java 8 基线；Spring 与 SLF4J 为 `optional`（版本由宿主 Boot 决定），仅 jackson-databind / aspectjweaver / commons-lang3 以 compile 传递（宿主不必然提供，且跨 Boot 2/3 二进制兼容） |
| `operate-log-spring-boot-starter` | Boot 2.x / 3.x 双栈自动装配：`javax` / `jakarta` 两套 Servlet 解析实现按 classpath 自动选择，非 Web 环境兜底。Java 8 字节码，Boot 相关依赖全部 `provided` 零传递 |
| `operate-log-test-boot2` | Boot 2.x 可运行示例（`javax` 栈冒烟） |
| `operate-log-test-boot3` | Boot 3.x 可运行示例（`jakarta` 栈冒烟，JDK 17+ 构建时自动纳入，见「构建」） |

## 快速开始

### 1. 引入依赖

Boot 2.x 与 3.x 使用同一坐标（Starter 本体为 Java 8 字节码；Boot 2.x 应用需 JDK 8+，Boot 3.x 应用需 JDK 17+）：

```xml
<dependency>
    <groupId>io.github.devoracode</groupId>
    <artifactId>operate-log-spring-boot-starter</artifactId>
    <version>latestVersion</version>
</dependency>
```

> 要求宿主为 Web 应用（含 `ObjectMapper` Bean，Spring Boot Web 应用默认具备）。
> 非 Web 环境（定时任务、后台服务）也能工作：HTTP 相关字段自动降级为 `null`。

### 2. 标注注解

```java
@OperateLog(
        module = "user",
        operation = "query",
        type = OperateType.QUERY,
        description = "查询用户 #{#userId}",
        businessId = "#userId")
@GetMapping("/{userId}")
public UserVO query(@PathVariable String userId) { ... }
```

方法执行后，控制台即输出单行 JSON：

```text
operate-log={"id":"...","traceId":"04d3122e-...","application":"order-app","environment":"prod","version":"1.0.0","module":"user","operation":"query","operationType":"QUERY","description":"查询用户 42","businessId":"42","operatorUserId":"10001","operatorUserAccount":"demo","operatorUserName":"演示用户","requestMethod":"GET","requestUrl":"http://localhost:8080/demo/42","requestUri":"/demo/42","requestQuery":null,"requestHeaders":null,"requestBody":"[\"42\"]","responseBody":"{\"userId\":\"42\",\"message\":\"ok\"}","clientIp":"127.0.0.1","userAgent":"curl/8.21.0","success":true,"costTime":4,"startTime":"...","endTime":"...","errorType":null,"errorMessage":null,"errorStack":null}
```

### 3. 对接操作人（推荐）

默认操作人为匿名（三个字段均为 `null`）。对接登录态只需注册一个 `OperatorResolver` Bean，自动配置会让位：

```java
@Bean
public OperatorResolver operatorResolver() {
    return () -> {
        LoginUser user = LoginContextHolder.get(); // 你的登录态上下文
        return user == null ? null : Operator.builder()
                .userId(user.getId())
                .userAccount(user.getAccount())
                .userName(user.getName())
                .build();
    };
}
```

---

## 注解属性（`@OperateLog`）

`@Target(ElementType.METHOD)`：**仅方法级**。类级标注（连同「方法级按字段继承类级默认值」的合并语义）已被移除——它会让 `@within` 连带拦截该类全部方法（getter、内部复用方法一并进日志），对高频核心类是性能与噪声的双重代价，收益不足。

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `module` | `String` | `""` | 模块名（如 `user` / `order`） |
| `operation` | `String` | `""` | 操作名（如 `create` / `cancel`） |
| `type` | `OperateType` | `OTHER` | 操作类型：`CREATE` / `UPDATE` / `DELETE` / `QUERY` / `EXPORT` / `IMPORT` / `LOGIN` / `LOGOUT` / `ENABLE` / `DISABLE` / `GRANT` / `REVOKE` / `DOWNLOAD` / `PRINT` / `OTHER` |
| `description` | `String` | `""` | 操作描述，支持 **SpEL 模板**（`#{...}` 包裹），如 `"查询用户 #{#userId}"` |
| `businessId` | `String` | `""` | 业务 ID，**纯 SpEL 表达式**（无 `#{}`），求值结果转字符串，如 `"#userId"`、`"#result.id"` |
| `condition` | `String` | `""` | 记录条件，**纯 SpEL 布尔表达式**；为空或求值非 `true` 时不记录。如 `"#success"`、`"#costTime > 1000"`（慢调用审计）。与 `recordOn` 取交集 |
| `recordOn` | `RecordOn` | `ALWAYS` | 记录时机：`ALWAYS` 总是 / `SUCCESS` 仅正常返回 / `ERROR` 仅抛出异常。先于 `condition` 短路求值，零 SpEL 成本 |
| `recordRequest` | `boolean` | `true` | 是否记录方法参数（序列化进 `requestBody`） |
| `recordResponse` | `boolean` | `false` | 是否记录返回值（序列化进 `responseBody`），需显式开启 |

> 注解查找链：目标类 most-specific 方法 → 调用方法（JDK 代理时为接口方法）→ 目标类实现的全部接口上的同签名方法。CGLIB / JDK 代理、标注在接口方法上（JDK 代理场景）均可识别。
> 注意：Spring 创建 CGLIB 代理的资格判定只看目标类方法，若注解**仅**标注在接口方法上且宿主使用 CGLIB 代理（Boot 默认 `proxyTargetClass=true`），advice 不会织入——此时请把注解同时放到实现类方法上。

### 使用示例

```java
// 仅在成功时记录（登录失败不刷日志）——recordOn 比 condition 更直观且零 SpEL 开销
@OperateLog(module = "auth", operation = "login", type = OperateType.LOGIN,
        recordOn = RecordOn.SUCCESS)

// 仅记录失败（排障场景）
@OperateLog(module = "pay", operation = "refund", type = OperateType.UPDATE,
        recordOn = RecordOn.ERROR)

// 权限审计：授权 / 回收
@OperateLog(module = "acl", operation = "grantRole", type = OperateType.GRANT)

// 慢调用审计：只记录超过 1 秒的调用（condition 适合表达这类数值条件）
@OperateLog(module = "report", operation = "export", type = OperateType.EXPORT,
        condition = "#costTime > 1000")

// 需要记录返回值时必须显式开启（默认不记录）
@OperateLog(module = "order", operation = "pay", type = OperateType.CREATE,
        recordResponse = true)

// 大参数方法关闭参数记录（返回值默认也不记录，通常无需额外设置）
@OperateLog(module = "file", operation = "upload", recordRequest = false)

// 需要「整类统一配置」时：逐个方法显式标注（不再有类级默认值继承）。
// 若确实需要按类粒度全量审计，请自行注册 OperateLogAspect Bean 并扩大切点，
// 而不是把注解贴到类上——本组件不提供该语义。
@OperateLog(module = "order", operation = "cancel", type = OperateType.UPDATE, businessId = "#orderNo")
public void cancel(String orderNo) { ... }
```

## SpEL 支持

`description` / `businessId` / `condition` 均可使用 SpEL，可用变量：

| 变量 | 说明 |
| --- | --- |
| `#p0`、`#p1`... | 按位置的方法参数 |
| `#a0`、`#a1`... | 同 `#p0`（别名） |
| `#参数名` | 方法参数名（依赖调试信息，`javac -g` / IDE 默认开启） |
| `#result` | 方法返回值（异常时为 `null`） |
| `#error` | 异常对象（正常时为 `null`） |
| `#success` | 是否成功（`boolean`） |
| `#operator` | 当前操作人 `Operator`（可能为 `null`） |
| `#http` | 当前 HTTP 上下文 `HttpContext`（非 Web 环境为 `null`） |
| `#traceId` | 当前链路 traceId |
| `#costTime` | 已耗时（`long`，毫秒） |
| `#startTime` / `#endTime` | 起止时间 `Instant` |
| `#annotation` | 当前方法上的 `@OperateLog` 注解实例 |
| `#context` | 完整 `OperateLogContext` |

- `description` 使用**模板语法**：`"订单 #{#orderNo} 支付成功"`，仅 `#{...}` 内求值，其余为字面文本。
- `businessId` / `condition` 为**纯表达式**：`"#orderNo"`、`"#success && #result.count > 0"`。
- 表达式解析结果带定容 **LRU 缓存**（access-order，默认 1024 条），超出容量自动淘汰最久未使用项，同一注解方法重复调用无重复解析开销。
- **表达式失败不中断日志**：语法错误 / 空指针访问等按方向降级——`condition` 视为通过（宁可多记不漏记）、`description` 输出模板原文、`businessId` 记 `null`，原因以 debug 日志暴露。
- `operate-log.spel.enabled=false` 时引擎整体直通（零求值开销）：condition 恒通过、description 输出原文、businessId 为 `null`。

## 配置参考（`application.yml`）

所有配置均有合理默认值，零配置即可工作。

```yaml
operate-log:
  enabled: true                    # 总开关；设为 false 整个组件不装配
  application: order-app          # 应用名，写入日志（多应用聚合检索时区分来源）
  environment: prod               # 运行环境标识（dev / test / prod）
  version: 1.0.0                  # 应用版本号
  trace-id-mdc-key: traceId       # traceId 的 MDC key（对齐追踪体系可改为 trace_id 等）
  http:
    trust-proxy: false            # 是否信任反向代理头（X-Forwarded-For / X-Real-IP）
    capture-headers: false        # 是否采集完整请求头（写入 requestHeaders）
  mask:
    enabled: true                 # 是否启用敏感数据脱敏
    mask-text: "******"          # 脱敏替换文本
    fields:                       # 敏感字段名（匹配时忽略大小写，可增删）
      - password
      - passwd
      - pwd
      - token
      - accessToken
      - refreshToken
      - authorization
      - cookie
      - set-cookie
      - secret
      - clientSecret
  spel:
    enabled: true                 # SpEL 总开关；false 时引擎直通（见「SpEL 支持」）
    cache-size: 1024              # SpEL 表达式 LRU 缓存大小（最小 64）
  payload:                        # 载荷防护
    max-request-length: 2048      # requestBody / requestHeaders 最大字符数，<=0 不截断
    max-response-length: 2048     # responseBody 最大字符数，<=0 不截断
    max-error-stack-length: 4096  # errorStack 最大字符数，<=0 不截断
    # ignore-types:               # 序列化时跳过的参数类型（全限定类名，命中父类/接口即算）
    #   - org.springframework.web.multipart.MultipartFile
    #   - java.io.InputStream
    # 注意：配置该项会【整体替换】内置默认列表（默认已含 Servlet 请求/响应、流、字节数组等 12 项）
```

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `operate-log.enabled` | `true` | 总开关。`false` 时自动配置整体不生效 |
| `operate-log.application` | `""` | 应用名 |
| `operate-log.environment` | `""` | 环境标识 |
| `operate-log.version` | `""` | 版本号 |
| `operate-log.trace-id-mdc-key` | `traceId` | traceId 的 MDC key。与链路追踪体系的 MDC 写入 key 对齐（Micrometer/Sleuth 常见 `traceId`，OTel logback 桥接常见 `trace_id`）；取不到时自动生成 UUID，配置空白回退默认 key |
| `operate-log.http.trust-proxy` | `false` | 信任代理头时，客户端 IP 解析顺序：`X-Forwarded-For`（取逗号链第一个）→ `X-Real-IP` → `getRemoteAddr()`；否则直接取 `getRemoteAddr()`。**仅在可信网络边界后开启**，防止客户端伪造 IP |
| `operate-log.http.capture-headers` | `false` | 开启后采集全部请求头写入 `requestHeaders`（JSON 对象）。注意头部可能含 Cookie 等敏感信息，开启后脱敏器会一并处理 |
| `operate-log.mask.enabled` | `true` | 脱敏总开关。作用于 `requestHeaders` / `requestBody` / `responseBody` 三个 JSON 字段 |
| `operate-log.mask.mask-text` | `******` | 替换文本 |
| `operate-log.mask.fields` | 见上 | 脱敏字段集合（11 个内置默认值） |
| `operate-log.spel.enabled` | `true` | SpEL 开关。`false` 时引擎直通：condition 恒通过、description 输出模板原文、businessId 记 `null`，零求值开销 |
| `operate-log.spel.cache-size` | `1024` | 表达式 LRU 缓存容量（实际生效最小值 64，超出淘汰最久未使用项） |
| `operate-log.payload.max-request-length` | `2048` | `requestBody` / `requestHeaders` 最大字符数，`<=0` 不截断；超限时截断为**恰好该长度**（`...[truncated]` 标记计入上限，不外挂） |
| `operate-log.payload.max-response-length` | `2048` | `responseBody` 最大字符数，`<=0` 不截断；标记同上计入上限 |
| `operate-log.payload.max-error-stack-length` | `4096` | `errorStack` 最大字符数，`<=0` 不截断 |
| `operate-log.payload.ignore-types` | 12 项内置 | 序列化参数时跳过的类型（全限定类名，父类/接口命中即算），替换为 `<IGNORED:类型简名>`；配置后整体替换默认列表 |

## 日志输出字段（`OperateLogRecord`）

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| `id` | 自动生成 | 日志记录 UUID |
| `traceId` | MDC（key = `operate-log.trace-id-mdc-key`，默认 `traceId`）/ 自动生成 | 全链路 ID，优先按配置 key 读 MDC（可与 Sleuth / Micrometer / Zipkin 等链路追踪打通），缺失时自动生成 UUID |
| `application` / `environment` / `version` | 配置 | 应用、环境、版本 |
| `module` / `operation` / `operationType` | 注解 | 模块、操作、操作类型 |
| `description` | 注解 + SpEL | 模板求值后的描述 |
| `businessId` | SpEL | 业务 ID |
| `operatorUserId` / `operatorUserAccount` / `operatorUserName` | `OperatorResolver` | 操作人三要素，未对接时为 `null` |
| `requestMethod` / `requestUrl` / `requestUri` / `requestQuery` | HTTP 上下文 | 请求方法、完整 URL、URI、查询串 |
| `requestHeaders` | HTTP 上下文 + 序列化 | 请求头 JSON（仅 `capture-headers: true` 时） |
| `requestBody` | 方法参数序列化 | 参数 JSON（`recordRequest = false` 时为 `null`） |
| `responseBody` | 返回值序列化 | 响应 JSON（默认 `null`，需注解显式 `recordResponse = true`） |
| `clientIp` / `userAgent` | HTTP 请求 | 客户端 IP（含代理解析）、User-Agent |
| `success` | 运行时 | 方法是否正常返回 |
| `costTime` | 运行时 | 耗时（毫秒） |
| `startTime` / `endTime` | 运行时 | 起止时间（`Instant`，ISO-8601） |
| `errorType` / `errorMessage` / `errorStack` | 运行时 | 异常类名 / message / 堆栈（按 `payload.max-error-stack-length` 截断），正常时为 `null` |
| `extra` | `OperateLogContextHolder#putExtra` | 业务自定义字段（Map），方法内写入、随记录落地；未写入时为 `null` |

> 所有可能为 `null` 的字段在 JSON 中保留为 `null` 值，便于下游解析 schema 稳定。
> `requestBody` / `responseBody` / `requestHeaders` / `errorStack` 受 `payload.*` 长度上限保护，超限截断。

## 为什么没有 `httpStatus`

日志模型中**不提供 HTTP 状态码**，这是刻意的取舍而非遗漏：环绕通知的 `finally` 早于 Spring MVC 的返回值处理与全局异常处理，`response.getStatus()` 在那个时刻既可能读不到业务即将写入的值、也必然读不到 `ResponseEntity` / `@ResponseStatus` / `@ControllerAdvice` 的最终改写：

```text
Filter → DispatcherServlet → Interceptor#preHandle
        → 【OperateLogAspect：resolve() 采集请求侧 → 业务方法 → finally 落地日志】  ← 切面到此为止
        → HandlerMethodReturnValueHandler（ResponseEntity / @ResponseStatus 在此写 status）
        → @ControllerAdvice / @ExceptionHandler（异常场景在此改 status）
        → Interceptor#afterCompletion → Filter 收尾（容器错误页可能再改成 500）
```

留一个「看起来像最终状态码、实际是过程快照」的字段，比没有更危险（审计上会被当成判据）。因此：

- 审计判据请用 `success` + `errorType` / `errorMessage`（异常路径由切面如实捕获）；
- 需要 HTTP 访问状态码时，请在宿主侧用 `OncePerRequestFilter` / `HandlerInterceptor#afterCompletion` **另记一行访问日志**，用本组件的 `traceId` / `id` 关联；
- 非要把最终状态码写进同一条审计日志，只能用「延后落地」：自定义 `OperateLogHandler` 先入队、状态定稿后补写再刷出。Starter 不自带 Filter——那会把「单一环绕通知、日志同步落地」的架构换成 Filter + Aspect 双体系。

## 敏感数据脱敏

- 作用于 `requestHeaders`、`requestBody`、`responseBody` 三个 JSON 字符串字段。
- 实现为 **JSON 树递归**（Jackson `ObjectNode`）：嵌套对象、数组、集合中的敏感字段全部命中，不限于顶层。
- 字段名匹配**忽略大小写**（`Password` / `PASSWORD` / `password` 均脱敏）。
- 命中字段的值替换为 `mask-text`（默认 `******`）。
- 待脱敏内容非 JSON 或脱敏过程异常时**原样返回**，不阻断日志流程。
- 敏感字段集合通过 `operate-log.mask.fields` 扩展。

## 扩展点

以下接口均可通过注册同名 Bean 覆盖默认实现（自动配置全部标注 `@ConditionalOnMissingBean`，用户 Bean 优先）：

| 扩展点 | 默认实现 | 用途 |
| --- | --- | --- |
| `OperateLogHandler` | `DefaultOperateLogHandler`（SLF4J INFO 单行 JSON，前缀 `operate-log=`） | 替换日志落地方式：写数据库 / MQ / ES / 审计系统 |
| `OperatorResolver` | 匿名（返回 `null`） | 对接登录态，提供 `userId` / `userAccount` / `userName` |
| `HttpContextResolver` | Starter 内置（`javax` / `jakarta` 自动选择） | 定制 HTTP 上下文采集（如接入非 Servlet 容器） |
| `ClientIpResolver` | Starter 内置（含 `trust-proxy` 逻辑） | 定制客户端 IP 解析策略 |
| `OperateLogSerializer` | `JacksonOperateLogSerializer`（复用宿主 `ObjectMapper`） | 更换序列化器（如 FastJSON、自定义日期格式） |
| `SensitiveDataMasker` | `JacksonSensitiveDataMasker` | 定制脱敏规则（如手机号部分掩码 `138****1234`） |
| `SpelEngine` | `DefaultSpelEngine`（LRU 表达式缓存，支持 `enabled` 直通降级） | 定制表达式引擎（如增加自定义函数） |
| `PayloadPolicy` | 按 `operate-log.payload.*` 组装 | 定制载荷防护（自定义截断 / 忽略类型逻辑，注册 Bean 即覆盖） |

### 示例：业务自定义字段（extra）

落库方常常要记一些日志模型没有的业务字段（渠道、金额、审批单号……）。无需扩展 `OperateLogRecord`，在业务方法内写 `extra` 即可：

```java
@OperateLog(module = "order", operation = "cancel", type = OperateType.UPDATE,
        businessId = "#orderNo")
public void cancel(String orderNo) {
    Order order = orderService.get(orderNo);
    OperateLogContextHolder.putExtra("channel", order.getChannel());
    OperateLogContextHolder.putExtra("amount", order.getAmount());
    orderService.doCancel(orderNo);
}
```

- 记录 JSON 中体现为 `"extra":{"channel":"APP","amount":9900}`；未写入时 `extra` 为 `null`。
- 切面管辖范围之外（如业务另起的异步线程）调用是安全 no-op，不抛异常、不入日志。
- 值由序列化器直接写入日志，**请勿放入未脱敏的敏感数据**。

### 示例：落库 Handler

```java
@Bean
public OperateLogHandler operateLogHandler(OperateLogJdbcRepository repository) {
    return record -> repository.insert(record); // 替换默认的 SLF4J 输出
}
```

### 示例：手机号部分掩码

```java
@Bean
public SensitiveDataMasker sensitiveDataMasker(ObjectMapper mapper) {
    SensitiveDataMasker base = new JacksonSensitiveDataMasker(mapper,
            Set.of("mobile", "phone"), null);
    return json -> postProcess(base.mask(json)); // 在默认脱敏基础上追加自定义规则
}
```

### 示例：仅记录慢调用

配合注解 `condition` 属性零代码实现（见「使用示例」），无需扩展点。

## 工作原理

```text
@OperateLog 方法调用
        │
        ▼
OperateLogAspect @Around 拦截
        │
        ├─► 解析 MDC traceId（key 可配，缺失生成 UUID）
        ├─► OperatorResolver 解析操作人（异常降级 null）
        ├─► HttpContextResolver#resolve() 采集 HTTP 请求侧信息：method/url/uri/query/headers/ip/UA
        │     （不采集 response status，理由见「为什么没有 httpStatus」）
        ▼
   执行业务方法（正常 → result；异常 → error 并原样抛出）
        │
        ▼
   finally 中 finishQuietly（本方法绝不抛出：日志侧异常若逃逸会顶替业务异常）
        ├─► 计时（endTime / costTime）
        └─► handleSafely（全程 try-catch Throwable，绝不影响业务；失败以 warn 暴露）
              ├─► recordOn（ALWAYS/SUCCESS/ERROR）先行短路 → 不满足直接跳过
              ├─► condition SpEL 条件不满足 → 直接跳过
              ├─► 组装 OperateLogRecord（忽略类型过滤 + 整组/逐元素序列化降级 + 脱敏 + 截断）
              └─► OperateLogHandler.handle(record)
        └─► 恢复/清理 OperateLogContextHolder（嵌套场景还原外层，线程池零泄漏）
```

关键设计保证：

- **业务无感**：切面只在 `finally` 中做日志工作，业务异常原样透传；日志链路（注解查找、上下文构建、条件评估、序列化、脱敏、截断、Handler）整体包裹在 `catch (Throwable)` 中，任何日志侧故障不会导致业务请求失败。
- **组件可观测**：所有被吞掉的故障（解析降级、组装失败、表达式失败）都以 `warn` 输出原因，排查「日志为什么少了」时开 `logging.level.io.github.devoracode.operatelog=debug` 可进一步降噪对照，不会静默吞。
- **解析降级**：操作人 / HTTP 上下文解析各自独立 try-catch，单个解析器故障只损失对应字段，其余字段照常落地。
- **finally 不吞业务异常**：切面 `finally` 内的计时、组装与落地全部包在 `finishQuietly` 的
  `catch (Throwable)` 中——日志收尾异常绝不允许顶替业务异常（`recordOn=ERROR` 的审计恰恰依赖这点）。
- **序列化隔离**：参数整体序列化失败时逐元素降级，坏元素替换为 `<UNSERIALIZABLE:类型>` 占位、其余照常记录；返回值序列化失败降级为占位符——序列化问题从不损失整条日志。

## 双栈 Starter 说明

`operate-log-spring-boot-starter` 以 **JDK 8 / Java 8 字节码**编译，同时服务两类宿主：

| 宿主 | Servlet API | 生效实现 | 注册机制 |
| --- | --- | --- | --- |
| Spring Boot 2.x（JDK 8+） | `javax.servlet` | `...boot.servlet.javax` 栈 | `META-INF/spring.factories` |
| Spring Boot 3.x（JDK 17+） | `jakarta.servlet` | `...boot.servlet.jakarta` 栈 | `META-INF/spring/...AutoConfiguration.imports` |

- 装配结构（不依赖声明顺序）：自动配置拆为四个内部配置类，条件**两两互斥**，由 classpath 唯一决定：

  ```text
  OperateLogAutoConfiguration              operate-log.enabled 总开关 + 属性绑定
  ├── CommonConfiguration                   与栈无关：Serializer / Masker / SpelEngine / PayloadPolicy / Handler / Aspect
  ├── JakartaServletConfiguration           @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
  ├── JavaxServletConfiguration             @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
  │                                        + @ConditionalOnMissingClass("jakarta.servlet.http.HttpServletRequest")
  └── FallbackConfiguration                 @ConditionalOnMissingClass(两种 HttpServletRequest)
  ```

  「jakarta 优先」由 `@ConditionalOnMissingClass` **显式表达**，而不是靠「javax 配置排在 jakarta 之后」；
  兜底实现由「两种 Servlet API 都不存在」这一条件直接命中，保证非 Web 环境切面构造注入永远成立。
  各 bean 方法上的 `@ConditionalOnMissingBean` 只用于「让位给用户自定义 Bean」，
  不再承担栈间互斥职责——因此调整方法或内部类的排列次序不会改变装配结果
  （由两个测试工程的 `...StarterAssemblyTest` 锁死：任一 classpath 组合下都只有一套实现）。
- 条件注解一律用 `name = "..."` 字符串形式，条件 bean 方法的返回/参数类型只出现 core 接口
  （栈专属类型只在方法体内 `new`），因此条件未命中那一侧不会触发类加载失败。
- Starter 的 Boot / Spring 相关编译依赖全部为 `provided`，不向宿主传递任何 Boot 2.7 坐标，与 Boot 3 宿主零冲突。
- `operate-log-core` 中 Spring 与 SLF4J 为 `optional`（版本只用于 core 自身编译，
  不进消费者依赖图），jackson-databind / aspectjweaver / commons-lang3 保留 compile 传递
  （宿主不必然提供；其版本 == Boot 2.7.18 基线，不会反向压过宿主版本）。
  于是 Boot 2 宿主解析到 Spring 5.x、Boot 3 宿主解析到 Spring 6.x，
  不再出现 `starter → core → spring-core:5.3.32` 这类锁定；
  CI 的 `dependency-hygiene` 作业对三棵真实依赖树做回归锁定。
- jakarta 栈编译期使用 Servlet API 5.0.0（Java 8 字节码），运行时兼容 Boot 3 提供的 6.0.0。

## 支持的版本

| 宿主 | 支持范围 | CI 认证组合 | 说明 |
| --- | --- | --- | --- |
| Spring Boot 2.x | **2.2+ 全 2.x 线** | **2.7.18 × JDK 8 / 17 / 21** | 自动配置经 `spring.factories` 注册（Boot 2 全系一致）；2.0 / 2.1 理论可用（Spring < 5.2 忽略 `proxyBeanMethods` 属性，仅退化为 CGLIB 全代理），未列入认证矩阵；1.x 不支持 |
| Spring Boot 3.x | **3.0 – 3.5 全 3.x 线** | **3.3.13 × JDK 17 / 21** | `AutoConfiguration.imports` 注册机制自 3.0 起一致；所用 Spring / Jackson / Servlet API 均为跨小版本稳定面 |
| Spring Boot 4.x | 未认证 | — | Framework 7 / Jackson 3 默认栈下 `com.fasterxml` `ObjectMapper` 可能缺 Bean，待评估后另行宣布 |

- **JDK**：发布物字节码为 Java 8，任意 Boot 2 宿主 JDK ≥ 8；Boot 3 宿主跟随
  Spring Framework 6 要求 JDK ≥ 17。
- **Servlet API**：jakarta 实现按 Servlet 5.0 编译，运行于 5.0 / 6.0 / 6.1
  均可（仅使用签名一致的 API）。
- 日志字段与扩展点接口只依赖上述版本区间内稳定的公共 API。
- 提示：截至 2026-09，Boot 2.x / 3.x 各线在**上游均已 OSS 停止维护**，
  安全补丁请自行评估（商业延长支持如 HeroDevs NES 可选）。本项目仍会按
  CI 矩阵持续认证兼容性。

## 构建

| 环境 | 命令 | 构建内容 |
| --- | --- | --- |
| JDK 8 | `mvn clean install` | core + starter + test-boot2（3 个模块，测试只跑 test-boot2） |
| JDK 17+ | `mvn clean install` | 上述 + test-boot3（`jdk [17,)` profile 自动激活） |

冒烟验证（JDK 17 环境）：

```bash
# Boot 2 示例（javax 栈）
mvn -pl operate-log-test-boot2 spring-boot:run
# 另开终端
curl http://localhost:8080/demo/42
curl -X POST http://localhost:8080/demo -H "Content-Type: application/json" \
     -d '{"name":"a","password":"secret"}'

# Boot 3 示例（jakarta 栈）
mvn -pl operate-log-test-boot3 spring-boot:run
curl http://localhost:8080/demo/42
```

预期：两个示例的控制台均输出 `operate-log={...}` 单行 JSON，且 POST 请求中 `password` 字段被脱敏为 `******`。

持续集成（`.github/workflows/ci.yml`）在 **JDK 8 / 17 / 21** 三档跑全量
`mvn clean verify`：boot2 / boot3 两侧逐条对称的五类用例（装配选择、端到端字段、
配置覆盖语义、Business Zero Impact、非 Web 环境）全部在矩阵内——
「双栈 + 全 JDK 代际」是本项目对单栈同类库的护城河，以 CI 锁死，任何 PR 不允许回归。

**测试分层**：`operate-log-core` 与 `operate-log-spring-boot-starter` **不放单元测试**，
全部断言在两个真实 Boot 应用模块里完成——本项目是「装配 + 切面」型组件，
脱离真实容器与真实 classpath 的隔离单测容易测到实现细节，而测不到会出事的地方。

| 模块 / 用例 | 覆盖内容 |
| --- | --- |
| `operate-log-test-boot2`、`operate-log-test-boot3`（两侧逐条对称） | |
| `...MockMvcTest` | 经完整 Spring MVC + AOP 链路断言落地 JSON 的每个字段：SpEL（变量面 + 失败降级）、`condition` / `recordOn` 短路、脱敏（嵌套 + 大小写、精确匹配不误伤）、`<IGNORED:>` 忽略类型、`<UNSERIALIZABLE:>` 逐元素降级、截断恰等于上限（含标记）、嵌套调用 `extra` 隔离与上下文恢复、traceId（MDC 命中 / 缺失生成）、`clientIp` 与 `userAgent`、日志模型中不得出现 `httpStatus` |
| `...ConfigurationOverrideTest` | 独立上下文覆盖配置生效语义：`capture-headers`、`trust-proxy`（X-Forwarded-For 链取首个）、`mask.enabled=false`、`payload.max-response-length` 覆盖后的截断长度、`spel.enabled=false` 的引擎直通降级 |
| `...BusinessZeroImpactTest` | `RANDOM_PORT` 真实容器 + 故障开关：`OperatorResolver` / `OperateLogSerializer` / `OperateLogHandler` 分别抛异常时业务状态码与返回体不受影响；日志全线故障下业务异常仍以 500 收口、文案不被日志异常顶替 |
| `...NonWebContextTest` | `web-application-type=none`：应用照常启动、切面对普通 bean 生效、HTTP 字段全为 `null`、解析器返回 `null` 而不抛异常 |
| `...StarterAssemblyTest` | `ApplicationContextRunner` + `FilteredClassLoader`：本栈解析器命中且**只有一套**、隐藏 Servlet API 后走兜底实现、`enabled=false` 整体不装配、用户 `HttpContextResolver` 让位仍保留栈内 `ClientIpResolver`、属性绑定到 `PayloadPolicy` / `SensitiveDataMasker`；并以反射固化「`@OperateLog` 只允许方法级」「`OperateLogRecord` / `HttpContext` 无状态码字段」 |

> MockMvc 不执行容器真正的返回值处理阶段，所以 `@ResponseStatus` 这类「晚于切面」的状态改写
> 在测试中表现为「客户端拿到 201、日志里记 200」——这是**刻意的边界断言**
> （`annotatedResponseStatusIsBeyondAspectReach`），不是断言写错。
> 要断言真实响应语义就用 `webEnvironment = RANDOM_PORT` + `TestRestTemplate#getStatusCode()`。

**依赖边界检查**：测试工程 import BOM 会掩盖库侧污染，「测试通过」证明不了产物干净，
因此必须直接看依赖树（等价于 CI 的 `dependency-hygiene` 作业）：

```bash
mvn -B -DskipTests install                          # 先把本仓库装进本地库供依赖树解析
mvn -B -ntp dependency:tree -pl operate-log-spring-boot-starter \
    -DoutputFile=target/dep-tree-starter.txt
mvn -B -ntp dependency:tree -pl operate-log-test-boot2 \
    -DoutputFile=target/dep-tree-boot2.txt
mvn -B -ntp dependency:tree -pl operate-log-test-boot3 \
    -DoutputFile=target/dep-tree-boot3.txt          # 需 JDK 17+（boot3-test profile 自动激活）
bash .github/scripts/dependency-hygiene.sh \
    target/dep-tree-boot2.txt target/dep-tree-boot3.txt target/dep-tree-starter.txt
```

预期：Boot 2 树中 `spring-core` 只有一个版本且来自宿主（2.7.18 → `5.3.31`，**不是** core 曾传递的 `5.3.32`）；
Boot 3 树中 `org.springframework:*` 全为 `6.x`、`slf4j-api` 为 `2.0.x`、Jackson 三件套同版本、无 `5.x` 残留；
Starter 树中没有任何 `compile` 作用域的 `org.springframework:*` / `org.slf4j:*`，
但 `jackson-databind` / `aspectjweaver` / `commons-lang3` 必须以 `compile` 传递（宿主不必然提供）。
门禁是双向的：把任一 `optional` / `provided` 改回 `compile` 会红，把 core 的硬依赖裁成 `provided` 也会红。

发布与二进制兼容：core / starter 的 MINOR、PATCH 升级必须对下游二进制兼容，
合入前后可用 japicmp 门禁自查（详见 [RELEASE.md](RELEASE.md)）：

```bash
mvn -B -pl operate-log-core,operate-log-spring-boot-starter \
    verify -Djapicmp.oldVersion=<上一发布版本>
```

## 已知限制与规划

当前版本不包含（可按需向社区提 PR 或等待后续版本）：

- 异步 / 批量 Handler（当前 Handler 同步调用，自定义异步 Handler 可实现等价效果）。
- 内置 JDBC / MQ / Redis / ES Handler（请通过 `OperateLogHandler` 扩展点自行落地）。
- 注解只支持方法级（`@Target(METHOD)`）：不提供类级标注与「类级默认值继承」。需要整类全量审计时，
  自行注册 `OperateLogAspect` / 扩大切点，而不是把注解贴到类上。
- CGLIB 代理 + 注解仅标注接口方法（advice 不织入，见「注解属性」处的说明；JDK 代理场景已支持接口注解查找）。
- 不记录 HTTP 状态码：切面时刻取不到最终值，`ResponseEntity` / `@ResponseStatus` /
  `@ControllerAdvice` 改写的状态一律不在审计日志里，见「为什么没有 `httpStatus`」。

> 已实现（原计划项）：载荷长度截断（`operate-log.payload.*`）、序列化忽略类型与逐元素降级、
> `operate-log.spel.enabled` 生效（引擎直通降级）、表达式缓存 LRU 化、`extra` 自定义字段通道、
> traceId MDC key 可配置（`trace-id-mdc-key`）、`recordOn` 记录时机过滤、
> `OperateType` 扩展（GRANT / REVOKE / DOWNLOAD / PRINT）。

## License

本项目基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 开源，详见 [LICENSE](LICENSE)。
