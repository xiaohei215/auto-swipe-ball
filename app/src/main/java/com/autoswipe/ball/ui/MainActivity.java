package com.autoswipe.ball.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.autoswipe.ball.App;
import com.autoswipe.ball.R;
import com.autoswipe.ball.core.MetricsReader;
import com.autoswipe.ball.core.PrefsStore;
import com.autoswipe.ball.core.RunState;
import com.autoswipe.ball.core.ScreenMetrics;
import com.autoswipe.ball.core.StateHub;
import com.autoswipe.ball.core.SwipeConfig;
import com.autoswipe.ball.core.SwipeGeometry;
import com.autoswipe.ball.core.SwipeMotion;
import com.autoswipe.ball.service.AccessibilityHealer;
import com.autoswipe.ball.service.FloatingBallService;

/**
 * 主界面：权限引导 + 运行状态回显 + 悬浮球控制入口 + 无障碍修复。
 *
 * <p>状态展示不自行轮询，而是订阅 {@link StateHub}，与服务端保持单一数据源。</p>
 */
public class MainActivity extends Activity implements StateHub.Observer {

    private static final int REQ_NOTIFICATION = 1001;
    /** 自动恢复后等待系统重新绑定无障碍服务的复查延迟（ms） */
    private static final long[] RECHECK_DELAYS = {600L, 1500L, 3000L};

    private TextView tvState;
    private TextView tvReason;
    private TextView tvScreen;
    private TextView tvOverlay;
    private TextView tvAccessibility;
    private TextView tvBattery;
    private Button btnBall;

    private View layoutFix;
    private TextView tvFixDesc;
    private TextView tvAdbStatus;
    private Button btnAutoHeal;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int recheckIndex;

