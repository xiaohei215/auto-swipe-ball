package com.autoswipe.ball.core;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

/**
 * 从系统读取真实屏幕参数。
 *
 * <p>只取几何信息，不读取任何内容数据（规则「不越权操作」）。</p>
 */
public final class MetricsReader {

    private MetricsReader() {
    }

    /**
     * 读取当前屏幕参数；任何一步失败都会回退到 {@code Resources} 的显示指标，
     * 保证返回对象始终可用（后续 {@link SwipeGeometry} 还有二次保守回退）。
     */
    public static ScreenMetrics read(Context ctx) {
        int width = 0;
        int height = 0;
        float statusBar = 0f;
        float navBar = 0f;

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowMetrics metrics = wm.getCurrentWindowMetrics();
                Rect bounds = metrics.getBounds();
                width = bounds.width();
                height = bounds.height();
                WindowInsets insets = metrics.getWindowInsets();
                if (insets != null) {
                    Insets status = insets.getInsets(WindowInsets.Type.statusBars());
                    statusBar = status.top;
                    Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                    navBar = nav.bottom;
                    if (navBar <= 0f) {
                        // 手势导航条在部分 ROM 上只出现在 tappableElement 中
                        navBar = insets.getInsets(WindowInsets.Type.tappableElement()).bottom;
                    }
                }
            } catch (Throwable ignored) {
                // 部分 ROM / 非可视 Context 下会抛异常，走下方回退
                width = 0;
                height = 0;
            }
        }

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        if (width <= 0 || height <= 0) {
            DisplayMetrics real = new DisplayMetrics();
            if (wm != null && wm.getDefaultDisplay() != null) {
                wm.getDefaultDisplay().getRealMetrics(real);
                width = real.widthPixels;
                height = real.heightPixels;
            }
            if (width <= 0 || height <= 0) {
                width = dm.widthPixels;
                height = dm.heightPixels;
            }
        }
        return new ScreenMetrics(width, height, dm.densityDpi, statusBar, navBar);
    }
}
