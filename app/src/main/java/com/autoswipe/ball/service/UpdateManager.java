package com.autoswipe.ball.service;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.autoswipe.ball.core.UpdateInfo;
import com.autoswipe.ball.core.UpdateSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 应用内更新的执行者：检查版本 → 下载 APK → 交给系统安装器。
 *
 * <p>线程模型：所有网络与磁盘操作都在工作线程，回调统一抛回主线程，界面不需要自己切线程。</p>
 *
 * <p>安全边界：
 * <ul>
 *   <li>元数据来自网络，被视为不可信输入，字段校验失败即中止（见 {@link UpdateInfo}）；</li>
 *   <li>下载有字节上限，避免被恶意响应塞满存储；</li>
 *   <li>元数据给出 SHA-256 时，下载后必须完全匹配才允许安装；</li>
 *   <li>最终安装走系统 {@link PackageInstaller}，签名与包名由系统校验，同签名才可能覆盖成功。</li>
 * </ul>
 */
public final class UpdateManager {

    private static final String TAG = "AutoSwipe.Update";

    /** 元数据响应体上限：正常只有几百字节 */
    private static final int META_MAX_BYTES = 256 * 1024;
    /** APK 下载上限：本项目 APK 远小于此值，纯粹是防御性上限 */
    private static final long APK_MAX_BYTES = 50L * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int META_READ_TIMEOUT_MS = 15000;
    private static final int APK_READ_TIMEOUT_MS = 30000;
    private static final int REDIRECT_LIMIT = 5;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateManager() {
    }

    // ------------------------------------------------------------------
    // 回调定义

    /** 检查更新的结果：{@code info == null} 表示已是最新版本。 */
    public interface CheckCallback {
        void onResult(UpdateInfo info);

        void onError(String message);
    }

    /** 下载回调，所有方法都在主线程被调用。 */
    public interface DownloadCallback {
        /** @param percent 0–100；{@code -1} 表示总大小未知 */
        void onProgress(int percent);

        void onReady(File apk);

        void onError(String message);
    }

    // ------------------------------------------------------------------
    // 检查更新

