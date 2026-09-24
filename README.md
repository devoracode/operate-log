# operate-log

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
| 敏感数据脱敏 | JSON 树递归脱敏 + query string 参数名掩码，字段名忽略大小写，脱敏字段与替换文本可配置 |
| 全链路关联 | traceId 取 MDC，key 可配（`operate-log.trace-id-mdc-key`，默认 `traceId`，对齐 Sleuth / Micrometer / OTel 等链路追踪体系）；MDC 缺失时生成 UUID 并**回写 MDC**，同一次请求内多条记录共享同一值 |
| 异常安全 | 日志组件内部任何异常均被隔离捕获，**绝不影响业务方法执行**；故障细节以 debug 日志暴露，不静默吞 |
| 载荷防护 | `requestBody` / `responseBody` / `requestHeaders` / `requestQuery` / `userAgent` / `errorMessage` / `errorStack` 最大长度可配，超限截断打标记（标记计入上限，结果长度恒 `<=` 配置值）；文件 / 流 / Servlet 容器等不宜序列化的参数自动替换为 `<IGNORED:类型>` 占位符；序列化失败逐元素降级，单个坏参数不拖垮整条记录 |
| 自定义字段 | 业务方法与 Resolver 内通过 `OperateLogContextHolder#putExtra` 向当前日志记录追加任意字段（`extra`），无需扩展记录模型 |
| 宿主零污染 | 不注册任何 `ObjectMapper` bean，宿主 `spring.jackson.*` 与自定义 `Module` 不受影响；脱敏默认字段始终生效，配置只追加不移除 |
| 可插拔扩展点 | Handler / 操作人解析 / HTTP 上下文解析 / 序列化 / 脱敏 / SpEL 引擎 / 载荷策略全部可替换（`@ConditionalOnMissingBean` 自动让位） |
| 异常降级 | 操作人、HTTP 上下文解析失败时降级为 `null`，SpEL 表达式求值失败按方向降级（condition 视为通过、模板输出原文），日志其余字段照常记录 |

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `operate-log-core` | 核心：注解、AOP 切面、上下文模型（含 `extra` 自定义字段通道）、SpEL 引擎、序列化、脱敏、载荷防护（PayloadPolicy）、Handler / Resolver 扩展点。Java 8 基线；Spring、SLF4J、Jackson 为 `optional`（版本由宿主 Boot 决定），aspectjweaver / commons-lang3 以 compile 传递（宿主不必然提供）。日志 JSON 复用宿主 `ObjectMapper` |
| `operate-log-spring-boot-starter` | Boot 2.x / 3.x 双栈自动装配：`javax` / `jakarta` 两套 Servlet 解析实现按 classpath 自动选择，非 Web 环境兜底。Java 8 字节码，Boot 相关依赖全部 `provided` 零传递 |
| `operate-log-test-boot2` | Boot 2.x 可运行示例（`javax` 栈冒烟） |
| `operate-log-test-boot3` | Boot 3.x 可运行示例与全部集成用例（`jakarta` 栈，JDK 17+ 时自动纳入构建） |

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

