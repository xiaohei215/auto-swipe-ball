package com.autoswipe.ball.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import com.autoswipe.ball.App;
import com.autoswipe.ball.R;
import com.autoswipe.ball.core.MetricsReader;
import com.autoswipe.ball.core.PrefsStore;
import com.autoswipe.ball.core.RunState;
import com.autoswipe.ball.core.ScreenMetrics;
import com.autoswipe.ball.core.StateMachine;
import com.autoswipe.ball.core.SwipeConfig;
import com.autoswipe.ball.core.SwipeGeometry;
import com.autoswipe.ball.core.SwipeMotion;
import com.autoswipe.ball.ui.BallTouchController;
import com.autoswipe.ball.ui.BallView;
import com.autoswipe.ball.ui.PermissionUtils;

/**
 * 悬浮球前台服务：承载悬浮球的显示/交互与上滑定时器。
 *
 * <p>职责边界（规则「模块划分清晰，悬浮球逻辑、手势逻辑、定时逻辑解耦」）：</p>
 * <ul>
 *   <li>{@link BallView}   —— 只负责画；</li>
 *   <li>{@link BallTouchController} —— 只负责手势识别；</li>
 *   <li>{@link StateMachine} —— 只负责状态迁移；</li>
 *   <li>{@link AutoSwipeAccessibilityService} —— 只负责手势注入；</li>
 *   <li>本类 —— 只做编排：定时器、悬浮窗生命周期、故障检测与用户提示。</li>
 * </ul>
 */
public class FloatingBallService extends Service {

    private static final String TAG = "AutoSwipe.Ball";

    public static final String ACTION_LAUNCH = "com.autoswipe.ball.action.LAUNCH";
    public static final String ACTION_QUIT = "com.autoswipe.ball.action.QUIT";
    public static final String ACTION_RELOAD_CONFIG = "com.autoswipe.ball.action.RELOAD_CONFIG";

    /** 悬浮球重绘节拍（ms）：仅在运行中/已暂停时运行，用于倒计时环 */
    private static final long TICK_MS = 250L;
    /** 每 N 个节拍做一次环境体检，约 1.5s 一次 */
    private static final int HEALTH_CHECK_TICKS = 6;
    /** 手势连续失败多少次判定为异常 */
    private static final int MAX_GESTURE_FAILURES = 3;
    /** 无障碍断开后，在判定「异常停止」之前先尝试自动恢复的轮次（每轮约 1.5 秒） */
    private static final int MAX_HEAL_ATTEMPTS = 3;

    private static volatile FloatingBallService sInstance;

    private WindowManager windowManager;
    private BallView ballView;
    private BallTouchController touchController;
    private WindowManager.LayoutParams ballParams;

    private StateMachine stateMachine;
    private SwipeConfig config;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int tickCount;
    private int gestureFailures;
    private int healAttempts;
    private boolean healToastShown;
    private long nextSwipeAt;
    private boolean loopRunning;
    private boolean ballAttached;
    private int screenWidth;
    private int screenHeight;

    public static boolean isRunning() {
        return sInstance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        NotificationHelper.ensureChannel(this);
        config = PrefsStore.loadConfig(this);
        stateMachine = new StateMachine(new PermissionPrecondition());
        stateMachine.addListener(new StateMachine.Listener() {
            @Override
            public void onStateChanged(RunState from, RunState to, String reason) {
                onStateChangedInternal(from, to, reason);
            }
        });

        startForeground(NotificationHelper.notificationId(),
                NotificationHelper.build(this, stateMachine.state(), stateMachine.reason(), config));

        // 前台服务被系统清理后 START_STICKY 重启：上次处于运行中/已暂停 → 明确进入异常停止
        RunState previous = PrefsStore.loadRuntimeState(this);
        if (previous == RunState.RUNNING || previous == RunState.PAUSED) {
            stateMachine.markInterrupted(getString(R.string.tip_service_interrupted));
        }

        if (!PermissionUtils.canDrawOverlays(this)) {
            // 无悬浮窗权限时无法承载交互入口，直接告知并退出，避免留下一个「静默无效」的服务
            Toast.makeText(this, R.string.tip_need_overlay, Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        attachBall();
        publish();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_QUIT.equals(action)) {
            quit();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD_CONFIG.equals(action)) {
            applyConfig(PrefsStore.loadConfig(this));
            return START_STICKY;
        }
        if (!ballAttached && PermissionUtils.canDrawOverlays(this)) {
            attachBall();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 仅以 startService 方式运行
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        stopLoop();
        detachBall();
        if (stateMachine != null) {
            PrefsStore.saveRuntime(this, stateMachine.state(), stateMachine.reason());
        }
        publish();
        super.onDestroy();
    }

    /** 外部启动悬浮球（主界面 / 通知栏均走这里）。 */
    public static void launch(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, FloatingBallService.class)
                .setAction(ACTION_LAUNCH));
    }

