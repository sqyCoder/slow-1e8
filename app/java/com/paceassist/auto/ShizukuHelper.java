package com.paceassist.auto;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.InputStream;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 通过 Shizuku 以 shell(adb) 身份执行命令。
 *
 * 为什么需要它：把本应用设为「模拟位置信息应用」和授予 WRITE_SECURE_SETTINGS
 * 都只能由 adb 或用户手动完成，应用自己申请不到（这是安卓的设计）。
 * Shizuku 允许在**手机上**取得 adb 身份（Android 11+ 用无线调试自配对，无需电脑），
 * 于是这两件事就能在本应用内一键完成。
 *
 * 注意：Shizuku 靠 ContentProvider 传 binder，必须在 AndroidManifest 里声明
 * rikka.shizuku.ShizukuProvider（authorities = 包名 + ".shizuku"）。
 */
public final class ShizukuHelper {
    private ShizukuHelper() {}

    /** Shizuku 服务是否活着（没装 Shizuku 时会返回 false） */
    public static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本应用是否已获得 Shizuku 授权 */
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 请求 Shizuku 授权（会弹 Shizuku 的确认框） */
    public static void request(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable ignore) { }
    }

    /**
     * 以 shell 身份执行一条命令。
     * @return {exitCode, 合并的输出}
     */
    public static String[] run(String cmd) {
        IRemoteProcess proc = null;
        try {
            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            // stderr 合进 stdout（2>&1）：分两个管道读会有死锁风险 ——
            // 子进程把 stderr 写满管道缓冲就会卡住写，我们却还阻塞在读 stdout 上。
            proc = svc.newProcess(new String[]{"sh", "-c", cmd + " 2>&1"}, null, null);
            StringBuilder sb = new StringBuilder();
            readAll(proc.getInputStream(), sb);
            int code = proc.waitFor();
            return new String[]{String.valueOf(code), sb.toString().trim()};
        } catch (Throwable t) {
            return new String[]{"-1", "异常：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage())};
        } finally {
            try { if (proc != null) proc.destroy(); } catch (Throwable ignore) { }
        }
    }

    /** 一次性完成全部准备：模拟位置授权 + 改写系统设置权限 + 关闭开发者选项检测 */
    public static String doFullSetup(String pkg) {
        String cmd =
                "appops set " + pkg + " android:mock_location allow; "
              + "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS; "
              + "pm grant " + pkg + " android.permission.ACCESS_BACKGROUND_LOCATION; "
              + "settings put global development_settings_enabled 0; "
              + "echo '---CHECK---'; "
              + "appops get " + pkg + " android:mock_location; "
              + "settings get global development_settings_enabled";
        return run(cmd)[1];
    }

    private static void readAll(ParcelFileDescriptor pfd, StringBuilder sb) {
        if (pfd == null) return;
        InputStream is = null;
        try {
            is = new FileInputStream(pfd.getFileDescriptor());
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        } catch (Throwable ignore) {
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignore) { }
            try { pfd.close(); } catch (Throwable ignore) { }
        }
    }
}
