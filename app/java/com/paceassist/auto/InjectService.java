package com.paceassist.auto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** 前台服务：按 1 Hz 把引擎生成的轨迹写进系统 GPS/NETWORK Provider。 */
public class InjectService extends Service {

    public static final String ACTION_START = "com.paceassist.auto.START";
    public static final String ACTION_STOP = "com.paceassist.auto.STOP";
    public static final String ACTION_TEST = "com.paceassist.auto.TEST";
    public static final String EXTRA_POINTS = "points";   // 扁平 double[] [lat,lon,lat,lon,...]
                                                          // 不用 float32：经度 116° 附近 ulp≈1.5 米，整条轨迹会整体偏移
    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_PACE = "pace";
    public static final String EXTRA_GCJ = "gcj";
    public static final String EXTRA_BAND = "band";
    public static final String EXTRA_FROM_CURRENT = "fromCurrent";
    public static final String EXTRA_CURLAT = "curLat";
    public static final String EXTRA_CURLON = "curLon";
    public static final String EXTRA_REPEAT = "repeat";   // 0=折返 1=绕圈
    public static final String EXTRA_VIBRATE = "vibrate"; // 1=按步频脉冲振动（伪造成步数）
    public static final String ACTION_VIBTEST = "com.paceassist.auto.VIBTEST";

    /** 悬浮调速条：注入开着时浮在所有应用之上，切到跑步软件也还在 */
    private SpeedOverlay overlay;
    /** 基准配速（来自「里程与配速」）—— 滑轨的中心就是它 */
    private double basePace = 360.0;

    public static volatile boolean running = false;
    public static volatile String status = "空闲";
    public static volatile int idx = 0, total = 0;
    public static volatile int tickCount = 0;
    public static volatile long lastTickAt = 0;
    public static volatile String vibStatus = "";
    public static volatile double covered = 0, targetM = 0;
    /** 暂停中（悬浮条上的暂停按钮控制） */
    public static volatile boolean paused = false;
    /** 当前生效的配速（秒/公里）—— 滑轨调速后主界面也读这个，不会显示旧值 */
    public static volatile double livePace = 0;
    public static volatile double curLat = 0, curLon = 0;
    /** 沿路线走到的位置 0..1（折返/绕圈时来回走，和「目标完成度」不是一回事） */
    public static volatile double routePos = 0;

    private static final String CH_ID = "pace_auto";
    private static final int NOTI_ID = 1001;
    private static final double CP_RADIUS_M = 35.0;