    /** 外部（设置页 / 通知栏）更新配置：立即生效并重置计时。服务未运行时不做任何事。 */
    public static void requestReloadConfig(Context ctx) {
        if (!isRunning()) {
            return;
        }
        ctx.startService(new Intent(ctx, FloatingBallService.class)
                .setAction(ACTION_RELOAD_CONFIG));
    }

    // ------------------------------------------------------------------
    // 悬浮窗
    // ------------------------------------------------------------------

    private void attachBall() {
        if (ballAttached) {
            return;
        }
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            stateMachine.fault(getString(R.string.tip_fault_overlay));
            return;
        }
        ballView = new BallView(this);
        ballView.setState(stateMachine.state(), config.showCountdownRing);

        // 屏幕尺寸只在此处读一次，拖动时直接用缓存，避免每个 MOVE 事件都查系统
        ScreenMetrics metrics = MetricsReader.read(this);
        screenWidth = metrics.widthPx;
        screenHeight = metrics.heightPx;

        int size = (int) (52 * getResources().getDisplayMetrics().density);
        ballParams = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        // FLAG_LAYOUT_IN_SCREEN 让窗口坐标系以「整块屏幕」为原点。
                        // 不加它时会踩一个隐蔽的坑：父窗口 frame 是从状态栏下方开始的，
                        // 真机实测 offset 达 108px（Frames: parent=[0,108][1080,2400]），
                        // 于是这里设定的 y 会被整体下移 108px —— 球比预期低，
                        // 而且在拖动夹取时永远摸不到屏幕最顶端。
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = (int) (screenWidth * 0.86f);
        ballParams.y = (int) (screenHeight * 0.55f);

        touchController = new BallTouchController(ballView, new BallTouchController.Callback() {
            @Override
            public void onSingleTap() {
                stateMachine.toggle();
            }

            @Override
            public void onDoubleTapExit() {
                quit();
            }

            @Override
            public void onDragTo(int rawX, int rawY) {
                moveBall(rawX, rawY);
            }
        });

