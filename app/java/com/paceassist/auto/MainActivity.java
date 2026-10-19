package com.paceassist.auto;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    // 这个列表由「画路线」的 JS 桥接线程写、由主线程读，必须 volatile，
    // 否则主线程可能一直看到旧路线（画了新路却按老路跑）
    public static volatile List<double[]> picked = new ArrayList<>();

    private TextView tvLog, tvCmd;
    private EditText etPace;
    private final Handler h = new Handler(Looper.getMainLooper());

    /* Activity 是否已销毁：轮询 Runnable 正在执行的瞬间被销毁时，靠它阻止再入队 */
    private volatile boolean destroyed = false;
    /* 开场音效；播完/出错/销毁都要释放，不然漏掉一条 AudioTrack（最多 32 条，满了没声音） */
    private android.media.MediaPlayer introMp;

    /* ── 仪表盘与状态行 ── */
    private TextView tvDist, tvTargetTxt, tvPaceC, tvHealth, tvCoord;
    private RouteView routeView;
    private LinearLayout.LayoutParams routeLp;
    private TextView logHeadView;

    /** 顶栏：产品名 + 一行说明。不加多余装饰 */
    private View appBar() {
        LinearLayout c = Ui.col(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        c.addView(Ui.readout(this, "慢亿点", 27f, Ui.INK));
        TextView sub = Ui.text(this, "跑步配速 · 轨迹自动注入", 15f, Ui.INK_2, null);
        sub.setPadding(0, dp(7), 0, 0);
        c.addView(sub);
        return c;
    }

    /**
     * 主面板 —— 一整块面里装下所有东西：路线/圈、读数、状态、三格数据、主行动。
     * 默认（没开跑、没画路线）也有一个圈和三格数据，界面永远不会空着。
     */
    private View console() {
        LinearLayout p = Ui.panel(this);

        routeView = new RouteView(this);
        // 路线区吃掉面板里多余的纵向空间：有路线就铺开，没路线也撑住不留空
        // 固定高度，不参与权重：上方那圈 + 下方读数/按钮才是一个整体
        routeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(172));
        routeView.setMinimumHeight(Ui.dp(172));
        p.addView(routeView, routeLp);

        // 读数：左边是大数字 + 单位，右边把目标压到行尾当锚点
        LinearLayout big = Ui.row(this);
        big.setGravity(Gravity.BOTTOM);
        big.setPadding(0, dp(Ui.S4), 0, 0);
        tvDist = Ui.readout(this, "0", 60f, Ui.INK);
        big.addView(tvDist);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        up.leftMargin = dp(8); up.bottomMargin = dp(12);
        big.addView(Ui.readout(this, "米", 16f, Ui.INK_2), up);
        big.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        tvTargetTxt = Ui.readout(this, "目标 3000", 15f, Ui.INK_2);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(14);
        big.addView(tvTargetTxt, tp);
        p.addView(big);

        // 里程跑不满时会自己折返继续跑，说清楚，省得用户以为跑不够就白跑了
        TextView note = Ui.text(this,
                "跑不满会自动原路折返，不必担心", 13f, Ui.INK_2, null);
        note.setPadding(0, dp(Ui.S2), 0, 0);
        p.addView(note);

        // 一行状态：配速在左，运行态在右
        LinearLayout mid = Ui.row(this);
        mid.setPadding(0, dp(20), 0, dp(24));
        mid.addView(Ui.text(this, "配速", 15f, Ui.INK_2, null));
        tvPaceC = Ui.readout(this, "—", 19f, Ui.INK);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        m2.leftMargin = dp(10);
        mid.addView(tvPaceC, m2);
        mid.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        tvHealth = Ui.led(this, "待启动", Ui.INK_2);
        mid.addView(tvHealth);
        p.addView(mid);

        // 主行动就放在同一块面里
        p.addView(btn("▶ 开始自动", 0xFF18A058, new View.OnClickListener() {
            @Override public void onClick(View v) { startInject(); }
        }));
        p.addView(btn("画路线", 0xFF555555, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, MapPickerActivity.class);
                double[] here = lastKnown();
                if (here != null) {
                    i.putExtra(MapPickerActivity.EXTRA_LAT, here[0]);
                    i.putExtra(MapPickerActivity.EXTRA_LON, here[1]);
                }
                startActivityForResult(i, REQ_MAP);
            }
        }));
        p.addView(btn("停止并清理", 0xFFB03030, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmStop(); }
        }));

        return p;
    }

    private static String mmss(int sec) {
        return String.format(java.util.Locale.US, "%d'%02d\"", sec / 60, sec % 60);
    }

    private static final int REQ_MAP = 101;
    private static final int REQ_PERM = 102;
    private static final int REQ_SHIZUKU = 201;
    private static final int REQ_OVERLAY = 202;
    /**
     * 固定目标里程 —— 界面不再让用户改（没人管这个）。
     * 路线比它短时，注入会自动折返（开放路线）或绕圈（环线）继续，直到跑够。
     */
    private static final double TARGET_M = 3000;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(android.graphics.Color.TRANSPARENT);   // 露出底下的极光层
        sc.setFillViewport(true);
        LinearLayout root = Ui.col(this);
        int pad = dp(20);
        root.setPadding(pad, dp(18), pad, dp(30));
        sc.addView(root);

        // ── 顶栏 ──
        root.addView(appBar());
        // ── 一整块主面板：路线 + 读数 + 配速 + 主行动，全在里面 ──
        // 面板按内容高度走，不再用权重撑满整屏：
        // 撑满会把面板内的空档拉到几百 dp，并把折叠项和三颗按钮顶出屏幕。
        root.addView(console(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 使用说明：所有引导文字集中在这里，不散落在按钮旁边 ──
        LinearLayout helpBox = Ui.block(this);
        helpBox.addView(body("这个工具替你按固定配速「跑步」。你只需要在跑步软件里正常操作，"
                + "位置由它来喂。"));
        helpBox.addView(body("一、先在跑步软件里点「开始跑步」，等它开始记录位置。"));
        helpBox.addView(body("二、切回本页点「画路线」。进去先点「设起点」，"
                + "在地图上「自己选」一个出发位置 —— 别用你实际站的地方，"
                + "不然路线从宿舍出来会穿墙。"));
        helpBox.addView(body("三、再点「画路线」顺着路点密一点；路点顺序要和你打算走的方向一致。"
                + "点「完成」回来。"));
        helpBox.addView(body("四、点「开始自动」，然后把跑步软件切到前台。"
                + "需要人脸识别的，按它自己的提示完成。"));
        helpBox.addView(body("五、想停就点「停止并清理」。"));
        root.addView(collapsible("使用说明", helpBox, false));

        // ── 配速：只留配速，里程固定（跑完不够会自动折返） ──
        LinearLayout goalBox = Ui.block(this);
        goalBox.addView(lbl("平均配速（秒/公里，360 = 6分00秒）"));
        etPace = input("360");
        goalBox.addView(etPace);
        root.addView(collapsible("配速", goalBox, false));

        // ── 给跑步软件放行：只有拦开发者模式的软件才用得上 ──
        LinearLayout fixBox = Ui.block(this);
        fixBox.addView(body("如果你的跑步软件拦截开发者模式才需使用，并且要配合 Shizuku。"));
        fixBox.addView(bodyLink("Shizuku 下载地址：https://shizuku.rikka.app/"));
        fixBox.addView(btn("下载 Shizuku", 0xFF7A5AF8, new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl("https://shizuku.rikka.app/"); }
        }));
        fixBox.addView(btn("给跑步软件放行", 0xFF7A5AF8,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (Sys.setDevModeEnabled(MainActivity.this, false)) toast("已放行");
                        else toast("失败：缺少写入系统设置的权限");
                        refresh();
                    }
                }));
        fixBox.addView(btn("Shizuku 一键授权", 0xFF7A5AF8, new View.OnClickListener() {
            @Override public void onClick(View v) { shizukuSetup(); }
        }));
        fixBox.addView(btn("重新检查授权状态", 0xFF555555, new View.OnClickListener() {
            @Override public void onClick(View v) { recheckAuth(); }
        }));
        fixBox.addView(body("一、装好 shizuku，根据引导为「慢亿点」授权好。"));
        fixBox.addView(body("二、成功后回到本页，开启开发者给地址权限之后回来，点击放行。"));
        fixBox.addView(body("三、这样就能关闭了开发者同时保持给予地址权限，常规操作，无碍。"));
        root.addView(collapsible("给跑步软件放行", fixBox, false));

        tvLog = Ui.text(this, "", 13f, Ui.INK_2, null);
        LinearLayout logBox = collapsible("运行日志", tvLog, false);
        logHeadView = (TextView) logBox.getChildAt(0);
        root.addView(logBox);

        // ── 作者：放最底下，不抢内容，但想找的时候一定找得到 ──
        TextView by = bodyLink("作者 GitHub：https://github.com/sqyCoder");
        by.setGravity(Gravity.CENTER);
        by.setTextSize(12.5f);
        by.setPadding(0, dp(Ui.S6), 0, 0);
        root.addView(by);

        // 极光层（活的、被糊过的光）+ 玻璃内容层
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(new AuroraView(this), new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        frame.addView(sc, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        playIntro();
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuResult);
        } catch (Throwable ignore) { }
        // 权限弹窗等开场动画播完再弹，别一上来就盖住（动画 1500ms）
        h.postDelayed(new Runnable() {
            @Override public void run() { requestPerms(); }
        }, 1620);
        refresh();
        h.post(ticker);
    }

    /** 开场动画：在窗口最上面盖一层自绘的 SplashView，播完自己摘掉 */
    private void playIntro() {
        try {
            final android.view.ViewGroup deco =
                    (android.view.ViewGroup) getWindow().getDecorView();
            final SplashView sp = new SplashView(this);
            sp.setOnDone(new SplashView.OnDone() {
                @Override public void onDone() {
                    // 先 GONE 再 removeView：GONE 立刻停止绘制，
                    // 不会出现"已经要交接了，这一帧还被画在屏幕上"的残影
                    try { sp.setVisibility(View.GONE); } catch (Throwable ignore) { }
                    try { if (sp.getParent() != null) deco.removeView(sp); } catch (Throwable ignore) { }
                }
            });
            deco.addView(sp, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            sp.start();
            // 兜底：动画万一没回调（系统动画被关、异常打断），1.9 秒后也一定要摘掉，
            // 否则这一层会永久盖住主界面，等于软件打不开。
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try { sp.setVisibility(View.GONE); } catch (Throwable ignore) { }
                    try { if (sp.getParent() != null) deco.removeView(sp); } catch (Throwable ignore) { }
                }
            }, 1900);
        } catch (Throwable ignore) { }
        playLaunchSound();
    }

    /** 开场音效：手机在静音/震动模式下不响；音量压到 55%，别像通知一样炸耳朵 */
    private void playLaunchSound() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null
                    && am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return;
            releaseIntroSound();
            introMp = android.media.MediaPlayer.create(this, R.raw.launch);
            if (introMp == null) return;
            introMp.setOnCompletionListener(m -> releaseIntroSound());
            introMp.setOnErrorListener((m, what, extra) -> {
                releaseIntroSound();
                return true;
            });
            introMp.setVolume(0.55f, 0.55f);
            introMp.start();
        } catch (Throwable ignore) { }
    }

    /** 释放开场音效。onCompletion / onError / onDestroy 三处都会走到，重复调用安全 */
    private void releaseIntroSound() {
        android.media.MediaPlayer m = introMp;
        introMp = null;
        if (m != null) {
            try { m.stop(); } catch (Throwable ignore) { }
            try { m.release(); } catch (Throwable ignore) { }
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        // 弹框还挂在窗口上就销毁 Activity，系统会抛 WindowLeaked —— 先收掉
        ConfirmDialog.dismissCurrent();
        // 开场音效可能还在响：不释放会漏掉 AudioTrack
        releaseIntroSound();
        // 轮询、延迟的权限申请、动画兜底 —— 全部摘掉。
        // 只 removeCallbacks(ticker) 是不够的：那个延迟 1680ms 的权限申请会在
        // Activity 已经销毁之后再跑起来，等于往死掉的窗口上要权限。
        if (h != null) h.removeCallbacksAndMessages(null);
        // Shizuku 的授权回调是全局注册的，不摘会一直持有这个 Activity
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuResult);
        } catch (Throwable ignore) { }
        // 振动自检的传感器监听：那个 10 秒后的注销 Runnable 已经被
        // removeCallbacksAndMessages(null) 摘掉了，这里必须自己注销
        stopSensorTest();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (destroyed) return;
        // 从跑步软件切回来时对齐一次真实状态，别让界面停在离开前那一帧
        refresh();
        // 轮询重新起来（onPause 里停掉了，前后台不用一直每 500ms 刷）
        h.removeCallbacks(ticker);
        h.post(ticker);
        // 从设置页补齐权限回来：接着弹下一道，不用再点一次「开始自动」
        if (pendingStart && leftForGate) {
            leftForGate = false;
            // 只认「刚去刚回」。离开超过 90 秒说明中途去干别的了 ——
            // 这时候再自动开跑，就是用户在没预期的情况下被注入。
            if (System.currentTimeMillis() - leftForGateAt > 90000L) {
                pendingStart = false;
                gateWaited = GATE_NONE;
                toast("离开太久，已取消自动开始，需要就再点一次「开始自动」");
            } else {
                continueChain();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台就不要再每 500ms 刷界面了；服务端负责真正的位置注入
        if (h != null) h.removeCallbacks(ticker);
        // 不是我们自己把用户送去设置页的（切别的应用、开地图选点…），链条作废，
        // 否则过一会儿回到前台会莫名其妙自己开跑
        if (!leftForGate) {
            pendingStart = false;
            gateWaited = GATE_NONE;
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;                   // 已经销毁就绝不再入队
            if (InjectService.running) {
                long ago = InjectService.lastTickAt == 0 ? 0
                        : (System.currentTimeMillis() - InjectService.lastTickAt);
                boolean ok = ago < 2500;
                // 状态不再单独占一行文字（用户嫌废话），只刷新读数

                // 仪表盘实时刷新
                if (routeView != null) {
                    // 用「沿路线走到的位置」，不是「目标里程完成度」：跑不满 3000 米会折返，
                    // 后者会一直单向往前爬，和真实注入的轨迹对不上。
                    routeView.setProgress((float) InjectService.routePos, ok);
                    if (tvDist != null)
                        tvDist.setText(String.valueOf(Math.round(InjectService.covered)));
                    if (tvTargetTxt != null)
                        tvTargetTxt.setText("目标 " + Math.round(InjectService.targetM));
                    double pace = InjectService.livePace > 0 ? InjectService.livePace : num(etPace, 0);
                    tvPaceC.setText(pace > 0 ? mmss((int) pace) : "—");
                    tvHealth.setText(!ok ? "注入中断"
                            : (InjectService.paused ? "已暂停" : "注入中"));
                    tvHealth.setTextColor(!ok ? Ui.ERR
                            : (InjectService.paused ? Ui.ACCENT : Ui.OK));
                }
            } else {
                // 服务已经停了、界面却还挂在最后一帧（「注入中」「已跑 812 米」），
                // 看起来就像卡死。这里复位成空闲态。
                if (routeView != null) {
                    routeView.setProgress(0f, false);
                    if (tvDist != null) tvDist.setText("0");
                    if (tvPaceC != null) tvPaceC.setText("—");
                    if (tvHealth != null) {
                        tvHealth.setText("待启动");
                        tvHealth.setTextColor(Ui.INK_2);
                    }
                }
            }
            h.postDelayed(this, 500);
        }
    };

    private void startInject() {
        if (InjectService.running) {                 // 已经在跑就别重复下发
            toast("已经在跑了，要改配速用悬浮条");
            return;
        }
        if (picked == null || picked.size() < 2) {
            toast("请先在地图上点出路线（至少 2 个点）");
            return;
        }
        // 三道门槛串成一条链：点一次「开始自动」就够，缺哪道弹哪道，
        // 去设置里补齐回到本页自动接着弹下一道，全齐了直接开跑。
        pendingStart = true;
        gateWaited = GATE_NONE;
        leftForGate = false;
        runNextGate();
    }

    /* ══════════ 开跑门槛链：点一次，缺什么就依次弹什么 ══════════ */

    private static final int GATE_NONE = 0, GATE_MOCK = 1, GATE_BATT = 2, GATE_OVL = 3;
    private boolean pendingStart;        // 链条还没走完，在等用户去补权限
    private int gateWaited = GATE_NONE;  // 已经为哪道门槛出去过一次
    private boolean leftForGate;         // 是我们自己把用户送去设置页的
    private long leftForGateAt;          // 送出去的时间；回来超过 90 秒就整条作废

    private final Runnable gateRunner = new Runnable() {
        @Override public void run() { if (!destroyed) runNextGate(); }
    };

    /** 从设置页回来时接着走链条（去抖 320ms，等系统把权限真正落盘） */
    private void continueChain() {
        if (!pendingStart || destroyed) return;
        h.removeCallbacks(gateRunner);
        h.postDelayed(gateRunner, 320);
    }

    private void runNextGate() {
        if (!pendingStart || destroyed) return;
        // 第一道：模拟位置授权（开发者选项里那个选择器）
        if (!Sys.mockAllowed(this)) {
            if (exhausted(GATE_MOCK, "模拟位置信息应用")) return;
            showMockDialog();
            return;
        }
        gateWaited = GATE_NONE;
        // 第二道：后台运行（电池优化白名单），不然后台位置会停
        if (!batteryOk()) {
            if (exhausted(GATE_BATT, "电池优化白名单")) return;
            showBatteryDialog();
            return;
        }
        gateWaited = GATE_NONE;
        // 第三道：悬浮调速条权限 —— 没有它就等于没调速条，必须拦住
        if (!hasOverlay()) {
            if (exhausted(GATE_OVL, "显示在其他应用上层")) return;
            ensureOverlay();
            return;
        }
        pendingStart = false;
        gateWaited = GATE_NONE;
        doStart();
    }

    /** 同一道门槛已经让用户出去过一次还是没过 —— 别无限弹，直说差什么 */
    private boolean exhausted(int gate, String what) {
        if (gateWaited == gate) {
            pendingStart = false;
            gateWaited = GATE_NONE;
            leftForGate = false;
            toast("还差「" + what + "」，补齐后再点一次「开始自动」");
            return true;
        }
        gateWaited = gate;
        return false;
    }

    /** 三道门槛都过了，真正下发开始指令 */
    private void doStart() {
        // 快照一份：picked 是静态字段、可能被「画路线」的 JS 桥接线程整个换掉，
        // 边读 size 边 get 会撞成 IndexOutOfBounds / 空
        List<double[]> pts = picked;
        if (pts == null || pts.size() < 2) {
            toast("路线丢了，请重新画一次路线");
            return;
        }
        // 位置权限没给的话服务起不来（FGS type=location 在某些 ROM 上会直接抛），
        // 与其静默失败，不如当场要一次。
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            toast("还差「位置权限」，允许后再点一次「开始自动」");
            requestPerms();
            return;
        }
        // 用 double 过 IPC：float32 在经度 116° 附近 ulp ≈ 1.5 米，整条轨迹会整体偏移
        double[] flat = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            flat[i * 2] = pts.get(i)[0];
            flat[i * 2 + 1] = pts.get(i)[1];
        }
        Intent i = new Intent(this, InjectService.class);
        i.setAction(InjectService.ACTION_START);
        i.putExtra(InjectService.EXTRA_POINTS, flat);
        // 目标里程固定 3000 米：跑不够会自己折返，够了就停
        double tgt = TARGET_M;
        log("目标里程 " + Math.round(tgt) + " 米，跑不够会自动折返，达到后停止");
        i.putExtra(InjectService.EXTRA_TARGET, tgt);
        i.putExtra(InjectService.EXTRA_PACE, num(etPace, 360));
        i.putExtra(InjectService.EXTRA_GCJ, 1);
        i.putExtra(InjectService.EXTRA_VIBRATE, 0);
        // 起点就是地图上自己选的那个点 —— 必须直接从它开始注入，
        // 不能从「当前实际位置」慢慢走过去，否则开头会看到一段穿墙轨迹。
        i.putExtra(InjectService.EXTRA_FROM_CURRENT, false);
        boolean closed = isClosedLoop();
        i.putExtra(InjectService.EXTRA_REPEAT,
                closed ? TrackPlayer.REPEAT_LOOP : TrackPlayer.REPEAT_PINGPONG);
        log(closed ? "路线识别为闭合环线 → 跑完一圈接着绕圈"
                : "路线识别为开放路线 → 跑完沿原路折返");
        final String statusBefore = InjectService.status;
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            log("已下发开始指令");
        } catch (Throwable t) {
            log("启动失败：" + t);
        }
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                refresh();
                // 门链全过了、指令也发了，服务却没起来 —— 把它写的原因透出来。
                // 否则用户点完「开始自动」界面一动不动，等于静默失败。
                if (!InjectService.running && !InjectService.status.equals(statusBefore)) {
                    String why = InjectService.status;
                    log(why);
                    toast(why);
                }
            }
        }, 1200);
    }

    private void stopInject() {
        Intent i = new Intent(this, InjectService.class);
        i.setAction(InjectService.ACTION_STOP);
        try { startService(i); } catch (Throwable ignore) { }
        log("已下发停止指令");
        // 服务是异步收到 STOP 的：隔一会儿再刷新，否则读到的还是旧状态
        h.postDelayed(new Runnable() {
            @Override public void run() { if (!destroyed) refresh(); }
        }, 600);
    }

    private void refresh() {
        if (routeView != null) routeView.setRoute(picked);
        if (tvTargetTxt != null) tvTargetTxt.setText("目标 " + Math.round(TARGET_M));
        // 只有「没在注入」时才把仪表盘归零：开跑后会 postDelayed(refresh, 800)
        // 来切状态，如果那时也归零，里程会先跳到 0 再被 ticker 刷回来 —— 闪一下。
        if (!InjectService.running) {
            if (routeView != null) routeView.setProgress(0f, false);
            if (tvDist != null) tvDist.setText("0");
        }
        if (!InjectService.running) {
            tvPaceC.setText("—");
            tvHealth.setText("待启动");
            tvHealth.setTextColor(Ui.INK_2);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_MAP) {
            if (res == RESULT_OK && data != null) log("已选 " + picked.size() + " 个路线点");
            refresh();
        } else if (req == REQ_OVERLAY) {
            // 从权限页回来：接着走链条。给了就往下走（通常直接开跑），
            // 没给的话下一轮 runNextGate 会发现还差悬浮窗，按"出去过一次"处理。
            refresh();
            leftForGate = false;
            continueChain();
        }
    }

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) need.add("android.permission.POST_NOTIFICATIONS");
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    /** 重新检查授权状态，把三项结果打进日志 */
    private void recheckAuth() {
        log("当前状态：模拟位置=" + (Sys.mockAllowed(this) ? "已授权" : "未授权")
                + "，写入系统设置=" + (Sys.hasWriteSecure(this) ? "已授权" : "未授权")
                + "，Shizuku=" + (ShizukuHelper.hasPermission() ? "已授权" : "未授权"));
        refresh();
    }

    /** Shizuku 一键授权：装好 Shizuku 并启动后，点一下就把所有授权做完（不用电脑） */
    private void shizukuSetup() {
        if (!ShizukuHelper.available()) {
            log("没检测到 Shizuku。");
            log("   请先安装 Shizuku 并用「无线调试」启动它（在手机上操作，不需要电脑）：");
            log("   设置 → 开发者选项 → 无线调试 → 打开");
            log("   打开 Shizuku → 通过无线调试启动 → 按提示输入配对码");
            log("   成功后再回来点这个按钮");
            return;
        }
        if (!ShizukuHelper.hasPermission()) {
            log("Shizuku 已运行，正在申请授权…请在弹窗里点「允许」");
            ShizukuHelper.request(REQ_SHIZUKU);
            return;
        }
        doShizukuSetup();
    }

    private void doShizukuSetup() {
        log("正在通过 Shizuku 执行授权（以 adb 身份）…");
        // adb 那几条命令是同步阻塞的（pm grant / appops set / settings put 串行执行），
        // 放主线程会 ANR —— 挪到工作线程，结果再回主线程打日志。
        new Thread(new Runnable() {
            @Override public void run() {
                String out;
                try {
                    out = ShizukuHelper.doFullSetup(getPackageName());
                } catch (Throwable t) {
                    out = "执行失败：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " " + t.getMessage());
                }
                final String text = out;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        log("---- Shizuku 输出 ----");
                        log(text);
                        log("---- 结束 ----");
                        log("请点「重新检查授权状态」确认上面三项是否都变成「已授权」");
                        refresh();
                    }
                });
            }
        }, "shizuku-setup").start();
    }

    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuResult =
            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQ_SHIZUKU) return;
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        log("Shizuku 授权成功，继续执行…");
                        doShizukuSetup();
                    } else {
                        log("Shizuku 授权被拒绝（请在 Shizuku 里允许「慢亿点」）");
                    }
                }
            };

    /** 只做振动测试：按 2 Hz 脉冲振 60 秒，不注入位置 */
    private void vibTest() {
        // 先立刻振一下，给即时反馈（能感觉到就说明权限/硬件没问题）
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) {
                log("取不到振动服务（Vibrator 为 null）");
            } else if (!v.hasVibrator()) {
                log("这台设备报：没有振动马达");
            } else if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(400, 255));
                log("已发出一次 400ms 振动 —— 你感觉到了吗？");
            } else {
                v.vibrate(400);
                log("已发出一次 400ms 振动 —— 你感觉到了吗？");
            }
        } catch (Throwable t) {
            log("振动调用失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
        }

        Intent i = new Intent(this, InjectService.class);
        i.setAction(InjectService.ACTION_VIBTEST);
        log("振动测试开始：按 2 Hz（每分钟 120 次）脉冲振动 60 秒");
        log("请在跑步软件计步的情况下观察步数有没有涨");
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            log("振动测试启动失败：" + t.getClass().getSimpleName());
            return;
        }
        h.postDelayed(new Runnable() {
            @Override public void run() {
                log("振动测试结果：" + InjectService.status);
                log("振动状态：" + InjectService.vibStatus);
            }
        }, 64000);
    }

    // 传感器测量的临时状态：onDestroy 里一定要显式注销。
    // 不能只靠那个 10 秒后的 postDelayed —— onDestroy 的
    // removeCallbacksAndMessages(null) 会把那个 Runnable 一起摘掉，
    // 监听器就永远留在 SensorManager 里，还钉住这个 Activity。
    private android.hardware.SensorManager testSm;
    private android.hardware.SensorEventListener testListener;

    /** 注销振动自检用的加速度监听器。重复调用安全 */
    private void stopSensorTest() {
        android.hardware.SensorManager sm = testSm;
        android.hardware.SensorEventListener l = testListener;
        testSm = null;
        testListener = null;
        if (sm != null && l != null) {
            try { sm.unregisterListener(l); } catch (Throwable ignore) { }
        }
    }

    /**
     * 用数据判断"振动能不能算成步数"：静置 2 秒取基线，再按 2 Hz 脉冲振动 8 秒，
     * 同时读加速度传感器，统计去掉重力后的幅值。
     * 参考：真人脚步冲击约 5~20 m/s²；计步算法阈值通常要 1~2 m/s² 以上。
     */
    private void accelTest() {
        final android.hardware.SensorManager sm =
                (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) { log("取不到传感器服务"); return; }
        final android.hardware.Sensor acc =
                sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        if (acc == null) { log("这台设备没有加速度传感器"); return; }
        stopSensorTest();                  // 连点两次时先注销上一轮的监听器，别叠着

        final float[] mx = new float[]{0f};
        final double[] sum = new double[]{0, 0};
        final int[] n = new int[]{0};

        final android.hardware.SensorEventListener l = new android.hardware.SensorEventListener() {
            @Override public void onSensorChanged(android.hardware.SensorEvent e) {
                double x = e.values[0], y = e.values[1], z = e.values[2];
                double a = Math.abs(Math.sqrt(x * x + y * y + z * z) - 9.81);
                if (a > mx[0]) mx[0] = (float) a;
                sum[0] += a;
                sum[1] += a * a;
                n[0]++;
            }
            @Override public void onAccuracyChanged(android.hardware.Sensor s, int i) { }
        };

        log("开始测量：静置 2 秒 → 振动 8 秒。手机请放在硬桌面上。");
        sm.registerListener(l, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        testSm = sm;
        testListener = l;

        h.postDelayed(new Runnable() {
            @Override public void run() {
                log("静置基线峰值 " + f2(mx[0]) + " m/s²（这是环境噪声，正常应该很小）");
                mx[0] = 0f;
                try {
                    android.os.Vibrator v =
                            (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        long[] pat = new long[]{0, 150, 350};
                        if (Build.VERSION.SDK_INT >= 26)
                            v.vibrate(android.os.VibrationEffect.createWaveform(pat, 0));
                        else v.vibrate(pat, 0);
                        log("正在振动（2 Hz 脉冲）…");
                    } else {
                        log("没有可用的振动马达");
                    }
                } catch (Throwable t) { log("振动调用失败：" + t); }
            }
        }, 2000);

        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    android.os.Vibrator v =
                            (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null) v.cancel();
                } catch (Throwable ignore) { }
                try { stopSensorTest(); } catch (Throwable ignore) { }

                double mean = n[0] > 0 ? sum[0] / n[0] : 0;
                double rms = n[0] > 0 ? Math.sqrt(sum[1] / n[0]) : 0;
                log("===== 振动强度测量结果 =====");
                log("样本数  ：" + n[0]);
                log("振动峰值：" + f2(mx[0]) + " m/s²");
                log("平均幅值：" + f2(mean) + " m/s²");
                log("RMS     ：" + f2(rms) + " m/s²");
                log("参考值  ：真人脚步冲击 5~20 m/s²；计步阈值通常要 1~2 m/s² 以上");
                if (mx[0] >= 2.0) {
                    log("→ 幅值够大：问题可能在算法/滤波，值得继续调");
                } else {
                    log("→ 幅值太小（<2 m/s²）：振动马达这条路基本走不通，建议用摇步器");
                }
            }
        }, 10000);
    }

    private static String f2(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    /** 自检：试着注入 10 秒，看系统是否真的放行 */
    private void selfTest() {
        double[] here = lastKnown();
        if (here == null) { toast("读不到当前位置，先去地图选点一次再回来"); return; }
        Intent i = new Intent(this, InjectService.class);
        i.setAction(InjectService.ACTION_TEST);
        i.putExtra(InjectService.EXTRA_CURLAT, here[0]);
        i.putExtra(InjectService.EXTRA_CURLON, here[1]);
        log("自检开始（把位置临时移到北方 30 米，10 秒后自动还原）…");
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            log("自检启动失败：" + t.getClass().getSimpleName());
            return;
        }
        h.postDelayed(new Runnable() {
            @Override public void run() {
                log("自检结果：" + InjectService.status);
                refresh();
            }
        }, 14000);
    }

    /** 路线是否闭合（首尾相距 <60 米）→ 决定跑完是绕圈还是折返 */
    private boolean isClosedLoop() {
        if (picked == null || picked.size() < 3) return false;
        double[] a = picked.get(0), b = picked.get(picked.size() - 1);
        return Geo.dist(a[0], a[1], b[0], b[1]) < 60;
    }

    /** 读手机当前真实位置（WGS-84）；读不到返回 null */
    private double[] lastKnown() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Throwable ignore) { }
            }
            if (best == null) return null;
            return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- 小工具 ----------
    private void log(String s) {
        tvLog.append(s + "\n");
        // 折叠时也把最新一条显示在标题上 —— 日志是排障入口，不该被完全藏进抽屉
        if (logHeadView != null) {
            String one = s.length() > 30 ? s.substring(0, 30) + "…" : s;
            String arrow = logHeadView.getText().toString().startsWith("▾") ? "▾" : "▸";
            logHeadView.setText(arrow + "  运行日志 · " + one);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String fmt(double d) { return String.format(java.util.Locale.US, "%.6f", d); }

    private double num(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }

    private int dp(int v) { return Ui.dp(v); }

    /* ═══════════════ 视图工厂（统一走 Ui 设计系统） ═══════════════
       原来这里写死颜色/字号/直角灰块，是"默认控件堆叠"的样子。
       现在全部换成「夜间跑道仪表」的令牌：沥青底、钠灯琥珀、等宽读数、
       发丝分隔线。签名保持兼容，所以所有调用点无需改动。 */

    /** 页面主标题 */
    private View title(String s) {
        TextView t = Ui.readout(this, s, 27f, Ui.INK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(Ui.S2);
        t.setLayoutParams(lp);
        return t;
    }

    /** 区块标题 */
    private View section(String s) { return Ui.head(this, s); }

    /** 字段标签 */
    private View lbl(String s) {
        TextView t = Ui.text(this, s, 15f, Ui.INK_2, null);
        t.setPadding(0, dp(14), 0, dp(7));
        return t;
    }

    /** 正文：全墨色、16sp */
    private TextView body(String s) {
        TextView t = Ui.text(this, s, 16f, Ui.INK, null);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    /** 正文：全墨色、16sp；里面的网址可点 */
    private TextView bodyLink(String s) {
        // 必须先把 autoLinkMask 设好再 setText，否则链不上
        TextView t = Ui.text(this, "", 16f, Ui.INK, null);
        t.setPadding(0, 0, 0, dp(4));
        t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        t.setLinkTextColor(Ui.ACCENT);
        t.setText(s);
        return t;
    }

    /** 次要说明 */
    private TextView hint(String s) {
        TextView t = Ui.text(this, s, 15f, Ui.INK_2, null);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    /** 数值输入：一块比底亮一档的实色面 */
    private EditText input(String def) {
        EditText e = new EditText(this);
        e.setText(def);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(Ui.INK);
        e.setTypeface(Ui.med());
        e.setTextSize(19f);
        e.setFontFeatureSettings("tnum");
        int q = dp(18);
        e.setPadding(q, dp(15), q, dp(15));
        e.setBackground(Ui.shape(Ui.SURFACE, 16f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(Ui.S2);
        e.setLayoutParams(lp);
        return e;
    }

    /** 按钮工厂：映射到设计系统的三个层级 */
    /* ══════════ 开跑前的两道检查：没给就弹窗，让用户在弹窗里把它补齐 ══════════ */

    /** 用户点了「稍后再说」：整条链作废，下次点开始自动重新走 */
    private void cancelChain() {
        pendingStart = false;
        gateWaited = GATE_NONE;
        leftForGate = false;
    }

    /** 第一道：开发者选项里的「模拟位置信息应用」有没有选本应用 */
    private void showMockDialog() {
        ConfirmDialog.show(this,
                "还差一步授权",
                "请到「开发者选项 → 选择模拟位置信息应用」里选上「慢亿点」。\n"
                        + "选好返回本页，会自动接着检查下一项。",
                "去开发者选项",
                "稍后再说",
                new ConfirmDialog.OnPick() {
                    @Override public void onConfirm() { openDevSettings(); }
                    @Override public void onCancel() { cancelChain(); }
                });
    }

    private void openDevSettings() {
        leftForGate = true;          // 这是"我们送用户去设置页"，回来要接着走链条
        leftForGateAt = System.currentTimeMillis();
        // 「给跑步软件放行」会把全局 development_settings_enabled 写成 0，开发者选项入口就没了。
        // 那样这里只会打开一个不可用的页面 —— 能写就先把它改回来。
        if (Sys.hasWriteSecure(this) && Sys.devModeEnabled(this) == 0) {
            Sys.setDevModeEnabled(this, true);
        }
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable t) {
            leftForGate = false;
            toast("请手动进入 设置 → 开发者选项");
        }
    }

    /** 第二道：有没有被加进电池优化白名单（不然后台会被冻结，位置就停了） */
    private boolean batteryOk() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return true;   // 读不到就不拦，免得误伤
        }
    }

    private void showBatteryDialog() {
        ConfirmDialog.show(this,
                "再差一步：后台",
                "没把本应用加入「电池优化白名单」的话，屏幕一灭位置就会停。\n"
                        + "允许后返回本页，会自动接着检查下一项。",
                "去允许",
                "稍后再说",
                new ConfirmDialog.OnPick() {
                    @Override public void onConfirm() { requestBattery(); }
                    @Override public void onCancel() { cancelChain(); }
                });
    }

    private void requestBattery() {
        leftForGate = true;
        leftForGateAt = System.currentTimeMillis();
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                leftForGate = false;
                toast("请手动：设置 → 电池 → 后台耗电管理");
            }
        }
    }

    /** 停止前先问一句：跑步真的结束了吗 */
    private void confirmStop() {
        if (!InjectService.running) { stopInject(); return; }
        ConfirmDialog.show(this,
                "先别急着关",
                "要在软件跑步提交结束后关闭，不然会瞬间瞬移回实际地址",
                "已经结束，停止",
                "稍等，还未结束",
                new ConfirmDialog.OnPick() {
                    @Override public void onConfirm() { stopInject(); }
                    @Override public void onCancel() { toast("好，继续跑"); }
                });
    }

    /** 悬浮调速条需要「显示在其他应用上层」。没有就引导去开，但不拦着开跑。 */
    private boolean hasOverlay() {
        return android.os.Build.VERSION.SDK_INT < 23
                || android.provider.Settings.canDrawOverlays(this);
    }

    private void ensureOverlay() {
        if (hasOverlay()) return;
        ConfirmDialog.show(this,
                "还差一个权限",
                "「显示在其他应用上层」还没给。没有它就没有悬浮调速条，所以先不给开跑。\n"
                        + "开启后返回本页，会自动接着检查下一项。",
                "去开启",
                "先不用",
                new ConfirmDialog.OnPick() {
                    @Override public void onConfirm() { openOverlaySettings(); }
                    @Override public void onCancel() { cancelChain(); }
                });
    }

    private void openOverlaySettings() {
        leftForGate = true;
        leftForGateAt = System.currentTimeMillis();
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable t2) {
                leftForGate = false;
                toast("请手动到 设置 → 应用 → 显示在其他应用上层 里允许本应用");
            }
        }
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("没有可用的浏览器，请手动打开 " + url);
        }
    }

    private View btn(String s, int oldColor, View.OnClickListener l) {
        TextView b;
        if (oldColor == 0xFF18A058)      b = Ui.primary(this, s);
        else if (oldColor == 0xFFB03030) b = Ui.danger(this, s);
        else if (oldColor == 0xFF555555) b = Ui.quiet(this, s);
        else                             b = Ui.secondary(this, s);
        b.setOnClickListener(l);
        // 按下缩一点 + 手感 —— 库里 "Active States" 和 "Haptic Feedback" 两条都要求
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start();
                    v.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    break;
            }
            return false;   // 返回 false，click 照常触发
        });
        return b;
    }

    /** 可折叠区块：头部一行（标题 + 计数/箭头），点一下展开/收起 */
    private LinearLayout collapsible(String head, View bodyView, boolean open) {
        LinearLayout box = Ui.col(this);
        final LinearLayout inner = Ui.col(this);
        inner.addView(bodyView);
        inner.setVisibility(open ? View.VISIBLE : View.GONE);

        final TextView headView = Ui.text(this, (open ? "▾  " : "▸  ") + head,
                16f, Ui.INK, Ui.med());
        headView.setPadding(0, dp(16), 0, dp(16));
        headView.setClickable(true);
        headView.setFocusable(true);
        headView.setBackground(Ui.shape(0x00000000, 10f));
        headView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean showing = inner.getVisibility() == View.VISIBLE;
                inner.setVisibility(showing ? View.GONE : View.VISIBLE);
                String raw = headView.getText().toString().substring(3);
                headView.setText((showing ? "▸  " : "▾  ") + raw);
            }
        });

        box.addView(headView);
        box.addView(inner);
        return box;
    }
}
