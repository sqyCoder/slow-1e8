package com.paceassist.auto;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * 配速滑轨 —— 悬浮条的核心交互，整个工具里被摸得最多的控件。
 *
 * ══ 语义 ══
 * **往右拖 = 更快**，往左拖 = 更慢，中间是「配速」里设的基准配速。
 * 左右各 90 秒/公里，每 10 秒一档。
 *
 * ══ 触摸仲裁（解决两个真实抱怨）══
 *  ① **误触**：以前手指一碰滑轨配速就跳。现在按下**什么都不做**——
 *     只有横向移动超过系统 touchSlop 才真的开始调速。单击、轻扫、滑过去看
 *     都不会再改配速。
 *  ② **「搬窗口要按中间那个小抓手」**：现在**竖着拖滑轨也能搬**，
 *     加上整条读数区和抓手都能搬 —— 除了"横向拖滑轨"，几乎整条都能搬。
 *     判断规则：按下后先看往哪个方向走，横的调速、竖的搬窗口。
 *
 * ══ 视觉（对着 micro-interaction 案例做的）══
 *  · **拇指有质量** —— 不完全跟手，慢半拍追过去，看起来像有重量的实体
 *  · **拖尾** —— 拇指身后拖一段渐隐光带，越快越长
 *  · **持续涟漪** —— 拇指外面一圈光晕一波波往外扩
 *  · **过档火花** —— 每越过一档，在那个刻度位置炸开一个扩散圆环
 *  · **色温** —— 越快越偏橙红，越慢越偏冷蓝，拖的时候一眼看出方向
 *  · **刻度点亮** —— 越过的刻度变橙变长
 */
public class SpeedSlider extends View {

    /** 配速变化回调 */
    public interface Listener {
        void onPace(double secPerKm);
    }

    /** 让滑轨也能搬动整个悬浮窗口 */
    public interface MoveListener {
        void onMoveStart();
        void onMoveBy(float dx, float dy);
        void onMoveEnd();
    }

    private static final double SPAN = 90.0;   // 基准左右各 90 秒/公里
    private static final double STEP = 10.0;   // 每档 10 秒

    private static final int MODE_NONE = 0;
    private static final int MODE_PENDING = 1;   // 按下了，还没判断方向
    private static final int MODE_PACE = 2;      // 横向拖：调速
    private static final int MODE_MOVE = 3;      // 竖向拖：搬窗口

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFillGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTickHot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSpark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    private double basePace = 360.0;
    private double minPace = 270.0, maxPace = 450.0;   // minPace = 最快（数字最小）
    private double pace = 360.0;
    private float frac = 0.5f;          // 手指要的拇指位置 0..1（1 = 最快）
    private float renderFrac = 0.5f;    // 实际画出来的位置：慢半拍追 frac，制造"质量感"
    private float trailFrom = 0.5f;     // 拖尾起点（上一帧的 renderFrac）
    private boolean dragging = false;
    private boolean pressed = false;
    private int lastStep = Integer.MIN_VALUE;
    private Listener listener;
    private MoveListener mover;

    private int mode = MODE_NONE;
    private float downX, downY, lastMoveRawX, lastMoveRawY;
    private final int touchSlop;

    /** 松手回弹的临时位移 */
    private float springDx = 0f;
    private ValueAnimator springAnim;

    /** 拖动时的持续呼吸相位（驱动涟漪） */
    private float ripplePhase = 0f;
    private ValueAnimator rippleAnim;

    /** 过档火花 */
    private static final class Spark {
        float x; long born;
        Spark(float x, long born) { this.x = x; this.born = born; }
    }
    private final ArrayList<Spark> sparks = new ArrayList<>();

    private final float trackH, thumbR, padX, padV;
    private Vibrator vib;

