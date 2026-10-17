package com.paceassist.auto;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 确认弹框 —— 自己搭，不用系统 AlertDialog。
 *
 * 系统的 AlertDialog 在这台机器上是浅色方角、灰按钮，跟应用的玻璃/橙色调完全不搭，
 * 一眼就露怯。这里用同一套 Ui 令牌：玻璃面板 + 窄体标题 + 橙色主行动 + 大圆角。
 */
public final class ConfirmDialog {

    public interface OnPick {
        void onConfirm();
        void onCancel();
    }

    private ConfirmDialog() { }

    /* 当前正在显示的弹框。Activity 被销毁时必须先关掉它，
       否则 Window 会跟着一个已经死掉的 Activity，系统抛 WindowLeaked。 */
    private static Dialog current;

    /** 关闭当前弹框（Activity.onDestroy 里调用；没有弹框时安全返回） */
    public static void dismissCurrent() {
        Dialog d = current;
        current = null;
        if (d != null) {
            try { if (d.isShowing()) d.dismiss(); } catch (Exception ignored) { }
        }
    }

    public static void show(Context ctx, String title, String message,
                            String okText, String cancelText, final OnPick cb) {
        // Activity 已经在收尾 / 已销毁时再弹，Window 会挂到一个死 Activity 上
        if (ctx instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) ctx;
            if (a.isFinishing() || a.isDestroyed()) return;
        }
        // 同一时刻只留一个：上一个还没关就先把老的收掉，避免叠窗
        dismissCurrent();
        final Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = Ui.dp(Ui.S6);
        LinearLayout card = Ui.col(ctx);
        card.setPadding(pad, pad, pad, Ui.dp(Ui.S5));
        if (android.os.Build.VERSION.SDK_INT >= 28) card.setElevation(Ui.dp(18));
        card.setBackground(Ui.panelShape(30f));

        TextView t = Ui.text(ctx, title, 26f, Ui.INK, Ui.display());
        t.setLetterSpacing(-0.035f);
        card.addView(t);

        TextView m = Ui.text(ctx, message, 16f, Ui.INK_2, null);
        m.setPadding(0, Ui.dp(Ui.S3), 0, Ui.dp(Ui.S5));
        m.setLineSpacing(Ui.dp(6), 1f);
        card.addView(m);

        TextView ok = Ui.primary(ctx, okText);
        card.addView(ok);

        TextView no;
        if (cancelText != null && !cancelText.isEmpty()) {
            no = Ui.secondary(ctx, cancelText);
            card.addView(no);
        } else {
            no = null;
        }

        ok.setOnClickListener(v -> { d.dismiss(); if (cb != null) cb.onConfirm(); });
        if (no != null) no.setOnClickListener(v -> { d.dismiss(); if (cb != null) cb.onCancel(); });

        d.setContentView(card);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.62f);
            w.setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.88f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            // 进出都从中心缩放一点点，比系统弹框的默认动画干净
            w.getAttributes().windowAnimations = 0;
        }

        d.setCanceledOnTouchOutside(true);
        // 点空白处 / 按返回键也要走 onCancel：否则调用方会以为用户还在犹豫，
        // 权限链就停在"等一个永远不来的答复"上。
        d.setOnCancelListener(x -> { if (cb != null) cb.onCancel(); });
        d.setOnDismissListener(x -> { if (current == d) current = null; });
        current = d;
        d.show();

        // 入场：轻微放大 + 淡入
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).start();
    }
}
