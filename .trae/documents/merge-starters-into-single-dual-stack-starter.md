# 合并 Boot 2/3 Starter 为单一双栈 Starter

## Context

将 `operate-log-spring-boot2-starter` 和 `operate-log-spring-boot3-starter` 合并为一个 `operate-log-spring-boot-starter`：
- 使用 jakarta.servlet-api **5.0.0**（Java 8 字节码）替代 6.0.0（需 Java 11+），使 starter 可在 **JDK 8 环境**编译（release 8）
- 在 Boot 3.x 宿主中正常运行（宿主提供 servlet-api 6.0，用到的 API 二进制兼容）
- **保留双栈**：同时支持 Boot 2.x（javax）与 Boot 3.x（jakarta），按 classpath 条件装配自动选择

### 关键技术约束（方案的根基）

1. **JDK 8 编译只能用 Spring 5.3.32 / Boot 2.7.18 编译依赖**（Spring 6 class 是 Java 17）
2. **`ServletRequestAttributes.getRequest()/getResponse()` 二进制不兼容**：Spring 5.3 返回 javax 类型、Spring 6 返回 jakarta 类型，方法描述符不同 → 跨版本 `NoSuchMethodError`。**合并后的 resolver 禁止调用这两个方法**
3. **替代通道**：
   - request：`RequestContextHolder.getRequestAttributes()`（静态方法签名两代一致）→ `attributes.resolveReference(RequestAttributes.REFERENCE_REQUEST)` 返回 `Object`（运行时是真实 request）→ `instanceof` + 强转为编译期栈类型
   - response：反射 `attributes.getClass().getMethod("getResponse").invoke(attributes)`（方法名两代相同，反射不受描述符限制），失败降级 null
4. **instanceof 陷阱**：一个类同时写 javax 和 jakarta 的 instanceof 会在单栈环境 `NoClassDefFoundError` → 必须**一个类只 import 一种 Servlet API**，双栈拆 4 个 resolver 类，由 `@ConditionalOnClass(name = "...")`（字符串形式）条件装配选择
5. **注册机制**：同一 jar 同时打 `META-INF/spring.factories`（Boot 2.x）和 `META-INF/spring/...AutoConfiguration.imports`（Boot 3.x）
6. **注解兼容**：`@Configuration`/`@ConditionalOnMissingBean`/`@ConditionalOnClass`/`@ConditionalOnProperty`/`@EnableConfigurationProperties` 在 Boot 2.7.18 与 3.x 包名签名完全一致 → 用 2.7.18 编译、Boot 3 运行正常
7. **依赖零污染**：Boot 相关编译依赖全部 `provided`，不传递 2.7 坐标给宿主

## 实施步骤

### 1. 新建模块 `operate-log-spring-boot-starter`（包 `io.github.devoracode.operatelog.boot`）

```
operate-log-spring-boot-starter/
├── pom.xml
└── src/main/
    ├── java/io/github/devoracode/operatelog/boot/
    │   ├── OperateLogAutoConfiguration.java
    │   ├── OperateLogProperties.java
    │   └── servlet/
    │       ├── javax/  OperateLogJavaxClientIpResolver.java
    │       │           OperateLogJavaxHttpContextResolver.java
    │       └── jakarta/ OperateLogJakartaClientIpResolver.java
    │                   OperateLogJakartaHttpContextResolver.java
    └── resources/META-INF/
        ├── spring.factories
        └── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 2. 四个 Resolver（核心改动，详细中文注释说明兼容性缘由）

以 jakarta 栈为例（javax 对称，仅 import 不同）：

```java
// 获取 request 的唯一合法路径（禁止 ServletRequestAttributes.getRequest()）：
RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
if (attributes == null) return null;
Object requestObj = attributes.resolveReference(RequestAttributes.REFERENCE_REQUEST);
if (!(requestObj instanceof HttpServletRequest)) return null;
HttpServletRequest request = (HttpServletRequest) requestObj;

// response 反射获取（仅 HttpContextResolver 需要）：
try {
    Object responseObj = attributes.getClass().getMethod("getResponse").invoke(attributes);
    if (responseObj instanceof HttpServletResponse) {
        status = ((HttpServletResponse) responseObj).getStatus();
    }
} catch (ReflectiveOperationException ex) {
    status = null; // 优雅降级
}
```

- 业务逻辑（X-Forwarded-For / X-Real-IP / trustProxy、headers 采集、HttpContext.builder 字段）**照搬现有实现**，只换"拿 request 的方式"
- 每个 resolver 类 Javadoc 必须写清 4 点：为什么单类单栈、为什么禁用 getRequest()、为什么用 resolveReference、为什么反射 getResponse()
- 有意保留 ~10 行骨架重复，不抽公共基类（保持"本类只碰一种 Servlet API"的纪律清晰可见）

### 3. OperateLogAutoConfiguration（bean 声明顺序 = 正确性）

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperateLogProperties.class)
@ConditionalOnProperty(prefix = "operate-log", name = "enabled",
        havingValue = "true", matchIfMissing = true)
```

按以下顺序声明（Spring 按声明序评估同配置类内条件，不可重排）：

1. **通用 bean**：`operateLogOperatorResolver`（Anonymous）
2. **jakarta 条件 bean**（`@ConditionalOnClass(name="jakarta.servlet.http.HttpServletRequest")` + `@ConditionalOnMissingBean`）：jakarta ClientIpResolver、jakarta HttpContextResolver
3. **javax 条件 bean**（`@ConditionalOnClass(name="javax.servlet.http.HttpServletRequest")` + `@ConditionalOnMissingBean`）：javax ClientIpResolver、javax HttpContextResolver
4. **兜底 bean**（`@ConditionalOnMissingBean`，lambda `() -> null`）：非 Web 环境保证 Aspect 构造不失败
5. **其余通用 bean 照搬**：Serializer / Masker / SpelEngine / Handler / Aspect（参数类型改为 `OperateLogProperties`）

