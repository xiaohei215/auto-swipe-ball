package com.autoswipe.ball.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

import com.autoswipe.ball.core.SwipeMotion;

/**
 * 仅负责「把滑动指令变成系统手势」的无障碍服务。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>不申请 {@code canRetrieveWindowContent}，不读取任何界面内容与用户数据；</li>
 *   <li>只实现上滑手势注入，不模拟点击，天然不会触发点赞/评论/关注；</li>
 *   <li>通过静态引用暴露连接状态，供悬浮球服务做「无障碍已断开」的故障判定。</li>
 * </ul>
 */
public class AutoSwipeAccessibilityService extends AccessibilityService {

    private static final String TAG = "AutoSwipe.A11y";

    private static volatile AutoSwipeAccessibilityService sInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 无障碍服务是否已连接（悬浮球服务用它做前置条件判定）。 */
    public static boolean isConnected() {
        return sInstance != null;
    }

    /** 供悬浮球服务调用；未连接时返回 null。 */
    public static AutoSwipeAccessibilityService get() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不监听内容：仅用于满足框架回调，不读取事件数据
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        Log.w(TAG, "无障碍服务已被解除绑定");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    /**
     * 执行一次上滑手势。
     *
     * @param motion 由 {@link com.autoswipe.ball.core.SwipeGeometry} 计算出的起止坐标
     * @return 是否被系统受理（false 表示服务未就绪或上一次手势尚未结束）
     */
    public boolean performUpSwipe(SwipeMotion motion) {
        if (motion == null) {
            return false;
        }
        Path path = new Path();
        path.moveTo(motion.startX, motion.startY);
        path.lineTo(motion.endX, motion.endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, motion.durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean accepted = dispatchGesture(gesture, null, mainHandler);
        if (!accepted) {
            Log.w(TAG, "dispatchGesture 未受理: " + motion);
        }
        return accepted;
    }
}
