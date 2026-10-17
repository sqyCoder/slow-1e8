package com.paceassist.auto;

/** 坐标与几何工具：WGS-84 / GCJ-02 互转、距离、方位角。 */
public final class Geo {
    private Geo() {}

    private static final double PI = 3.1415926535897932384626;
    private static final double A = 6378245.0;          // 克拉索夫斯基椭球长半轴
    private static final double EE = 0.00669342162296594323;

    public static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double tLat(double x, double y) {
        double r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        r += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return r;
    }

    private static double tLon(double x, double y) {
        double r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        r += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return r;
    }

    /** WGS-84 -> GCJ-02，返回 {lat, lon} */
    public static double[] wgs2gcj(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = tLat(lon - 105.0, lat - 35.0);
        double dLon = tLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    /** GCJ-02 -> WGS-84（数值逼近，精度约 1e-7 度） */
    public static double[] gcj2wgs(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double wLat = lat, wLon = lon;
        for (int i = 0; i < 8; i++) {
            double[] g = wgs2gcj(wLat, wLon);
            double dLat = g[0] - lat, dLon = g[1] - lon;
            if (Math.abs(dLat) < 1e-9 && Math.abs(dLon) < 1e-9) break;
            wLat -= dLat;
            wLon -= dLon;
        }
        return new double[]{wLat, wLon};
    }

    /** 两点间距离（米，Haversine） */
    public static double dist(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double p1 = lat1 / 180 * PI, p2 = lat2 / 180 * PI;
        double dp = (lat2 - lat1) / 180 * PI, dl = (lon2 - lon1) / 180 * PI;
        double s = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(s)));
    }

    /** 方位角（度，0=北） */
    public static float bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = lat1 / 180 * PI, p2 = lat2 / 180 * PI;
        double dl = (lon2 - lon1) / 180 * PI;
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.atan2(y, x) / PI * 180.0;
        return (float) ((b + 360.0) % 360.0);
    }

    /** 米 -> 纬度增量 */
    public static double mToLat(double m) { return m / 111320.0; }

    /** 米 -> 经度增量（按纬度折算） */
    public static double mToLon(double m, double lat) {
        double k = Math.cos(lat / 180.0 * PI);
        if (Math.abs(k) < 1e-6) k = 1e-6;
        return m / (111320.0 * k);
    }
}
