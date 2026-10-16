package com.autoswipe.ball.core;

/**
 * 滑动安全区（纯 Java）。所有取值均为屏幕绝对像素坐标。
 *
 * <p>安全区用于把上滑手势限制在「不触发系统手势 + 不碰到互动按钮」的区域内。</p>
 */
public final class SafeArea {

    public final float left;
    public final float top;
    public final float right;
    public final float bottom;
    /** 是否使用了保守回退比例（系统栏信息不可用时为 true） */
    public final boolean fallback;

    public SafeArea(float left, float top, float right, float bottom, boolean fallback) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.fallback = fallback;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    public float centerX() {
        return (left + right) / 2f;
    }

    @Override
    public String toString() {
        return "SafeArea{x[" + Math.round(left) + "," + Math.round(right)
                + "] y[" + Math.round(top) + "," + Math.round(bottom)
                + "] fallback=" + fallback + '}';
    }
}
