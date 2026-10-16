package com.autoswipe.ball.core;

/**
 * 屏幕几何参数的纯数据载体（不依赖任何 Android API）。
 *
 * <p>由 {@code MetricsReader} 从 {@code WindowManager} 读取后填充，
 * 坐标计算全部基于本对象，保证「不硬编码像素值」。</p>
 */
public final class ScreenMetrics {

    private static final float DENSITY_FALLBACK_DPI = 320f;

    /** 屏幕可用宽度（px，含状态栏/导航栏区域） */
    public final int widthPx;
    /** 屏幕可用高度（px，含状态栏/导航栏区域） */
    public final int heightPx;
    /** 屏幕密度（dpi），无效时回退 {@link #DENSITY_FALLBACK_DPI} */
    public final int densityDpi;
    /** 状态栏高度（px），无法读取时为 0 */
    public final float statusBarHeightPx;
    /** 底部导航栏（含手势条）高度（px），无法读取时为 0 */
    public final float navBarHeightPx;

    public ScreenMetrics(int widthPx, int heightPx, int densityDpi,
                         float statusBarHeightPx, float navBarHeightPx) {
        this.widthPx = Math.max(widthPx, 1);
        this.heightPx = Math.max(heightPx, 1);
        this.densityDpi = densityDpi > 0 ? densityDpi : (int) DENSITY_FALLBACK_DPI;
        this.statusBarHeightPx = Math.max(statusBarHeightPx, 0f);
        this.navBarHeightPx = Math.max(navBarHeightPx, 0f);
    }

    /** 密度系数（1.0 = mdpi）。 */
    public float density() {
        return densityDpi / 160f;
    }

    /** 屏幕是否携带了可用的系统栏信息（否则滑动安全区走保守回退）。 */
    public boolean hasSystemBarInfo() {
        return statusBarHeightPx > 0f || navBarHeightPx > 0f;
    }

    @Override
    public String toString() {
        return "ScreenMetrics{" + widthPx + "x" + heightPx
                + ", dpi=" + densityDpi
                + ", statusBar=" + statusBarHeightPx
                + ", navBar=" + navBarHeightPx + '}';
    }
}
