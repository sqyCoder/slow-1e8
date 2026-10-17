package com.paceassist.auto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线主视图 —— 把"你要跑的这一条路"画成界面的主角。
 *
 * 路线来自用户在地图上点出的点；本视图把它等比投影到一块方形画布上，
 * 并画出三个状态：未跑（暗）、已跑（蓝）、当前位置（黄，带呼吸光晕）。
 *
 * 所有几何都用 Canvas 自绘，不依赖任何外部库，任何安卓机型都能跑。
 */
public class RouteView extends View {

    private final List<double[]> pts = new ArrayList<>();
    private float progress = 0f;
    private boolean running = false;

    private final Paint pTrack  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDone   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlow   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNode   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNodeIn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHint2  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();
    private final float[] sx = new float[0];
    private float[] px = new float[0];
    private float[] py = new float[0];
    /* 上次投影用的视图尺寸，尺寸变了要重算 */
    private int lastW = -1, lastH = -1;

    /* 当前位置的呼吸：跑的时候一下一下地扩，静态时不动 */
    private float pulse = 0f;
    private android.animation.ValueAnimator pulseAnim;
    /* 界面是否可见：切到跑步软件后不该继续 60fps 呼吸重绘 */
    private boolean windowVisible = true;

    @Override protected void onWindowVisibilityChanged(int vis) {
        super.onWindowVisibilityChanged(vis);
        windowVisible = (vis == View.VISIBLE);
        syncPulse();
    }

    private void syncPulse() {
        if (running && windowVisible && pulseAnim == null) {
            pulseAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            pulseAnim.setDuration(1500);
            pulseAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulseAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulseAnim.addUpdateListener(a -> {
                pulse = (float) a.getAnimatedValue();
                invalidate();
            });
            pulseAnim.start();
        } else if ((!running || !windowVisible) && pulseAnim != null) {
            pulseAnim.cancel();
            pulseAnim = null;
            pulse = 0f;
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulseAnim != null) { pulseAnim.cancel(); pulseAnim = null; }
    }

    public RouteView(Context c) {
        super(c);
        float w = Ui.dp(9);
        pTrack.setStyle(Paint.Style.STROKE);
        pTrack.setStrokeWidth(w);
        pTrack.setStrokeCap(Paint.Cap.ROUND);
        pTrack.setStrokeJoin(Paint.Join.ROUND);
        pTrack.setColor(Ui.TRACK);

        pDone.setStyle(Paint.Style.STROKE);
        pDone.setStrokeWidth(w);
        pDone.setStrokeCap(Paint.Cap.ROUND);
        pDone.setStrokeJoin(Paint.Join.ROUND);
        pDone.setColor(Ui.ACCENT);

        pGlow.setStyle(Paint.Style.FILL);
        pGlow.setColor(0x40F97316);
        pDot.setStyle(Paint.Style.FILL);
        pDot.setColor(Ui.INK);

        pNode.setStyle(Paint.Style.FILL);
        pNode.setColor(Ui.GROUND);
        pNodeIn.setStyle(Paint.Style.STROKE);
        pNodeIn.setStrokeWidth(Ui.dp(3f));
        pNodeIn.setColor(0xFF4A5568);

        pHint.setColor(0xFFCBD5E1);
        pHint.setTextAlign(Paint.Align.CENTER);
        pHint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        pHint.setTextSize(Ui.dp(20));
        pHint2.setColor(0xFF8A94A6);
        pHint2.setTextAlign(Paint.Align.CENTER);
        pHint2.setTextSize(Ui.dp(13));
    }

    /** 传入用户点出的路线（经纬度）；为空则显示提示语 */
    public void setRoute(List<double[]> route) {
        pts.clear();
        if (route != null) pts.addAll(route);
        px = new float[0]; py = new float[0];
        invalidate();
    }

