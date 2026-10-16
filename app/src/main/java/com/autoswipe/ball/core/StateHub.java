package com.autoswipe.ball.core;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 状态与配置变更的进程内事件总线（纯 Java）。
 *
 * <p>把「状态机 / 配置」与「Activity UI / 悬浮球 UI」解耦：
 * 服务与悬浮球只负责发布，界面只负责订阅，双方不互相持有引用。</p>
 */
public final class StateHub {

    /** 观测值快照 */
    public static final class Snapshot {
        public final RunState state;
        public final String reason;
        public final SwipeConfig config;

        Snapshot(RunState state, String reason, SwipeConfig config) {
            this.state = state;
            this.reason = reason;
            this.config = config;
        }
    }

    public interface Observer {
        void onHubChanged(Snapshot snapshot);
    }

    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot =
            new Snapshot(RunState.NOT_STARTED, "", new SwipeConfig());

    public void addObserver(Observer o) {
        if (o == null) {
            return;
        }
        observers.add(o);
        o.onHubChanged(snapshot);
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void publish(RunState state, String reason, SwipeConfig config) {
        snapshot = new Snapshot(
                state == null ? RunState.NOT_STARTED : state,
                reason == null ? "" : reason,
                new SwipeConfig(config));
        for (Observer o : observers) {
            o.onHubChanged(snapshot);
        }
    }
}
