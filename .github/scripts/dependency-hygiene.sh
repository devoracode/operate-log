#!/usr/bin/env bash
#
# 依赖污染门禁（生产级防线）
#
# 锁定本次修复的核心结论：operate-log-core 只能把「宿主不必然提供、且跨 Boot 2/3 二进制兼容」
# 的依赖（jackson-databind / aspectjweaver / commons-lang3）作为 compile 传递；
# Spring 与 SLF4J 必须 optional，由宿主 Boot BOM 定版。
#
# 为什么必须验依赖树而不是只跑测试：测试工程 import 了 Boot BOM，
# dependencyManagement 会覆盖 core 的传递版本——「测试能跑」证明不了发布产物干净。
# 因此这里同时检查：
#   1) Boot 3 工程依赖树里不得出现任何 Spring 5.x / slf4j 1.7.x；
#   2) Boot 2 工程依赖树里 spring-core 必须唯一，且不得是 core 的 5.3.32
#      （即版本必须由宿主 Boot 决定，而不是被 core 锁定）；
#   3) Starter 自身不得以 compile 作用域向消费者传递 Spring / SLF4J。
#
set -euo pipefail

usage() {
  echo "usage: $0 <boot2-tree> <boot3-tree> <starter-tree>" >&2
  exit 2
}

[ "$#" -eq 3 ] || usage

BOOT2_TREE="$1"
BOOT3_TREE="$2"
STARTER_TREE="$3"

for f in "$BOOT2_TREE" "$BOOT3_TREE" "$STARTER_TREE"; do
  [ -s "$f" ] || { echo "::error::依赖树文件缺失或为空: $f"; exit 1; }
done

fail() {
  echo "::error::$1"
  exit 1
}

# ---- 1) Boot 3：宿主提供 Spring 6，core 不得拖入 Spring 5 ----
SPRING5_IN_BOOT3="$(grep -E 'org\.springframework:[a-z-]+:jar:5\.' "$BOOT3_TREE" || true)"
if [ -n "$SPRING5_IN_BOOT3" ]; then
  echo "$SPRING5_IN_BOOT3"
  fail "Boot 3 依赖树出现 Spring 5.x：core 的框架依赖未设 optional，把 Spring 5 传递给了宿主"
fi
grep -qE 'org\.springframework:spring-webmvc:jar:6\.' "$BOOT3_TREE" \
  || fail "Boot 3 依赖树未解析到 Spring 6（检查 BOM 是否生效）"
grep -qE 'org\.springframework:[a-z-]+:jar:6\.' "$BOOT3_TREE" \
  || fail "Boot 3 依赖树未解析到任何 Spring 6 构件"

# slf4j 1.7 会被 Boot 3 的 logback 1.5 忽略（无 StaticLoggerBinder）-> 日志静默变 NOP
SLF4J17_IN_BOOT3="$(grep -E 'org\.slf4j:slf4j-api:jar:1\.' "$BOOT3_TREE" || true)"
if [ -n "$SLF4J17_IN_BOOT3" ]; then
  echo "$SLF4J17_IN_BOOT3"
  fail "Boot 3 依赖树出现 slf4j-api 1.7.x：core 传递未隔离，将导致宿主日志静默降级"
fi
grep -qE 'org\.slf4j:slf4j-api:jar:2\.' "$BOOT3_TREE" \
  || fail "Boot 3 依赖树未解析到 slf4j-api 2.x"

# Jackson 由 Boot 3 BOM 定版（core 的传递下界仅作兜底）
grep -qE 'com\.fasterxml\.jackson\.core:jackson-databind:jar:2\.1[6-9]\.' "$BOOT3_TREE" \
  || fail "Boot 3 的 jackson-databind 未按 BOM 定版（应 >= 2.16）"

# ---- 2) Boot 2：spring-core 版本必须唯一，且来自宿主而非 core ----
SPRING_CORE_BOOT2="$(grep -oE 'org\.springframework:spring-core:jar:[0-9][0-9A-Za-z._-]*' "$BOOT2_TREE" \
  | sed 's/.*jar://' | sort -u || true)"