    private final Runnable recheckTask = new Runnable() {
        @Override
        public void run() {
            refreshPermissionRows();
            if (recheckIndex < RECHECK_DELAYS.length - 1) {
                recheckIndex++;
                uiHandler.postDelayed(this, RECHECK_DELAYS[recheckIndex]);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvState = findViewById(R.id.tv_state);
        tvReason = findViewById(R.id.tv_reason);
        tvScreen = findViewById(R.id.tv_screen);
        tvOverlay = findViewById(R.id.tv_overlay_status);
        tvAccessibility = findViewById(R.id.tv_accessibility_status);
        tvBattery = findViewById(R.id.tv_battery_status);
        btnBall = findViewById(R.id.btn_ball);

        layoutFix = findViewById(R.id.layout_fix);
        tvFixDesc = findViewById(R.id.tv_fix_desc);
        tvAdbStatus = findViewById(R.id.tv_adb_status);
        btnAutoHeal = findViewById(R.id.btn_auto_heal);

        findViewById(R.id.btn_overlay).setOnClickListener(v -> PermissionUtils.openOverlaySettings(this));
        findViewById(R.id.btn_accessibility).setOnClickListener(
                v -> AccessibilityHealer.openServiceDetailSettings(this));
        findViewById(R.id.btn_battery).setOnClickListener(v -> PermissionUtils.openBatteryOptimizationSettings(this));
        findViewById(R.id.btn_autostart).setOnClickListener(v -> PermissionUtils.openAutoStartSettings(this));
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnBall.setOnClickListener(v -> onBallButtonClicked());

        btnAutoHeal.setOnClickListener(v -> onAutoHealClicked());
        findViewById(R.id.btn_manual_open).setOnClickListener(
                v -> AccessibilityHealer.openServiceDetailSettings(this));
        findViewById(R.id.btn_app_info).setOnClickListener(
                v -> AccessibilityHealer.openAppDetails(this));

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        StateHub hub = App.hub();
        if (hub != null) {
            hub.addObserver(this);
        }
        refreshStaticInfo();
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(recheckTask);
        StateHub hub = App.hub();
        if (hub != null) {
            hub.removeObserver(this);
        }
        super.onPause();
    }

    @Override
    public void onHubChanged(StateHub.Snapshot snapshot) {
        renderState(snapshot.state, snapshot.reason, snapshot.config);
        refreshPermissionRows();
    }

    // ------------------------------------------------------------------

    private void refreshStaticInfo() {
        ScreenMetrics m = MetricsReader.read(this);
        SwipeMotion motion = SwipeGeometry.computeMotion(m, PrefsStore.loadConfig(this));
        tvScreen.setText(getString(R.string.fmt_screen,
                m.widthPx, m.heightPx, m.densityDpi,
                Math.round(motion.startY), Math.round(motion.endY),
                Math.round(motion.distancePx()), Math.round(motion.startX)));
        refreshPermissionRows();
        renderState(PrefsStore.loadRuntimeState(this), PrefsStore.loadRuntimeReason(this),
                PrefsStore.loadConfig(this));
        btnBall.setText(FloatingBallService.isRunning()
                ? R.string.btn_stop_ball : R.string.btn_start_ball);
    }

    private void refreshPermissionRows() {
        boolean overlay = PermissionUtils.canDrawOverlays(this);
        boolean accessibility = PermissionUtils.isAccessibilityEnabled(this);
        boolean battery = PermissionUtils.isIgnoringBatteryOptimizations(this);
        applyRow(tvOverlay, getString(R.string.row_overlay), overlay);
        applyRow(tvAccessibility, getString(R.string.row_accessibility), accessibility);
        applyRow(tvBattery, getString(R.string.row_battery), battery);
        renderFixCard(accessibility);
    }

    /**
     * 无障碍被关闭时展示修复卡片。
     *
     * <p>卡片内容随是否已获得 {@code WRITE_SECURE_SETTINGS} 一次性授权而变：
     * 已授权则突出「一键自动恢复」，未授权则说明手动路径与授权方法。</p>
     */
    private void renderFixCard(boolean accessibilityOk) {
        layoutFix.setVisibility(accessibilityOk ? View.GONE : View.VISIBLE);
        if (accessibilityOk) {
            uiHandler.removeCallbacks(recheckTask);
            return;
        }
        boolean canHeal = AccessibilityHealer.canWriteSecureSettings(this);
        btnAutoHeal.setVisibility(canHeal ? View.VISIBLE : View.GONE);
        tvFixDesc.setText(canHeal ? R.string.fix_desc_auto : R.string.fix_desc_manual);
        tvAdbStatus.setText(canHeal ? R.string.adb_granted : R.string.adb_not_granted);
        tvAdbStatus.setTextColor(getColor(canHeal ? R.color.state_ok : R.color.text_secondary));
    }

    /** 点「一键自动恢复」：把本服务写回系统无障碍名单，然后分几次复查绑定结果。 */
    private void onAutoHealClicked() {
        if (!AccessibilityHealer.canWriteSecureSettings(this)) {
            Toast.makeText(this, R.string.toast_heal_need_grant, Toast.LENGTH_LONG).show();
            return;
        }
        boolean written = AccessibilityHealer.heal(this);
        Toast.makeText(this, written ? R.string.toast_healed : R.string.toast_heal_failed,
                Toast.LENGTH_LONG).show();
        if (written) {
            // 系统绑定是异步的，隔一段时间复查几次，恢复后卡片会自动消失
            uiHandler.removeCallbacks(recheckTask);
            recheckIndex = 0;
            uiHandler.postDelayed(recheckTask, RECHECK_DELAYS[0]);
        }
        refreshPermissionRows();
    }

    private void applyRow(TextView view, String label, boolean granted) {
        view.setText(getString(R.string.fmt_row, label, granted ? "已开启" : "未开启"));
        view.setTextColor(getColor(granted ? R.color.state_ok : R.color.state_bad));
    }

    private void renderState(RunState state, String reason, SwipeConfig config) {
        RunState safeState = state == null ? RunState.NOT_STARTED : state;
        tvState.setText(getString(R.string.fmt_state, safeState.label()));
        tvState.setTextColor(safeState.colorArgb());

        StringBuilder sb = new StringBuilder();
        if (reason != null && !reason.isEmpty()) {
            sb.append(reason);
        }
        sb.append(sb.length() == 0 ? "" : "　|　")
                .append(getString(R.string.fmt_config,
                        config.intervalMs / 1000, config.swipeDurationMs,
                        Math.round(config.distanceRatio * 100)));
        tvReason.setText(sb.toString());
    }

    private void onBallButtonClicked() {
        if (FloatingBallService.isRunning()) {
            startService(new Intent(this, FloatingBallService.class)
                    .setAction(FloatingBallService.ACTION_QUIT));
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();
            btnBall.setText(R.string.btn_start_ball);
            return;
        }
        String missing = PermissionUtils.firstMissingReason(this);
        if (missing != null) {
            if (!PermissionUtils.canDrawOverlays(this)) {
                Toast.makeText(this, missing, Toast.LENGTH_LONG).show();
                PermissionUtils.openOverlaySettings(this);
                return;
            }
            // 无障碍缺失：已授权就静默恢复后继续启动；未授权则引导到本服务的开关页
            if (AccessibilityHealer.canWriteSecureSettings(this)) {
                AccessibilityHealer.heal(this);
                Toast.makeText(this, R.string.toast_healed, Toast.LENGTH_SHORT).show();
                uiHandler.removeCallbacks(recheckTask);
                recheckIndex = 0;
                uiHandler.postDelayed(recheckTask, RECHECK_DELAYS[0]);
            } else {
                Toast.makeText(this, missing, Toast.LENGTH_LONG).show();
                AccessibilityHealer.openServiceDetailSettings(this);
                return;
            }
        }
        FloatingBallService.launch(this);
        Toast.makeText(this, R.string.toast_ball_launched, Toast.LENGTH_LONG).show();
        btnBall.setText(R.string.btn_stop_ball);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }
}
