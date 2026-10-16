package com.autoswipe.ball.core;

/**
 * 运行的四种状态（纯 Java）。
 *
 * <p>状态定义与「规则 3 状态机定义」一一对应，颜色内联为 ARGB 常量，
 * 使 core 层不依赖 Android 资源系统。</p>
 */
public enum RunState {

    /** 未启动：初始状态或用户主动停止后，无手势任务运行 */
    NOT_STARTED("未启动", 0xFF7A8087),
    /** 运行中：权限齐备且定时任务按周期触发上滑 */
    RUNNING("运行中", 0xFF22C55E),
    /** 已暂停：手势任务挂起，保留配置与状态上下文 */
    PAUSED("已暂停", 0xFFF59E0B),
    /** 异常停止：无障碍/悬浮窗/前台服务异常，需用户处理权限后手动恢复 */
    ERROR_STOPPED("异常停止", 0xFFEF4444);

    private final String label;
    private final int colorArgb;

    RunState(String label, int colorArgb) {
        this.label = label;
        this.colorArgb = colorArgb;
    }

    /** 中文状态名，用于悬浮球气泡与页面提示。 */
    public String label() {
        return label;
    }

    /** 悬浮球主色（ARGB）。 */
    public int colorArgb() {
        return colorArgb;
    }

    /** 是否处于「定时任务应当在跑」的状态。 */
    public boolean isActive() {
        return this == RUNNING;
    }

    /** 是否处于需要用户介入的状态。 */
    public boolean needsUserAction() {
        return this == ERROR_STOPPED;
    }
}
