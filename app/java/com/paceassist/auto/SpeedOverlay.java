package com.paceassist.auto;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮配速条 —— 注入开着的时候浮在**所有应用之上**，切到跑步软件也还在。
 *
 * ══ 形态 ══
 *      ▬▬▬▬▬                     ← 抓手：只负责搬动整条（唯一可拖窗口的地方）
 *   ────────┼─────●───────         ← 滑轨：往右 = 更快，往左 = 更慢
 *   5'40" /km            快 20 秒   ← 读数
 *
 * ══ 为什么把「搬窗口」和「调速」分成两块 ══
 * 如果整条都能拖，拖动和调速就会抢同一次触摸 —— 这是这类悬浮控件最常见的 bug：
 * 想调速结果把条搬走了，或者想搬条结果配速被拖飞。
 * 所以刻意分成两个互不重叠的触摸区：**上面的抓手管搬，下面的滑轨管调速**。
 */
public class SpeedOverlay {

    public interface Host {
        /** 滑块给出的目标配速（秒/公里），已经是量化过的整档 */
        void onPace(double secPerKm);
        /** 点了中间的暂停 / 继续 */
        void onTogglePause();
    }

    private final Context ctx;
    private final Host host;
    private final WindowManager wm;

    private WindowManager.LayoutParams lp;
    private LinearLayout root;
    private SpeedSlider slider;
    private PauseButton pauseBtn;
    private TextView paceView, deltaView;
    private int lastShownPace = Integer.MIN_VALUE;
    private boolean shown;

    public SpeedOverlay(Context c, Host h) {
        this.ctx = c;
        this.host = h;
        this.wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean canShow() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void show(double basePace) {
        if (shown || wm == null || !canShow()) return;

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(10);
        root.setPadding(p, dp(6), p, p);
        root.setBackground(Ui.panelShape(26f));
        if (Build.VERSION.SDK_INT >= 28) root.setElevation(dp(16));

        // ── 抓手：唯一能搬窗口的地方 ──
        View grip = new View(ctx);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(46), dp(22));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        grip.setLayoutParams(glp);
        android.graphics.drawable.GradientDrawable bar = Ui.shape(0x59FFFFFF, 3f);
        android.graphics.drawable.LayerDrawable gripBg =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{
                                new android.graphics.drawable.ColorDrawable(0x00000000), bar});
        gripBg.setLayerInset(1, dp(11), dp(9), dp(11), dp(9));
        grip.setBackground(gripBg);
        root.addView(grip);

