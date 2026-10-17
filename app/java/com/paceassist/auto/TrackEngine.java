package com.paceassist.auto;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹 + 配速引擎。
 *
 * 输入：一整条**路线**（按点选顺序排列的路径点）+ 目标里程 + 目标配速。
 *       路线可以有很多路径点，用来贴着道路走，而不是在点之间拉直线。
 * 输出：1 Hz 的轨迹点（WGS-84，可直接喂给系统 GPS Provider）。
 *
 * 走法：起点 → 沿路线依次经过每个路径点；
 *       里程不够就沿原路折返（带横向偏移，避免两条腿完全重合），直到累计里程达标。
 * 速度剖面：起步/收尾缓变 + AR(1) 噪声 + 加速度限幅，用于通过 speedConsistency 之类的形态检查。
 */
public final class TrackEngine {
    private TrackEngine() {}

    public static class Tick {
        public double lat, lon;      // WGS-84
        public float speed;          // m/s
        public float bearing;        // 度
        public float accuracy;       // 米
        public double altitude;
        public long timeMillis;
        public double covered;       // 累计里程（米）
    }

    public static class Result {
        public List<Tick> ticks = new ArrayList<>();
        public double pathLength = 0;
    }

    public static class Params {
        /** 路线点：每个为 {lat, lon}，按点选顺序，源坐标系 */
        public List<double[]> path = new ArrayList<>();
        public boolean sourceIsGcj = true;      // 高德地图选点 => GCJ-02
        public boolean startFromCurrent = false;
        public double curLat, curLon;           // 当前真实位置（WGS-84）
        public double targetMeters = 2000.0;
        public double paceSecPerKm = 360.0;     // 目标平均配速 6:00/km
        public double band = 0.12;              // 速度波动带宽 ±12%
        public double jitterMeters = 1.0;       // 横向漂移幅度
        public double startRampSec = 30.0;      // 起步加速时间
        public int maxTicks = 40000;
        public long startTimeMillis = System.currentTimeMillis();
    }

    /** 源坐标 -> WGS-84 */
    public static double[] toWgs(double lat, double lon, boolean fromGcj) {
        return fromGcj ? Geo.gcj2wgs(lat, lon) : new double[]{lat, lon};
    }

    public static Result build(Params p) {
        Result res = new Result();

        // 1) 组装折线（WGS-84）：可选起点 + 全部路线点
        List<double[]> poly = new ArrayList<>();
        if (p.startFromCurrent) poly.add(new double[]{p.curLat, p.curLon});
        for (int i = 0; i < p.path.size(); i++) {
            double[] r = p.path.get(i);
            poly.add(toWgs(r[0], r[1], p.sourceIsGcj));
        }
        if (poly.size() < 2) return res;

        // 2) 分段长度与累计里程
        int n = poly.size();
        double[] seg = new double[n - 1];
        double L = 0;
        double[] cum = new double[n];
        for (int i = 0; i < n - 1; i++) {
            seg[i] = Geo.dist(poly.get(i)[0], poly.get(i)[1], poly.get(i + 1)[0], poly.get(i + 1)[1]);
            cum[i] = L;
            L += seg[i];
        }
        cum[n - 1] = L;
        res.pathLength = L;
        if (L < 1.0) return res;

        // 3) 生成 1 Hz 轨迹
        double v0 = 1000.0 / Math.max(60.0, p.paceSecPerKm);   // m/s
        double vMin = v0 * (1 - p.band), vMax = v0 * (1 + p.band);
        double maxAccel = 0.30;                                // m/s^2

        double d = 0, v = Math.min(v0 * 0.6, vMax);
        double noise = 0;
        long t = p.startTimeMillis;
        int k = 0;

        while (d < p.targetMeters && k < p.maxTicks) {
            double sec = k;
            double remain = p.targetMeters - d;

            double target = v0 * (1.0 + 0.030 * Math.sin(2 * Math.PI * sec / 240.0));
            if (sec < p.startRampSec) target *= 0.60 + 0.40 * (sec / p.startRampSec);
            double tail = p.startRampSec;
            if (remain < v0 * tail) target *= 0.80 + 0.20 * Math.max(0, remain / (v0 * tail));
            noise = 0.85 * noise + 0.15 * (Math.random() * 2 - 1) * 0.020;
            target *= (1.0 + noise);
            if (target < vMin) target = vMin;
            if (target > vMax) target = vMax;

            double dv = target - v;
            if (dv > maxAccel) dv = maxAccel;
            if (dv < -maxAccel) dv = -maxAccel;
            v += dv;
            if (v < vMin * 0.8) v = vMin * 0.8;
            if (v > vMax) v = vMax;

            d += v;

            double dd = pingPong(d, L);
            double[] pos = pointAt(poly, seg, L, dd);
            double[] prev = pointAt(poly, seg, L, pingPong(Math.max(0, d - 3), L));
            double[] nxt = pointAt(poly, seg, L, pingPong(Math.min(2 * L, d + 3), L));
            float brg = Geo.bearing(prev[0], prev[1], nxt[0], nxt[1]);
            double jit = p.jitterMeters * Math.sin(2 * Math.PI * sec / 97.0);
            double perp = Math.toRadians(brg + 90);
            pos[0] += Geo.mToLat(jit * Math.cos(perp));
            pos[1] += Geo.mToLon(jit * Math.sin(perp), pos[0]);

            Tick tk = new Tick();
            tk.lat = pos[0];
            tk.lon = pos[1];
            tk.speed = (float) v;
            tk.bearing = brg;
            tk.accuracy = 6f + (float) (Math.random() * 6.0);
            tk.altitude = 20 + 3 * Math.sin(sec / 120.0);
            tk.timeMillis = t;
            tk.covered = d;
            res.ticks.add(tk);

            t += 1000;
            k++;
        }
        return res;
    }

    private static double pingPong(double d, double L) {
        double m = d % (2 * L);
        return m <= L ? m : 2 * L - m;
    }

    private static double[] pointAt(List<double[]> poly, double[] seg, double L, double dist) {
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

    /** 路线总长（米） */
    public static double pathLength(List<double[]> path, boolean gcj) {
        double L = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            double[] a = toWgs(path.get(i)[0], path.get(i)[1], gcj);
            double[] b = toWgs(path.get(i + 1)[0], path.get(i + 1)[1], gcj);
            L += Geo.dist(a[0], a[1], b[0], b[1]);
        }
        return L;
    }
}
