# SSHBuddy v1.8.1 连接可靠性专项审查

> 审查方法：`code-review-bug-diagnosis` skill（静态审查）+ `android-apk-build` skill（产物级验证）+ 真机协议实测（宿主 xrdp 0.9.24 / FreeRDP 3.32.1）
> 审查范围：`app/src/main/java/com/czk/sshai/**`、`app/src/main/java/com/freerdp/**`、`proguard-rules.pro`、`app/build.gradle.kts`、`settings.gradle.kts`、`dist/SSHBuddy-*.apk`
> 目标：确认「RDP 远程桌面」不存在连不上的 bug

## 总体结论

**可上线**。连接链路本身是通的（有实测），但审查中抓到 **1 个潜在致命雷** 与 **1 个容易踩的输入坑**，两者都已在 v1.8.1 修掉。

## 实测证据（不是推断）

用与 App `buildArgs()` **完全一致**的参数（`/gdi:sw /bpp:32 /kbd:unicode /network:auto /dynamic-resolution /cert:ignore /size:800x600`，不带 `/sec`、不带 `/gfx`）连本机 xrdp：

```
xrdp-sesman: ++ created session (access granted): username rdptest2
xrdp-sesman: starting Xorg session...
xrdp-sesman: Starting session: session_pid 20315, display :10.0, width 800, height 600, bpp 24
xrdp-sesman: Session started successfully for user rdptest2 on display 10
client:      gdi_init_ex / dvcman_load_addin: ainput, rdpgfx, disp, rdpsnd
```

→ 认证通过、Xorg 会话建立、GFX/Display/Sound/Input 四条动态通道全部协商成功，持续 15 秒无错误。

## 问题清单

### [P1] `/gfx:AVC444` 是解析期炸弹 —— 已在 1.8.1 修
- **位置**：`rdp/RdpSession.kt` `buildArgs()`
- **现象**：宿主机同版本 FreeRDP 3.32.1 实测
  `/gfx:AVC444` → `Command line parsing failed at 'gfx' value 'AVC444' [-1000]`
  而 `/gfx`、`/gfx:RFX`、`/gfx:progressive`、`/gfx:mask:1` 均能通过。
- **根因**：`AVC444`/`AVC420` 这两个 token 只在**构建时编入 H.264** 的 FreeRDP 里注册。当前 APK 的 `libfreerdp-client3.so` 虽含 AVC444 字样、但**没有任何 avcodec/openh264 符号**，`hasH264()` 大概率为 false，所以现在走不到这一行 —— 属于"埋着的雷"。
- **后果**：一旦换成带 H.264 的 native 构建，`setArguments` 整条失败 → App 弹「不支持的参数：/gfx:AVC444」→ **永远连不上**。而 xrdp 根本不支持 AVC444，这行对 Linux 目标收益为零。
- **修复**：改为「先探测再添加」——临时实例跑一次 `/gfx:AVC444`，通过才加，结果缓存。

### [P2] 主机名带端口会双重拼端口 —— 已在 1.8.1 修
- **位置**：`rdp/RdpSession.kt` `buildArgs()`；`ui/HostEditorScreen.kt:117`
- **现象**：用户把主机填成 `192.168.1.10:3389`（照着连接串抄很常见）→ 参数变成 `/v:192.168.1.10:3389` + `/port:3389` → FreeRDP 解析失败，只报一句「参数解析失败」。
- **根因**：`host` 只做 `trim()`，`ready` 只校验非空；端口由 `effectiveRdpPort` 单独拼。
- **修复**：`buildArgs()` 里用 `^\[?([^\[\]]+?)\]?:(\d{1,5})$` 拆出端口，显式填写的端口优先。

### [P2] 选 NLA 必然连不上，但 UI 没有提示
- **位置**：`ui/HostEditorScreen.kt` 安全层三个芯片（自动/NLA/TLS/RDP）
- **现象**：xrdp 不支持 CredSSP/NLA，用户手选 NLA → 必失败。
- **修复建议**：文案加一句"xrdp 等 Linux 端不要选 NLA"（代码里已有类似提示，确认覆盖到 RDP 目标）。

