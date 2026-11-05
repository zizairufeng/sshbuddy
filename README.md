# SSHBuddy

SSH 终端 + RDP 远程桌面的 Android 应用，v1.8.1（versionCode 28）。

一个自用的运维客户端：主机列表里同时放 SSH 和 RDP 两种目标，点进去就是一个能用的终端或远程桌面，不依赖云端跳板、不注册账号、不做遥测。

## 功能

- SSH 终端：完整 VT100/xterm 终端模拟，支持私钥（RSA/ECDSA/Ed25519）与密码两种认证
- RDP 远程桌面：基于 FreeRDP 3.32.2 原生库，支持 NLA / TLS 安全层，自带触控板与修饰键栏
- 主机管理：SSH / RDP 混列，分组、快速连接、自动重连
- known_hosts（TOFU）：首次连接记录服务端公钥指纹，之后指纹变化会告警
- AI 命令助手：可选。填入任意 OpenAI 兼容端点 + Key，把自然语言变成命令再手动确认执行
- 本地存储，本机加密：见下方「安全设计」

## 安装

从 [Releases](https://github.com/zizairufeng/sshbuddy/releases/latest) 下载 `SSHBuddy-<版本>-release.apk`。

- 要求 Android 7.0（API 24）及以上，仅 **arm64-v8a** 架构
- 发布包经过 R8 混淆 + 资源压缩 + zipalign，签名 v1 + v2 + v3
- 只有正式发布密钥签名的包才能覆盖升级；换签必须先卸载

## 构建

### 依赖

- JDK 17、Android SDK（compileSdk 35）、Gradle 8.9
- FreeRDP 原生库：`app/src/main/jniLibs/arm64-v8a/` 下的 `.so` 已随仓库提供（约 11 MB），
  交叉编译流程见 `THIRD_PARTY_NOTICES.md` 引用的上游文档；FreeRDP 源码树本身体积过大，不入库

### 出包

```bash
# 1) 准备签名密钥（首次）
keytool -genkeypair -v -keystore sshai.jks -alias sshai \
  -keyalg RSA -keysize 2048 -validity 10000

# 2) 提供口令，三选一
export SSHBUDDY_KS_PASS='你的口令'      # 推荐
# 或：echo '你的口令' > .keystore-pass && chmod 600 .keystore-pass
# 或：运行时交互输入

# 3) 构建
./build_release.sh
```

脚本会自动从 `app/build.gradle.kts` 读取 `versionName` 决定产物名，然后
`assembleRelease → zipalign → apksigner(v1+v2+v3) → verify`，产物落在 `dist/`。

签名口令不写进任何会被提交的文件。`.jks` 与 `.keystore-pass` 都在 `.gitignore` 里。
密钥一旦泄露，别人能签出同签名 APK 覆盖安装你设备上的应用，且无法靠换签补救。

### RDP 联调台

```bash
cd tools/rdp-lab
RDP_LAB_PASS='测试机口令' ./run_lab.sh <host> <port> <user> [seconds]
```

`tools/rdp-lab` 是一个跑在桌面 JVM 上的最小 RDP 客户端，用来在真机之外快速验证协议层改动。
不提供默认口令，必须显式传入。

## 安全设计

| 项 | 做法 |
|---|---|
| 主机密码 / 私钥口令 / RDP 密码 / AI Key | `SecureStore.kt`：AndroidKeyStore 生成 AES-GCM 密钥，密文存 SharedPreferences；密钥不出 TEE/StrongBox |
| known_hosts | 只存**公钥指纹**，不存密钥，普通 SharedPreferences 即可 |
| 私钥 | 由用户粘贴导入，加密后落盘，不导出、不联网 |
| 日志 | 不打印任何口令、私钥、令牌 |
| 遥测 | 无。App 不向任何第三方服务上报数据 |
| 网络 | SSH / RDP 均为协议自带加密；`usesCleartextTraffic` 仅用于兼容用户自填的**内网** OpenAI 兼容端点（如局域网 LLM），不使用该功能时不会发起任何明文请求 |

安全边界说明：AI 助手开启后，你输入的提示词会发往你自己填的那个端点，本项目不代理、不中转。
如果端点填的是公网服务，请自行评估该服务的数据处理政策。

## 第三方组件

见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。摘要：

- Termux `terminal-emulator` / `terminal-view`：Apache-2.0（上游 `LICENSE.md` 对这两个模块有明确 exception，源自 jackpal/Android-Terminal-Emulator）
- FreeRDP 3.32.2：Apache-2.0
- JSch（`com.github.mwiede:jsch`）：BSD 三条款
- OkHttp / Gson / AndroidX / Jetpack Compose：Apache-2.0

## 许可

本项目以 [Apache License 2.0](LICENSE) 发布。
`app/src/main/java/com/termux/` 下的文件保留上游版权声明与许可，详见 `THIRD_PARTY_NOTICES.md`。