> 日志 JSON 复用宿主 `ObjectMapper`（Boot Web 默认已有）。**Starter 不注册任何 `ObjectMapper` bean**：自动配置按类名排序时本组件排在 Boot 的 `JacksonAutoConfiguration` 之前，一旦注册就会顶掉宿主的 `spring.jackson.*` 与自定义 `Module`，波及宿主自身的 MVC 序列化；日志侧只取宿主 mapper 的防御性副本。
> **注意**：该副本会补注册 `JavaTimeModule`，因此非 Web 宿主即使提供了未注册 jsr310 的自定义 `ObjectMapper`，日志���列化也不会失败；宿主完全没有 `ObjectMapper` bean 时（非 Web 且未装配 Jackson）才内部自建兜底实例，仍不污染容器。
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
        if (user == null) {
            return null;
        }
        // 角色这类扩展属性写进 extra，不占记录字段
        OperateLogContextHolder.putExtra("roleId", user.getRoleId());
        OperateLogContextHolder.putExtra("roleCode", user.getRoleCode());
        OperateLogContextHolder.putExtra("roleName", user.getRoleName());
        return Operator.builder()
                .userId(user.getId())
                .userAccount(user.getAccount())
                .userName(user.getName())
                .build();
    };
}
```

`Operator` 刻意只留三要素：角色、部门、租户的形状各系统差别太大（单角色 / 多角色 / 角色带层级），做成固定字段等于把某一家的模型钉进公共契约。解析器在日志上下文绑定之后执行，所以 `resolve()` 内可以直接写 `extra`——多角色 `putExtra("roleCodes", user.getRoles())`，多个键也可用 `putExtras(Map)` 一次写入。这些键与业务方法内写入的共存，同样不自动脱敏（见「日志输出字段」）。

---

## 注解属性（`@OperateLog`）

注解只标注在**方法**上（`@Target(ElementType.METHOD)`）：审计范围由业务方法逐个显式声明。类上不识别 `@OperateLog`——类级标注会连带拦截该类全部方法（getter、内部复用方法一并进日志），噪声与开销都不可控，因此不提供类级默认值与字段级继承语义。

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

// 想覆盖一个类的多个方法：逐个方法显式标注即可。
// 若确实需要按类粒度全量审计，请自行注册 OperateLogAspect Bean 并扩大切点，
// 而不是把注解贴到类上——类上不识别 @OperateLog。
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
- 方法参数名与内置变量或位置别名 `#pN` / `#aN` 同名时**以保留名为准**：撞名参数不写入求值上下文（否则 `#result` 会被参数静默改写，审计字段失真），该参数改用 `#p0` / `#a0` 等位置别名取。
- 表达式解析结果带定容缓存（默认 1024 条，`ConcurrentHashMap` 读路径无锁），写满容量后不再放入新条目；key 空间由编译期表达式集合天然有界，正常使用不会写满，同一注解方法重复调用无重复解析开销。
- **表达式失败不中断日志**：语法错误 / 空指针访问等按方向降级——`condition` 视为通过（宁可多记不漏记）、`description` 输出模板原文、`businessId` 记 `null`，原因以 debug 日志暴露。
- `operate-log.spel.enabled=false` 时引擎整体直通（零求值开销）：condition 恒通过、description 输出原文、businessId 为 `null`。
- **安全沙箱**：SpEL 表达式仅允许来自**编译期注解常量**（即 `@OperateLog` 的 `description` / `businessId` / `condition` 属性值），**禁止运行时动态注入表达式**（如从数据库、配置文件或外部接口读取表达式文本）。引擎使用受限的 `SimpleEvaluationContext` 沙箱，仅支持变量读取、属性访问与实例方法调用，**不支持**类型引用（`T(...)`）、构造函数、bean 引用与静态方法调用——即使表达式文本被动态注入，也无法执行任意代码，从源头杜绝远程代码执行（RCE）风险。

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
    # fields 为【追加】语义（匹配忽略大小写）：内置 16 项默认字段始终脱敏，配置只往里加。
    # 默认字段：password / passwd / pwd / token / accessToken / refreshToken / authorization /
    #          cookie / set-cookie / secret / clientSecret / apiKey / privateKey / accessKey / secretKey / creditCard
    fields:
      - mobile
      - idCard
  spel:
    enabled: true                 # SpEL 总开关；false 时引擎直通（见「SpEL 支持」）
    cache-size: 1024              # SpEL 表达式缓存容量（最小 64，写满后不再放入新条目）
  payload:                        # 载荷防护
    max-request-length: 0         # requestBody / requestHeaders / requestQuery / userAgent 最大字符数，<=0 不截断
    max-response-length: 0        # responseBody 最大字符数，<=0 不截断
    max-error-length: 0           # errorStack / errorMessage 最大字符数，<=0 不截断
    max-extra-length: 0           # extra 整体 JSON 最大字符数，<=0 不截断（超限按实际 JSON 长度缩减）
    # ignore-types:               # 在内置 17 项安全基线之外追加的忽略类型
    #   - java.util.concurrent.Callable
    # 注意：配置项会在内置 17 项安全基线之外【追加】，不会移除 Servlet、安全上下文、流、字节数组等默认防护。
