# ---------------------------------------------------------------- JNI（FreeRDP）
# native 侧 android_freerdp.c 用 FindClass/GetStaticMethodID 按名字查找
# com.freerdp.freerdpcore.services.LibFreeRDP 上的 On* 静态回调，
# 再按接口方法名回调 Listener（实现类是 com.czk.sshai.rdp.RdpSession）。
# 类名、方法名、签名任一被混淆都会导致连接成功但收不到画面/事件。
-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }
-keep interface com.freerdp.freerdpcore.services.LibFreeRDP$Listener { *; }
-keep class com.czk.sshai.rdp.RdpSession { *; }
-keep class com.czk.sshai.rdp.RdpKeyMapper { *; }

# ---------------------------------------------------------------- Gson
# SshConfig / Prefs 通过反射读写 JSON 字段，字段名即 JSON key。
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.czk.sshai.SshConfig { *; }
-keep class com.czk.sshai.Prefs { *; }
-keep class com.czk.sshai.Tab { *; }

# ---------------------------------------------------------------- JSch
# JSch 的算法实现通过反射注册；整套保留最稳妥。它引用的 Pageant / Kerberos /
# log4j2 / junixsocket 在 Android 上都不存在，属于可选依赖，忽略即可。
-keep class com.jcraft.jsch.** { *; }
-dontwarn org.slf4j.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.bouncycastle.**

# OkHttp 会按需探测这些可选的安全提供者，运行时不存在属正常。
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
