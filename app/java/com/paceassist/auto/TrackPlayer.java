package com.paceassist.auto;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹播放器：按**真实经过的时间**推进里程。
 *
 * 为什么不用「预生成 1 Hz 列表 + 按墙上时钟追进度」：
 * 那样一旦进程被系统降频/冻结（比如跑步软件在前台），回调会迟到，
 * 下一拍就会把欠下的好几十拍在几毫秒内全部补上 —— 表现就是**瞬移 + 配速异常**。
 *
 * 这里的做法：
 *  - 每次回调只走一步；
 *  - 走的距离 = 当前速度 × 真实经过时间（dt），所以配速永远是对的；
 *  - dt 被限幅在 [0.2s, 3.0s]，被冻结多久都不会一口气窜出去。
 */
public final class TrackPlayer {

    public static final int REPEAT_PINGPONG = 0;   // 到终点后原路折返
    public static final int REPEAT_LOOP = 1;       // 到终点后回到起点继续（适合闭合路线）

    private final List<double[]> poly = new ArrayList<>();
    private final double[] seg;
    private final double L;

    private double v0, vMin, vMax;              // 不再是 final：悬浮按钮要能实时改配速
    private final double band;
    private double paceSecPerKm;                 // 当前配速（秒/公里），可实时调整
    private final double targetMeters, startRampSec, jitterMeters;
    private final int repeatMode;

    private double progress = 0;     // 累计里程（米）
    private double v;                // 当前速度 m/s
    private double elapsed = 0;      // 累计时间（秒，不含被冻结的部分）
    private double noise = 0;

    /** 暂停中：时间停住，位置/里程/速度全部原地不动 */
    private volatile boolean paused = false;

    public void setPaused(boolean p) { paused = p; }
    public boolean isPaused() { return paused; }

    public TrackPlayer(TrackEngine.Params p, int repeatMode) {
        this.repeatMode = repeatMode;
        if (p.startFromCurrent) poly.add(new double[]{p.curLat, p.curLon});
        for (int i = 0; i < p.path.size(); i++) {
            double[] r = p.path.get(i);
            poly.add(TrackEngine.toWgs(r[0], r[1], p.sourceIsGcj));
        }
        int n = poly.size();
        seg = new double[Math.max(0, n - 1)];
        double tot = 0;
        double[] cum = new double[n];
        for (int i = 0; i < n - 1; i++) {
            seg[i] = Geo.dist(poly.get(i)[0], poly.get(i)[1], poly.get(i + 1)[0], poly.get(i + 1)[1]);
            cum[i] = tot;
            tot += seg[i];
        }
        if (n > 0) cum[n - 1] = tot;
        L = tot;

        this.band = Math.max(0.02, Math.min(0.5, p.band));
        this.paceSecPerKm = Math.max(150.0, Math.min(1200.0, p.paceSecPerKm));
        recomputeSpeeds();
        this.targetMeters = p.targetMeters;
        this.startRampSec = p.startRampSec;
        this.jitterMeters = p.jitterMeters;
        this.v = Math.min(v0 * 0.6, vMax);
    }

    /** 按当前配速重算速度带 */
    private void recomputeSpeeds() {
        this.v0 = 1000.0 / paceSecPerKm;
        this.vMin = v0 * (1 - band);
        this.vMax = v0 * (1 + band);
    }

    /**
     * 直接设定目标配速（秒/公里）—— 悬浮滑轨用的就是这个。
     * 速度带立刻重算，当前速度夹进新带内，避免突跳（突跳容易被判异常）。
     */
    public void setPace(double secPerKm) {
        double v = Math.max(150.0, Math.min(1200.0, secPerKm));
        if (v == paceSecPerKm) return;
        paceSecPerKm = v;
        recomputeSpeeds();
        if (this.v > vMax) this.v = vMax;
        if (this.v < vMin) this.v = vMin;
    }

    /**
     * 相对调速。deltaSecPerKm **正数 = 配速数字变大 = 跑得更慢**，负数 = 更快。
     */
    public void adjustPace(double deltaSecPerKm) {
        setPace(paceSecPerKm + deltaSecPerKm);
    }

    public double paceSecPerKm() { return paceSecPerKm; }

    public boolean valid() { return poly.size() >= 2 && L > 1.0; }
    public double pathLength() { return L; }
    public boolean done() { return progress >= targetMeters; }
    public double progress() { return progress; }

