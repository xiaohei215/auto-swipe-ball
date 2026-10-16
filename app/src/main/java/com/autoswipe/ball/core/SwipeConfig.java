package com.autoswipe.ball.core;

/**
 * 可调参数模型（纯 Java，不含 Android 依赖）。
 *
 * <p>所有赋值入口都会经过 {@link #clamp()} 上下限收敛，避免非法值进入手势链路。</p>
 */
public final class SwipeConfig {

    /** 上滑间隔下限（ms） */
    public static final int MIN_INTERVAL_MS = 5_000;
    /** 上滑间隔上限（ms） */
    public static final int MAX_INTERVAL_MS = 120_000;
    /** 上滑间隔默认值（ms） */
    public static final int DEFAULT_INTERVAL_MS = 15_000;

    /** 滑动时长下限（ms） */
    public static final int MIN_DURATION_MS = 100;
    /** 滑动时长上限（ms） */
    public static final int MAX_DURATION_MS = 1_000;
    /** 滑动时长默认值（ms） */
    public static final int DEFAULT_DURATION_MS = 300;

    /** 滑动距离占安全区高度比例的下限 */
    public static final float MIN_DISTANCE_RATIO = 0.15f;
    /** 滑动距离占安全区高度比例的上限 */
    public static final float MAX_DISTANCE_RATIO = 0.60f;
    /** 滑动距离比例默认值（安全区高度的 40%） */
    public static final float DEFAULT_DISTANCE_RATIO = 0.40f;

    /** 两次上滑之间的间隔（ms） */
    public int intervalMs = DEFAULT_INTERVAL_MS;
    /** 单次手势从起点到终点的时长（ms） */
    public int swipeDurationMs = DEFAULT_DURATION_MS;
    /** 滑动距离 = 安全区高度 × 本比例 */
    public float distanceRatio = DEFAULT_DISTANCE_RATIO;
    /** 是否在悬浮球上绘制下一次上滑的倒计时环 */
    public boolean showCountdownRing = true;
    /**
     * 无障碍服务被系统关闭时是否尝试自动恢复。
     *
     * <p>仅在已通过 ADB 一次性授予 {@code WRITE_SECURE_SETTINGS} 时才会真正生效，
     * 未授权时该项无副作用。</p>
     */
    public boolean autoHealAccessibility = true;

    public SwipeConfig() {
    }

    public SwipeConfig(SwipeConfig other) {
        if (other == null) {
            return;
        }
        this.intervalMs = other.intervalMs;
        this.swipeDurationMs = other.swipeDurationMs;
        this.distanceRatio = other.distanceRatio;
        this.showCountdownRing = other.showCountdownRing;
        this.autoHealAccessibility = other.autoHealAccessibility;
        clamp();
    }

    /** 将全部字段收敛到合法区间，返回自身便于链式调用。 */
    public SwipeConfig clamp() {
        intervalMs = clampInt(intervalMs, MIN_INTERVAL_MS, MAX_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        swipeDurationMs = clampInt(swipeDurationMs, MIN_DURATION_MS, MAX_DURATION_MS, DEFAULT_DURATION_MS);
        distanceRatio = clampFloat(distanceRatio, MIN_DISTANCE_RATIO, MAX_DISTANCE_RATIO, DEFAULT_DISTANCE_RATIO);
        return this;
    }

    public static int clampInt(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            // 非法值（含 0 / 负数 / SharedPreferences 缺省）回退到默认值，而非夹到边界
            return value == 0 ? fallback : Math.max(min, Math.min(max, value));
        }
        return value;
    }

    public static float clampFloat(float value, float min, float max, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        if (value == 0f) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return "SwipeConfig{interval=" + intervalMs + "ms, duration=" + swipeDurationMs
                + "ms, distanceRatio=" + distanceRatio + ", ring=" + showCountdownRing
                + ", autoHeal=" + autoHealAccessibility + '}';
    }
}
