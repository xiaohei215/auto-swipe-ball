package com.autoswipe.ball.service;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 无障碍服务的「保活 / 自愈」工具。
 *
 * <h3>问题背景</h3>
 * Android 把「哪些无障碍服务处于开启状态」记录在系统安全设置
 * {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}（冒号分隔的平坦化组件名）里。
 * 当本应用被 <b>force-stop</b>（强行停止、国产 ROM 的「一键清理」、省电策略杀进程）时，
 * 系统会把本服务从这份名单里<b>自动移除</b>，于是下次打开应用就发现无障碍又关上了——
 * 这是 Android 自身的行为，不是插件的 bug。
 *
 * <h3>解法</h3>
 * 读回名单、把本服务补进去、写回名单，系统便会重新绑定服务。
 * 写安全设置需要 {@code WRITE_SECURE_SETTINGS}，该权限的 protectionLevel 含
 * {@code development}，因此系统设置里没有开关，也不会自动授予，
 * 但可以由用户通过 <b>一次</b> ADB 命令授予：
 * <pre>adb shell pm grant &lt;包名&gt; android.permission.WRITE_SECURE_SETTINGS</pre>
 * 授权后长期有效（除非卸载重装），本应用即可在无障碍被关闭时静默恢复，
 * 用户无需再进系统设置手动打开。
 *
 * <p>安全边界：本类<b>只读写这一个与本应用功能直接相关的键</b>，
 * 写入时会保留名单中其他服务的条目，绝不整表覆盖、不触碰任何其他安全设置。</p>
 */
public final class AccessibilityHealer {

    private static final String TAG = "AutoSwipe.Healer";

    /** 已启用的无障碍服务名单（冒号分隔的平坦化组件名） */
    public static final String SECURE_ENABLED_SERVICES = "enabled_accessibility_services";
    /** 无障碍功能总开关 */
    public static final String SECURE_ACCESSIBILITY_ENABLED = "accessibility_enabled";

    private AccessibilityHealer() {
    }

    /** 本无障碍服务的平坦化组件名：{@code 包名/类名}。 */
    public static String flattenedComponent(Context ctx) {
        return new ComponentName(ctx, AutoSwipeAccessibilityService.class).flattenToString();
    }

    /**
     * 是否已通过 ADB 一次性授予 {@code WRITE_SECURE_SETTINGS}。
     * 只有为 true 时自动恢复才可用。
     */
    public static boolean canWriteSecureSettings(Context ctx) {
        return ctx.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 尝试把本服务写回启用名单（自愈）。
     *
     * @return true 表示写入成功（系统随后会自行绑定，通常几十到几百毫秒）；
     *         false 表示没有权限或写入被系统拒绝
     */
    public static boolean heal(Context ctx) {
        if (!canWriteSecureSettings(ctx)) {
            Log.w(TAG, "未获得 WRITE_SECURE_SETTINGS，无法自动恢复无障碍");
            return false;
        }
        final String target = flattenedComponent(ctx);
        try {
            String current = readEnabledServices(ctx);

            // 用 LinkedHashSet 保留原有顺序并去重，绝不丢弃其他无障碍服务的条目
            Set<String> services = new LinkedHashSet<>();
            if (!TextUtils.isEmpty(current)) {
                for (String item : current.split(":")) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        services.add(trimmed);
                    }
                }
            }
            boolean changed = services.add(target);

            boolean ok = true;
            if (changed) {
                ok = Settings.Secure.putString(ctx.getContentResolver(),
                        SECURE_ENABLED_SERVICES, TextUtils.join(":", services));
            }
            // 总开关必须为 1，否则即使名单里有本服务系统也不会绑定
            if (ok && !isAccessibilityMasterEnabled(ctx)) {
                ok = Settings.Secure.putString(ctx.getContentResolver(),
                        SECURE_ACCESSIBILITY_ENABLED, "1");
            }
            Log.i(TAG, "heal() changed=" + changed + " ok=" + ok + " list=" + TextUtils.join(":", services));
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "写入无障碍安全设置失败", e);
            return false;
        }
    }

    /** 无障碍功能总开关是否为开。 */
    public static boolean isAccessibilityMasterEnabled(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), SECURE_ACCESSIBILITY_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readEnabledServices(Context ctx) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(), SECURE_ENABLED_SERVICES);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 跳转到<b>本服务自己的</b>无障碍开关页，用户只需点一下开关，
     * 不用在一长串服务列表里找。
     *
     * <p>{@code ACTION_ACCESSIBILITY_DETAILS_SETTINGS} 是系统内部接口
     * （{@code android.settings.ACCESSIBILITY_DETAILS_SETTINGS}），
     * 需要携带 {@link Intent#EXTRA_COMPONENT_NAME}，且系统传入的是
     * <b>平坦化字符串</b>而非 ComponentName 对象，这里照此传递。
     * 部分 ROM 没有实现该页面，因此逐级降级到无障碍列表页、应用详情页。</p>
     */
    public static void openServiceDetailSettings(Activity activity) {
        Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        detail.putExtra(Intent.EXTRA_COMPONENT_NAME, flattenedComponent(activity));
        if (tryStart(activity, detail)) {
            return;
        }
        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (tryStart(activity, list)) {
            return;
        }
        Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + activity.getPackageName()));
        tryStart(activity, appInfo);
    }

    /**
     * 跳转到「应用详情」页 —— Android 13+ 侧载应用的无障碍开关默认被「受限设置」拦住，
     * 需要在应用详情的右上角菜单里点「允许受限设置」才能打开无障碍。
     */
    public static void openAppDetails(Activity activity) {
        tryStart(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + activity.getPackageName())));
    }

    private static boolean tryStart(Activity activity, Intent intent) {
        try {
            if (activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                return false;
            }
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "跳转失败: " + intent.getAction(), e);
            return false;
        }
    }
}
