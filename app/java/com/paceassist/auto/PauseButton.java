package com.paceassist.auto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 悬浮条上的「暂停 / 继续」按钮。自己画，不依赖字体里的 ⏸ ▶ 字形
 * （不同手机字体覆盖不一样，用字符迟早有一台显示成方块）。
 *
 * 运行中 = 深底 + 白色双竖条（意思是"按我就能停"）
 * 已暂停 = 橙色实心 + 深色三角（意思是"按我继续跑"）
 */
public class PauseButton extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private final RectF rect = new RectF();
    private boolean paused;

    public PauseButton(Context c) {
        super(c);
        bg.setStyle(Paint.Style.FILL);
        fg.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(false);
    }

    public void setPaused(boolean p) {
        if (paused == p) return;
        paused = p;
        invalidate();
    }

    public boolean paused() { return paused; }

    @Override protected void onMeasure(int w, int h) {
        int s = Ui.dp(46);
        setMeasuredDimension(s, s);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;

        bg.setColor(paused ? Ui.ACCENT : 0xFF2B3546);
        c.drawCircle(cx, cy, r, bg);

        fg.setColor(paused ? 0xFF120D00 : 0xFFFFFFFF);
        if (!paused) {
            // 双竖条
            float bw = r * 0.16f, bh = r * 0.78f, gap = r * 0.24f;
            float rr = bw * 0.42f;
            rect.set(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f);
            c.drawRoundRect(rect, rr, rr, fg);
            rect.set(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f);
            c.drawRoundRect(rect, rr, rr, fg);
        } else {
            // 右向三角：整体右移一点，视觉重心才落在圆心
            float t = r * 0.50f;
            tri.reset();
            tri.moveTo(cx - t * 0.58f, cy - t);
            tri.lineTo(cx + t * 0.88f, cy);
            tri.lineTo(cx - t * 0.58f, cy + t);
            tri.close();
            c.drawPath(tri, fg);
        }
    }
}