    public SpeedSlider(Context c) {
        super(c);
        trackH = Ui.dp(14);
        thumbR = Ui.dp(17);
        padX = thumbR + Ui.dp(3);
        padV = Ui.dp(20);
        touchSlop = Math.max(8, ViewConfiguration.get(c).getScaledTouchSlop());

        pTrack.setColor(0x33FFFFFF);
        pFill.setColor(Ui.ACCENT);
        pTick.setColor(0x40FFFFFF);
        pTickHot.setColor(Ui.ACCENT);
        pTickHot.setStrokeWidth(Math.max(1, Ui.dp(2f)));
        pTick.setStrokeWidth(Math.max(1, Ui.dp(1.5f)));
        pCenter.setColor(0xCCFFFFFF);
        pCenter.setStrokeWidth(Ui.dp(2));
        pThumb.setColor(0xFFFFFFFF);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(Ui.dp(3.5f));
        pRing.setColor(Ui.ACCENT);
        pSpark.setStyle(Paint.Style.STROKE);
        pSpark.setStrokeWidth(Ui.dp(2.5f));

        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) vib = vm.getDefaultVibrator();
        }
        if (vib == null) vib = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setMoveListener(MoveListener l) { this.mover = l; }

    /** 基准配速，决定滑轨的中心 */
    public void setBase(double secPerKm) {
        basePace = clamp(secPerKm, 150.0, 1200.0);
        minPace = clamp(basePace - SPAN, 150.0, 1200.0);
        maxPace = clamp(basePace + SPAN, 150.0, 1200.0);
        setPace(basePace);
    }

    /** 外部设定当前配速 */
    public void setPace(double p) {
        pace = clamp(p, 150.0, 1200.0);
        double span = Math.max(1.0, maxPace - minPace);
        frac = (float) clamp((maxPace - pace) / span, 0.0, 1.0);
        renderFrac = frac;
        trailFrom = frac;
        lastStep = stepOf(pace);
        invalidate();
    }

    public double pace() { return pace; }

    /** 相对基准快了多少秒（正数=更快） */
    public int deltaSec() { return (int) Math.round(basePace - pace); }

    private float baseFrac() {
        return (float) clamp((maxPace - basePace) / Math.max(1.0, maxPace - minPace), 0.0, 1.0);
    }

    private int maxStep() {
        return (int) Math.round(Math.max(basePace - minPace, maxPace - basePace) / STEP);
    }

    private static double clamp(double v, double a, double b) { return v < a ? a : (v > b ? b : v); }
    private int stepOf(double p) { return (int) Math.round((basePace - p) / STEP); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (padV * 2 + Math.max(trackH, thumbR * 2)));
    }

    /* ═══════════════ 触感 ═══════════════ */

    private void buzzStep(int step) {
        int ms = maxStep();
        float t = ms <= 0 ? 0f : Math.min(1f, Math.abs(step) / (float) ms);
        vibrate(14, (int) (70 + 130 * t));
    }
    private void buzzBase() { vibrate(9, 235); }
    private Runnable buzzEndR;
    private void buzzEnd() {
        vibrate(7, 120);
        // 55ms 后的第二下必须能被取消：拖到端点松手后悬浮条可能马上被 hide()，
        // 那一下多余振动会落在服务已经停了之后。
        if (buzzEndR != null) removeCallbacks(buzzEndR);
        buzzEndR = new Runnable() {
            @Override public void run() { buzzEndR = null; vibrate(7, 120); }
        };
        postDelayed(buzzEndR, 55);
    }
    private void buzzGrab() { vibrate(8, 150); }

    private void vibrate(int ms, int amplitude) {
        try {
            if (vib == null || !vib.hasVibrator()) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return;
            }
            vib.vibrate(VibrationEffect.createOneShot(ms, Math.max(1, Math.min(255, amplitude))));
        } catch (Throwable ignore) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /* ═══════════════ 绘制 ═══════════════ */

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cy = h / 2f;
        float x0 = padX, x1 = w - padX;
        float span = x1 - x0;

        // 拇指带一点弹簧位移
        float tx = x0 + renderFrac * span + springDx;

        // ── 1. 轨道 ──
        rf.set(x0, cy - trackH / 2, x1, cy + trackH / 2);
        float r = trackH / 2;
        cv.drawRoundRect(rf, r, r, pTrack);

        // ── 2. 基准位到拇指的填充：颜色随快慢变温 ──
        float cx = x0 + baseFrac() * span;
        float delta = tx - cx;                      // >0 = 更快
        if (Math.abs(delta) > 1f) {
            rf.set(Math.min(tx, cx), cy - trackH / 2, Math.max(tx, cx), cy + trackH / 2);

            // 色温：快 → 橙红 #FF4D2E，慢 → 冷蓝 #38BDF8，基准 → 品牌橙
            int col = tempColor(delta / Math.max(1f, span * 0.5f));
            pFill.setColor(col);

            if (dragging) {
                // 发光底：比轨道粗一圈、很淡，模拟"blurred glowing track"
                pFillGlow.setColor(withAlpha(col, 0x33));
                rf.set(Math.min(tx, cx), cy - trackH * 0.95f,
                       Math.max(tx, cx), cy + trackH * 0.95f);
                cv.drawRoundRect(rf, trackH, trackH, pFillGlow);
                rf.set(Math.min(tx, cx), cy - trackH / 2, Math.max(tx, cx), cy + trackH / 2);
            }
            cv.drawRoundRect(rf, r, r, pFill);
        }

        // ── 3. 刻度 ──
        int steps = (int) Math.round((maxPace - minPace) / STEP);
        for (int i = 0; i <= steps; i++) {
            double p = minPace + i * STEP;
            float f = (float) ((maxPace - p) / Math.max(1.0, maxPace - minPace));
            float x = x0 + f * span;
            if (Math.abs(p - basePace) < 0.5) {
                cv.drawLine(x, cy - trackH / 2 - Ui.dp(6), x, cy + trackH / 2 + Ui.dp(6), pCenter);
                continue;
            }
            boolean passed = (tx > cx && x > cx && x < tx) || (tx < cx && x < cx && x > tx);
            Paint pt = passed ? pTickHot : pTick;
            float len = passed ? Ui.dp(5) : Ui.dp(3);
            cv.drawLine(x, cy - trackH / 2 - len, x, cy - trackH / 2 - Ui.dp(1), pt);
            cv.drawLine(x, cy + trackH / 2 + Ui.dp(1), x, cy + trackH / 2 + len, pt);
        }

        // ── 4. 拖尾：拇指身后拖一段渐隐光带 ──
        if (dragging && Math.abs(renderFrac - trailFrom) > 0.002f) {
            float fx = x0 + trailFrom * span;
            float gap = tx - fx;
            float len = Math.min(Math.abs(gap), Ui.dp(34));
            if (len > 2f) {
                float s = tx - Math.signum(gap) * len;
                int c1 = withAlpha(tempColor(delta / Math.max(1f, span * 0.5f)), 0x77);
                int c2 = withAlpha(tempColor(delta / Math.max(1f, span * 0.5f)), 0x00);
                pTrail.setShader(new android.graphics.LinearGradient(
                        tx, cy, s, cy, c1, c2, Shader.TileMode.CLAMP));
                rf.set(Math.min(tx, s), cy - trackH / 2 - Ui.dp(2),
                       Math.max(tx, s), cy + trackH / 2 + Ui.dp(2));
                cv.drawRoundRect(rf, r + Ui.dp(2), r + Ui.dp(2), pTrail);
                pTrail.setShader(null);
            }
        }

        // ── 5. 过档火花 ──
        long now = SystemClock.uptimeMillis();
        for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
            Spark sp = it.next();
            float t = (now - sp.born) / 340f;
            if (t >= 1f) { it.remove(); continue; }
            float rad = Ui.dp(5) + Ui.dp(20) * t;
            pSpark.setColor(withAlpha(Ui.ACCENT, (int) (0x99 * (1f - t))));
            cv.drawCircle(sp.x, cy, rad, pSpark);
        }
        if (!sparks.isEmpty()) invalidate();

        // ── 6. 拇指：涟漪 + 光晕 ──
        float rr = dragging ? thumbR * 1.16f : (pressed ? thumbR * 1.06f : thumbR);

        if (dragging) {
            // 持续涟漪：两波错开，一波接一波往外扩
            for (int k = 0; k < 2; k++) {
                float ph = (ripplePhase + k * 0.5f) % 1f;
                float rad = rr * (1.15f + 1.9f * ph);
                pGlow.setShader(new RadialGradient(tx, cy, Math.max(1f, rad),
                        new int[]{withAlpha(tempColor(delta / Math.max(1f, span * 0.5f)), (int) (0x55 * (1 - ph))), 0x00000000},
                        new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
                cv.drawCircle(tx, cy, rad, pGlow);
            }
        } else if (pressed) {
            pGlow.setShader(new RadialGradient(tx, cy, rr * 2.2f,
                    new int[]{0x33EA580C, 0x00EA580C}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(tx, cy, rr * 2.2f, pGlow);
        }
        pGlow.setShader(null);

        cv.drawCircle(tx, cy, rr, pThumb);
        cv.drawCircle(tx, cy, rr - pRing.getStrokeWidth() / 2f, pRing);
    }

    /** deltaN ∈ [-1,1]：>0 更快（暖），<0 更慢（冷） */
    private static int tempColor(float deltaN) {
        float t = Math.max(-1f, Math.min(1f, deltaN));
        int hot = 0xFFFF4D2E;   // 橙红
        int mid = 0xFFEA580C;   // 品牌橙
        int cool = 0xFF38BDF8;  // 冷蓝
        return t >= 0 ? mix(mid, hot, t) : mix(mid, cool, -t);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return Color.rgb((int) (ar + (br - ar) * t), (int) (ag + (bg - ag) * t), (int) (ab + (bb - ab) * t));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /* ═══════════════ 触摸仲裁 ═══════════════ */

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                mode = MODE_PENDING;
                downX = e.getRawX(); downY = e.getRawY();
                lastMoveRawX = downX; lastMoveRawY = downY;
                cancelSpring();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float rx = e.getRawX(), ry = e.getRawY();
                float dx = rx - downX, dy = ry - downY;

                if (mode == MODE_PENDING) {
                    if (Math.hypot(dx, dy) < touchSlop) return true;   // 还没动够，继续等
                    // 横的 → 调速；竖的 → 搬窗口
                    if (Math.abs(dx) > Math.abs(dy)) {
                        mode = MODE_PACE;
                        dragging = true;
                        buzzGrab();
                        startRipple();
                    } else {
                        mode = MODE_MOVE;
                        if (mover != null) mover.onMoveStart();
                    }
                }

                if (mode == MODE_PACE) {
                    moveThumb(e.getX());
                } else if (mode == MODE_MOVE) {
                    if (mover != null) mover.onMoveBy(rx - lastMoveRawX, ry - lastMoveRawY);
                    lastMoveRawX = rx; lastMoveRawY = ry;
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == MODE_PACE) {
                    // 落位到量化后的档
                    dragging = false;
                    lastStep = Integer.MIN_VALUE;
                    startSpring();
                    stopRipple();
                } else if (mode == MODE_MOVE) {
                    if (mover != null) mover.onMoveEnd();
                } else {
                    // MODE_PENDING：这是一次单击 / 误触 —— **什么都不做**，配速不变
                }
                pressed = false;
                mode = MODE_NONE;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    /** 拇指跟手（连续），配速量化到 10 秒档 */
    private void moveThumb(float x) {
        float x0 = padX, x1 = getWidth() - padX;
        if (x1 <= x0) return;
        float prev = frac;
        frac = (float) clamp((x - x0) / (x1 - x0), 0.0, 1.0);

        double raw = maxPace - frac * (maxPace - minPace);
        int step = (int) Math.round((basePace - raw) / STEP);
        double snapped = clamp(basePace - step * STEP, 150.0, 1200.0);

        boolean hitEnd = frac <= 0.0001f || frac >= 0.9999f;
        boolean wasEnd = prev <= 0.0001f || prev >= 0.9999f;

        if (step != lastStep) {
            lastStep = step;
            if (step == 0) buzzBase();
            else if (hitEnd && !wasEnd) buzzEnd();
            else buzzStep(step);

            // 过档火花：在那个刻度的位置炸一个
            float span = x1 - x0;
            float xf = x0 + baseFrac() * span
                     + (float) ((basePace - (basePace - step * STEP)) / Math.max(1.0, maxPace - minPace) * span);
            sparks.add(new Spark(xf, SystemClock.uptimeMillis()));
            if (sparks.size() > 8) sparks.remove(0);
        }
        if (snapped != pace) {
            pace = snapped;
            if (listener != null) listener.onPace(pace);
        }
        invalidate();
    }

    /* ═══════════════ 动画 ═══════════════ */

    /** 松手回弹 + 拇指"追上"目标位置 */
    private void startSpring() {
        cancelSpring();
        springAnim = ValueAnimator.ofFloat(0f, 1f);
        springAnim.setDuration(300);
        springAnim.setInterpolator(new OvershootInterpolator(2.4f));
        springAnim.addUpdateListener(a -> {
            float p = (Float) a.getAnimatedValue();
            springDx = Ui.dp(4.5f) * p;
            renderFrac = frac;          // 松手后立刻对齐，否则回弹看着像漂移
            invalidate();
        });
        springAnim.start();
    }

    private void cancelSpring() {
        if (springAnim != null) { springAnim.cancel(); springAnim = null; }
        springDx = 0f;
    }

    /** 拖动时的持续呼吸（涟漪相位） */
    private void startRipple() {
        stopRipple();
        rippleAnim = ValueAnimator.ofFloat(0f, 1f);
        rippleAnim.setDuration(1100);
        rippleAnim.setRepeatCount(ValueAnimator.INFINITE);
        rippleAnim.setInterpolator(new DecelerateInterpolator());
        rippleAnim.addUpdateListener(a -> {
            ripplePhase = (Float) a.getAnimatedValue();
            // 拇指"有质量"：每帧只走 42%，看上去慢半拍
            trailFrom = renderFrac;
            renderFrac += (frac - renderFrac) * 0.42f;
            invalidate();
        });
        rippleAnim.start();
    }

    private void stopRipple() {
        if (rippleAnim != null) { rippleAnim.cancel(); rippleAnim = null; }
        ripplePhase = 0f;
    }

    @Override protected void onDetachedFromWindow() {
        stopRipple();
        cancelSpring();
        if (buzzEndR != null) { removeCallbacks(buzzEndR); buzzEndR = null; }
        super.onDetachedFromWindow();
    }
}
