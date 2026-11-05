#!/bin/bash
# 出签名 release APK：assembleRelease -> zipalign -> apksigner(v1+v2+v3) -> dist/
#
# 签名口令**不再写在本脚本里**。按以下优先级获取：
#   1) 环境变量 SSHBUDDY_KS_PASS
#   2) 本目录下 .keystore-pass 文件（应 chmod 600，且已在 .gitignore 中）
#   3) 交互式输入
#
# 密钥文件路径默认 <repo>/sshai.jks，可用 SSHBUDDY_KS 覆盖。
# 工具链位置默认 ~/my-server/scan-app-mobile/androidbuild，可用 AB_BASE 覆盖。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AB="${AB_BASE:-$HOME/my-server/scan-app-mobile/androidbuild}"

export JAVA_HOME=$AB/jdk/jdk17 ANDROID_HOME=$AB/sdk ANDROID_SDK_ROOT=$AB/sdk
export GRADLE_USER_HOME=$AB/gradle-home TMPDIR=$AB/dl ANDROID_USER_HOME=$AB/dl/home
export JAVA_OPTS="-Duser.home=$AB/dl/home -Djava.io.tmpdir=$AB/dl"
export PATH=$JAVA_HOME/bin:$AB/gradle/gradle-8.9/bin:$PATH TERM=dumb
cd "$ROOT"

BT="$ANDROID_HOME/build-tools/35.0.0"
KS="${SSHBUDDY_KS:-$ROOT/sshai.jks}"
ALIAS="${SSHBUDDY_KS_ALIAS:-sshai}"
OUT="$ROOT/dist"

# ---- 版本号从 build.gradle.kts 读，避免脚本里的文件名忘改 ----
VNAME=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)
VCODE=$(sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)
[ -n "$VNAME" ] || { echo "!! 无法从 app/build.gradle.kts 读出 versionName"; exit 1; }
NAME="SSHBuddy-${VNAME}-release.apk"
mkdir -p "$OUT"

# ---- 口令解析 ----
PASS="${SSHBUDDY_KS_PASS:-}"
if [ -z "$PASS" ] && [ -f "$ROOT/.keystore-pass" ]; then
  PASS="$(tr -d '\r\n' < "$ROOT/.keystore-pass")"
fi
if [ -z "$PASS" ]; then
  read -r -s -p "签名密钥口令: " PASS; echo
fi
[ -n "$PASS" ] || { echo "!! 未提供签名口令，退出"; exit 1; }
[ -f "$KS" ] || { echo "!! 找不到密钥文件: $KS（用 SSHBUDDY_KS 指定）"; exit 1; }
# apksigner 用 env: 前缀读口令，避免口令出现在 ps 命令行里
export SSHBUDDY_KS_PASS="$PASS"

echo "== 目标: $NAME (versionCode $VCODE)"

echo "== [1/4] assembleRelease"
gradle --console=plain --no-daemon :app:assembleRelease \
  -x lintVitalRelease -x lintVitalAnalyzeRelease

SRC="app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$SRC" ] || SRC="app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "!! 未找到 release 产物"; exit 1; }

echo "== [2/4] zipalign"
"$BT/zipalign" -p -f 4 "$SRC" "$OUT/aligned.apk"

echo "== [3/4] apksigner sign"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "env:SSHBUDDY_KS_PASS" \
  --ks-key-alias "$ALIAS" --key-pass "env:SSHBUDDY_KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/$NAME" "$OUT/aligned.apk"

echo "== [4/4] verify"
"$BT/apksigner" verify --verbose --print-certs "$OUT/$NAME"
"$BT/zipalign" -c -v 4 "$OUT/$NAME" | tail -2
ls -la "$OUT/$NAME"
sha256sum "$OUT/$NAME"
