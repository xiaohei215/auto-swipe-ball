package com.autoswipe.ball.core;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行状态机（纯 Java，可脱离 Android SDK 单元测试）。
 *
 * <p>状态迁移表：</p>
 * <pre>
 *   未启动  --START(前置条件通过)-->  运行中
 *   未启动/已暂停/运行中  --START(前置条件不通过)-->  异常停止
 *   运行中  --PAUSE-->  已暂停
 *   已暂停  --START-->  运行中
 *   任意状态 --STOP-->  未启动
 *   运行中/已暂停 --FAULT-->  异常停止
 *   异常停止 --START(前置条件通过)-->  运行中（需用户手动触发恢复）
 * </pre>
 *
 * <p>异常停止必须由用户重新授权后手动触发 START 才能恢复，不做静默自动重试，
 * 避免在权限缺失时反复触发失败（规则 2「异常处理完善」）。</p>
 */
public final class StateMachine {

    /** 运行前置条件（由 Android 层实现：悬浮窗权限 / 无障碍服务 / 前台服务） */
    public interface Precondition {
        /** 悬浮窗权限是否可用 */
        boolean overlayGranted();

        /** 无障碍服务是否已连接 */
        boolean accessibilityConnected();

        /**
         * 返回首个缺失条件的中文描述；全部满足时返回 null。
         */
        String firstMissingReason();
    }

    /** 状态变更监听 */
    public interface Listener {
        void onStateChanged(RunState from, RunState to, String reason);
    }

    private final Precondition precondition;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile RunState state = RunState.NOT_STARTED;
    private volatile String reason = "";

    public StateMachine(Precondition precondition) {
        if (precondition == null) {
            throw new IllegalArgumentException("Precondition must not be null");
        }
        this.precondition = precondition;
    }

    public RunState state() {
        return state;
    }

    /** 当前状态说明：正常状态为空串，异常停止时为用户可读的失败原因。 */
    public String reason() {
        return reason;
    }

    public boolean isRunning() {
        return state == RunState.RUNNING;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * 启动 / 恢复运行。前置条件不满足时进入「异常停止」。
     *
     * @return 是否成功进入运行中
     */
    public synchronized boolean start() {
        String missing = precondition.firstMissingReason();
        if (missing != null) {
            transitionTo(RunState.ERROR_STOPPED, missing);
            return false;
        }
        transitionTo(RunState.RUNNING, "");
        return true;
    }

    /** 暂停。仅运行中有意义。 */
    public synchronized void pause() {
        if (state == RunState.RUNNING) {
            transitionTo(RunState.PAUSED, "");
        }
    }

    /** 运行中 <-> 已暂停 切换（悬浮球单击）。异常停止时单击视为尝试恢复。 */
    public synchronized void toggle() {
        switch (state) {
            case RUNNING:
                pause();
                break;
            case PAUSED:
            case NOT_STARTED:
                start();
                break;
            case ERROR_STOPPED:
            default:
                start(); // 手动恢复：条件仍不满足则继续停留在异常停止并刷新原因
                break;
        }
    }

    /** 用户主动停止，回到未启动。 */
    public synchronized void stop() {
        transitionTo(RunState.NOT_STARTED, "");
    }

    /**
     * 运行期故障（无障碍断开 / 悬浮窗权限被回收 / 手势注入失败）。
     * 仅在任务处于运行中或已暂停时生效，避免重复覆盖用户主动停止后的状态。
     */
    public synchronized void fault(String faultReason) {
        if (state == RunState.RUNNING || state == RunState.PAUSED) {
            transitionTo(RunState.ERROR_STOPPED,
                    faultReason == null || faultReason.isEmpty() ? "运行环境异常" : faultReason);
        }
    }

    /**
     * 服务被系统中断后重启时的标记：无条件进入「异常停止」。
     *
     * <p>与 {@link #fault(String)} 的区别：本方法不受当前状态限制，
     * 用于「前台服务被系统杀死后 START_STICKY 重启」这种从零重建的场景，
     * 让用户明确看到「上次运行被中断」，而不是看起来一切正常却已停摆。</p>
     */
    public synchronized void markInterrupted(String interruptedReason) {
        transitionTo(RunState.ERROR_STOPPED,
                interruptedReason == null || interruptedReason.isEmpty()
                        ? "前台服务曾被系统中断" : interruptedReason);
    }

    private void transitionTo(RunState next, String nextReason) {
        RunState from = state;
        state = next;
        reason = nextReason == null ? "" : nextReason;
        if (from != next || !reason.isEmpty()) {
            for (Listener l : listeners) {
                l.onStateChanged(from, next, reason);
            }
        }
    }
}
