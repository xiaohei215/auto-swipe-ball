package com.autoswipe.ball;

import android.app.Application;

import com.autoswipe.ball.core.StateHub;

/**
 * 应用入口：持有进程级单例，供各组件取用共享的事件总线与配置缓存。
 */
public class App extends Application {

    private static App sInstance;

    private final StateHub hub = new StateHub();

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    /** 全局状态/配置事件总线。 */
    public static StateHub hub() {
        return sInstance == null ? null : sInstance.hub;
    }

    /**
     * 安全发布：Application 未初始化完成（如被系统直接拉起 Service）时静默跳过。
     */
    public static void publish(com.autoswipe.ball.core.RunState state, String reason,
                              com.autoswipe.ball.core.SwipeConfig config) {
        StateHub h = hub();
        if (h != null) {
            h.publish(state, reason, config);
        }
    }
}