    /**
     * 沿路线走到的位置，0..1 —— 注意这和「已完成目标里程的比例」不是一回事：
     * 跑不满目标会折返/绕圈，progress 一直涨，而路线上的位置是来回走的。
     * 仪表盘上那个点必须按这个值画，否则折返时点还在单向往前爬。
     */
    public double routeProgress() {
        if (L <= 0) return 0;
        double r = mapProgress(progress) / L;
        return r < 0 ? 0 : (r > 1 ? 1 : r);
    }

    /** 推进一步；dtSec 为真实经过秒数（调用方已限幅） */
    public TrackEngine.Tick next(double dtSec, long nowMs) {
        // 暂停：把 dt 归零 —— 相当于时间不走了。位置、累计里程、速度全部原地冻结，
        // 恢复时也不会因为"欠了一堆时间"而一次补拍（那就成瞬移了）。
        if (paused) dtSec = 0;
        // 1) 目标速度（带起步/收尾缓变 + AR(1) 噪声）
        double t = v0 * (1.0 + 0.030 * Math.sin(2 * Math.PI * elapsed / 240.0));
        if (elapsed < startRampSec) t *= 0.60 + 0.40 * (elapsed / startRampSec);
        double remain = targetMeters - progress;
        if (remain < v0 * startRampSec)
            t *= 0.80 + 0.20 * Math.max(0, remain / (v0 * startRampSec));
        noise = 0.85 * noise + 0.15 * (Math.random() * 2 - 1) * 0.020;
        t *= (1.0 + noise);
        if (t < vMin) t = vMin;
        if (t > vMax) t = vMax;

        // 2) 加速度限幅（按 dt 缩放，0.3 m/s²）
        double dv = t - v;
        double maxDv = 0.30 * dtSec;
        if (dv > maxDv) dv = maxDv;
        if (dv < -maxDv) dv = -maxDv;
        v += dv;
        if (v < vMin * 0.8) v = vMin * 0.8;
        if (v > vMax) v = vMax;

        // 3) 按真实时间推进里程
        progress += v * dtSec;
        elapsed += dtSec;
        if (progress > targetMeters) progress = targetMeters;

        // 4) 位置
        double d = mapProgress(progress);
        double[] pos = pointAt(d);
        double[] prev = pointAt(mapProgress(Math.max(0, progress - 3)));
        double[] nxt = pointAt(mapProgress(Math.min(repeatTotal(), progress + 3)));
        float brg = Geo.bearing(prev[0], prev[1], nxt[0], nxt[1]);

        double jit = jitterMeters * Math.sin(2 * Math.PI * elapsed / 97.0);
        double perp = Math.toRadians(brg + 90);
        pos[0] += Geo.mToLat(jit * Math.cos(perp));
        pos[1] += Geo.mToLon(jit * Math.sin(perp), pos[0]);

        TrackEngine.Tick tk = new TrackEngine.Tick();
        tk.lat = pos[0];
        tk.lon = pos[1];
        tk.speed = (float) (paused ? 0.0 : v);   // 暂停时速度必须是 0，否则跑步软件会显示"人在跑、里程不动"
        tk.bearing = brg;
        tk.accuracy = 6f + (float) (Math.random() * 6.0);
        tk.altitude = 20 + 3 * Math.sin(elapsed / 120.0);
        tk.timeMillis = nowMs;
        tk.covered = progress;
        return tk;
    }

    private double repeatTotal() { return repeatMode == REPEAT_LOOP ? L : 2 * L; }

    private double mapProgress(double d) {
        if (d <= 0) return 0;
        if (repeatMode == REPEAT_LOOP) {
            double m = d % L;
            return m;
        }
        double m = d % (2 * L);
        return m <= L ? m : 2 * L - m;
    }

    private double[] pointAt(double dist) {
        if (dist <= 0) return new double[]{poly.get(0)[0], poly.get(0)[1]};
        if (dist >= L) {
            double[] last = poly.get(poly.size() - 1);
            return new double[]{last[0], last[1]};
        }
        double acc = 0;
        for (int i = 0; i < seg.length; i++) {
            if (acc + seg[i] >= dist) {
                double r = seg[i] <= 0 ? 0 : (dist - acc) / seg[i];
                double[] a = poly.get(i), b = poly.get(i + 1);
                return new double[]{a[0] + (b[0] - a[0]) * r, a[1] + (b[1] - a[1]) * r};
            }
            acc += seg[i];
        }
        double[] last = poly.get(poly.size() - 1);
        return new double[]{last[0], last[1]};
    }
}