### [P2] 同账号已有本地图形会话时，xrdp 新建会话存在风险（需真机确认）
- **实测**：新账号一次连接成功；同账号**第二次**连接 12 秒无报错但 sesman 未再打印 `Session started`（疑似 reconnect 到已有会话，属正常）。
- **风险点**：`czk` 已占用 `:0` 的 GDM 本地会话，而那不是 xrdp 会话、无法被接管 → xrdp 会尝试新建。GNOME 同账号第二个会话在本机历史上失败过（`services.json` 有记录）。
- **缓解**：手机端看桌面优先用 RustDesk（抓的就是 `:0`）；RDP 更适合连 Windows 目标。

### [P3] `rdp-core` 是死模块
- `settings.gradle.kts` 只 `include(":app")`，`app` 也未依赖 `:rdp-core` → 该目录 9 个纯 Kotlin RDP 实现文件**不进包**、不参与编译。留着容易误导后来者（以为有两套 RDP 可选）。建议移出主仓或加 `README` 标注。

### [P3] `-keep` 建议放宽一档
- 现有：`-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }` + `Listener` + `RdpSession` + `RdpKeyMapper`
- 建议：`-keep class com.freerdp.freerdpcore.services.** { *; }`，避免将来新增内部类/回调被裁。

### [P3] `close()` 的 10 秒兜底会泄漏 native 实例
- 已连接实例在 native 线程 10s 未退出时跳过 `freeInstance`（有意为之，防 use-after-free）。反复重连失败会累积泄漏，可接受，但建议加计数日志便于观测。

## VNC 移除回归检查（本次改动专项）

| 检查项 | 结论 |
|---|---|
| `isVnc` / `vncPort` / `effectiveVncPort` 引用 | 0 处（已彻底移除） |
| `vnc/` 目录 | 已删（1080 行） |
| 全项目 `vnc` 残留 | 仅 1 处：`SshConfig.loadHosts()` 的 `protocol=="vnc"` 迁移过滤（**故意保留**，用于清掉旧数据）与若干说明性注释 |
| APK dex 内符号 | `VncSession` / `sshai/vnc` / `RFB` / `VncKeymap` 命中 **0** |
| `RemoteConnections.create()` | 只剩 RDP 分支；调用点由 `h.isRdp` 守卫，不会误把 SSH 主机会话送进来 |

## JNI 完整性检查（产物级）

- `lib/arm64-v8a/libfreerdp-android.so` 导出 **20 个** `Java_com_freerdp_freerdpcore_services_LibFreeRDP_*` 符号，与 Java 侧 20 个 `native` 声明（`_` → `_1` 转义）**逐一对应**
- 该 .so 内含 `com/freerdp/freerdpcore/services/LibFreeRDP` 类名常量；回调名 `OnConnectionSuccess` / `OnConnectionFailure` / `OnDisconnecting` / `OnDisconnected` / `OnSettingsChanged` / `OnGraphicsResize` / `OnGraphicsUpdate` / `OnVerifyCertificateEx` / `OnVerifyChangedCertificateEx` / `OnPointerSet{,Null,Default}` / `OnRemoteClipboardChanged` 在 Java 侧全部有同名 `private static` 方法
- 9 个 `.so` 全部打包（arm64-v8a）：`libfreerdp-android` / `libfreerdp-client3` / `libfreerdp3` / `libwinpr3` / `libssl` / `libcrypto` / `libcjson` / `liburiparser` / `libandroidx.graphics.path`
- 结论：**类名、方法名、签名三者一致，混淆也有 keep 覆盖 → 不会出现"连上了但没画面/收不到事件"**

## 加分项

- `RdpSession.buildArgs()` 对 `/sec` 的处理有实测依据（FreeRDP 3.x 不接受 `/sec:auto`，用"不传"表达自动协商），不是猜的
- `close()` 用 per-instance `CountDownLatch` + `closing` 标志区分新旧实例回调，避免了重连时的画面被旧实例回收（这个坑一般项目都会踩）
- 错误信息做了脱敏（`/p:***`），且解析失败会逐条探测定位到具体参数
- 键盘走 `/kbd:unicode` + 代用键码回退，且明确写了"发送失败会被 native 当致命错误"的原因

## 需确认的疑点

1. **`hasH264()` 在 Android 构建里的实际返回值**：只从符号层面判断为 false（无 avcodec/openh264），未在真机跑。v1.8.1 的探测逻辑对两种结果都安全。
2. **GNOME 同账号并发会话**：未用 `czk` 实测（不知密码），仅验证了新账号路径。
3. UI 渲染、触摸手势、软键盘行为：**未验证**（无真机/模拟器），本次审查只覆盖连接链路。
