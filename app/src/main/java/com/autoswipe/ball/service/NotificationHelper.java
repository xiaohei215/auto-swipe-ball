package com.autoswipe.ball.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.autoswipe.ball.R;
import com.autoswipe.ball.core.RunState;
import com.autoswipe.ball.core.SwipeConfig;
import com.autoswipe.ball.ui.MainActivity;

/**
 * 前台服务通知的创建与更新。
 */
final class NotificationHelper {

    private static final String CHANNEL_ID = "auto_swipe_foreground";
    private static final int NOTIFICATION_ID = 0x1001;

    private NotificationHelper() {
    }

    static int notificationId() {
        return NOTIFICATION_ID;
    }

    /** 创建低优先级渠道，避免打扰用户；重复调用无副作用。 */
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(ctx.getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    /** 按当前状态构建常驻通知。 */
    static Notification build(Context ctx, RunState state, String reason, SwipeConfig config) {
        Intent contentIntent = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                ctx, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent quitIntent = new Intent(ctx, FloatingBallService.class)
                .setAction(FloatingBallService.ACTION_QUIT);
        PendingIntent quit = PendingIntent.getService(
                ctx, 1, quitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = state.label();
        if (state == RunState.ERROR_STOPPED && reason != null && !reason.isEmpty()) {
            text = state.label() + " · " + reason;
        } else if (state == RunState.RUNNING && config != null) {
            text = state.label() + " · 每 " + (config.intervalMs / 1000) + " 秒上滑一次";
        }

        return new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(ctx.getString(R.string.notif_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(
                        null, ctx.getString(R.string.notif_action_quit), quit).build())
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    static void update(Context ctx, RunState state, String reason, SwipeConfig config) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, build(ctx, state, reason, config));
        }
    }
}
