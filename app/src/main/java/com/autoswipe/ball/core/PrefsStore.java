package com.autoswipe.ball.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置与运行时状态的持久化（唯一读写 SharedPreferences 的地方）。
 *
 * <p>读取时统一走 {@link SwipeConfig#clamp()}，即使历史数据被外部篡改也不会产生越界参数。</p>
 */
public final class PrefsStore {

    private static final String FILE = "auto_swipe_prefs";
    private static final String K_INTERVAL = "interval_ms";
    private static final String K_DURATION = "swipe_duration_ms";
    private static final String K_DISTANCE_RATIO = "distance_ratio";
    private static final String K_RING = "show_countdown_ring";
    private static final String K_AUTO_HEAL = "auto_heal_accessibility";
    private static final String K_LAST_STATE = "last_state";
    private static final String K_LAST_REASON = "last_reason";

    private PrefsStore() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 读取配置，缺失/非法字段自动回退默认值。 */
    public static SwipeConfig loadConfig(Context ctx) {
        SwipeConfig c = new SwipeConfig();
        SharedPreferences p = sp(ctx);
        c.intervalMs = p.getInt(K_INTERVAL, SwipeConfig.DEFAULT_INTERVAL_MS);
        c.swipeDurationMs = p.getInt(K_DURATION, SwipeConfig.DEFAULT_DURATION_MS);
        c.distanceRatio = p.getFloat(K_DISTANCE_RATIO, SwipeConfig.DEFAULT_DISTANCE_RATIO);
        c.showCountdownRing = p.getBoolean(K_RING, true);
        c.autoHealAccessibility = p.getBoolean(K_AUTO_HEAL, true);
        return c.clamp();
    }

    /** 保存配置（内部先收敛再写入）。 */
    public static void saveConfig(Context ctx, SwipeConfig config) {
        SwipeConfig c = config == null ? new SwipeConfig() : new SwipeConfig(config);
        sp(ctx).edit()
                .putInt(K_INTERVAL, c.intervalMs)
                .putInt(K_DURATION, c.swipeDurationMs)
                .putFloat(K_DISTANCE_RATIO, c.distanceRatio)
                .putBoolean(K_RING, c.showCountdownRing)
                .putBoolean(K_AUTO_HEAL, c.autoHealAccessibility)
                .apply();
    }

    /** 记录运行状态，用于「前台服务被系统中断」的恢复判定与界面回显。 */
    public static void saveRuntime(Context ctx, RunState state, String reason) {
        sp(ctx).edit()
                .putString(K_LAST_STATE, state == null ? RunState.NOT_STARTED.name() : state.name())
                .putString(K_LAST_REASON, reason == null ? "" : reason)
                .apply();
    }

    public static RunState loadRuntimeState(Context ctx) {
        String raw = sp(ctx).getString(K_LAST_STATE, RunState.NOT_STARTED.name());
        try {
            return RunState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return RunState.NOT_STARTED;
        }
    }

    public static String loadRuntimeReason(Context ctx) {
        String r = sp(ctx).getString(K_LAST_REASON, "");
        return r == null ? "" : r;
    }
}