        try {
            windowManager.addView(ballView, ballParams);
            ballAttached = true;
        } catch (Exception e) {
            Log.e(TAG, "添加悬浮球失败", e);
            stateMachine.fault(getString(R.string.tip_fault_overlay));
        }
    }

    private void detachBall() {
        if (touchController != null) {
            touchController.detach();
            touchController = null;
        }
        if (ballAttached && windowManager != null && ballView != null) {
            try {
                windowManager.removeView(ballView);
            } catch (Exception e) {
                Log.w(TAG, "移除悬浮球失败", e);
            }
        }
        ballAttached = false;
        ballView = null;
    }

    /** 拖动时把悬浮球限制在屏幕内，避免拖出可视区域后无法再操作。 */
    private void moveBall(int rawX, int rawY) {
        if (!ballAttached || ballView == null || windowManager == null) {
            return;
        }
        int size = ballParams.width;
        int maxX = Math.max(0, screenWidth - size);
        int maxY = Math.max(0, screenHeight - size);
        ballParams.x = Math.max(0, Math.min(maxX, rawX - size / 2));
        ballParams.y = Math.max(0, Math.min(maxY, rawY - size / 2));
        try {
            windowManager.updateViewLayout(ballView, ballParams);
        } catch (Exception e) {
            Log.w(TAG, "拖动悬浮球失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 状态响应
    // ------------------------------------------------------------------

    private void onStateChangedInternal(RunState from, RunState to, String reason) {
        Log.i(TAG, "状态变化 " + from + " -> " + to + " " + reason);
        if (ballView != null) {
            ballView.setState(to, config.showCountdownRing);
        }
        NotificationHelper.update(this, to, reason, config);
        PrefsStore.saveRuntime(this, to, reason);
        publish();

        if (to == RunState.RUNNING) {
            nextSwipeAt = SystemClock.uptimeMillis() + config.intervalMs;
            gestureFailures = 0;
            healAttempts = 0;
            healToastShown = false;
            startLoop();
        } else if (to == RunState.PAUSED) {
            startLoop(); // 保持体检节拍，便于及时发现权限被回收
        } else {
            stopLoop();
        }

        if (to == RunState.ERROR_STOPPED && reason != null && !reason.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_fault_prefix, reason), Toast.LENGTH_LONG).show();
        }
    }

    private void publish() {
        App.publish(stateMachine.state(), stateMachine.reason(), config);
    }

    private void quit() {
        stateMachine.stop();
        // 状态变化回调里已停止定时器并落盘，这里只需结束服务
        Toast.makeText(this, R.string.toast_quit, Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    private void applyConfig(SwipeConfig newConfig) {
        config = newConfig;
        if (ballView != null) {
            ballView.setState(stateMachine.state(), config.showCountdownRing);
        }
        if (stateMachine.isRunning()) {
            // 间隔变更立即生效：从「现在 + 新间隔」重新计时
            nextSwipeAt = SystemClock.uptimeMillis() + config.intervalMs;
        }
        NotificationHelper.update(this, stateMachine.state(), stateMachine.reason(), config);
        publish();
    }

    // ------------------------------------------------------------------
    // 定时器
    // ------------------------------------------------------------------

    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            if (!loopRunning) {
                return;
            }
            long now = SystemClock.uptimeMillis();

            if (stateMachine.isRunning()) {
                if (ensureAccessibility() && now >= nextSwipeAt) {
                    performSwipe();
                    nextSwipeAt = SystemClock.uptimeMillis() + config.intervalMs;
                }
                updateCountdown(now);
            }

            if (++tickCount % HEALTH_CHECK_TICKS == 0) {
                healthCheck();
            }
            // performSwipe / healthCheck 可能触发故障停机（loopRunning 变 false），此处必须复检
            if (!loopRunning) {
                return;
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void startLoop() {
        if (loopRunning) {
            return;
        }
        loopRunning = true;
        tickCount = 0;
        handler.postDelayed(tickTask, TICK_MS);
    }

    private void stopLoop() {
        loopRunning = false;
        handler.removeCallbacks(tickTask);
    }

    private void updateCountdown(long now) {
        if (ballView == null || !config.showCountdownRing || config.intervalMs <= 0) {
            return;
        }
        long remaining = nextSwipeAt - now;
        float elapsed = 1f - (remaining / (float) config.intervalMs);
        ballView.setCountdownProgress(elapsed);
    }

    private void performSwipe() {
        AutoSwipeAccessibilityService service = AutoSwipeAccessibilityService.get();
        if (service == null) {
            ensureAccessibility(); // 内部会先尝试自愈，失败才进入异常停止
            return;
        }
        SwipeMotion motion = SwipeGeometry.computeMotion(MetricsReader.read(this), config);
        if (service.performUpSwipe(motion)) {
            gestureFailures = 0;
            if (ballView != null) {
                ballView.setCountdownProgress(0f);
            }
        } else if (++gestureFailures >= MAX_GESTURE_FAILURES) {
            gestureFailures = 0;
            stateMachine.fault(getString(R.string.tip_fault_gesture));
        }
    }

    /**
     * 确保手势注入通道可用。
     *
     * <p>顺序是「先自愈，再判定故障」：无障碍被系统关闭时（force-stop / 一键清理 /
     * 省电策略的常见后果），若已获得 {@code WRITE_SECURE_SETTINGS} 且用户开启了
     * 自动恢复，则把本服务写回系统启用名单，等系统重新绑定；
     * 连续 {@link #MAX_HEAL_ATTEMPTS} 轮仍未恢复，才进入「异常停止」并提示用户。</p>
     *
     * @return true 表示当前可用，可以继续执行上滑
     */
    private boolean ensureAccessibility() {
        if (AutoSwipeAccessibilityService.get() != null) {
            healAttempts = 0;
            healToastShown = false;
            return true;
        }
        boolean canHeal = config.autoHealAccessibility
                && AccessibilityHealer.canWriteSecureSettings(this);
        if (canHeal && healAttempts < MAX_HEAL_ATTEMPTS) {
            if (!healToastShown) {
                healToastShown = true;
                Toast.makeText(this, R.string.toast_healing, Toast.LENGTH_SHORT).show();
            }
            boolean written = AccessibilityHealer.heal(this);
            healAttempts++;
            Log.i(TAG, "无障碍未连接，自动恢复尝试 " + healAttempts + "/" + MAX_HEAL_ATTEMPTS
                    + "，写入结果=" + written);
            return false; // 本轮跳过，等系统绑定
        }
        stateMachine.fault(getString(R.string.tip_fault_accessibility));
        return false;
    }

    /**
     * 环境体检：悬浮窗权限被回收 / 无障碍断开（含自动恢复）。
     * 只在运行中或已暂停时执行，未运行时完全不轮询。
     */
    private void healthCheck() {
        if (!PermissionUtils.canDrawOverlays(this)) {
            detachBall();
            stateMachine.fault(getString(R.string.tip_fault_overlay));
            return;
        }
        ensureAccessibility();
    }

    // ------------------------------------------------------------------
    // 前置条件
    // ------------------------------------------------------------------

    /** 把 Android 世界的权限检测适配成 {@link StateMachine} 需要的纯逻辑接口。 */
    private final class PermissionPrecondition implements StateMachine.Precondition {
        @Override
        public boolean overlayGranted() {
            return PermissionUtils.canDrawOverlays(FloatingBallService.this);
        }

        @Override
        public boolean accessibilityConnected() {
            return PermissionUtils.isAccessibilityEnabled(FloatingBallService.this);
        }

        @Override
        public String firstMissingReason() {
            return PermissionUtils.firstMissingReason(FloatingBallService.this);
        }
    }
}