    private LocationManager lm;
    private static volatile InjectService inst;
    private Handler handler;
    private PowerManager.WakeLock wl;
    private TrackPlayer player;
    private long lastTickMs;
    private android.os.Vibrator vib;
    private boolean providersReady = false;
    // 网络定位这一路是「锦上添花」：有些机型不允许给 NETWORK_PROVIDER 加测试源，
    // 那种情况只跳过它，不该把整个注入判成失败。
    private boolean networkReady = false;
    private long lastNotiMs = 0;
    private boolean atTarget = false;          // 已达目标：不再推进，但保持位置不动
    private TrackEngine.Tick lastTick;         // 最后一拍，用于到达目标后原地续推

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        Ui.init(this);          // 悬浮调速条用 dp() 排版，服务单独进程被拉起时也要有正确密度
        handler = new Handler(Looper.getMainLooper());
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createChannel();
        installCrashCleanup();
    }

    /**
     * 进程要是被未捕获异常带走，测试 provider 不会跟着消失 —— 系统会一直用最后一帧
     * 假位置，把整机定位钉死（导航、打车、打卡全错位）。所以兜一层：先撤干净再交出去。
     */
    private static boolean crashHookInstalled = false;
    private void installCrashCleanup() {
        if (crashHookInstalled) return;
        crashHookInstalled = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable ex) {
                InjectService s = inst;
                if (s != null) { try { s.cleanup(); } catch (Throwable ignore) { } }
                if (prev != null) prev.uncaughtException(th, ex);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // 极端情况：外面用的是 startForegroundService，5 秒内不调 startForeground
            // 会被判崩溃。先挂上通知再退，别留下一个必崩的窗口。
            startForegroundCompat("准备中…");
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAll("已停止");
            return START_NOT_STICKY;
        }
        if (ACTION_TEST.equals(action)) {
            startForegroundCompat("自检中…");
            final double la = intent.getDoubleExtra(EXTRA_CURLAT, 0);
            final double lo = intent.getDoubleExtra(EXTRA_CURLON, 0);
            try {
                ensureProviders();
            } catch (SecurityException se) {
                stopAll("自检失败：模拟位置授权无效（系统拒绝）。请重新授权");
                return START_NOT_STICKY;
            } catch (Throwable t) {
                stopAll("自检失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
                return START_NOT_STICKY;
            }
            running = true;
            status = "自检运行中";
            total = 10;
            idx = 0;
            handler.postDelayed(new Runnable() {
                int n = 0;
                @Override public void run() {
                    if (!running) return;
                    if (n >= 10) { stopAll("自检成功：注入 10 秒无异常，位置已临时移到北方 30 米"); return; }
                    try {
                        Location l = new Location(LocationManager.GPS_PROVIDER);
                        l.setLatitude(la + Geo.mToLat(30));
                        l.setLongitude(lo);
                        l.setAccuracy(8f);
                        l.setSpeed(0f);
                        l.setBearing(0f);
                        l.setAltitude(20);
                        l.setTime(System.currentTimeMillis());
                        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                        lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, l);
                        lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, l);
                    } catch (Throwable e) {
                        stopAll("自检失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
                        return;
                    }
                    n++;
                    idx = n;
                    handler.postDelayed(this, 1000);
                }
            }, 300);
            return START_NOT_STICKY;
        }
        if (ACTION_VIBTEST.equals(action)) {
            startForegroundCompat("振动测试中…");
            running = true;
            status = "振动测试中（按 2 Hz 脉冲振动 60 秒）";
            startVib();
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    stopAll("振动测试结束（已按 2 Hz 脉冲振动 60 秒，去看跑步软件的步数）");
                }
            }, 60000);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        // 重复点「开始自动」绝不能起第二条注入循环 —— 两条 step 同时跑，速度会翻倍。
        // 注意：这里必须先 startForeground 再返回。外面用的是 startForegroundService()，
        // 5 秒内不调 startForeground 会被系统判成崩溃。
        if (running) {
            startForegroundCompat("运行中 · 已跑 " + fmt(covered) + " 米");
            noti("已经在跑了，不用再点一次");
            return START_NOT_STICKY;
        }

        startForegroundCompat("准备中…");

        if (!Sys.mockAllowed(this)) {
            stopAll("未获得模拟位置授权：请先在开发者选项里把本应用选为「模拟位置信息应用」，或执行 adb 命令授权");
            return START_NOT_STICKY;
        }

        double[] pts = intent.getDoubleArrayExtra(EXTRA_POINTS);
        if (pts == null || pts.length < 4) {
            stopAll("路线点不足（至少 2 个点）");
            return START_NOT_STICKY;
        }

        TrackEngine.Params p = new TrackEngine.Params();
        for (int i = 0; i + 1 < pts.length; i += 2) {
            p.path.add(new double[]{pts[i], pts[i + 1]});
        }
        p.sourceIsGcj = intent.getIntExtra(EXTRA_GCJ, 1) == 1;
        p.targetMeters = intent.getDoubleExtra(EXTRA_TARGET, 2000);
        p.paceSecPerKm = intent.getDoubleExtra(EXTRA_PACE, 360);
        basePace = Math.max(150.0, Math.min(1200.0, p.paceSecPerKm));
        p.band = intent.getDoubleExtra(EXTRA_BAND, 0.12);
        p.startFromCurrent = intent.getBooleanExtra(EXTRA_FROM_CURRENT, false);
        p.curLat = intent.getDoubleExtra(EXTRA_CURLAT, 0);
        p.curLon = intent.getDoubleExtra(EXTRA_CURLON, 0);
        p.startTimeMillis = System.currentTimeMillis();

        player = new TrackPlayer(p, intent.getIntExtra(EXTRA_REPEAT, TrackPlayer.REPEAT_PINGPONG));
        if (!player.valid()) {
            stopAll("轨迹生成失败：路线点距离过小");
            return START_NOT_STICKY;
        }
        livePace = player.paceSecPerKm();

        try {
            ensureProviders();
        } catch (SecurityException se) {
            stopAll("注入被拒绝（SecurityException）：模拟位置授权无效，请重新授权");
            return START_NOT_STICKY;
        } catch (Throwable t) {
            stopAll("注入初始化失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {                       // 取不到就算了，不能因此崩掉整个注入
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paceassist:inject");
            wl.setReferenceCounted(false);
            wl.acquire(6 * 60 * 60 * 1000L);
        }

        atTarget = false;
        lastTick = null;
        idx = 0;
        total = 0;
        tickCount = 0;
        lastTickAt = 0;
        targetM = p.targetMeters;
        covered = 0;
        running = true;
        lastTickMs = System.currentTimeMillis();
        status = "运行中";
        if (intent.getIntExtra(EXTRA_VIBRATE, 0) == 1) startVib();
        showOverlay();
        handler.post(step);
        return START_NOT_STICKY;
    }

    private void ensureProviders() {
        // GPS 是必须的：跑步软件都看这一路。失败就往上抛，让用户看到明确原因。
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Throwable ignore) { }
        lm.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE);
        lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);

        // 网络定位：能加就加，加不上就算了（不能因此让整个注入失败）
        try {
            lm.removeTestProvider(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable ignore) { }
        try {
            lm.addTestProvider(LocationManager.NETWORK_PROVIDER, false, true, false, false, true, true, true,
                    android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE);
            lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            networkReady = true;
        } catch (Throwable ignore) {
            networkReady = false;
        }
        providersReady = true;
    }

    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (!running) return;
            // 唤醒锁只申请了 6 小时，超时会被系统自动释放，之后后台冻结、注入停摆。
            // 每一拍顺手检查一次，掉了就续上。
            if (wl != null && !wl.isHeld()) {
                try { wl.acquire(2 * 60 * 60 * 1000L); } catch (Throwable ignore) { }
            }
            long now = System.currentTimeMillis();
            double dt = (now - lastTickMs) / 1000.0;
            lastTickMs = now;
            // 关键：被系统冻结/降频时，只按最多 3 秒算，绝不把欠下的拍子一次性补上（那样就是瞬移）
            if (dt < 0.2) dt = 0.2;
            if (dt > 3.0) dt = 3.0;

            if (player.done() || atTarget) {
                // 到目标后**不能**立刻停服务：一撤掉模拟位置，跑步软件会看到位置瞬移回真实地址。
                // 正确做法是原地续推同一个坐标，等用户确认跑步提交后自己点「停止并清理」。
                if (!atTarget) {
                    atTarget = true;
                    // 位置冻结时速度也必须归零：跑步软件大多按 speed 积分里程和即时配速，
                    // 留着最后一拍的速度会变成「人站着不动、配速还在 2.8 m/s」，里程继续涨。
                    if (lastTick != null) lastTick.speed = 0f;
                    noti("已到目标 " + fmt(targetM) + " 米 · 位置停在终点，确认跑步提交后再点停止并清理");
                }
                // 原地续推：位置不变，但两个时间必须跟着走 ——
                //   lastTickAt 不更新 → 主界面会误报「注入中断」；
                //   timeMillis 不更新 → 跑步软件看到 GPS 时间不前进，可能判信号丢失。
                lastTickAt = now;
                if (lastTick != null) {
                    lastTick.timeMillis = now;
                    try { push(lastTick); } catch (Throwable ignored) { }
                }
                handler.postDelayed(this, 1000);
                return;
            }
            TrackEngine.Tick t = player.next(dt, now);
            try {
                push(t);
            } catch (Throwable e) {
                stopAll("注入失败：" + e.getClass().getSimpleName());
                return;
            }
            lastTick = t;
            idx++;
            tickCount++;
            lastTickAt = now;
            covered = t.covered;
            curLat = t.lat;
            curLon = t.lon;
            routePos = player.routeProgress();
            if (now - lastNotiMs > 4000) {
                lastNotiMs = now;
                noti("已跑 " + fmt(covered) + "/" + fmt(targetM) + " 米");
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** 前台服务：必须带 location 类型，否则进程在后台会被系统冻结（位置就不动了） */
    private void startForegroundCompat(String text) {
        Notification n = buildNoti(text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTI_ID, n);
            }
            return;
        } catch (Throwable t1) {
            // 带 location 类型的调用在部分 ROM（MIUI/HyperOS/ColorOS 对 FGS 类型校验更严）
            // 上会抛。这里退回不带类型的写法 —— 但**这一步也必须包起来**：
            // 二次抛出会直接从 onStartCommand 逃出去崩掉进程，用户看到的是闪退，
            // 而不是「还差什么权限」。
            try {
                startForeground(NOTI_ID, n);
            } catch (Throwable t2) {
                status = "前台服务启动失败：" + t2.getClass().getSimpleName();
            }
        }
    }

    /**
     * 按"步频"脉冲振动：响 150ms → 停 350ms → 循环 = 2 Hz（每分钟 120 步）。
     * 目的：让加速度传感器产生 1~4 Hz 的周期性冲击，可能被跑步软件的计步算法数成步数。
     * 注意：不能"一直震" —— 持续高频振动滤波后是平直线，一个峰值都没有，算不出步数。
     */
    private void startVib() {
        try {
            vib = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null) { vibStatus = "振动失败：取不到 Vibrator 服务"; return; }
            if (!vib.hasVibrator()) { vibStatus = "振动失败：这台设备没有振动马达"; return; }
            long[] pattern = new long[]{0, 150, 350};   // 立即 / 响150ms / 停350ms
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0));
            } else {
                vib.vibrate(pattern, 0);
            }
            vibStatus = "振动已启动：2 Hz 脉冲（每分钟 120 次）";
        } catch (Throwable e) {
            vibStatus = "振动失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
    }

    private void stopVib() {
        try { if (vib != null) vib.cancel(); } catch (Throwable ignore) { }
        vib = null;
    }

    private void push(TrackEngine.Tick t) {
        // GPS 失败就是真失败，往上抛
        lm.setTestProviderLocation(LocationManager.GPS_PROVIDER,
                makeLocation(t, LocationManager.GPS_PROVIDER));
        if (networkReady) {
            try {
                lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER,
                        makeLocation(t, LocationManager.NETWORK_PROVIDER));
            } catch (Throwable ignore) { }
        }
    }

    private static Location makeLocation(TrackEngine.Tick t, String provider) {
        Location l = new Location(provider);
        l.setLatitude(t.lat);
        l.setLongitude(t.lon);
        l.setAccuracy(t.accuracy);
        l.setSpeed(t.speed);
        l.setBearing(t.bearing);
        l.setAltitude(t.altitude);
        l.setTime(t.timeMillis);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= 26) {
            l.setVerticalAccuracyMeters(3f);
            l.setSpeedAccuracyMetersPerSecond(0.3f);
            l.setBearingAccuracyDegrees(2f);
        }
        return l;
    }

    /* ══════════ 悬浮调速条 ══════════ */

    private void showOverlay() {
        try {
            if (overlay == null) {
                overlay = new SpeedOverlay(this, new SpeedOverlay.Host() {
                    @Override public void onPace(double secPerKm) { applyPace(secPerKm); }
                    @Override public void onTogglePause() { togglePause(); }
                });
            }
            overlay.show(basePace);
            if (player != null) overlay.setPace(player.paceSecPerKm());
            overlay.setPausedState(paused);
        } catch (Throwable ignore) { }
    }

    private void hideOverlay() {
        try { if (overlay != null) { overlay.hide(); overlay = null; } } catch (Throwable ignore) { }
    }

    /**
     * 滑轨给出的**绝对配速** —— 直接写进引擎，下一拍注入的速度立刻跟着变，
     * 不是只改个显示数字。滑轨自己已经量化到 10 秒档并做了上下限夹取。
     */
    private void applyPace(double secPerKm) {
        if (player == null || !running) return;   // 已经停了就别再回写/发通知
        player.setPace(secPerKm);
        livePace = player.paceSecPerKm();
        // 注意：这里**不能**回写 overlay.setPace()。
        // 那会把滑块的 frac 按量化后的配速重算，拖动时拇指就会一跳一跳，
        // 而不是跟手连续走 —— 手感直接毁掉。滑轨自己的读数由它内部刷新。
        noti("配速 " + paceText(player.paceSecPerKm()) + " /km · 已跑 " + fmt(covered) + " 米");
    }

    private static String paceText(double secPerKm) {
        if (secPerKm <= 0) return "--'--\"";
        int s = (int) Math.round(secPerKm);
        return String.format(java.util.Locale.US, "%d'%02d\"", s / 60, s % 60);
    }

    /** 暂停 / 继续 —— 暂停不是"停服务"，服务还在、位置还钉在那一点上，随时能接着跑 */
    void togglePause() {
        if (!running) return;                      // 服务已停，别再翻转状态
        paused = !paused;
        if (player != null) player.setPaused(paused);
        if (overlay != null) overlay.setPausedState(paused);
        noti(paused ? "已暂停 · 位置停在这里不动"
                : "已继续 · 已跑 " + fmt(covered) + " 米");
    }

    /** 把运行期占用的东西全部还回去。stopAll 和 onDestroy 共用，避免两处漏一个。 */
    private void cleanup() {
        running = false;
        paused = false;
        atTarget = false;
        lastTick = null;
        livePace = 0;
        routePos = 0;
        stopVib();
        hideOverlay();
        if (handler != null) {
            try { handler.removeCallbacksAndMessages(null); } catch (Throwable ignore) { }
        }
        if (providersReady && lm != null) {
            for (String name : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try { lm.setTestProviderEnabled(name, false); } catch (Throwable ignore) { }
                try { lm.removeTestProvider(name); } catch (Throwable ignore) { }
            }
        }
        providersReady = false;
        networkReady = false;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignore) { }
        wl = null;
    }

    private void stopAll(String why) {
        status = why;
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    /** 系统回收服务（被清后台 / force-stop）时的兜底：否则悬浮窗和唤醒锁会一直挂着 */
    @Override
    public void onDestroy() {
        cleanup();
        if (inst == this) inst = null;
        super.onDestroy();
    }

    /** 用户从最近任务里把应用划掉：同样必须把测试 provider 撤掉，否则假位置会钉住整机定位 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (running) stopAll("应用被划掉，已清理模拟位置");
        else cleanup();
        super.onTaskRemoved(rootIntent);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "自动配速",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNoti(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("慢亿点运行中")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void noti(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNoti(text));
        } catch (Throwable ignore) { }
    }

    private static String fmt(double m) { return String.valueOf(Math.round(m)); }
}
