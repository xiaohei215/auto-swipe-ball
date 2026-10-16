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
    private static final String K_UPDATE_KIND = "update_source_kind";
    private static final String K_UPDATE_OWNER = "update_source_owner";
    private static final String K_UPDATE_REPO = "update_source_repo";
    private static final String K_UPDATE_BRANCH = "update_source_branch";
    private static final String K_UPDATE_CUSTOM = "update_source_custom";
    private static final String K_UPDATE_WIFI_ONLY = "update_wifi_only";
    private static final String K_UPDATE_AUTO_CHECK = "update_auto_check";
    private static final String K_UPDATE_CHECKED_AT = "update_checked_at";

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

    // ------------------------------------------------------------------
    // 更新源

    /**
     * 读取更新源配置；从未配置过时返回默认的 Gitee 源。
     *
     * <p>之所以做成「可切换」而不是写死：不同网络环境下 Gitee 与 GitHub 的可达性会变，
     * 写死意味着换源必须重新发版，而那正是本功能想解决的痛点。</p>
     */
    public static UpdateSource loadUpdateSource(Context ctx) {
        SharedPreferences p = sp(ctx);
        UpdateSource.Kind kind = UpdateSource.Kind.fromName(p.getString(K_UPDATE_KIND, null));
        String owner = p.getString(K_UPDATE_OWNER, UpdateSource.DEFAULT_OWNER);
        String repo = p.getString(K_UPDATE_REPO, UpdateSource.DEFAULT_REPO);
        String branch = p.getString(K_UPDATE_BRANCH, UpdateSource.DEFAULT_BRANCH);
        String custom = p.getString(K_UPDATE_CUSTOM, "");
        switch (kind) {
            case GITHUB:
                return UpdateSource.github(owner, repo, branch);
            case CUSTOM:
                return UpdateSource.custom(custom == null ? "" : custom);
            case GITEE:
            default:
                return UpdateSource.gitee(owner, repo, branch);
        }
    }

    /** 保存更新源配置。 */
    public static void saveUpdateSource(Context ctx, UpdateSource source) {
        UpdateSource s = source == null ? UpdateSource.defaultGitee() : source;
        sp(ctx).edit()
                .putString(K_UPDATE_KIND, s.kind.name())
                .putString(K_UPDATE_OWNER, s.owner)
                .putString(K_UPDATE_REPO, s.repo)
                .putString(K_UPDATE_BRANCH, s.branch)
                .putString(K_UPDATE_CUSTOM, s.customBaseUrl)
                .apply();
    }

    /** 是否只在 Wi-Fi 下自动检查更新（默认开启，避免消耗移动流量）。 */
    public static boolean isUpdateWifiOnly(Context ctx) {
        return sp(ctx).getBoolean(K_UPDATE_WIFI_ONLY, true);
    }

    public static void setUpdateWifiOnly(Context ctx, boolean value) {
        sp(ctx).edit().putBoolean(K_UPDATE_WIFI_ONLY, value).apply();
    }

    /**
     * 是否在打开应用时自动检查更新（默认开启）。
     *
     * <p>关掉之后应用不会主动发起任何网络请求，只有你亲手点「检查更新」时才会联网。
     * 这是「要不要联网」这件事的最终开关。</p>
     */
    public static boolean isUpdateAutoCheck(Context ctx) {
        return sp(ctx).getBoolean(K_UPDATE_AUTO_CHECK, true);
    }

    public static void setUpdateAutoCheck(Context ctx, boolean value) {
        sp(ctx).edit().putBoolean(K_UPDATE_AUTO_CHECK, value).apply();
    }

    /** 上次检查更新的时间戳（毫秒）；0 表示从未检查过。 */
    public static long lastUpdateCheckAt(Context ctx) {
        return sp(ctx).getLong(K_UPDATE_CHECKED_AT, 0L);
    }

    public static void markUpdateChecked(Context ctx) {
        sp(ctx).edit().putLong(K_UPDATE_CHECKED_AT, System.currentTimeMillis()).apply();
    }
}
