# 说明：本项目未引入混淆，release 也保持 minifyEnabled false。
# 若后续开启混淆，需保留无障碍服务的类名与 meta-data（系统按类名反射绑定）。
-keep class com.autoswipe.ball.service.AutoSwipeAccessibilityService { *; }
-keep class com.autoswipe.ball.service.FloatingBallService { *; }