    /**
     * 异步拉取远端元数据并与本地版本比较。回调总会被调用一次，要么 onResult 要么 onError。
     *
     * @param localVersionCode 本机已安装版本的 versionCode
     */
    public static void checkAsync(final Context context, final UpdateSource source,
                                  final int localVersionCode, final CheckCallback callback) {
        if (source == null || !source.isConfigured()) {
            post(() -> callback.onError("更新源未配置完整"));
            return;
        }
        final Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            try {
                String json = httpGetText(source.metaUrl(), source, META_MAX_BYTES);
                UpdateInfo info = UpdateInfo.fromJson(json);
                Log.i(TAG, "远端元数据: " + info + "；本地 versionCode=" + localVersionCode);
                post(() -> callback.onResult(info.isNewerThan(localVersionCode) ? info : null));
            } catch (Exception e) {
                Log.w(TAG, "检查更新失败: " + source.describe(), e);
                String msg = friendlyMessage(e);
                post(() -> callback.onError(msg));
            }
        }, "update-check-" + appCtx.getPackageName()).start();
    }

    // ------------------------------------------------------------------
    // 下载

    /**
     * 下载 APK 到应用缓存目录，边下边算 SHA-256，完成后校验大小与摘要。
     */
    public static void downloadAsync(final Context context, final UpdateSource source,
                                     final UpdateInfo info, final DownloadCallback callback) {
        if (source == null || !source.isConfigured()) {
            post(() -> callback.onError("更新源未配置完整"));
            return;
        }
        if (info == null) {
            post(() -> callback.onError("没有可下载的版本信息"));
            return;
        }
        final Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            File dir = cacheDir(appCtx);
            deleteRecursively(dir);
            if (!dir.exists() && !dir.mkdirs()) {
                post(() -> callback.onError("无法创建缓存目录"));
                return;
            }
            File target = new File(dir, info.apkFileName);
            try {
                long written = download(source, info, target, percent ->
                        post(() -> callback.onProgress(percent)));
                if (info.sizeBytes > 0 && written != info.sizeBytes) {
                    throw new IOException("下载大小不符：期望 " + info.sizeBytes + " 字节，实际 " + written);
                }
                if (info.hasSha256()) {
                    String actual = sha256Of(target);
                    if (!info.sha256.equalsIgnoreCase(actual)) {
                        throw new IOException("文件校验失败（SHA-256 不匹配），已丢弃");
                    }
                }
                Log.i(TAG, "下载完成并校验通过: " + target.getName() + "（" + written + " 字节）");
                post(() -> callback.onReady(target));
            } catch (Exception e) {
                Log.w(TAG, "下载失败", e);
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                String msg = friendlyMessage(e);
                post(() -> callback.onError(msg));
            }
        }, "update-download").start();
    }

    private static long download(UpdateSource source, UpdateInfo info, File target,
                                 ProgressSink progress) throws Exception {
        HttpURLConnection conn = open(source.apkUrl(info.apkFileName), source, APK_READ_TIMEOUT_MS);
        long written = 0L;
        MessageDigest digest = newSha256();
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("服务器返回 HTTP " + code);
            }
            long total = info.sizeBytes > 0 ? info.sizeBytes : conn.getContentLength();
            try (InputStream in = wrapGzip(conn);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                int lastPercent = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (digest != null) {
                        digest.update(buf, 0, n);
                    }
                    written += n;
                    if (written > APK_MAX_BYTES) {
                        throw new IOException("下载内容超过上限，已中止");
                    }
                    int percent = total > 0 ? (int) (written * 100 / total) : -1;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.onPercent(percent);
                    }
                }
                out.flush();
            }
        } finally {
            conn.disconnect();
        }
        return written;
    }

    // ------------------------------------------------------------------
    // 安装

    /** 是否已被允许「安装未知应用」。false 时需引导用户去授权。 */
    public static boolean canRequestInstall(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * 把已下载的 APK 交给系统安装器。
     *
     * <p>非系统应用无法静默安装：提交会话后系统会回报
     * {@code STATUS_PENDING_USER_ACTION}，由 {@link InstallResultReceiver} 拉起确认界面，
     * 用户点一次「安装」即完成。这是 Android 的安全设计，不是本实现的取巧之处。</p>
     *
     * @return 是否成功把会话提交给系统（不代表安装已完成）
     */
    public static boolean install(Context context, File apk) {
        if (apk == null || !apk.isFile() || apk.length() <= 0) {
            Log.w(TAG, "安装包不存在或为空");
            return false;
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("autoswipe.apk", 0, apk.length())) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            Intent resultIntent = new Intent(context, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 系统要往这个 Intent 里塞回执数据，必须是可变 PendingIntent
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags);
            session.commit(pending.getIntentSender());
            Log.i(TAG, "安装会话已提交: sessionId=" + sessionId);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "发起安装失败", e);
            return false;
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 会话已被 commit，关闭失败不影响安装
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 缓存管理

    /** 更新包缓存目录。安装成功后下一次启动会清掉它。 */
    public static File cacheDir(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), "update");
    }

    /** 清空下载缓存（安装成功后或用户手动清理时调用）。 */
    public static void clearCache(Context context) {
        deleteRecursively(cacheDir(context));
    }

    // ------------------------------------------------------------------
    // 本机版本

    /** 本机已安装版本的 versionCode；读取失败返回 0（此时任何远端版本都会被判定为「更新」）。 */
    @SuppressWarnings("deprecation")
    public static int localVersionCode(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) (info.getLongVersionCode() & 0xFFFFFFFFL);
            }
            return info.versionCode;
        } catch (Exception e) {
            Log.w(TAG, "读取本机 versionCode 失败", e);
            return 0;
        }
    }

    /** 本机已安装版本的 versionName；读取失败返回 "?"。 */
    public static String localVersionName(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 当前是否连接在 Wi-Fi 上。
     *
     * <p>读不到网络状态时返回 {@code true}（放行）：宁可多检查一次读几百字节的元数据，
     * 也不要因为权限或系统版本差异导致「自动检查静默失效」。</p>
     */
    public static boolean isOnWifi(Context context) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            android.net.Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) {
                return true;
            }
            return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            Log.w(TAG, "判断 Wi-Fi 状态失败，按放行处理", e);
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 内部工具

    private interface ProgressSink {
        void onPercent(int percent);
    }

    private static HttpURLConnection open(String url, UpdateSource source, int readTimeout)
            throws IOException {
        String current = url;
        for (int hop = 0; hop <= REDIRECT_LIMIT; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeout);
            // 自己处理跳转：GitHub 的 contents API 与部分 CDN 会 302 到别的域名
            conn.setInstanceFollowRedirects(false);
            // 明确要求不压缩，省得为进度百分比猜原始大小
            conn.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> header : source.requestHeaders().entrySet()) {
                conn.setRequestProperty(header.getKey(), header.getValue());
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("重定向缺少 Location 头");
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("重定向次数过多");
    }

    private static String httpGetText(String url, UpdateSource source, int maxBytes) throws IOException {
        HttpURLConnection conn = open(url, source, META_READ_TIMEOUT_MS);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("服务器返回 HTTP " + code);
            }
            try (InputStream in = wrapGzip(conn)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IOException("响应内容超过上限");
                    }
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), "UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static InputStream wrapGzip(HttpURLConnection conn) throws IOException {
        InputStream raw = conn.getInputStream();
        String encoding = conn.getContentEncoding();
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            return new GZIPInputStream(raw);
        }
        return raw;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            // 理论上不会发生；返回 null 时下载仍可继续，只是不做摘要校验
            return null;
        }
    }

    private static String sha256Of(File file) throws IOException {
        MessageDigest digest = newSha256();
        if (digest == null) {
            throw new IOException("本机不支持 SHA-256");
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void post(Runnable action) {
        MAIN.post(action);
    }

    /** 把技术异常翻译成用户能看懂的一句话。 */
    private static String friendlyMessage(Exception e) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = raw.toLowerCase(Locale.US);
        if (e instanceof java.net.UnknownHostException
                || lower.contains("unable to resolve host")) {
            return "域名解析失败，请检查网络或更换更新源";
        }
        if (e instanceof java.net.SocketTimeoutException || lower.contains("timed out")) {
            return "连接超时，请检查网络后重试";
        }
        if (lower.contains("http 403") || lower.contains("http 429")) {
            return "服务端拒绝了请求（可能是访问频率限制），稍后再试";
        }
        if (lower.contains("http 404")) {
            return "更新源上找不到版本信息，请确认元数据文件已上传";
        }
        if (lower.contains("sha-256") || lower.contains("校验失败")) {
            return "下载文件校验失败，已丢弃，请重试";
        }
        return "更新失败：" + raw;
    }
}
