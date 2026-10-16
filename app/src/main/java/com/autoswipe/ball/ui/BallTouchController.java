package com.autoswipe.ball.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 悬浮球手势识别：单击开始/暂停、长按拖动、双击退出。
 *
 * <p>自己实现而不用 {@code GestureDetector} 的原因：拖动需要「长按后跟手移动」，
 * 与双击的 300ms 判定窗口会互相干扰，集中在一处实现更易维护也更好调参。</p>
 */
public final class BallTouchController implements View.OnTouchListener {

    /** 长按判定阈值（ms） */
    private static final long LONG_PRESS_MS = 400L;
    /** 双击判定窗口（ms） */
    private static final long DOUBLE_TAP_MS = 300L;

    public interface Callback {
        /** 单击：开始 / 暂停切换 */
        void onSingleTap();

        /** 双击：退出插件 */
        void onDoubleTapExit();

        /** 长按拖动中，返回屏幕绝对坐标 */
        void onDragTo(int rawX, int rawY);
    }

    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    private float downRawX;
    private float downRawY;
    private boolean longPressFired;
    private boolean dragged;
    private long lastTapUpAt = -1L;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            longPressFired = true;
            View v = attachedView;
            if (v != null) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        }
    };

    private final Runnable pendingSingleTap = new Runnable() {
        @Override
        public void run() {
            lastTapUpAt = -1L;
            callback.onSingleTap();
        }
    };

    private View attachedView;

    public BallTouchController(View view, Callback callback) {
        this.callback = callback;
        this.attachedView = view;
        this.touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        view.setOnTouchListener(this);
    }

    /** 视图销毁时调用，避免回调泄漏。 */
    public void detach() {
        handler.removeCallbacksAndMessages(null);
        if (attachedView != null) {
            attachedView.setOnTouchListener(null);
            attachedView = null;
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                longPressFired = false;
                dragged = false;
                v.postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getRawX() - downRawX);
                float dy = Math.abs(event.getRawY() - downRawY);
                if (!longPressFired && (dx > touchSlop || dy > touchSlop)) {
                    dragged = true;
                    v.removeCallbacks(longPressRunnable);
                }
                if (longPressFired) {
                    callback.onDragTo((int) event.getRawX(), (int) event.getRawY());
                }
                return true;

            case MotionEvent.ACTION_UP:
                v.removeCallbacks(longPressRunnable);
                if (longPressFired || dragged) {
                    return true; // 拖动结束，不触发点击
                }
                long now = SystemClock.uptimeMillis();
                if (lastTapUpAt > 0 && now - lastTapUpAt <= DOUBLE_TAP_MS) {
                    lastTapUpAt = -1L;
                    v.removeCallbacks(pendingSingleTap);
                    callback.onDoubleTapExit();
                } else {
                    lastTapUpAt = now;
                    v.postDelayed(pendingSingleTap, DOUBLE_TAP_MS);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                v.removeCallbacks(longPressRunnable);
                v.removeCallbacks(pendingSingleTap);
                longPressFired = false;
                dragged = false;
                return true;

            default:
                return false;
        }
    }
}
