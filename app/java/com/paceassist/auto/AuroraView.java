package com.paceassist.auto;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 极光底 —— 全屏背景层。
 *
 * 高级感的来源不是"黑色"，是**一块活的、有层的光**：
 * 深靛蓝底 + 三团缓慢漂移的色彩光晕（品牌橙 / 配速绿 / 靛紫）。
 * Android 12 及以上再套一层 RenderEffect 真模糊，光就完全化开了；
 * 老系统上径向渐变本身就是软的，观感差不了多少。
 *
 * 纯 Canvas + 框架 API，不引任何库。
 */
public class AuroraView extends View {

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private float t = 0f;
    private long lastInvMs = 0;

    private static final int C_ORANGE = 0xFFEA580C;
    private static final int C_GREEN  = 0xFF059669;
    private static final int C_VIOLET = 0xFF4C1D95;

    public AuroraView(Context c) {
        super(c);
        base.setStyle(Paint.Style.FILL);
        // Android 12+：给整层背景来一次真模糊，光晕彻底化开
        if (Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(RenderEffect.createBlurEffect(46f, 46f, Shader.TileMode.CLAMP));
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(26000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            // 光晕是 26 秒一圈的极慢漂移，没必要 60fps 重绘：
            // 这层还挂着全屏 RenderEffect 模糊，60fps 重绘很费电。40ms 一帧完全看不出来。
            anim.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                long nowMs = android.os.SystemClock.uptimeMillis();
                if (nowMs - lastInvMs < 40) return;
                lastInvMs = nowMs;
                invalidate();
            });
        }
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();   // 离开界面就停，不空转耗电
    }

    /**
     * 本工具的**核心使用姿势**就是开跑后切到跑步软件 —— 那时本界面不可见，
     * 但动画器不会自己停，这层还挂着全屏 46px RenderEffect 模糊，
     * 25fps 重绘会让整场跑步一直在后台烧 GPU/电。窗口不可见就停掉。
     */
    @Override protected void onWindowVisibilityChanged(int vis) {
        super.onWindowVisibilityChanged(vis);
        if (anim == null) return;
        if (vis == View.VISIBLE) {
            if (!anim.isStarted()) { lastInvMs = 0; anim.start(); }
        } else {
            anim.cancel();
        }
    }

    private void softBlob(Canvas cv, float cx, float cy, float r, int color, int alpha) {
        blob.setShader(new RadialGradient(cx, cy, r,
                new int[]{withAlpha(color, alpha), withAlpha(color, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, blob);
    }

    private static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // 底：从上到下由深靛蓝渐变到近黑
        base.setShader(new LinearGradient(0, 0, w * 0.4f, h,
                new int[]{0xFF16213E, 0xFF0F172A, 0xFF080C16},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, base);

        // 三团光，各自按不同周期漂移 —— 周期互质，所以永远不会重复同一个画面
        float p = t * 6.2831853f;
        softBlob(cv,
                w * (0.22f + 0.10f * (float) Math.cos(p)),
                h * (0.20f + 0.06f * (float) Math.sin(p * 1.0f)),
                w * 0.78f, C_ORANGE, 128);
        softBlob(cv,
                w * (0.88f + 0.08f * (float) Math.sin(p * 0.7f + 1.7f)),
                h * (0.42f + 0.07f * (float) Math.cos(p * 0.9f)),
                w * 0.66f, C_VIOLET, 118);
        softBlob(cv,
                w * (0.35f + 0.12f * (float) Math.sin(p * 0.5f + 3.1f)),
                h * (0.86f + 0.05f * (float) Math.cos(p * 0.6f)),
                w * 0.72f, C_GREEN, 92);
    }
}
