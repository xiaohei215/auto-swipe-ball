package com.autoswipe.ball.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.autoswipe.ball.core.RunState;

/**
 * 悬浮球视图：用「颜色 + 图形 + 倒计时环」表达四种运行状态，不依赖任何贴图资源。
 *
 * <p>状态表现：</p>
 * <ul>
 *   <li>未启动（灰）· 播放三角</li>
 *   <li>运行中（绿）· 暂停双竖条（点击即暂停）</li>
 *   <li>已暂停（琥珀）· 播放三角</li>
 *   <li>异常停止（红）· 感叹号</li>
 * </ul>
 */
public class BallView extends View {

    private static final float RING_WIDTH_DP = 3f;
    private static final float GLOW_EXTRA_DP = 2.5f;
    private static final float GLYPH_RATIO = 0.34f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();

    private RunState state = RunState.NOT_STARTED;
    private float ringProgress = 0f;
    private boolean ringVisible = true;
    private final float density;

    public BallView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        fillPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(RING_WIDTH_DP * density);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setColor(0xFFFFFFFF);
        applyStateColors();
    }

    /** 更新状态并重绘。 */
    public void setState(RunState newState, boolean showRing) {
        this.state = newState == null ? RunState.NOT_STARTED : newState;
        this.ringVisible = showRing;
        applyStateColors();
        invalidate();
    }

    /** 倒计时进度：0 = 刚完成一次上滑，1 = 即将触发下一次。 */
    public void setCountdownProgress(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (Math.abs(clamped - ringProgress) < 0.004f) {
            return; // 变化过小时跳过重绘，降低耗电
        }
        ringProgress = clamped;
        invalidate();
    }

    private void applyStateColors() {
        int color = state.colorArgb();
        fillPaint.setColor(color);
        ringPaint.setColor(color);
        glowPaint.setColor((color & 0x00FFFFFF) | 0x33000000);
        glyphPaint.setColor(0xFFFFFFFF);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        if (size <= 0) {
            size = (int) (52 * density);
        }
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float ringWidth = RING_WIDTH_DP * density;
        final float glowExtra = GLOW_EXTRA_DP * density;
        final float radius = Math.min(cx, cy) - ringWidth - glowExtra;
        if (radius <= 1f) {
            return;
        }

        // 外圈柔光，提升深色视频背景下的辨识度
        canvas.drawCircle(cx, cy, radius + glowExtra, glowPaint);
        canvas.drawCircle(cx, cy, radius, fillPaint);

        if (ringVisible && state.isActive()) {
            ringRect.set(cx - radius - ringWidth, cy - radius - ringWidth,
                    cx + radius + ringWidth, cy + radius + ringWidth);
            ringPaint.setAlpha(70);
            canvas.drawArc(ringRect, -90f, 360f, false, ringPaint);
            ringPaint.setAlpha(235);
            canvas.drawArc(ringRect, -90f, 360f * ringProgress, false, ringPaint);
        }

        drawGlyph(canvas, cx, cy, radius * GLYPH_RATIO);
    }

    private void drawGlyph(Canvas canvas, float cx, float cy, float unit) {
        switch (state) {
            case RUNNING:
                // 暂停：两条竖条，提示「点击即暂停」
                drawRoundBar(canvas, cx - unit * 0.42f, cy, unit);
                drawRoundBar(canvas, cx + unit * 0.42f, cy, unit);
                break;
            case ERROR_STOPPED:
                // 感叹号
                drawRoundBar(canvas, cx, cy - unit * 0.42f, unit * 0.92f);
                canvas.drawCircle(cx, cy + unit * 0.72f, unit * 0.20f, glyphPaint);
                break;
            case NOT_STARTED:
            case PAUSED:
            default:
                drawPlayTriangle(canvas, cx, cy, unit);
                break;
        }
    }

    private void drawRoundBar(Canvas canvas, float cx, float cy, float unit) {
        float halfW = unit * 0.16f;
        float halfH = unit * 0.62f;
        ringRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        canvas.drawRoundRect(ringRect, halfW, halfW, glyphPaint);
    }

    private void drawPlayTriangle(Canvas canvas, float cx, float cy, float unit) {
        android.graphics.Path path = new android.graphics.Path();
        float offsetX = unit * 0.10f; // 视觉居中修正
        path.moveTo(cx - unit * 0.52f + offsetX, cy - unit * 0.70f);
        path.lineTo(cx + unit * 0.78f + offsetX, cy);
        path.lineTo(cx - unit * 0.52f + offsetX, cy + unit * 0.70f);
        path.close();
        canvas.drawPath(path, glyphPaint);
    }
}