    /** progress 0..1；running 决定当前位置点是否发光 */
    public void setProgress(float progress, boolean running) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.running = running;
        syncPulse();
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (h <= 0 || h == Integer.MAX_VALUE) h = (int) (w * 0.94f);
        setMeasuredDimension(w, h);
    }

    /** 把经纬度等比投影到视图内（修正纬度方向上的经度压缩） */
    private void project(int w, int h) {
        int n = pts.size();
        if (n == 0) return;
        // 尺寸变了必须重算：Manifest 声明了 configChanges，旋转/分屏不会重建 Activity，
        // 只按键点数当缓存键会让路线按旧尺寸投影、整体错位。
        if (px.length == n && lastW == w && lastH == h) return;

        float pad = Ui.dp(38);
        double minLat = 1e9, maxLat = -1e9, minLon = 1e9, maxLon = -1e9;
        for (double[] p : pts) {
            minLat = Math.min(minLat, p[0]); maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]); maxLon = Math.max(maxLon, p[1]);
        }
        double midLat = (minLat + maxLat) / 2.0;
        double kx = Math.cos(Math.toRadians(midLat));      // 经度在高纬度更"短"
        double spanX = (maxLon - minLon) * kx;
        double spanY = (maxLat - minLat);
        if (spanX <= 0 && spanY <= 0) { spanX = spanY = 1e-6; }

        float availW = w - pad * 2, availH = h - pad * 2;
        double scale = Math.min(availW / Math.max(spanX, 1e-9), availH / Math.max(spanY, 1e-9));
        double offX = (w - spanX * scale) / 2.0, offY = (h - spanY * scale) / 2.0;

        px = new float[n]; py = new float[n];
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            px[i] = (float) (offX + ((p[1] - minLon) * kx) * scale);
            py[i] = (float) (h - (offY + (p[0] - minLat) * scale));   // 纬度向上
        }
        lastW = w;
        lastH = h;
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (pts.size() < 2) {
            // 没有路线时也画一个"圈" + 中心提示：
            // 既不留一块空白，也预告"画完路线就是这个样子"（圈就是应用图标那个母题）
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w * 0.34f, h * 0.45f);
            r = Math.max(r, Ui.dp(58));
            pTrack.setColor(Ui.TRACK);
            cv.drawCircle(cx, cy, r, pTrack);
            RectF o = new RectF(cx - r, cy - r, cx + r, cy + r);
            pDone.setColor(Ui.ACCENT);
            cv.drawArc(o, -90f, 104f, false, pDone);
            cv.drawCircle(cx, cy - r, Ui.dp(6), pDot);
            cv.drawText("还没有路线", cx, cy + Ui.dp(4), pHint);
            pHint2.setColor(Ui.INK_2);
            cv.drawText("点下面的「画路线」", cx, cy + Ui.dp(28), pHint2);
            return;
        }
        project(w, h);
        int n = px.length;
        if (n < 2) return;

        // 1. 未跑的整条路
        path.reset();
        path.moveTo(px[0], py[0]);
        for (int i = 1; i < n; i++) path.lineTo(px[i], py[i]);
        cv.drawPath(path, pTrack);

        // 2. 已跑的部分：按累计长度截断
        float total = 0;
        float[] seg = new float[n - 1];
        for (int i = 1; i < n; i++) {
            seg[i - 1] = (float) Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
            total += seg[i - 1];
        }
        float want = total * progress;
        float acc = 0;
        float curX = px[0], curY = py[0];
        Path done = new Path();
        done.moveTo(px[0], py[0]);
        for (int i = 1; i < n && acc < want; i++) {
            float s = seg[i - 1];
            if (acc + s <= want) {
                done.lineTo(px[i], py[i]);
                curX = px[i]; curY = py[i];
                acc += s;
            } else {
                float t = s <= 0 ? 0 : (want - acc) / s;
                curX = px[i - 1] + (px[i] - px[i - 1]) * t;
                curY = py[i - 1] + (py[i] - py[i - 1]) * t;
                done.lineTo(curX, curY);
                acc = want;
            }
        }
        if (progress > 0.001f) cv.drawPath(done, pDone);

        // 3. 路线点
        for (int i = 0; i < n; i++) {
            boolean isCur = (i == 0 && progress <= 0.001f);
            cv.drawCircle(px[i], py[i], Ui.dp(7), pNode);
            pNodeIn.setColor(isCur ? Ui.INK : 0xFF3A4A63);
            cv.drawCircle(px[i], py[i], Ui.dp(7), pNodeIn);
        }

        // 4. 当前位置：黄点 + 呼吸光晕
        if (progress > 0.001f) {
            // 呼吸：光晕一边扩一边淡，像真的在跳
            pGlow.setColor((Math.round(0x44 - 0x22 * pulse) << 24) | 0x00EA580C);
            cv.drawCircle(curX, curY, Ui.dp(17 + 13 * pulse), pGlow);
            cv.drawCircle(curX, curY, Ui.dp(9), pDot);
            cv.drawCircle(curX, curY, Ui.dp(4), pNode);
        }
    }
}
