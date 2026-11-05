/*
 * FreeRDP Android JNI 封装（精简版）
 *
 * 类名、native 方法签名、静态回调方法名与签名必须与 FreeRDP 的
 *   client/Android/Studio/freeRDPCore/src/main/cpp/android_freerdp.c
 * 完全一致，否则 native 侧 FindClass/GetStaticMethodID 会失败。
 *
 * 与原版 aFreeRDP 的 LibFreeRDP 相比：去掉了 SessionState / GlobalApp /
 * BookmarkBase 等 UI 域模型依赖，回调改为按 instance 直接分发，
 * 参数通过标准 FreeRDP 命令行风格字符串数组传入。
 */

package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LibFreeRDP {
    private static final String TAG = "LibFreeRDP";

    /** 指针事件标志（与 FreeRDP PTR_FLAGS_* 对应） */
    public static final int PTR_FLAGS_DOWN = 0x8000;
    public static final int PTR_FLAGS_BUTTON1 = 0x1000;
    public static final int PTR_FLAGS_BUTTON2 = 0x2000;
    public static final int PTR_FLAGS_BUTTON3 = 0x4000;
    public static final int PTR_FLAGS_WHEEL = 0x0200;
    public static final int PTR_FLAGS_WHEEL_NEGATIVE = 0x0100;
    public static final int PTR_FLAGS_MOVE = 0x0800;

    /** 证书校验标志，回调返回值用 */
    public static final long VERIFY_CERT_FLAG_NONE = 0x00;
    public static final long VERIFY_CERT_FLAG_LEGACY = 0x02;
    public static final long VERIFY_CERT_FLAG_REDIRECT = 0x10;
    public static final long VERIFY_CERT_FLAG_GATEWAY = 0x20;
    public static final long VERIFY_CERT_FLAG_CHANGED = 0x40;
    public static final long VERIFY_CERT_FLAG_MISMATCH = 0x80;
    public static final long VERIFY_CERT_FLAG_MATCH_LEGACY_SHA1 = 0x100;
    public static final long VERIFY_CERT_FLAG_FP_IS_PEM = 0x200;

    /** 证书校验回调返回值：0 = 不信任（连接失败），1 = 临时信任，2 = 永久信任 */
    public static final int VERIFY_CERT_ACCEPT = 1;

    private static final Map<Long, Listener> mListeners = new ConcurrentHashMap<>();
    private static volatile boolean mHasH264 = false;
    private static volatile boolean mHasCamera = false;

    static {
        System.loadLibrary("freerdp-android");
        // 触发依赖库的 JNI_OnLoad（版本号取自 native 侧，如 3.32）
        try {
            String version = freerdp_get_jni_version();
            String major = version.split("\\.")[0];
            System.loadLibrary("freerdp-client" + major);
            System.loadLibrary("freerdp" + major);
            System.loadLibrary("winpr" + major);
            mHasH264 = freerdp_has_h264();
            mHasCamera = freerdp_has_camera_redirection();
            Log.i(TAG, "native " + version + " h264=" + mHasH264 + " camera=" + mHasCamera);
        } catch (Throwable t) {
            Log.e(TAG, "加载依赖库失败", t);
            throw t;
        }
    }

    private LibFreeRDP() {}

    // ---------------------------------------------------------------- native

    private static native boolean freerdp_has_h264();
    private static native boolean freerdp_has_camera_redirection();
    private static native String freerdp_get_jni_version();
    private static native String freerdp_get_version();
    private static native String freerdp_get_build_revision();
    private static native String freerdp_get_build_config();
    private static native long freerdp_new(Context context);
    private static native void freerdp_free(long inst);
    private static native boolean freerdp_parse_arguments(long inst, String[] args);
    private static native boolean freerdp_connect(long inst);
    private static native boolean freerdp_disconnect(long inst);
    private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y,
                                                          int width, int height);
    private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);
    private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);
    private static native boolean freerdp_send_unicodekey_event(long inst, int keycode,
                                                               boolean down);
    private static native boolean freerdp_is_unicode_input_supported(long inst);
    private static native boolean freerdp_send_clipboard_data(long inst, String data);
    private static native boolean freerdp_send_clipboard_image_data(long inst, byte[] data,
                                                                   String mimeType);
    private static native boolean freerdp_send_monitor_layout(long inst, int width, int height);
    private static native String freerdp_get_last_error_string(long inst);

    // ------------------------------------------------------------ 公开 API

    public static String version() {
        return freerdp_get_version();
    }

    public static String buildConfig() {
        return freerdp_get_build_config();
    }

    public static boolean hasH264() {
        return mHasH264;
    }

    public static boolean hasCamera() {
        return mHasCamera;
    }

    public static void setListener(long inst, Listener l) {
        if (l == null) mListeners.remove(inst);
        else mListeners.put(inst, l);
    }

    public static long newInstance(Context ctx) {
        return freerdp_new(ctx);
    }

    public static void freeInstance(long inst) {
        mListeners.remove(inst);
        freerdp_free(inst);
    }

    /** 传入 FreeRDP 命令行风格参数，如 /v:1.2.3.4 /port:3389 /u:user /p:pass /size:1920x1080 */
    public static boolean setArguments(long inst, String[] args) {
        return freerdp_parse_arguments(inst, args);
    }

    public static boolean connect(long inst) {
        return freerdp_connect(inst);
    }

    public static boolean disconnect(long inst) {
        return freerdp_disconnect(inst);
    }

    public static boolean updateGraphics(long inst, Bitmap bmp, int x, int y, int w, int h) {
        return freerdp_update_graphics(inst, bmp, x, y, w, h);
    }

    /** x/y 为远端桌面坐标，flags 见 PTR_FLAGS_* */
    public static boolean sendCursor(long inst, int x, int y, int flags) {
        return freerdp_send_cursor_event(inst, x, y, flags);
    }

    public static boolean sendKey(long inst, int keycode, boolean down) {
        return freerdp_send_key_event(inst, keycode, down);
    }

    public static boolean sendUnicodeKey(long inst, int codepoint, boolean down) {
        return freerdp_send_unicodekey_event(inst, codepoint, down);
    }

    public static boolean isUnicodeInputSupported(long inst) {
        return freerdp_is_unicode_input_supported(inst);
    }

    public static boolean sendClipboard(long inst, String text) {
        return freerdp_send_clipboard_data(inst, text);
    }

    public static boolean sendMonitorLayout(long inst, int w, int h) {
        return freerdp_send_monitor_layout(inst, w, h);
    }

    public static String lastError(long inst) {
        return freerdp_get_last_error_string(inst);
    }

    // ------------------------------------------------- 回调（由 native 调用）

    private static Listener listener(long inst) {
        return mListeners.get(inst);
    }

    private static void OnPreConnect(long inst) {
        Listener l = listener(inst);
        if (l != null) l.onPreConnect(inst);
    }

    private static void OnConnectionSuccess(long inst) {
        Listener l = listener(inst);
        if (l != null) l.onConnected(inst);
    }

    private static void OnConnectionFailure(long inst) {
        Listener l = listener(inst);
        if (l != null) l.onConnectionFailure(inst);
    }

    private static void OnDisconnecting(long inst) {
        Listener l = listener(inst);
        if (l != null) l.onDisconnecting(inst);
    }

    private static void OnDisconnected(long inst) {
        Listener l = listener(inst);
        if (l != null) l.onDisconnected(inst);
    }

    private static void OnSettingsChanged(long inst, int width, int height, int bpp) {
        Listener l = listener(inst);
        if (l != null) l.onSettingsChanged(inst, width, height, bpp);
    }

    private static void OnGraphicsResize(long inst, int width, int height, int bpp) {
        Listener l = listener(inst);
        if (l != null) l.onGraphicsResize(inst, width, height, bpp);
    }

    private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height) {
        Listener l = listener(inst);
        if (l != null) l.onGraphicsUpdate(inst, x, y, width, height);
    }

    private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
                                          StringBuilder password) {
        Listener l = listener(inst);
        return l != null && l.onAuthenticate(inst, username, domain, password);
    }

    private static boolean OnGatewayAuthenticate(long inst, StringBuilder username,
                                                 StringBuilder domain, StringBuilder password) {
        Listener l = listener(inst);
        return l != null && l.onGatewayAuthenticate(inst, username, domain, password);
    }

    private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
                                             String subject, String issuer, String fingerprint,
                                             long flags) {
        Listener l = listener(inst);
        return l == null ? 0 : l.onVerifyCertificate(inst, host, port, commonName, subject, issuer,
                                                        fingerprint, flags);
    }

    private static int OnVerifyChangedCertificateEx(long inst, String host, long port,
                                                    String commonName, String subject,
                                                    String issuer, String fingerprint,
                                                    String oldSubject, String oldIssuer,
                                                    String oldFingerprint, long flags) {
        Listener l = listener(inst);
        return l == null ? 0
                         : l.onVerifyChangedCertificate(inst, host, port, commonName, subject,
                                                        issuer, fingerprint, oldSubject, oldIssuer,
                                                        oldFingerprint, flags);
    }

    private static boolean OnExperimentalFeature(long inst, int feature) {
        Listener l = listener(inst);
        return l == null || l.onExperimentalFeature(inst, feature);
    }

    private static void OnRemoteClipboardChanged(long inst, String data) {
        Listener l = listener(inst);
        if (l != null) l.onRemoteClipboardChanged(inst, data);
    }

    private static void OnRemoteClipboardImageChanged(long inst, byte[] data) {
        Listener l = listener(inst);
        if (l != null) l.onRemoteClipboardImageChanged(inst, data);
    }

    private static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX,
                                     int hotY) {
        Listener l = listener(inst);
        if (l != null) l.onPointerSet(inst, pixels, width, height, hotX, hotY);
    }

    private static void OnPointerSetNull(long inst) {
        Listener l = listener(inst);
        if (l != null) l.onPointerSetNull(inst);
    }

    private static void OnPointerSetDefault(long inst) {
        Listener l = listener(inst);
        if (l != null) l.onPointerSetDefault(inst);
    }

    private static void OnRailWindowUpdate(long inst, long windowId, int width, int height,
                                           int[] pixels) {}

    // ------------------------------------------------------------- 监听接口

    public interface Listener {
        void onPreConnect(long inst);

        void onConnected(long inst);

        void onConnectionFailure(long inst);

        void onDisconnecting(long inst);

        void onDisconnected(long inst);

        void onSettingsChanged(long inst, int width, int height, int bpp);

        void onGraphicsResize(long inst, int width, int height, int bpp);

        void onGraphicsUpdate(long inst, int x, int y, int width, int height);

        default boolean onAuthenticate(long inst, StringBuilder username, StringBuilder domain,
                                       StringBuilder password) {
            return false;
        }

        default boolean onGatewayAuthenticate(long inst, StringBuilder username,
                                             StringBuilder domain, StringBuilder password) {
            return false;
        }

        default int onVerifyCertificate(long inst, String host, long port, String commonName,
                                        String subject, String issuer, String fingerprint,
                                        long flags) {
            return 0;
        }

        default int onVerifyChangedCertificate(long inst, String host, long port, String commonName,
                                               String subject, String issuer, String fingerprint,
                                               String oldSubject, String oldIssuer,
                                               String oldFingerprint, long flags) {
            return 0;
        }

        default boolean onExperimentalFeature(long inst, int feature) {
            return true;
        }

        default void onRemoteClipboardChanged(long inst, String data) {}

        default void onRemoteClipboardImageChanged(long inst, byte[] data) {}

        default void onPointerSet(long inst, int[] pixels, int w, int h, int hotX, int hotY) {}

        default void onPointerSetNull(long inst) {}

        default void onPointerSetDefault(long inst) {}
    }
}
