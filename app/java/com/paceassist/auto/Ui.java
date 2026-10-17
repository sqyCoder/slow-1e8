package com.paceassist.auto;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设计系统 —— 一个地面、一个墨色、一个强调色。
 *
 * 遵守的硬规则：
 *   · 代码块以外不用等宽字体 —— 数字靠「表格数字」对齐，而不是换字体
 *   · 不用细线/发丝分隔线 —— 分层靠色调差与留白
 *   · 不用序号（01/02、Step 2）—— 顺序由位置与文字表达
 *   · 静止元素不加阴影、不加辉光
 *   · 正文用全墨色、16sp；灰只给次要信息
 *   · 按钮扁平、单色填充、统一胶囊形
 *   · 一页只允许一个强调色
 */
public final class Ui {
    private Ui() {}

    /* ═══════ 色 · ui-ux-pro-max 针对 "Running & Cycling GPS" 的配色（正好是这个品类）═══════
       原话 Notes: "Energetic orange + pace green on dark" */

    public static final int GROUND     = 0xFF0F172A;   // 背景（深靛蓝，不是纯黑）
    public static final int SURFACE    = 0xFF201C27;   // muted
    public static final int CARD       = 0xFF192134;   // 卡片块
    public static final int TRACK      = 0xFF2C3A55;   // 轨道 / 未跑的路线
    public static final int INK        = 0xFFFFFFFF;   // 正文
    public static final int INK_2      = 0xFF94A3B8;   // muted-foreground
    public static final int ACCENT     = 0xFFEA580C;   // ★ energetic orange
    public static final int ON_ACCENT  = 0xFF000000;   // on-primary
    public static final int GREEN      = 0xFF059669;   // pace green
    public static final int OK         = 0xFF059669;
    public static final int ERR        = 0xFFDC2626;
    public static final int BORDER     = 0x14FFFFFF;   // rgba(255,255,255,.08)
    // 兼容旧名
    public static final int SURFACE_HI = CARD;

    /* ═══════════════ 尺度 ═══════════════ */

    private static float D = 2.75f;
    public static void init(Context c) { D = c.getResources().getDisplayMetrics().density; }
    public static int dp(float v) { return Math.round(v * D); }

    // 间距：风格要求「block layout with 48px+ gaps」，所以档位整体拉大
    public static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 28, S6 = 40, S7 = 56;

    /* ═══════════════ 字 ═══════════════ */

    private static Typeface MED, BOLD, DISP;
    public static Typeface med() {
        if (MED == null) MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return MED;
    }
    public static Typeface bold() {
        if (BOLD == null) BOLD = Typeface.create("sans-serif", Typeface.BOLD);
        return BOLD;
    }
    /** 显示字：窄体。字体库给运动类产品的配对是 Barlow Condensed，
     *  安卓自带的 sans-serif-condensed 是同一类（窄、紧凑、有速度感）。 */
    public static Typeface display() {
        if (DISP == null) DISP = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        return DISP;
    }