        // ── 滑轨：管调速 ──
        slider = new SpeedSlider(ctx);
        slider.setListener(p0 -> {
            if (host != null) host.onPace(p0);
            refreshReadout();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        root.addView(slider, slp);

        // ── 读数行 ──
        LinearLayout row = Ui.row(ctx);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        // 暂停放在读数行最左边 —— 拇指自然落点，又和滑轨的调速区完全不重叠
        pauseBtn = new PauseButton(ctx);
        LinearLayout.LayoutParams pbp =
                new LinearLayout.LayoutParams(dp(46), dp(46));
        pbp.rightMargin = dp(12);
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 先翻自己的图标，再通知服务 —— 手感要立刻有
                pauseBtn.setPaused(!pauseBtn.paused());
                if (pauseBtn.paused()) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
                host.onTogglePause();
            }
        });
        row.addView(pauseBtn, pbp);
        paceView = Ui.text(ctx, "--'--\" /km", 17f, Ui.INK, Ui.display());
        paceView.setLetterSpacing(-0.03f);
        row.addView(paceView);
        row.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        deltaView = Ui.text(ctx, "基准配速", 13.5f, Ui.INK_2, null);
        row.addView(deltaView);
        root.addView(row);

        slider.setBase(basePace);
        refreshReadout();

        lp = new WindowManager.LayoutParams(
                Math.max(dp(220), ctx.getResources().getDisplayMetrics().widthPixels - dp(28)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(28);

        attachShiftDrag(grip, true);
        // 读数区也能搬 —— 不用非得去戳上面那个小抓手
        attachShiftDrag(row, false);
        // 滑轨上竖着拖也能搬（横向才是调速），这样整条基本处处可拖
        slider.setMoveListener(new SpeedSlider.MoveListener() {
            @Override public void onMoveStart() { }
            @Override public void onMoveBy(float dx, float dy) { shift(dx, dy); }
            @Override public void onMoveEnd() { }
        });

        try {
            wm.addView(root, lp);
            shown = true;
        } catch (Throwable t) {
            shown = false;
        }
    }

    /** 拖窗口：抓手、读数区、以及滑轨的竖向拖动都走这里（整条基本都能搬） */
    private void attachShiftDrag(View v, final boolean fade) {
        final float[] last = new float[2];
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    last[0] = e.getRawX(); last[1] = e.getRawY();
                    if (fade) x.animate().alpha(1f).setDuration(90).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - last[0], dy = e.getRawY() - last[1];
                    last[0] = e.getRawX(); last[1] = e.getRawY();
                    shift(dx, dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (fade) x.animate().alpha(0.55f).setDuration(160).start();
                    return true;
                default:
                    return false;
            }
        });
        if (fade) v.setAlpha(0.55f);
    }

    /** 按手指位移搬窗口。BOTTOM 重力下 lp.y 是「距底距离」，手指往上 dy<0，所以要取反 */
    private void shift(float dx, float dy) {
        if (root == null || lp == null) return;
        lp.x += (int) dx;
        lp.y -= (int) dy;
        clampInScreen();
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignore) { }
    }

    /** 别让用户把条拖出屏幕外面去 —— 拖丢了就再也找不回来了 */
    private void clampInScreen() {
        if (root == null || lp == null) return;
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
        int vh = root.getHeight() > 0 ? root.getHeight() : dp(96);
        int maxUp = Math.max(0, sh - vh - dp(48));   // 顶部留 48dp，别顶到状态栏
        lp.y = Math.max(0, Math.min(maxUp, lp.y));
        // 窗口本身几乎满宽，横向只给一点余量
        lp.x = Math.max(-dp(20), Math.min(dp(20), lp.x));
    }

    /** 外部（服务）同步暂停态：比如通知栏/主界面点了，悬浮条也要跟着变 */
    public void setPausedState(boolean p) {
        if (pauseBtn != null) pauseBtn.setPaused(p);
        if (deltaView != null) {
            if (p) {
                deltaView.setText("已暂停");
                deltaView.setTextColor(Ui.ACCENT);
            } else {
                refreshReadout();
            }
        }
    }

    private void refreshReadout() {
        if (slider == null) return;
        int s = (int) Math.round(slider.pace());
        if (paceView != null) {
            paceView.setText(String.format(java.util.Locale.US, "%d'%02d\" /km", s / 60, s % 60));
            // 数字每变一档弹一下 —— 让人不用盯也能感觉到"又调了"
            if (s != lastShownPace) {
                boolean first = lastShownPace == Integer.MIN_VALUE;
                lastShownPace = s;
                if (!first) {
                    paceView.setScaleX(1.13f);
                    paceView.setScaleY(1.13f);
                    paceView.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                }
            }
        }
        if (deltaView != null) {
            // 暂停中别被拖动滑轨刷成「快/慢 N 秒」—— 那时位置是不动的
            if (pauseBtn != null && pauseBtn.paused()) {
                deltaView.setText("已暂停");
                deltaView.setTextColor(Ui.ACCENT);
                return;
            }
            int d = slider.deltaSec();
            if (d == 0) {
                deltaView.setText("基准配速");
                deltaView.setTextColor(Ui.INK_2);
            } else if (d > 0) {
                deltaView.setText("快 " + d + " 秒");
                deltaView.setTextColor(Ui.ACCENT);
            } else {
                deltaView.setText("慢 " + (-d) + " 秒");
                deltaView.setTextColor(Ui.INK_2);
            }
        }
    }

    /** 外部同步当前配速（开跑时对齐引擎） */
    public void setPace(double secPerKm) {
        if (slider == null) return;
        slider.setPace(secPerKm);
        refreshReadout();
    }

    public void hide() {
        if (!shown || wm == null || root == null) return;
        try { wm.removeView(root); } catch (Throwable ignore) { }
        root = null;
        slider = null;
        pauseBtn = null;
        paceView = null;
        deltaView = null;
        shown = false;
    }
}
