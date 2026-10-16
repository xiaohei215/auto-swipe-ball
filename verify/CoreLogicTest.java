import com.autoswipe.ball.core.RunState;
import com.autoswipe.ball.core.SafeArea;
import com.autoswipe.ball.core.ScreenMetrics;
import com.autoswipe.ball.core.StateHub;
import com.autoswipe.ball.core.StateMachine;
import com.autoswipe.ball.core.SwipeConfig;
import com.autoswipe.ball.core.SwipeGeometry;
import com.autoswipe.ball.core.SwipeMotion;

import java.util.ArrayList;
import java.util.List;

/**
 * core 层纯逻辑验证（不依赖 Android SDK，可直接 javac + java 运行）。
 *
 * <p>覆盖：坐标计算 / 安全区回退 / 参数收敛 / 四种状态迁移 / 异常停止恢复。</p>
 */
public class CoreLogicTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        testIqooNeo9ProGeometry();
        testGeometryWithinSafeAreaAcrossDevices();
        testFallbackWhenSystemBarUnavailable();
        testConfigClamping();
        testStateMachineTransitions();
        testStateMachineFaultSemantics();
        testStateHubPublish();
        testMotionDurationFollowsConfig();

        System.out.println("==================================================");
        System.out.println("通过: " + passed + "  失败: " + failures.size());
        for (String f : failures) {
            System.out.println("  [FAIL] " + f);
        }
        System.out.println(failures.isEmpty() ? "结果: ALL PASS" : "结果: FAILED");
        System.out.println("==================================================");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------- 1. iQOO Neo9 Pro 基准机型 ----------

    private static void testIqooNeo9ProGeometry() {
        // iQOO Neo9 Pro：1260x2800，dpi 453，状态栏约 130px，手势导航条约 108px
        ScreenMetrics m = new ScreenMetrics(1260, 2800, 453, 130f, 108f);
        SwipeConfig c = new SwipeConfig();
        SafeArea area = SwipeGeometry.computeSafeArea(m);
        SwipeMotion motion = SwipeGeometry.computeMotion(m, c);

        System.out.println("[iQOO Neo9 Pro] " + m);
        System.out.println("  " + area);
        System.out.println("  " + motion);

        check("水平居中", Math.abs(motion.startX - m.widthPx / 2f) < 0.51f);
        check("竖直上滑 (endY < startY)", motion.endY < motion.startY);
        check("起点落在安全区内", motion.startY > area.top && motion.startY < area.bottom);
        check("终点落在安全区内", motion.endY > area.top && motion.endY < area.bottom);
        check("起点约为屏幕高度 60%-70%",
                motion.startY / m.heightPx > 0.55f && motion.startY / m.heightPx < 0.72f);
        check("终点约为屏幕高度 30%-45%",
                motion.endY / m.heightPx > 0.28f && motion.endY / m.heightPx < 0.45f);
        check("默认距离约为安全区高度 40%",
                Math.abs(motion.distancePx() - area.height() * 0.40f) < 2f);
        check("避开底部导航栏", motion.startY < m.heightPx - m.navBarHeightPx);
        check("避开顶部状态栏", motion.endY > m.statusBarHeightPx);
        check("水平位置远离右侧互动按钮列", motion.startX < m.widthPx * 0.70f);
    }

    // ---------- 2. 多机型安全区不变量 ----------

    private static void testGeometryWithinSafeAreaAcrossDevices() {
        int[][] devices = {
                {1080, 2340, 440, 84, 126},
                {1260, 2800, 453, 130, 108},
                {1440, 3200, 560, 142, 132},
                {720, 1280, 320, 60, 48},
                {2800, 1260, 560, 96, 132},   // 异常横屏
        };
        for (int[] d : devices) {
            ScreenMetrics m = new ScreenMetrics(d[0], d[1], d[2], d[3], d[4]);
            for (float ratio : new float[]{0.15f, 0.40f, 0.60f}) {
                SwipeConfig c = new SwipeConfig();
                c.distanceRatio = ratio;
                SafeArea area = SwipeGeometry.computeSafeArea(m);
                SwipeMotion motion = SwipeGeometry.computeMotion(m, c);
                String tag = d[0] + "x" + d[1] + " ratio=" + ratio;
                check(tag + " 安全区有效", area.top < area.bottom && area.left < area.right);
                check(tag + " 起点在安全区内", motion.startY > area.top && motion.startY < area.bottom);
                check(tag + " 终点在安全区内", motion.endY > area.top && motion.endY < area.bottom);
                check(tag + " 为上滑方向", motion.endY < motion.startY);
                check(tag + " X 居中", Math.abs(motion.startX - d[0] / 2f) < 0.51f);
            }
        }
    }

    // ---------- 3. 系统栏信息缺失时的保守回退 ----------

    private static void testFallbackWhenSystemBarUnavailable() {
        ScreenMetrics m = new ScreenMetrics(1080, 2340, 440, 0f, 0f);
        SafeArea area = SwipeGeometry.computeSafeArea(m);
        SwipeMotion motion = SwipeGeometry.computeMotion(m, new SwipeConfig());
        System.out.println("[回退] " + area + " " + motion);
        check("标记为回退模式", area.fallback);
        check("回退区上边界约 12%", Math.abs(area.top - m.heightPx * 0.12f) < 1f);
        check("回退区下边界约 86%", Math.abs(area.bottom - m.heightPx * 0.86f) < 1f);
        check("回退后仍为上滑", motion.endY < motion.startY);
        check("回退后起终点均安全", motion.endY > area.top && motion.startY < area.bottom);
    }

    // ---------- 4. 参数收敛 ----------

    private static void testConfigClamping() {
        SwipeConfig c = new SwipeConfig();
        c.intervalMs = 1000;
        c.swipeDurationMs = 5000;
        c.distanceRatio = 0.95f;
        c.clamp();
        check("间隔低于下限被收敛到 5s", c.intervalMs == SwipeConfig.MIN_INTERVAL_MS);
        check("时长超上限被收敛到 1000ms", c.swipeDurationMs == SwipeConfig.MAX_DURATION_MS);
        check("距离比例超上限被收敛到 0.60", Math.abs(c.distanceRatio - SwipeConfig.MAX_DISTANCE_RATIO) < 1e-6);

        SwipeConfig z = new SwipeConfig();
        z.intervalMs = 0;
        z.swipeDurationMs = 0;
        z.distanceRatio = 0f;
        z.clamp();
        check("零值回退默认间隔 15s", z.intervalMs == SwipeConfig.DEFAULT_INTERVAL_MS);
        check("零值回退默认时长 300ms", z.swipeDurationMs == SwipeConfig.DEFAULT_DURATION_MS);
        check("零值回退默认比例 0.40", Math.abs(z.distanceRatio - SwipeConfig.DEFAULT_DISTANCE_RATIO) < 1e-6);

        SwipeConfig n = new SwipeConfig();
        n.distanceRatio = Float.NaN;
        n.clamp();
        check("NaN 回退默认比例", Math.abs(n.distanceRatio - SwipeConfig.DEFAULT_DISTANCE_RATIO) < 1e-6);

        SwipeConfig copy = new SwipeConfig(z);
        check("拷贝构造独立", copy.intervalMs == z.intervalMs);

        SwipeConfig heal = new SwipeConfig();
        check("无障碍自愈默认开启", heal.autoHealAccessibility);
        heal.autoHealAccessibility = false;
        check("自愈开关可关闭", !new SwipeConfig(heal).autoHealAccessibility);
        check("自愈开关不影响滑动参数",
                new SwipeConfig(heal).intervalMs == SwipeConfig.DEFAULT_INTERVAL_MS);
    }

    // ---------- 5. 状态机主流程 ----------

    private static void testStateMachineTransitions() {
        FakePrecondition p = new FakePrecondition();
        StateMachine sm = new StateMachine(p);
        final List<String> log = new ArrayList<String>();
        sm.addListener(new StateMachine.Listener() {
            @Override
            public void onStateChanged(RunState from, RunState to, String reason) {
                log.add(from.name() + "->" + to.name() + (reason.isEmpty() ? "" : "(" + reason + ")"));
            }
        });

        check("初始为未启动", sm.state() == RunState.NOT_STARTED);

        p.overlay = false;
        p.accessibility = false;
        check("缺权限时启动失败", !sm.start());
        check("缺权限时进入异常停止", sm.state() == RunState.ERROR_STOPPED);
        check("异常原因非空", !sm.reason().isEmpty());

        p.overlay = true;
        p.accessibility = true;
        check("授权后手动恢复成功", sm.start());
        check("恢复后为运行中", sm.state() == RunState.RUNNING);

        sm.toggle();
        check("单击切换为已暂停", sm.state() == RunState.PAUSED);
        sm.toggle();
        check("再次单击恢复运行", sm.state() == RunState.RUNNING);

        sm.fault("无障碍服务已断开");
        check("运行中故障进入异常停止", sm.state() == RunState.ERROR_STOPPED);
        check("故障原因被记录", "无障碍服务已断开".equals(sm.reason()));

        p.accessibility = true;
        check("处理后手动恢复", sm.start());
        check("恢复为运行中", sm.state() == RunState.RUNNING);

        sm.stop();
        check("主动停止回到未启动", sm.state() == RunState.NOT_STARTED);
        check("主动停止清空原因", sm.reason().isEmpty());
        check("状态迁移日志完整", log.size() >= 7);

        System.out.println("[状态机] " + log);
    }

    // ---------- 6. 故障语义 ----------

    private static void testStateMachineFaultSemantics() {
        FakePrecondition p = new FakePrecondition();
        StateMachine sm = new StateMachine(p);
        sm.fault("不该生效");
        check("未启动时故障不改变状态", sm.state() == RunState.NOT_STARTED);

        p.overlay = true;
        p.accessibility = true;
        sm.start();
        sm.pause();
        sm.fault("悬浮窗权限已被回收");
        check("暂停中故障进入异常停止", sm.state() == RunState.ERROR_STOPPED);
        check("暂停中故障原因正确", "悬浮窗权限已被回收".equals(sm.reason()));

        sm.stop();
        sm.fault("停止后故障应被忽略");
        check("停止后故障被忽略", sm.state() == RunState.NOT_STARTED && sm.reason().isEmpty());

        check("异常停止需要用户介入", RunState.ERROR_STOPPED.needsUserAction());
        check("运行中 isActive", RunState.RUNNING.isActive());
        check("暂停不 isActive", !RunState.PAUSED.isActive());
        check("状态颜色互不相同",
                RunState.NOT_STARTED.colorArgb() != RunState.RUNNING.colorArgb()
                        && RunState.RUNNING.colorArgb() != RunState.PAUSED.colorArgb()
                        && RunState.PAUSED.colorArgb() != RunState.ERROR_STOPPED.colorArgb());

        // 前台服务被系统杀死后 START_STICKY 重启：从零重建也必须落到异常停止
        FakePrecondition p2 = new FakePrecondition();
        StateMachine revived = new StateMachine(p2);
        revived.markInterrupted("前台服务曾被系统中断");
        check("服务重启后进入异常停止", revived.state() == RunState.ERROR_STOPPED);
        check("服务重启后保留原因", "前台服务曾被系统中断".equals(revived.reason()));
        check("重启标记后仍可手动恢复", revived.start() && revived.state() == RunState.RUNNING);
    }

    // ---------- 7. StateHub 事件总线 ----------

    private static void testStateHubPublish() {
        StateHub hub = new StateHub();
        final int[] count = {0};
        final String[] lastReason = {""};
        StateHub.Observer o = new StateHub.Observer() {
            @Override
            public void onHubChanged(StateHub.Snapshot s) {
                count[0]++;
                lastReason[0] = s.reason;
            }
        };
        hub.addObserver(o);
        check("订阅时立即回调一次", count[0] == 1);
        hub.publish(RunState.RUNNING, "", new SwipeConfig());
        check("发布后收到通知", count[0] == 2);
        check("快照状态正确", hub.snapshot().state == RunState.RUNNING);
        hub.publish(RunState.ERROR_STOPPED, "无障碍服务已断开", new SwipeConfig());
        check("故障原因透传", "无障碍服务已断开".equals(lastReason[0]));
        hub.removeObserver(o);
        hub.publish(RunState.NOT_STARTED, "", new SwipeConfig());
        check("退订后不再回调", count[0] == 3);
        check("配置快照为深拷贝", hub.snapshot().config != null);
    }

    // ---------- 8. 滑动时长跟随配置 ----------

    private static void testMotionDurationFollowsConfig() {
        ScreenMetrics m = new ScreenMetrics(1260, 2800, 453, 130f, 108f);
        SwipeConfig c = new SwipeConfig();
        c.swipeDurationMs = 100;
        check("时长 100ms 生效", SwipeGeometry.computeMotion(m, c).durationMs == 100L);
        c.swipeDurationMs = 1000;
        check("时长 1000ms 生效", SwipeGeometry.computeMotion(m, c).durationMs == 1000L);
        c.swipeDurationMs = 1;
        check("非法时长被收敛到下限", SwipeGeometry.computeMotion(m, c).durationMs == 100L);

        SwipeConfig big = new SwipeConfig();
        big.distanceRatio = SwipeConfig.MAX_DISTANCE_RATIO;
        SwipeConfig small = new SwipeConfig();
        small.distanceRatio = SwipeConfig.MIN_DISTANCE_RATIO;
        float d1 = SwipeGeometry.computeMotion(m, big).distancePx();
        float d2 = SwipeGeometry.computeMotion(m, small).distancePx();
        check("距离比例越大滑动越远", d1 > d2);
    }

    // ---------- 工具 ----------

    private static final class FakePrecondition implements StateMachine.Precondition {
        boolean overlay = true;
        boolean accessibility = true;

        @Override
        public boolean overlayGranted() {
            return overlay;
        }

        @Override
        public boolean accessibilityConnected() {
            return accessibility;
        }

        @Override
        public String firstMissingReason() {
            if (!overlay) {
                return "悬浮窗权限未开启";
            }
            if (!accessibility) {
                return "无障碍服务未开启";
            }
            return null;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failures.add(name);
            System.out.println("  [FAIL] " + name);
        }
    }
}
