package com.paceassist.auto;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 开场动画 —— 一圈弧自己画出来，然后名字浮上来，最后整块推近淡出。
 *
 * 全部 Canvas 自绘 + 一个 ValueAnimator，不依赖 Lottie / 任何第三方库，
 * 也不会因为机型不同播不出来（VectorDrawable 的 trimPath 在老 WebView 内核上就翻过车）。
 *
 * 时间轴（总 1500ms，t 为归一化进度）：
 *   0.00 – 0.34  弧从 0° 扫到 280°（减速，像有惯性）
 *   0.13 – 0.27  弧尖的光点长出来
 *   0.34 – 0.52  名字「慢亿点」往上浮 12dp 并淡入
 *   0.44 – 0.60  副标题淡入
 *   0.78 – 1.00  整体放大到 1.05 并线性淡出，把主界面交出来
 *
 * 为什么是 1500ms 而不是 2100ms：超过 1.5 秒人就会觉得"卡住"。
 * 为什么淡出用线性而不是减速：减速曲线在前 30% 就把亮度掉光，
 * 后面还有大半段时间在"画一个几乎看不见的东西"，看起来就像动画结束了还留了层残影。
 */
public class SplashView extends View {

    public interface OnDone { void onDone(); }

    private static final long TOTAL = 1500L;
    // 每帧都要用，别在 seg() 里 new —— 那是每秒 60 次的垃圾分配
    private static final DecelerateInterpolator DEC = new DecelerateInterpolator(1.6f);

    private final Paint pBg     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlowBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrack  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pArc    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTip    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTipIn  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTipGlow= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pName   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub    = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float t = 0f;                 // 0..1 总进度
    private OnDone done;
    private ValueAnimator anim;
    private boolean finished = false;

    private final RectF arcRect = new RectF();

    public SplashView(Context c) {
        super(c);
        setClickable(true);          // 吃掉这一层的点击，别漏到底下的界面
        setFocusable(true);

        pBg.setColor(Ui.GROUND);

        float sw = Ui.dp(11f);
        pTrack.setStyle(Paint.Style.STROKE);
        pTrack.setStrokeWidth(sw);
        pTrack.setColor(0x1AFFFFFF);

        pArc.setStyle(Paint.Style.STROKE);
        pArc.setStrokeWidth(sw);
        pArc.setStrokeCap(Paint.Cap.ROUND);

        pTip.setStyle(Paint.Style.FILL);
        pTip.setColor(0xFFFFFFFF);
        pTipIn.setStyle(Paint.Style.FILL);
        pTipIn.setColor(Ui.ACCENT);

        pTipGlow.setStyle(Paint.Style.FILL);

        pName.setColor(Ui.INK);
        pName.setTextAlign(Paint.Align.CENTER);
        pName.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        pName.setTextSize(Ui.dp(44f));
        pName.setLetterSpacing(-0.045f);

        pSub.setColor(Ui.INK_2);
        pSub.setTextAlign(Paint.Align.CENTER);
        pSub.setTextSize(Ui.dp(13.5f));
        pSub.setLetterSpacing(0.04f);
    }

    public void setOnDone(OnDone d) { this.done = d; }

