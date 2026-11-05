#!/bin/bash
# 编译并跑 RDP 联调。用法: ./run_lab.sh [host] [port] [user] [pass] [seconds]
set -uo pipefail

AB="${AB_BASE:-$HOME/my-server/scan-app-mobile/androidbuild}"
export JAVA_HOME=$AB/jdk/jdk17
export GRADLE_USER_HOME=$AB/gradle-home
export TMPDIR=$AB/dl
export ANDROID_USER_HOME=$AB/dl/home
export JAVA_OPTS="-Duser.home=$AB/dl/home -Djava.io.tmpdir=$AB/dl"
export PATH=$JAVA_HOME/bin:$AB/gradle/gradle-8.9/bin:$PATH
export TERM=dumb

cd "$(dirname "$0")"
# 编译失败必须立刻停，否则会拿旧产物跑，日志看起来"正常"但其实没验证到新代码
BUILD_LOG=/tmp/rdp-lab-build.log
gradle --console=plain --no-daemon installDist > "$BUILD_LOG" 2>&1
if ! grep -q "BUILD SUCCESSFUL" "$BUILD_LOG"; then
  echo "==== 编译失败 ===="
  grep -E "^e: |^w: .*error|BUILD FAILED|Caused by" "$BUILD_LOG" | head -40
  exit 1
fi

HOST=${1:-127.0.0.1}
PORT=${2:-3390}
USER=${3:-rdptest}
SECS=${5:-8}

# 密码必须显式传入：参数 4 或环境变量 RDP_LAB_PASS。
# 这里刻意不设默认值——写死的默认密码一旦随仓库公开，等于把测试账号送人。
PASS="${4:-${RDP_LAB_PASS:-}}"
if [ -z "$PASS" ]; then
  echo "!! 缺少密码：./run_lab.sh <host> <port> <user> <pass> [seconds] 或设置 RDP_LAB_PASS"
  exit 1
fi

LOG="$(cd "$(dirname "$0")" && pwd)/fixtures/lab.log"
mkdir -p "$(dirname "$LOG")"
rm -f "$LOG"
timeout 40 ./build/install/rdp-lab/bin/rdp-lab "$HOST" "$PORT" "$USER" "$PASS" "$SECS" "${6:-AUTO}" > "$LOG" 2>&1
echo "---- exit=$? ----"
cat "$LOG"
