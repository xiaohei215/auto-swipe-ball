package com.autoswipe.ball.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

import com.autoswipe.ball.R;

/**
 * 接收系统安装器的会话回执。
 *
 * <p>普通应用提交安装会话后，系统一般不会直接安装，而是回报
 * {@link PackageInstaller#STATUS_PENDING_USER_ACTION} 并附带一个「确认安装」的 Intent。
 * 这里负责把它拉起来，用户确认后才真正完成安装。这是 Android 对非系统应用的强制要求，
 * 任何侧载应用的自更新都要走这一步。</p>
 */
public class InstallResultReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoSwipe.Install";

    public static final String ACTION_INSTALL_RESULT = "com.autoswipe.ball.action.INSTALL_RESULT";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        Log.i(TAG, "收到安装回执 status=" + status);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                Toast.makeText(context, R.string.update_confirm_missing, Toast.LENGTH_LONG).show();
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (Exception e) {
                Log.w(TAG, "拉起安装确认界面失败", e);
                Toast.makeText(context, R.string.update_confirm_failed, Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            // 更新自己的场景下当前进程即将被替换，这条提示多半看不到，但仍保留以便调试
            UpdateManager.clearCache(context);
            Toast.makeText(context, R.string.update_installed, Toast.LENGTH_LONG).show();
            return;
        }

        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (detail == null || detail.trim().isEmpty()) {
            detail = "错误码 " + status;
        }
        Log.w(TAG, "安装未完成: " + detail);
        Toast.makeText(context,
                context.getString(R.string.fmt_update_install_failed, detail),
                Toast.LENGTH_LONG).show();
    }
}
