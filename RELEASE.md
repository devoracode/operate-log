# 发布手册（operate-log）

面向维护者的发布流程与兼容性承诺。自动化部分在
`.github/workflows/release.yml`，本文说明其背后的人为决策。

## 版本策略

- 语义化版本 `MAJOR.MINOR.PATCH`：
  - **MAJOR**：破坏性变更（公共 API 删除/改签名、配置键语义变化、最低
    Boot/JDK 基线上移）；
  - **MINOR**：向后兼容的新增（新注解属性、新扩展点、新配置项）；
  - **PATCH**：仅缺陷修复，不动任何签名。
- 预发布用 `-RC1` / `-M1` 后缀（如 `1.1.0-RC1`），Central 允许不可变上传后
  废弃（deprecate），故 RC 也走正式坐标。
- 开发主干保持 `x.y.z-SNAPSHOT`；打 tag 即发布，tag 版本必须与 pom 一致
  （release 流水线有强校验，SNAPSHOT tag 直接拒绝）。

## 二进制兼容承诺（下游视角）

`operate-log-core` 与 `operate-log-spring-boot-starter` 是类库，
**MINOR / PATCH 升级必须对下游二进制兼容**。承诺范围：

- 所有 `public` / `protected` 类的构造器与方法签名；
- 扩展点接口（`OperateLogHandler`、`OperatorResolver`、`HttpContextResolver`、
  `ClientIpResolver`、`OperateLogSerializer`、`SensitiveDataMasker`、
  `SpelEngine`）：只加 default 方法，不加抽象方法；
- `@OperateLog` 注解属性：只加带默认值的属性，不删不改语义；
- `OperateLogProperties` 配置键：只加不改。

不承诺的部分（文档已声明为内部实现的可随 MINOR 变化）：`MergedOperateLog`
等 private 嵌套结构、Bean 名称、日志文本。

### japicmp 门禁

```bash
# 本地：与上一发布版本对比（需该版本已可从 Central/本地仓库解析）
mvn -B -pl operate-log-core,operate-log-spring-boot-starter \
    verify -Djapicmp.oldVersion=1.0.0

# 干跑不阻塞构建（只看报告）：
#   target/japicmp/japicmp.diff
# CI（release.yml）在上传前自动执行同样的门禁，基线 = 上一个 v* tag；
# 首版本无基线自动跳过。不兼容修改 => 构建失败。
```

- 门禁失败且确属有意变更时：升级为 MAJOR 发布，并在 CHANGELOG 的
  `Removed / Changed` 中逐条列出破坏点。
- 确需豁免单个签名（罕见）：japicmp `<overrides>` 配置，且必须在
  CHANGELOG 注明豁免理由。

## Maven Central 前置条件（一次性）

1. **groupId 命名空间**：`io.github.devoracode` 需在
   [Central Portal](https://central.sonatype.com) 完成 DNS 验证
   （GitHub 用户占位 `io.github.<username>` 走 GitHub 仓库验证即可）。
2. **首次发布**：新项目/新坐标须在 Portal 上创建 namespace 后由
   `central-publishing-maven-plugin` 自动发布（已配 `autoPublish`）。
3. **GPG 密钥**：生成 4096 RSA key 或 ed25519，公钥上传到
   keyserver（`keys.openpgp.org`），私钥 ASCII-armor 后存 secret。

### 所需 repository secrets

| Secret | 用途 |
| --- | --- |
| `GPG_PRIVATE_KEY` | ASCII armored 私钥（maven-gpg-plugin） |
| `GPG_PASSPHRASE` | 私钥口令 |
| `CENTRAL_USERNAME` | Central Portal User Token 的 username |
| `CENTRAL_TOKEN` | Central Portal User Token 的 password |

## 发布步骤（维护者）

```bash
# 1) 确认 CI 全绿（JDK 8/17/21 矩阵）
# 2) 定版：4 个模块 pom 的 <parent><version> 与依赖引用同步改
mvn -B org.codehaus.mojo:versions-maven-plugin:2.16.2:set \
    -DnewVersion=1.0.1 -DprocessAllModules
# 3) CHANGELOG：[Unreleased] 固化为 [1.0.1] - YYYY-MM-DD，补新 Unreleased 空节
# 4) 提交并打 tag，推 tag 触发 release.yml
git commit -am "chore(release): v1.0.1" && git tag v1.0.1
git push && git push origin v1.0.1
# 5) 主干回 SNAPSHOT
mvn -B org.codehaus.mojo:versions-maven-plugin:2.16.2:set \
    -DnewVersion=1.0.2-SNAPSHOT -DprocessAllModules
```

发布后：在 Portal 确认状态 `PUBLISHED`（同步到 repo1 索引最长约 30 分钟）；
GitHub Release 由流水线自动创建（说明取 CHANGELOG 小节）。

**制品不可撤销**：Central 不接受覆盖/删除，只能 deprecate。发现问题走
下一个 PATCH 版本。

## 版本占位说明（README）

README 依赖示例使用 `${latestVersion}` 风格的占位写法，正式文档站发布前
由维护者维护；每次发布后如 MINOR/MAJOR 升级，顺手更新 README 示例坐标
（badge 的 Maven Central 版本徽章发布后自动生效，无需手改）。

## 测试矩阵与「双栈护城河」

| 组合 | 认证途径 |
| --- | --- |
| Boot 2.7.18 × JDK 8/17/21 | CI `build` job（boot2 模块 + core/starter 单测/装配测试） |
| Boot 3.3.13 × JDK 17/21 | CI `build` job（JDK≥17 时 boot3-test profile 激活） |
| jakarta→javax→fallback 装配顺序 | starter `OperateLogAutoConfigurationTest`（FilteredClassLoader） |
| 双栈端到端日志字段 | boot2/boot3 `OperateLog*MockMvcTest` |

任何改动只要 CI 矩阵全绿即可认为双栈语义未破坏；新增装配分支必须同步
新增 `ApplicationContextRunner` 用例。
