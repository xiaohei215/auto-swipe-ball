package com.autoswipe.ball.core;

/**
 * 滑动几何计算（纯 Java，可脱离 Android SDK 单元测试）。
 *
 * <p>核心约束（规则 1「精准优先」/ 规则 4「不硬编码坐标」）：</p>
 * <ul>
 *   <li>水平方向：始终取安全区水平中心，天然避开右侧点赞/评论/分享按钮列与左右边缘返回手势；</li>
 *   <li>垂直方向：起点约为安全区 70% 处，终点为起点上方一个可配置距离；</li>
 *   <li>上下均留出边距，避开顶部搜索栏/状态栏与底部导航栏/手势区；</li>
 *   <li>系统栏信息不可用时回退到屏幕中部保守比例（12%~86%），绝不写死像素。</li>
 * </ul>
 */
public final class SwipeGeometry {

    /** 安全区水平内缩比例（避开左右边缘返回手势） */
    private static final float H_INSET_RATIO = 0.08f;
    /** 安全区顶部内缩：取「状态栏 + 屏幕高度 5%」与「屏幕高度 12%」中的较大者 */
    private static final float TOP_MIN_RATIO = 0.12f;
    private static final float TOP_GAP_RATIO = 0.05f;
    /** 安全区底部内缩：取「导航栏 + 屏幕高度 5%」与「屏幕高度 14%」中的较大者 */
    private static final float BOTTOM_MIN_RATIO = 0.14f;
    private static final float BOTTOM_GAP_RATIO = 0.05f;
    /** 起点在安全区中的垂直锚点比例 */
    private static final float ANCHOR_RATIO = 0.70f;
    /** 终点与安全区边界之间保留的最小间距（px） */
    private static final float EDGE_PADDING_PX = 8f;

    private SwipeGeometry() {
    }

    /**
     * 按屏幕参数计算滑动安全区。
     *
     * @param m 屏幕参数，不得为 null
     */
    public static SafeArea computeSafeArea(ScreenMetrics m) {
        if (m == null) {
            throw new IllegalArgumentException("ScreenMetrics must not be null");
        }
        final float w = m.widthPx;
        final float h = m.heightPx;

        float left = w * H_INSET_RATIO;
        float right = w * (1f - H_INSET_RATIO);

        float top = Math.max(m.statusBarHeightPx + h * TOP_GAP_RATIO, h * TOP_MIN_RATIO);
        float bottom = Math.min(h - m.navBarHeightPx - h * BOTTOM_GAP_RATIO, h * (1f - BOTTOM_MIN_RATIO));

        boolean fallback = !m.hasSystemBarInfo();
        // 系统栏信息缺失，或推算结果不可用（极小屏 / 异常横屏）时，回退到屏幕中部的保守比例区
        if (fallback || top >= bottom || (bottom - top) < h * 0.30f) {
            top = h * TOP_MIN_RATIO;
            bottom = h * (1f - BOTTOM_MIN_RATIO);
            fallback = true;
        }
        return new SafeArea(left, top, right, bottom, fallback);
    }

    /**
     * 计算一次上滑手势的起止坐标与时长。
     *
     * @param m 屏幕参数
     * @param c 可调参数（内部会先做区间收敛）
     */
    public static SwipeMotion computeMotion(ScreenMetrics m, SwipeConfig c) {
        if (m == null) {
            throw new IllegalArgumentException("ScreenMetrics must not be null");
        }
        SwipeConfig cfg = c == null ? new SwipeConfig() : new SwipeConfig(c);
        SafeArea area = computeSafeArea(m);
        final float safeH = area.height();

        // 水平居中：竖直上滑，最大程度减少横向误触
        final float x = area.centerX();

        // 起点：安全区垂直 70% 处
        float anchorY = area.top + safeH * ANCHOR_RATIO;

        // 距离：按比例缩放后收敛到 [安全区 15%, 安全区 60%]，并保证终点不越过安全区上边界
        float desired = safeH * cfg.distanceRatio;
        float maxDistance = Math.min(safeH * SwipeConfig.MAX_DISTANCE_RATIO, anchorY - area.top - EDGE_PADDING_PX);
        float minDistance = Math.min(safeH * SwipeConfig.MIN_DISTANCE_RATIO, maxDistance);
        float distance = Math.max(minDistance, Math.min(maxDistance, desired));

        float startY = anchorY;
        float endY = anchorY - distance;

        // 起点不得压到安全区下边界
        float maxStartY = area.bottom - EDGE_PADDING_PX;
        if (startY > maxStartY) {
            startY = maxStartY;
            endY = startY - distance;
        }
        // 终点不得越过安全区上边界
        if (endY < area.top + EDGE_PADDING_PX) {
            endY = area.top + EDGE_PADDING_PX;
            startY = Math.min(area.bottom - EDGE_PADDING_PX, endY + distance);
        }

        return new SwipeMotion(x, startY, x, endY, cfg.swipeDurationMs);
    }
}
