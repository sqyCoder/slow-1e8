package com.paceassist.auto;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;

/** 系统层面的检查与改写（全部需要 adb 一次性授权的权限）。 */
public final class Sys {
    private Sys() {}

    public static final int OK = 0;
    public static final int NO_MOCK = 1;
    public static final int NO_WRITE_SECURE = 2;

    /**
     * 本应用是否已被授予「模拟位置信息应用」。
     * 读不到 AppOps / 两个 API 都抛异常时**不判死**：返回 true 放行，
     * 真实失败由服务端写进 status 再回报给界面（否则用户怎么授权都过不去）。
     */
    public static boolean mockAllowed(Context c) {
        AppOpsManager ao = null;
        try {
            ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        } catch (Throwable ignore) { }
        if (ao == null) return true;
        // 先试 unsafeCheckOpNoThrow（API 29+ 起可用、不需要额外权限，多数 ROM 走这条）
        Integer unsafe = unsafeOp(ao, c);
        if (unsafe != null) return (int) unsafe == AppOpsManager.MODE_ALLOWED;
        // 退回公开 API
        try {
            int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignore) {
            return true;
        }
    }

    private static Integer unsafeOp(AppOpsManager ao, Context c) {
        try {
            java.lang.reflect.Method m = AppOpsManager.class.getMethod(
                    "unsafeCheckOpNoThrow", String.class, int.class, String.class);
            Object r = m.invoke(ao, AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(), c.getPackageName());
            return r instanceof Integer ? (Integer) r : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static boolean hasWriteSecure(Context c) {
        return c.checkPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    /** 读 development_settings_enabled（部分跑步软件会读这个键来判断是否开了开发者模式） */
    public static int devModeEnabled(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), "development_settings_enabled", 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 改 development_settings_enabled；返回是否成功 */
    public static boolean setDevModeEnabled(Context c, boolean on) {
        try {
            return Settings.Global.putInt(c.getContentResolver(),
                    "development_settings_enabled", on ? 1 : 0);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int check(Context c) {
        if (!mockAllowed(c)) return NO_MOCK;
        if (!hasWriteSecure(c)) return NO_WRITE_SECURE;
        return OK;
    }
}