硬性纪律：
- `@Bean` 方法**返回类型必须是 core 接口**（`ClientIpResolver`/`HttpContextResolver`），方法体内才 `new` 具体栈类（Spring ASM 解析方法签名时若返回具体类会触发类加载）
- `@ConditionalOnClass` 必须 `name=` 字符串形式
- 类级不放任何 servlet 相关 `@ConditionalOnClass`

`OperateLogProperties`：与现有两份逐字段相同（已核对无差异），仅改名换包。

### 4. 注册文件

- `spring.factories`：`org.springframework.boot.autoconfigure.EnableAutoConfiguration=io.github.devoracode.operatelog.boot.OperateLogAutoConfiguration`
- `AutoConfiguration.imports`：`io.github.devoracode.operatelog.boot.OperateLogAutoConfiguration`
- 保持 `@Configuration`（非 `@AutoConfiguration`）以兼容 Boot 2.0–2.6

### 5. 新 starter pom.xml

```xml
<dependencyManagement>  <!-- 导入 Boot 2.7.18 BOM（${spring.boot2.version}），仅编译期定版 -->
<dependencies>
    operate-log-core                     <!-- 唯一 compile 依赖 -->
    spring-boot-autoconfigure   provided <!-- BOM 定版 2.7.18 -->
    spring-web  ${spring.framework.version}  provided <!-- 显式 5.3.32 对齐 core -->
    javax.servlet:javax.servlet-api 4.0.1  provided
    jakarta.servlet:jakarta.servlet-api 5.0.0  provided <!-- 5.0.0 = Java 8 字节码 -->
    commons-lang3  ${commons-lang3.version}  provided <!-- 运行时由 core 传递 -->
    lombok  ${lombok.version}  provided
</dependencies>
<build> maven-compiler-plugin <release>8</release> </build>
```

不引入 spring-boot-starter-aop（AOP 依赖由 core 传递的 aspectjweaver + 宿主 spring-aop 满足，避免传递 2.7 版 aspectjweaver）。

### 6. 根 pom 改动

```xml
<modules>
    <module>operate-log-core</module>
    <module>operate-log-spring-boot-starter</module>
    <module>operate-log-test-boot2</module>   <!-- 默认（Boot 2.7 支持 JDK 8） -->
</modules>
<profiles>
    <profile>
        <id>boot3-test</id>
        <activation><jdk>[17,)</jdk></activation>  <!-- JDK 17+ 自动含 test-boot3 -->
        <modules><module>operate-log-test-boot3</module></modules>
    </profile>
</profiles>
```

### 7. test 模块 pom 改动

- **test-boot2**：依赖坐标换成 `operate-log-spring-boot-starter`，其余不变
- **test-boot3**：换坐标 + **新增 `dependencyManagement` 导入 Boot 3.3.13 BOM**，starter-web/test 去掉硬编码版本由 BOM 管。此为必须项：修复既有隐患——core 传递的 spring-context 5.3.32 与 starter-web 的 6.1.16 同深度，按"先声明优先"5.3.32 胜出，Boot 3 运行时必然崩溃

### 8. 删除旧模块 + README 更新

- 删除目录 `operate-log-spring-boot2-starter/`、`operate-log-spring-boot3-starter/`
- README：模块列表合并为一条双栈描述；「当前版本能力」的"Boot 2 / Boot 3 独立 Servlet 适配"改为"单一 Starter 双栈 Servlet 适配（javax/jakarta 条件装配，非 Web 兜底）"；使用方式合并为单一坐标；新增「构建」小节说明 JDK 8 / JDK 17+ 构建差异

## 验证

1. **JDK 17 全量构建**（当前机器 JDK 17.0.2 + Maven 3.8.8）：`mvn clean install`（profile 自动含 test-boot3）。注意本地仓库无 jakarta.servlet-api 5.0.0，需联网下载
2. **字节码确认 Java 8**：`javap -verbose` 抽查 AutoConfiguration 与 4 个 resolver，`major version: 52`
3. **jar 检查**：两个注册文件、4 个 resolver 类齐全；`dependency:tree` 确认无 servlet-api/2.7 坐标传递
4. **Boot 2 冒烟**：`mvn -pl operate-log-test-boot2 spring-boot:run`，`curl http://localhost:8080/demo/42` + POST（含 password 字段）→ 验证 JSON 日志输出（ip/userAgent/status=200 证明 resolveReference + 反射路径通，password 脱敏为 ******）
5. **Boot 3 冒烟**：`mvn -pl operate-log-test-boot3 spring-boot:run`，同样请求 → 无 `NoSuchMethodError`/`NoClassDefFoundError`，jakarta bean 装配命中
6. **JDK 8 构建**（如有 JDK 8 环境）：`mvn clean install` 只构建 3 模块并成功

## 风险点

- `@Bean` 返回类型/参数类型只能是兼容类型（core 接口/Properties/ObjectMapper），写具体栈类会在条件不满足时类加载失败
- bean 声明顺序 jakarta → javax → fallback 绝不可重排
- resolver 只用 Servlet 5/6 签名一致的 API，禁用 Servlet 6 独有 API
- 双 servlet API 共存环境 jakarta 优先，属可接受边界
- core 传递 spring-* 5.3.32 对无 BOM 宿主的污染是既有问题，本次仅修 test-boot3；core 依赖 optional 化列为独立后续优化