    public void start() {
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(TOTAL);
        anim.setInterpolator(null);                 // 各段自己缓动，别叠一层
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
            if (t >= 0.9995f && !finished) {
                finished = true;
                if (done != null) done.onDone();
            }
        });
        anim.start();
    }

    public void stopNow() {
        if (anim != null) { anim.cancel(); anim = null; }
        if (!finished) { finished = true; if (done != null) done.onDone(); }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) { anim.cancel(); anim = null; }
    }

    /** 把 x 从 [a,b] 映射到 0..1，再做减速缓动 */
    private static float seg(float v, float a, float b) {
        if (b <= a) return v >= b ? 1f : 0f;
        float k = (v - a) / (b - a);
        if (k <= 0f) return 0f;
        if (k >= 1f) return 1f;
        return DEC.getInterpolation(k);
    }

    /** 整块内容的"离场"变换：放大 + 淡出。线性，末尾干脆收干净 */
    private float exitK() {
        if (t <= 0.78f) return 0f;
        float k = (t - 0.78f) / 0.22f;
        return k >= 1f ? 1f : k;
    }
    private float exitScale() { return 1f + 0.05f * exitK(); }
    private float exitAlpha() {
        float a = 1f - exitK();
        return a < 0f ? 0f : a;
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;

        // 底：整块铺满，跟着内容一起淡出，交接给主界面（硬切会闪一下）
        float ea = exitAlpha();
        if (ea > 0.001f) {
            pBg.setAlpha((int) (255 * ea));
            cv.drawRect(0, 0, w, h, pBg);
        }
        if (ea <= 0.001f) return;

        float k = exitScale();
        int save = cv.save();
        cv.scale(k, k, cx, cy);

        // 圈 + 下面两行字是一个整体：圈心往上抬一点，整体才是视觉居中
        float R = Math.min(w, h) * 0.225f;
        float ringCy = cy - Ui.dp(55f);
        arcRect.set(cx - R, ringCy - R, cx + R, ringCy + R);

        // 1) 背后的橙色氛围光，随进度慢慢亮起来
        float amb = seg(t, 0.02f, 0.36f);
        if (amb > 0.001f) {
            pGlowBg.setShader(new RadialGradient(cx, ringCy, R * 2.2f,
                    new int[]{ ((int) (0x44 * amb) << 24) | 0x00EA580C,
                               0x00EA580C },
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, ringCy, R * 2.2f, pGlowBg);
            pGlowBg.setShader(null);
        }

        // 2) 灰底环（一直都在，给弧一个"未完待续"的参照）
        float ta = 0.55f + 0.45f * seg(t, 0f, 0.16f);
        pTrack.setAlpha((int) (0x1A * ta * ea));
        cv.drawCircle(cx, ringCy, R, pTrack);

        // 3) 主弧：0 → 280°
        float sweep = 280f * seg(t, 0f, 0.34f);
        if (sweep > 0.5f) {
            pArc.setShader(new LinearGradient(cx - R, ringCy - R, cx + R, ringCy + R,
                    new int[]{ 0xFFFB923C, Ui.ACCENT, 0xFFC2410C },
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            cv.drawArc(arcRect, -90f, sweep, false, pArc);
            pArc.setShader(null);
        }

        // 4) 弧尖的光点（跟着弧一起长出来）
        float tp = seg(t, 0.13f, 0.27f);
        if (tp > 0.001f) {
            double rad = Math.toRadians(-90.0 + sweep);
            float tx = (float) (cx + R * Math.cos(rad));
            float ty = (float) (ringCy + R * Math.sin(rad));

            pTipGlow.setShader(new RadialGradient(tx, ty, Ui.dp(26),
                    new int[]{ 0x88FFFFFF, 0x33EA580C, 0x00EA580C },
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(tx, ty, Ui.dp(26), pTipGlow);
            pTipGlow.setShader(null);

            float rr = Ui.dp(7f) * tp;
            pTip.setAlpha((int) (255 * ea));
            cv.drawCircle(tx, ty, rr, pTip);
            pTipIn.setAlpha((int) (255 * ea));
            cv.drawCircle(tx, ty, rr * 0.42f, pTipIn);
        }

        // 5) 名字：往上浮 12dp + 淡入
        float np = seg(t, 0.34f, 0.52f);
        if (np > 0.001f) {
            float dy = Ui.dp(12f) * (1f - np);
            pName.setAlpha((int) (255 * np * ea));
            cv.drawText("慢亿点", cx, ringCy + R + Ui.dp(66f) + dy, pName);
        }

        // 6) 副标题
        float sp2 = seg(t, 0.44f, 0.60f);
        if (sp2 > 0.001f) {
            float dy = Ui.dp(10f) * (1f - sp2);
            pSub.setAlpha((int) (255 * sp2 * ea));
            cv.drawText("跑步配速 · 轨迹自动注入", cx,
                    ringCy + R + Ui.dp(102f) + dy, pSub);
        }

        cv.restoreToCount(save);
    }
}