[ -n "$SPRING_CORE_BOOT2" ] || fail "Boot 2 依赖树未解析到 spring-core"
if [ "$(printf '%s\n' "$SPRING_CORE_BOOT2" | wc -l)" -ne 1 ]; then
  echo "发现多个 spring-core 版本: $(printf '%s ' $SPRING_CORE_BOOT2)"
  fail "Boot 2 spring-core 版本不唯一：宿主与 core 传递版本发生混用"
fi
if [ "$SPRING_CORE_BOOT2" = "5.3.32" ]; then
  fail "Boot 2 的 spring-core 被 core 锁定为 5.3.32（应随宿主 Boot 版本，如 2.7.18 -> 5.3.31）"
fi

# ---- 2b) 两棵树的 Jackson 三件套必须同版本（混版 = NoSuchMethodError 高发区）----
assert_jackson_line_consistent() {
  local tree="$1"
  local databind core annotations
  databind="$(grep -oE 'com\.fasterxml\.jackson\.core:jackson-databind:jar:[0-9][0-9A-Za-z._-]*' "$tree" \
    | sed 's/.*jar://' | sort -u | head -1)"
  core="$(grep -oE 'com\.fasterxml\.jackson\.core:jackson-core:jar:[0-9][0-9A-Za-z._-]*' "$tree" \
    | sed 's/.*jar://' | sort -u | head -1)"
  annotations="$(grep -oE 'com\.fasterxml\.jackson\.core:jackson-annotations:jar:[0-9][0-9A-Za-z._-]*' "$tree" \
    | sed 's/.*jar://' | sort -u | head -1)"
  [ -n "$databind" ] || fail "$(basename "$tree") 未解析到 jackson-databind"
  if [ "$databind" != "$core" ] || [ "$databind" != "$annotations" ]; then
    echo "databind=$databind core=$core annotations=$annotations"
    fail "$(basename "$tree") 的 Jackson 版本不一致（库侧传递下界压过了宿主版本 = 混版）"
  fi
  # 同一构件在树中只允许出现一个版本
  for artifact in jackson-databind jackson-core jackson-annotations; do
    local count
    count="$(grep -oE "com\.fasterxml\.jackson\.core:$artifact:jar:[^ ]+" "$tree" | sort -u | wc -l)"
    [ "$count" -le 1 ] || fail "$(basename "$tree") 的 $artifact 出现多版本"
  done
}

assert_jackson_line_consistent "$BOOT2_TREE"
assert_jackson_line_consistent "$BOOT3_TREE"

# ---- 3) Starter：不得以 compile 作用域传递 Spring / SLF4J ----
LEAK_SPRING="$(grep -E 'org\.springframework:[a-z-]+:jar:[^:]+:compile' "$STARTER_TREE" || true)"
if [ -n "$LEAK_SPRING" ]; then
  echo "$LEAK_SPRING"
  fail "Starter 以 compile 作用域向消费者传递 Spring（应 optional / provided）"
fi
LEAK_SLF4J="$(grep -E 'org\.slf4j:[a-z0-9-]+:jar:[^:]+:compile' "$STARTER_TREE" || true)"
if [ -n "$LEAK_SLF4J" ]; then
  echo "$LEAK_SLF4J"
  fail "Starter 以 compile 作用域向消费者传递 SLF4J（应 optional）"
fi
# core 保留传递的三项硬依赖必须在（防止「为干净而全砍」把宿主需要的依赖砍没）
for required in jackson-databind aspectjweaver commons-lang3; do
  grep -qE "$required:jar:[^:]+:compile" "$STARTER_TREE" \
    || fail "Starter 依赖树缺少 $required（core 的硬运行时依赖，宿主不必然提供）"
done

echo "依赖污染门禁通过："
echo "  boot2 spring-core = $SPRING_CORE_BOOT2（宿主定版，非 core 锁定）"
echo "  boot3 Spring = 6.x / slf4j = 2.x / jackson 按 BOM"
echo "  starter 未 compile 传递 Spring 与 SLF4J"