    /* ═══════════════ 基础件 ═══════════════ */

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setLineSpacing(dp(4), 1f);
        t.setFontFeatureSettings("tnum");   // 数字对齐靠表格数字，不靠换字体
        return t;
    }

    /** 读数：窄体 + 负字距，是页面上唯一"大"的东西 */
    public static TextView readout(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color, display());
        t.setLetterSpacing(-0.04f);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 次要说明 */
    public static TextView caption(Context c, CharSequence s) {
        return text(c, s, 14f, INK_2, null);
    }

    /** 正文：全墨色、16sp */
    public static TextView bodyTxt(Context c, CharSequence s) {
        return text(c, s, 16f, INK, null);
    }

    /* ═══════════════ 面 ═══════════════ */

    public static GradientDrawable shape(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 竖向渐变的面：一点光从上面来，面就有厚度，不再是一块死平的灰 */
    public static GradientDrawable vGrad(int top, int bottom, float radiusDp) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 面：只靠色调差分层，不用线、不用阴影、不用渐变 */
    public static android.graphics.drawable.Drawable litSurface(Context c, float radiusDp) {
        return shape(SURFACE_HI, radiusDp);
    }

    /* ═══════════ 玻璃 ═══════════
       库里 Glassmorphism 的规格：半透明白 15-30% + 1px 亮边 rgba(255,255,255,.2) + 层级深度。
       真正的"毛玻璃"由底层 AuroraView 的 RenderEffect 模糊提供，这里只负责那块玻璃本身。 */

    public static GradientDrawable glassShape(float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        // 深色底 62% 打住 —— 只用白 10% 太透，底下的极光会把字糊掉
        g.setColor(Color.argb(0x9E, 0x17, 0x1E, 0x2E));
        g.setStroke(Math.max(1, (int) dp(1f)), Color.argb(46, 255, 255, 255)); // 白 18%
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 实心面板：弹框、悬浮条这类「必须看清字」的地方用它，不用玻璃 */
    public static GradientDrawable panelShape(float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(Color.argb(0xF7, 0x14, 0x1B, 0x29));                        // 97% 实底
        g.setStroke(Math.max(1, (int) dp(1f)), Color.argb(58, 255, 255, 255)); // 白 23% 亮边
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 把一块 View 做成浮起来的玻璃：圆角轮廓 + 带色阴影 */
    public static void glass(View v, final float radiusDp, float elevDp) {
        v.setBackground(glassShape(radiusDp));
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setElevation(dp(elevDp));
        if (Build.VERSION.SDK_INT >= 28) {
            v.setOutlineAmbientShadowColor(Color.argb(120, 0, 0, 0));
            v.setOutlineSpotShadowColor(Color.argb(150, 0, 0, 0));
        }
    }

    /** 按下有反馈：缩一点 + 手感 + 水波纹。库里"Active States / Haptic Feedback"两条都要求。 */
    public static void pressable(View v, final Runnable action) {
        v.setOnClickListener(x -> {
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction(() -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                if (action != null) action.run();
            }).start();
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        });
    }

    /** 仪表面板：页面里唯一的抬起面，带上受光边 */
    public static LinearLayout panel(Context c) {
        LinearLayout p = col(c);
        glass(p, 28f, 10f);               // 浮起来的玻璃面
        int q = dp(S5);
        p.setPadding(q, q, q, q);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(S5);
        p.setLayoutParams(lp);
        return p;
    }

    /** 普通区块：靠留白分层，不画线 */
    public static LinearLayout block(Context c) {
        LinearLayout p = col(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(S5);
        p.setLayoutParams(lp);
        return p;
    }

    public static TextView head(Context c, String s) {
        TextView t = text(c, s, 19f, INK, med());
        t.setLetterSpacing(-0.01f);
        t.setPadding(0, 0, 0, dp(S3));
        return t;
    }

    /**
     * 状态行：左说是什么（次要色、15sp），右说现在怎么样（全墨色、16sp）。
     * 行间靠留白分开，不用分隔线。
     */
    public static LinearLayout statusRow(Context c, String label, String value, int valueColor) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(9), 0, dp(9));
        r.addView(text(c, label, 15f, INK_2, null),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(text(c, value, 16f, valueColor, med()));
        return r;
    }

    /* ═══════════════ 按钮（扁平、单色、统一胶囊） ═══════════════ */

    private static TextView button(Context c, String label, int fill, int fg) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(16f);
        b.setTypeface(med());
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(54));
        b.setPadding(dp(S4), dp(15), dp(S4), dp(15));
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                shape(fill, 27f), null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(S2);
        b.setLayoutParams(lp);
        return b;
    }

    /** 主行动：整页只应出现一次。实心浅玉 —— 全页唯一的强调色 */
    public static TextView primary(Context c, String s) {
        return button(c, s, ACCENT, ON_ACCENT);
    }
    public static TextView secondary(Context c, String s) { return button(c, s, SURFACE, INK); }
    public static TextView quiet(Context c, String s) { return button(c, s, SURFACE, INK_2); }
    public static TextView danger(Context c, String s) { return button(c, s, SURFACE, ERR); }

    /* ═══════════════ 状态点与进度 ═══════════════ */

    public static TextView led(Context c, String s, int color) {
        return text(c, s, 15f, color, med());
    }

    public static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        v.setBackground(shape(color, sizeDp / 2f));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    public static View[] progress(Context c) {
        LinearLayout track = new LinearLayout(c);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(shape(TRACK, 4f));
        View fill = new View(c);
        fill.setBackground(shape(ACCENT, 4f));
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(8), 0f));
        return new View[]{track, fill};
    }

    public static void setProgress(View[] bar, double ratio) {
        View track = bar[0], fill = bar[1];
        int w = track.getWidth();
        if (w <= 0) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) fill.getLayoutParams();
        lp.width = (int) (w * Math.max(0, Math.min(1, ratio)));
        lp.weight = 0f;
        fill.setLayoutParams(lp);
    }
}