```

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `operate-log.enabled` | `true` | 总开关。`false` 时自动配置整体不生效 |
| `operate-log.application` | `""` | 应用名 |
| `operate-log.environment` | `""` | 环境标识 |
| `operate-log.version` | `""` | 版本号 |
| `operate-log.trace-id-mdc-key` | `traceId` | traceId 的 MDC key。与链路追踪体系的 MDC 写入 key 对齐（Micrometer/Sleuth 常见 `traceId`，OTel logback 桥接常见 `trace_id`）；MDC 取不到时生成 UUID 并回写该 key（收尾清理，只清理本组件写入的值），配置空白回退默认 key |
| `operate-log.http.trust-proxy` | `false` | 信任代理头时，客户端 IP 解析顺序：`X-Forwarded-For`（取逗号链最后一个，即可信代理追加值）→ `X-Real-IP` → `getRemoteAddr()`；否则直接取 `getRemoteAddr()`。**仅在应用前存在单一可信代理、且该代理会清洗客户端头并追加转发链时开启**；多级代理需自行注册 `ClientIpResolver` |
| `operate-log.http.capture-headers` | `false` | 开启后采集全部请求头写入 `requestHeaders`（JSON 对象）。注意头部可能含 Cookie 等敏感信息，开启后脱敏器会一并处理 |
| `operate-log.mask.enabled` | `true` | 脱敏总开关。作用于 `requestHeaders` / `requestBody` / `responseBody` 三个 JSON 字段、`requestQuery`（按参数名匹配）、`errorMessage` / `errorStack`（纯文本子串匹配）。**不作用于 `extra`**——见字段表说明 |
| `operate-log.mask.mask-text` | `******` | 替换文本 |
| `operate-log.mask.fields` | 空 | 在 16 个内置默认字段之外**追加**的脱敏字段名（匹配忽略大小写）。默认字段始终生效，无法通过配置移除 |
| `operate-log.spel.enabled` | `true` | SpEL 开关。`false` 时引擎直通：condition 恒通过、description 输出模板原文、businessId 记 `null`，零求值开销 |
| `operate-log.spel.cache-size` | `1024` | 表达式缓存容量（实际生效最小值 64，写满后不再放入新条目） |
| `operate-log.payload.max-request-length` | `0` | `requestBody` / `requestHeaders` / `requestQuery` / `userAgent` 最大字符数，`<=0` 不截断；超限时截断为**恰好该长度**（`...[truncated]` 标记计入上限，不外挂） |
| `operate-log.payload.max-response-length` | `0` | `responseBody` 最大字符数，`<=0` 不截断；标记同上计入上限 |
| `operate-log.payload.max-error-length` | `0` | `errorStack` / `errorMessage` 最大字符数，`<=0` 不截断 |
| `operate-log.payload.max-extra-length` | `0` | `extra` 整体 JSON 的最大字符数，`<=0` 不截断（与其他三项载荷上限一致）；超限时按单条实际 JSON 长度缩减：`String` 二分截短并保留 `...[truncated]` 标记，非 `String` 按实际大小移除，保留项类型不变；最终结果严格不超过上限 |
| `operate-log.payload.ignore-types` | 17 项内置 | 在内置安全类型之外**追加**序列化时跳过的类型（全限定类名，父类/接口命中即算），替换为 `<IGNORED:类型简名>`；内置类型始终生效，无法通过配置移除，如需不同集合请注册自定义 `PayloadPolicy` bean。字节数组的 JVM 内部名为 `[B`，YAML 里须写成 `- "[B"`（不加引号会被当成流式序列而解析失败） |

> **升级提示**：`payload.ignore-types` 已从“整体替换”改为“追加”，内置 17 项安全防护无法再通过配置移除；必须自定义完整集合时，请改注册 `PayloadPolicy` bean。同时，默认脱敏字段新增 `apiKey` / `privateKey` / `accessKey` / `secretKey` / `creditCard`，升级后这些字段将默认被掩码。

## 日志输出字段（`OperateLogRecord`）

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| `id` | 自动生成 | 日志记录 UUID |
| `traceId` | MDC（key = `operate-log.trace-id-mdc-key`，默认 `traceId`）/ 自动生成 | 链路 ID：优先按配置 key 读 MDC（与 Sleuth / Micrometer / Zipkin 等打通）；MDC 缺失时由本组件生成 UUID 并**回写 MDC**，同一次请求内多个 `@OperateLog` 方法共享同一值（收尾清理，不覆盖宿主已有值）。未接入链路追踪体系时**不跨服务**，仅保证单次请求内关联 |
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
| `errorType` / `errorMessage` / `errorStack` | 运行时 | 异常类名 / message / 堆栈（message 与堆栈按 `payload.max-error-length` 截断），正常时为 `null` |
| `extra` | `OperateLogContextHolder#putExtra`（业务方法内 / 各 Resolver 内） | 业务自定义字段（Map），写入后随记录落地；**由开发者主动写入，本组件不对其脱敏**（脱敏需要字段的业务语义，工具无法代判，硬猜只会误伤），请勿往 `extra` 里放密码 / token 等敏感数据；坏值（循环引用 / getter 抛错）降级为 `<UNSERIALIZABLE:类型>` 占位、不拖垮整条日志，并按 `payload.max-extra-length` 的实际 JSON 长度规则限长；未写入时为 `null` |

> 所有可能为 `null` 的字段在 JSON 中保留为 `null` 值，便于下游解析 schema 稳定。
> `requestBody` / `responseBody` / `requestHeaders` / `requestQuery` / `userAgent` / `errorMessage` / `errorStack` 均受 `payload.*` 长度上限保护，超限截断。

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

- 作用于 `requestHeaders`、`requestBody`、`responseBody` 三个 JSON 字符串字段、`requestQuery`（URL 查询参数）以及 `errorMessage` / `errorStack`（异常消息与堆栈）。JSON 字段采用 **JSON 树递归**（Jackson `JsonNode`）：嵌套对象、数组、集合中的敏感字段全部命中，不限于顶层；query string 按参数名匹配；异常文本按敏感字段名做单遍子串匹配，三条通道复用同一份敏感字段集合。
- query 参数名先按 `application/x-www-form-urlencoded` **解码再匹配**，`%74oken=...` / `Pass%77ord=...` 这类编码写法与明文同名同等命中，防止编码绕过；输出仍保留参数名原始写法，只替换值。
- 一个敏感字段都没命中时返回原文，不做无谓的重写（避免数字与格式漂移）。
- 字段名匹配**忽略大小写**（`Password` / `PASSWORD` / `password` 均脱敏）。
- 命中字段的值替换为 `mask-text`（默认 `******`）。
- JSON 内容无法解析或脱敏过程异常时**原样返回**，不阻断日志流程。
- 敏感字段集合通过 `operate-log.mask.fields` **追加**。16 个内置默认字段（`password` / `passwd` / `pwd` / `token` / `accessToken` / `refreshToken` / `authorization` / `cookie` / `set-cookie` / `secret` / `clientSecret` / `apiKey` / `privateKey` / `accessKey` / `secretKey` / `creditCard`）**始终脱敏，配置无法移除**——避免「只想加一个字段」却把既有脱敏项一起关掉。
- **作用边界**：`requestUrl`（不含 query 的完整 URL）与 `extra` **不经过脱敏管道**。`extra` 只按开发者显式写入的内容采集，不做字段语义猜测或自动脱敏。

## 扩展点

以下接口均可通过注册同名 Bean 覆盖默认实现（自动配置全部标注 `@ConditionalOnMissingBean`，用户 Bean 优先）：

| 扩展点 | 默认实现 | 用途 |
| --- | --- | --- |
| `OperateLogHandler` | `DefaultOperateLogHandler`（SLF4J INFO 单行 JSON，前缀 `operate-log=`） | 替换日志落地方式：写数据库 / MQ / ES / 审计系统 |
| `OperatorResolver` | 匿名（返回 `null`） | 对接登录态，提供 `userId` / `userAccount` / `userName` |
| `HttpContextResolver` | Starter 内置（`javax` / `jakarta` 自动选择） | 定制 HTTP 上下文采集（如接入非 Servlet 容器） |
| `ClientIpResolver` | Starter 内置（含 `trust-proxy` 逻辑） | 定制客户端 IP 解析策略 |
| `OperateLogSerializer` | `DefaultOperateLogSerializer`（宿主 `ObjectMapper`） | 更换序列化器（如 Gson、自定义日期格式） |
| `SensitiveDataMasker` | `DefaultSensitiveDataMasker`（JSON 树递归替换 + query 参数名掩码 + 异常文本字段名掩码） | 定制脱敏规则（如手机号部分掩码 `138****1234`） |
| `SpelEngine` | `DefaultSpelEngine`（定容表达式缓存，读路径无锁，支持 `enabled` 直通降级） | 定制表达式引擎（如增加自定义函数） |
| `PayloadPolicy` | 按 `operate-log.payload.*` 组装 | 定制载荷防护（自定义截断 / 忽略类型逻辑，注册 Bean 即覆盖） |

### 示例：业务自定义字段（extra）

落库方常常要记一些日志模型没有的业务字段（渠道、金额、审批单号……）。无需扩展 `OperateLogRecord`，在业务方法内写 `extra` 即可（操作人角色等登录态属性同理，在 `OperatorResolver` 内写，见「快速开始」第 3 步）：

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
- 值由序列化器直接写入日志，**本组件不对 `extra` 做自动脱敏**（脱敏开关 `mask.enabled` 不覆盖它），请勿放入密码 / token 等敏感数据。

### 示例：落库 Handler

```java
@Bean
public OperateLogHandler operateLogHandler(OperateLogJdbcRepository repository) {
    return record -> repository.insert(record); // 替换默认的 SLF4J 输出
}
```

### 示例：手机号部分掩码

自定义实现需保留 `mask`、`maskPlainText` 与 `maskQuery` 三个入口，避免 query 或异常文本脱敏静默失效。

```java
@Bean
public SensitiveDataMasker operateLogSensitiveDataMasker(ObjectMapper objectMapper) {
    Set<String> fields = new HashSet<String>(Arrays.asList("mobile", "phone"));
    SensitiveDataMasker base = new DefaultSensitiveDataMasker(objectMapper, fields, null);
    return new SensitiveDataMasker() {
        @Override
        public String mask(String content) {
            return postProcess(base.mask(content));
        }

        @Override
        public String maskPlainText(String text) {
            return base.maskPlainText(text);
        }

        @Override
        public String maskQuery(String query) {
            return base.maskQuery(query);
        }
    };
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
        ├─► 绑定线程上下文（OperateLogContextHolder.bind）
        ├─► OperatorResolver 解析操作人（异常降级 null；resolve() 内可写 extra）
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

- 装配结构：四个内部配置类，栈间条件**两两互斥**，由 classpath 唯一决定（与声明次序无关）：

  ```text
  OperateLogAutoConfiguration              operate-log.enabled 总开关 + 属性绑定
  ├── CommonConfiguration                   与栈无关：Serializer / Masker / SpelEngine / PayloadPolicy / Handler / Aspect
  ├── JakartaServletConfiguration           @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
  ├── JavaxServletConfiguration             @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
  │                                        + @ConditionalOnMissingClass("jakarta.servlet.http.HttpServletRequest")
  └── FallbackConfiguration                 @ConditionalOnMissingClass(两种 HttpServletRequest)
  ```

  「jakarta 优先」是 `JavaxServletConfiguration` 上 `@ConditionalOnMissingClass` 的直接结论；
  兜底实现由「两种 Servlet API 都不存在」命中，保证非 Web 环境切面构造注入永远成立。
  `@ConditionalOnMissingBean` 在各类里只负责一件事：让位给用户自定义 Bean；
  栈间互斥与兜底判定全由类级 classpath 条件承担，因此方法与内部类的排列次序不影响装配结果
  （由两个测试工程的 `...StarterAssemblyTest` 锁死：任一 classpath 组合下都只有一套实现）。
- 条件注解一律用 `name = "..."` 字符串形式，条件 bean 方法的返回/参数类型只出现 core 接口
  （栈专属类型只在方法体内 `new`），因此条件未命中那一侧不会触发类加载失败。
- Starter 的 Boot / Spring 相关编译依赖全部为 `provided`，不向宿主传递任何 Boot 2.7 坐标，与 Boot 3 宿主零冲突。
- `operate-log-core` 中 Spring、SLF4J、Jackson 为 `optional`（版本只用于 core 自身编译，
  不进消费者依赖图）；aspectjweaver / commons-lang3 保留 compile 传递
  （宿主不必然提供；版本 == Boot 2.7.18 基线，不会反向压过宿主版本）。
  于是 Boot 2 宿主解析到 Spring 5.x / Jackson 2.13、Boot 3 宿主解析到 Spring 6.x / Jackson 2.17：
  宿主框架版本始终由宿主自己定，
  可分别用 `mvn -B -pl operate-log-test-boot2,operate-log-test-boot3 dependency:tree` 核对。
- jakarta 栈编译期使用 Servlet API 5.0.0（Java 8 字节码），运行时兼容 Boot 3 提供的 6.0.0。

## 支持的版本

| 宿主 | 支持范围 | 验证基线（示例工程所用版本） | 说明 |
| --- | --- | --- | --- |
| Spring Boot 2.x | **2.2+ 全 2.x 线** | **Boot 2.7.18**（JDK 8 起，17 / 21 亦可） | 自动配置经 `spring.factories` 注册（Boot 2 全系一致）；2.0 / 2.1 理论可用（Spring < 5.2 忽略 `proxyBeanMethods` 属性，仅退化为 CGLIB 全代理），未列入认证范围；1.x 不支持 |
| Spring Boot 3.x | **3.0 – 3.5 全 3.x 线** | **Boot 3.3.13**（JDK 17+，由 `boot3-test` profile 自动纳入） | `AutoConfiguration.imports` 注册机制自 3.0 起一致；所用 Spring / Servlet API 均为跨小版本稳定面 |
| Spring Boot 4.x | 未认证 | — | Boot 4 默认 Jackson 3（`tools.jackson`），与本组件使用的 `com.fasterxml.jackson` 不是同一套 API，需自行提供 Jackson 2 `ObjectMapper` 或自定义序列化扩展点 |

- **JDK**：发布物字节码为 Java 8，任意 Boot 2 宿主 JDK ≥ 8；Boot 3 宿主跟随
  Spring Framework 6 要求 JDK ≥ 17。
- **Servlet API**：jakarta 实现按 Servlet 5.0 编译，运行于 5.0 / 6.0 / 6.1
  均可（仅使用签名一致的 API）。
- 日志字段与扩展点接口只依赖上述版本区间内稳定的公共 API。
- 提示：截至 2026-09，Boot 2.x / 3.x 各线在**上游均已 OSS 停止维护**，
  安全补丁请自行评估（商业延长支持如 HeroDevs NES 可选）。

## 已知限制与规划

当前版本不包含（可按需向社区提 PR 或等待后续版本）：

- 异步 / 批量 Handler（当前 Handler 同步调用，自定义异步 Handler 可实现等价效果）。
- 内置 JDBC / MQ / Redis / ES Handler（请通过 `OperateLogHandler` 扩展点自行落地）。
- 注解只支持方法级（`@Target(METHOD)`）：不提供类级标注与「类级默认值继承」。需要整类全量审计时，
  自行注册 `OperateLogAspect` / 扩大切点，而不是把注解贴到类上。
- CGLIB 代理 + 注解仅标注接口方法（advice 不织入，见「注解属性」处的说明；JDK 代理场景已支持接口注解查找）。
- 不记录 HTTP 状态码：切面时刻取不到最终值，`ResponseEntity` / `@ResponseStatus` /
  `@ControllerAdvice` 改写的状态一律不在审计日志里，见「为什么没有 `httpStatus`」。
- `success` 的语义边界是「业务方法未抛异常」，**不含事务提交结果**：切面顺序已显式声明为
  `Ordered.LOWEST_PRECEDENCE`（不再依赖默认值推断），但与 `@Transactional` 的默认顺序**同值**，
  两级通知的相对次序不由本组件保证。事务提交失败并回滚时，日志可能已写为 `success = true`。
  审计判据需要与事务结果一致时，请勿直接采信 `success`，应为事务通知显式指定更低 order
  （使其位于本切面外层），或改用自定义 `OperateLogHandler` 延后落地。
- traceId **不跨服务**：未接入 Sleuth / Micrometer / OTel 等链路追踪体系时，本组件生成的 UUID
  仅保证「同一次请求内多条记录共享」（生成后回写 MDC、收尾清理），不具备跨进程传递能力。
- `extra` 与 traceId 基于 **ThreadLocal**：业务方法内切换线程的场景（`@Async`、自建线程池、
  `CompletableFuture` 默认线程池等）不会自动传播——切线程后 `OperateLogContextHolder#putExtra`
  写入的字段不再归属当前记录，traceId 关联同样失效。跨线程需要时自行传递上下文
  （如以 `TaskDecorator` 包装任务，或在异步方法内重新 `putExtra`）。

> 已实现（原计划项）：载荷长度截断（`operate-log.payload.*`）、序列化忽略类型与逐元素降级、
> `operate-log.spel.enabled` 生效（引擎直通降级）、表达式缓存定容化、`extra` 自定义字段通道、
> traceId MDC key 可配置（`trace-id-mdc-key`）、`recordOn` 记录时机过滤、
> `OperateType` 扩展（GRANT / REVOKE / DOWNLOAD / PRINT）。

## License

本项目基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 开源，详见 [LICENSE](LICENSE)。
