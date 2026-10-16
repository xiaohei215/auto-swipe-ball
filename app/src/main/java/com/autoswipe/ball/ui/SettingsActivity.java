package com.autoswipe.ball.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.autoswipe.ball.R;
import com.autoswipe.ball.core.MetricsReader;
import com.autoswipe.ball.core.PrefsStore;
import com.autoswipe.ball.core.ScreenMetrics;
import com.autoswipe.ball.core.SwipeConfig;
import com.autoswipe.ball.core.SwipeGeometry;
import com.autoswipe.ball.core.SwipeMotion;
import com.autoswipe.ball.service.FloatingBallService;

/**
 * 参数设置页。
 *
 * <p>三个参数都用 SeekBar 承载，拖动即时预览，点「保存并立即生效」后写盘并通知服务重载：
 * 间隔变更会重置下一次计时，距离与时长在「下一次上滑」时生效。</p>
 */
public class SettingsActivity extends Activity {

    /** 间隔步长：5s */
    private static final int INTERVAL_STEP_S = 5;
    /** 时长步长：50ms */
    private static final int DURATION_STEP_MS = 50;

    private SeekBar sbInterval;
    private SeekBar sbDuration;
    private SeekBar sbDistance;
    private CheckBox cbRing;
    private CheckBox cbAutoHeal;
    private TextView tvInterval;
    private TextView tvDuration;
    private TextView tvDistance;
    private TextView tvPreview;

    private SwipeConfig pending;
    /** 屏幕参数只在进入页面时读一次，拖动时不再反复查询系统 */
    private ScreenMetrics metrics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        metrics = MetricsReader.read(this);

        sbInterval = findViewById(R.id.sb_interval);
        sbDuration = findViewById(R.id.sb_duration);
        sbDistance = findViewById(R.id.sb_distance);
        cbRing = findViewById(R.id.cb_ring);
        cbAutoHeal = findViewById(R.id.cb_auto_heal);
        tvInterval = findViewById(R.id.tv_interval_value);
        tvDuration = findViewById(R.id.tv_duration_value);
        tvDistance = findViewById(R.id.tv_distance_value);
        tvPreview = findViewById(R.id.tv_preview);

        pending = PrefsStore.loadConfig(this);

        sbInterval.setMax((SwipeConfig.MAX_INTERVAL_MS - SwipeConfig.MIN_INTERVAL_MS)
                / (INTERVAL_STEP_S * 1000));
        sbDuration.setMax((SwipeConfig.MAX_DURATION_MS - SwipeConfig.MIN_DURATION_MS) / DURATION_STEP_MS);
        sbDistance.setMax(Math.round((SwipeConfig.MAX_DISTANCE_RATIO - SwipeConfig.MIN_DISTANCE_RATIO) * 100f));

        bindSeekBar(sbInterval, new OnValueChanged() {
            @Override
            public void onChanged(int progress) {
                pending.intervalMs = SwipeConfig.MIN_INTERVAL_MS + progress * INTERVAL_STEP_S * 1000;
            }
        });
        bindSeekBar(sbDuration, new OnValueChanged() {
            @Override
            public void onChanged(int progress) {
                pending.swipeDurationMs = SwipeConfig.MIN_DURATION_MS + progress * DURATION_STEP_MS;
            }
        });
        bindSeekBar(sbDistance, new OnValueChanged() {
            @Override
            public void onChanged(int progress) {
                pending.distanceRatio = SwipeConfig.MIN_DISTANCE_RATIO + progress / 100f;
            }
        });

        cbRing.setChecked(pending.showCountdownRing);
        cbRing.setOnCheckedChangeListener((buttonView, isChecked) -> {
            pending.showCountdownRing = isChecked;
            render();
        });

        cbAutoHeal.setChecked(pending.autoHealAccessibility);
        cbAutoHeal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            pending.autoHealAccessibility = isChecked;
            render();
        });

        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            pending = new SwipeConfig();
            cbRing.setChecked(pending.showCountdownRing);
            cbAutoHeal.setChecked(pending.autoHealAccessibility);
            pushToSeekBars();
            render();
            Toast.makeText(this, R.string.toast_reset, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            pending.clamp();
            PrefsStore.saveConfig(this, pending);
            FloatingBallService.requestReloadConfig(this);
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        });

        pushToSeekBars();
        render();
    }

    private interface OnValueChanged {
        void onChanged(int progress);
    }

    private void bindSeekBar(final SeekBar bar, final OnValueChanged changed) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                changed.onChanged(progress);
                pending.clamp();
                render();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    /** 用当前配置回填进度条（进入页面 / 恢复默认时使用）。 */
    private void pushToSeekBars() {
        sbInterval.setProgress((pending.intervalMs - SwipeConfig.MIN_INTERVAL_MS) / (INTERVAL_STEP_S * 1000));
        sbDuration.setProgress((pending.swipeDurationMs - SwipeConfig.MIN_DURATION_MS) / DURATION_STEP_MS);
        sbDistance.setProgress(Math.round((pending.distanceRatio - SwipeConfig.MIN_DISTANCE_RATIO) * 100f));
    }

    private void render() {
        tvInterval.setText(getString(R.string.fmt_seconds, pending.intervalMs / 1000));
        tvDuration.setText(getString(R.string.fmt_millis, pending.swipeDurationMs));
        tvDistance.setText(getString(R.string.fmt_percent, Math.round(pending.distanceRatio * 100)));

        SwipeMotion motion = SwipeGeometry.computeMotion(metrics, pending);
        tvPreview.setText(getString(R.string.fmt_preview,
                Math.round(motion.startX), Math.round(motion.startY),
                Math.round(motion.endX), Math.round(motion.endY),
                Math.round(motion.distancePx())));
    }
}
