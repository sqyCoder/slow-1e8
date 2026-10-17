package com.paceassist.auto;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 在高德（GCJ-02）底图上按顺序点选路线点。 */
public class MapPickerActivity extends Activity {

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LON = "lon";

    private WebView wv;
    private double initLat = 39.9086, initLon = 116.3975;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (in != null) {
            initLat = in.getDoubleExtra(EXTRA_LAT, initLat);
            initLon = in.getDoubleExtra(EXTRA_LON, initLon);
        }
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // 页面是我们自己的本地资源，不许它去读别的 file:// 内容、也不许跨源
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        wv.addJavascriptInterface(new Bridge(), "Bridge");
        wv.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript("setCenter(" + initLat + "," + initLon + ",16)", null);
            }
        });
        wv.setBackgroundColor(0xFF07090D);   // 与 map.html 的沥青底一致，避免加载前闪一下白
        wv.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        wv.loadUrl("file:///android_asset/map.html");
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0xFF07090D));
        setContentView(wv);
    }

    @Override
    protected void onDestroy() {
        // WebView 不主动销毁会一直占着渲染进程，反复画路线会越积越多
        try {
            if (wv != null) {
                wv.removeJavascriptInterface("Bridge");
                wv.loadUrl("about:blank");
                wv.destroy();
                wv = null;
            }
        } catch (Throwable ignore) { }
        super.onDestroy();
    }

    public class Bridge {
        @JavascriptInterface
        public void onPoints(String json) {
            List<double[]> pts = new ArrayList<>();
            boolean ok = false;
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray p = arr.getJSONArray(i);
                    double la = p.getDouble(0), lo = p.getDouble(1);
                    // 桥接口是谁都能调的（页面里任何脚本，含将来可能引入的第三方脚本），
                    // 所以坐标必须自己校验一遍，不能页面给什么就注入什么。
                    if (la < -90 || la > 90 || lo < -180 || lo > 180) continue;
                    pts.add(new double[]{la, lo});
                }
                if (pts.size() >= 2) {
                    MainActivity.picked = pts;
                    ok = true;
                }
            } catch (Throwable ignore) { }
            final boolean good = ok;
            // setResult 不是线程安全的：这里是 WebView 的 JS 桥接线程，
            // 统一回主线程再 setResult + finish
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    setResult(good ? RESULT_OK : RESULT_CANCELED);
                    finish();
                }
            });
        }
    }
}
