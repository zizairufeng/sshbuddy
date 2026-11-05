#!/usr/bin/env python3
"""校验 LibFreeRDP.java 的 native 声明 与 libfreerdp-android.so 的导出符号一致性。

JNI 的符号名规则：Java_<包名>_<类名>_<方法名>，其中 '_' 转义成 '_1'。
两边只要有一处对不上，运行期就是 UnsatisfiedLinkError / NoSuchMethodError。
"""
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# NDK 路径可用 ANDROID_NDK_ROOT 覆盖，默认按本机工具链布局推断
NDK = os.environ.get("ANDROID_NDK_ROOT") or os.path.expanduser(
    "~/my-server/scan-app-mobile/androidbuild/ndk/android-ndk-r27c")
NM = os.path.join(NDK, "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm")
SO = os.path.join(REPO, "app/src/main/jniLibs/arm64-v8a/libfreerdp-android.so")
JAVA = os.path.join(REPO, "app/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java")

PREFIX = "Java_com_freerdp_freerdpcore_services_LibFreeRDP_"


def unmangle(sym: str) -> str:
    for a, b in (("_1", "_"), ("_2", ";"), ("_3", "[")):
        sym = sym.replace(a, b)
    return sym


so_methods = set()
out = subprocess.run([NM, "-D", "--defined-only", SO], capture_output=True, text=True).stdout
for line in out.splitlines():
    parts = line.split()
    if len(parts) >= 3 and parts[-1].startswith(PREFIX):
        so_methods.add(unmangle(parts[-1][len(PREFIX):]))

src = open(JAVA, encoding="utf-8").read()
java_native = set(re.findall(r"native\s+[\w\.\[\]]+\s+(\w+)\s*\(", src))

# 回调（native 侧用 GetStaticMethodID 找，不用符号）
callbacks = set(re.findall(r"private\s+static\s+[\w\.\[\]]+\s+(On\w+)\s*\(", src))

print(f"so 导出 JNI 方法: {len(so_methods)}")
print(f"Java native 声明: {len(java_native)}")
print(f"Java 静态回调:   {len(callbacks)}")

missing_in_java = sorted(so_methods - java_native)
missing_in_so = sorted(java_native - so_methods)

ok = True
if missing_in_java:
    ok = False
    print("\n[FAIL] so 有、Java 没有（运行期 UnsatisfiedLinkError）：")
    for m in missing_in_java:
        print("   ", m)
if missing_in_so:
    ok = False
    print("\n[FAIL] Java 有、so 没有（运行期 UnsatisfiedLinkError）：")
    for m in missing_in_so:
        print("   ", m)

# C 侧硬编码的回调名/签名，必须都实现
required = {
    "OnPreConnect": "(J)V",
    "OnConnectionSuccess": "(J)V",
    "OnConnectionFailure": "(J)V",
    "OnDisconnecting": "(J)V",
    "OnDisconnected": "(J)V",
    "OnSettingsChanged": "(JIII)V",
    "OnGraphicsResize": "(JIII)V",
    "OnGraphicsUpdate": "(JIIII)V",
    "OnPointerSet": "(J[IIIII)V",
    "OnPointerSetNull": "(J)V",
    "OnPointerSetDefault": "(J)V",
    "OnRemoteClipboardChanged": "(JLjava/lang/String;)V",
    "OnRemoteClipboardImageChanged": "(J[B)V",
    "OnRailWindowUpdate": "(JJII[I)V",
    "OnVerifyCertificateEx": "存在",
    "OnVerifyChangedCertificateEx": "存在",
    "OnAuthenticate": "存在",
}
miss_cb = [k for k in required if k not in callbacks]
if miss_cb:
    ok = False
    print("\n[FAIL] native 需要但 Java 未实现/未命名的回调：")
    for k in miss_cb:
        print("   ", k, required[k])

print("\n结果:", "OK —— 两边完全对齐" if ok else "有问题，见上")
sys.exit(0 if ok else 1)
