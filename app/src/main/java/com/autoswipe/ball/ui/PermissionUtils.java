package com.autoswipe.ball.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

import com.autoswipe.ball.service.AutoSwipeAccessibilityService;

/**
 * 权限检测与跳转引导：悬浮窗、无障碍、电池优化、厂商自启动白名单。
 *
 * <p>所有跳转都带 try/catch 降级：国产 ROM 权限页 Activity 名称差异极大，
 * 找不到目标页时统一退到「应用详情页」，不让用户卡在崩溃上。</p>
 */
public final class PermissionUtils {

    private PermissionUtils() {
    }

    /** 是否已授予悬浮窗（显示在其他应用上层）权限。 */
    public static boolean canDrawOverlays(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** 无障碍服务是否已被用户开启并连接。 */
    public static boolean isAccessibilityEnabled(Context ctx) {
        if (AutoSwipeAccessibilityService.isConnected()) {
            return true;
        }
        ComponentName expected = new ComponentName(ctx, AutoSwipeAccessibilityService.class);
        String enabled = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        // 系统以「包名/服务类名:包名/服务类名」形式返回，逐个比对
        for (String item : enabled.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(item);
            if (cn != null && cn.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    /** 是否已加入电池优化白名单。 */
    public static boolean isIgnoringBatteryOptimizations(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    public static void openOverlaySettings(Activity activity) {
        safeStart(activity, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName())));
    }

    /**
     * 打开电池优化设置页。
     */
    public static void openBatteryOptimizationSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.getPackageName()));
        safeStart(activity, intent);
    }

    /**
     * 尝试打开厂商自启动/后台管理页（iQOO / vivo 的 OriginOS 尤为关键）。
     * 全部失败时退到应用详情页，由用户手动进入「自启动」与「后台高耗电」白名单。
     */
    public static void openAutoStartSettings(Activity activity) {
        String[][] candidates = {
                // vivo / iQOO OriginOS
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                // 其他常见厂商
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
        };
        for (String[] candidate : candidates) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(candidate[0], candidate[1]));
            if (tryStart(activity, intent)) {
                return;
            }
        }
        openAppDetails(activity);
    }

    public static void openAppDetails(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()));
        safeStart(activity, intent);
    }

    /** 系统设置页是否可解析，避免 ActivityNotFoundException 崩溃。 */
    private static boolean tryStart(Activity activity, Intent intent) {
        try {
            if (activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                return false;
            }
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void safeStart(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }

    /** 用于界面回显的权限缺失原因（全部满足时返回 null）。 */
    public static String firstMissingReason(Context ctx) {
        if (!canDrawOverlays(ctx)) {
            return ctx.getString(com.autoswipe.ball.R.string.tip_need_overlay);
        }
        if (!isAccessibilityEnabled(ctx)) {
            return ctx.getString(com.autoswipe.ball.R.string.tip_need_accessibility);
        }
        return null;
    }
}
