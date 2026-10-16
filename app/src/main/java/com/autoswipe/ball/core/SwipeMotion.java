package com.autoswipe.ball.core;

/**
 * 一次上滑手势的几何描述（纯 Java，不含 Android 依赖）。
 *
 * <p>由 {@link SwipeGeometry} 计算得出，Android 层再转换为
 * {@code android.graphics.Path} 交给 {@code dispatchGesture}。</p>
 */
public final class SwipeMotion {

    public final float startX;
    public final float startY;
    public final float endX;
    public final float endY;
    public final long durationMs;

    public SwipeMotion(float startX, float startY, float endX, float endY, long durationMs) {
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.durationMs = durationMs;
    }

    /** 实际滑动像素距离（竖直方向投影）。 */
    public float distancePx() {
        return Math.abs(startY - endY);
    }

    @Override
    public String toString() {
        return "SwipeMotion{(" + fmt(startX) + "," + fmt(startY) + ") -> ("
                + fmt(endX) + "," + fmt(endY) + ") " + durationMs + "ms}";
    }

    private static String fmt(float v) {
        return String.valueOf(Math.round(v));
    }
}
