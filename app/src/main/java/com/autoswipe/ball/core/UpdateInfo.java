package com.autoswipe.ball.core;

import java.util.Locale;
import java.util.Map;

/**
 * 远端版本元数据（对应仓库里的 {@code dist/version.json}）。
 *
 * <p>字段全部按「不可信输入」处理：缺失、类型不对、越界一律拒绝，而不是给个默认值糊过去。
 * 因为这份数据来自网络，一旦被篡改成指向别的 APK 或别的版本号，后果是装上错误的东西。</p>
 */
public final class UpdateInfo {

    /** 更新说明的展示上限，避免元数据被塞进大段文本拖垮界面 */
    private static final int MAX_NOTES_LEN = 2000;

    /** 远端版本号，与本地 versionCode 直接比较 */
    public final int versionCode;
    /** 形如 1.0.3，仅用于展示 */
    public final String versionName;
    /** APK 文件名（同目录下的相对名，不含任何路径分隔符） */
    public final String apkFileName;
    /** APK 的 SHA-256（小写十六进制）；为空表示元数据未提供，下载后不做校验 */
    public final String sha256;
    /** APK 字节数，0 表示未知 */
    public final long sizeBytes;
    /** 更新说明，可为空 */
    public final String notes;

    private UpdateInfo(int versionCode, String versionName, String apkFileName,
                       String sha256, long sizeBytes, String notes) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkFileName = apkFileName;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.notes = notes;
    }

    /**
     * 从 JSON 文本解析。
     *
     * @throws IllegalArgumentException 结构非法或必填字段异常
     */
    public static UpdateInfo fromJson(String json) {
        return fromMap(LiteJson.parseObject(json));
    }

    /**
     * 从已解析的键值表构建并做完整校验。
     *
     * @throws IllegalArgumentException 必填字段缺失或取值异常
     */
    public static UpdateInfo fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException("元数据为空");
        }
        int code = requirePositiveInt(map, "versionCode");
        String name = requireText(map, "versionName");
        String apkName = requireText(map, "apkFileName");

        long size = optionalLong(map, "sizeBytes");
        if (size < 0) {
            // 辅助字段写坏了不该阻断整次更新，按「大小未知」处理（下载后仍会校验摘要）
            size = 0L;
        }
        return new UpdateInfo(
                code,
                name,
                validateApkFileName(apkName),
                optionalSha256(map),
                size,
                truncate(optionalText(map, "notes")));
    }

    /** 远端版本是否比本地新。相同或更旧都返回 false（不打扰用户）。 */
    public boolean isNewerThan(int localVersionCode) {
        return versionCode > localVersionCode;
    }

    public boolean hasSha256() {
        return sha256 != null && !sha256.isEmpty();
    }

    @Override
    public String toString() {
        return "UpdateInfo{v" + versionName + "(" + versionCode + "), apk=" + apkFileName
                + ", sha256=" + (hasSha256() ? sha256.substring(0, Math.min(8, sha256.length())) + "…" : "无")
                + ", size=" + sizeBytes + "B}";
    }

    // ------------------------------------------------------------------
    // 字段校验

    private static int requirePositiveInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalArgumentException("缺少必填字段 " + key);
        }
        if (!(v instanceof Double)) {
            throw new IllegalArgumentException(key + " 必须是数字");
        }
        double d = (Double) v;
        if (d != Math.floor(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException(key + " 必须是整数: " + d);
        }
        if (d < 1 || d > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " 超出合理范围: " + d);
        }
        return (int) d;
    }

    private static String requireText(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("缺少必填字段或类型不是字符串: " + key);
        }
        String s = ((String) v).trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("字段为空: " + key);
        }
        return s;
    }

    private static String optionalText(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String)) {
            return "";
        }
        return ((String) v).trim();
    }

    private static long optionalLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof Double)) {
            return 0L;
        }
        double d = (Double) v;
        if (d != Math.floor(d)) {
            return 0L;
        }
        return (long) d;
    }

    /**
     * 文件名必须是「同目录下的纯文件名」。
     *
     * <p>拒绝路径分隔符与 {@code ..}，避免元数据被构造成 {@code ../../某个文件} 之类，
     * 让我们把别的东西下载下来再安装。</p>
     */
    static String validateApkFileName(String raw) {
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("apkFileName 为空");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("apkFileName 不能包含路径分隔符: " + name);
        }
        if (name.contains("..")) {
            throw new IllegalArgumentException("apkFileName 不能包含 ..: " + name);
        }
        if (!name.toLowerCase(Locale.US).endsWith(".apk")) {
            throw new IllegalArgumentException("apkFileName 必须以 .apk 结尾: " + name);
        }
        return name;
    }

    /**
     * 校验 SHA-256 格式。为空/缺省返回 null（表示元数据未提供）；格式非法同样返回 null。
     *
     * <p>这里刻意选择「宽容」：格式错误只说明元数据写坏了，不应该让整个更新不可用。
     * 但一旦提供了合法值，下载后就必须匹配，否则拒绝安装。</p>
     */
    private static String optionalSha256(Map<String, Object> map) {
        Object v = map.get("sha256");
        if (!(v instanceof String)) {
            return null;
        }
        String s = ((String) v).trim().toLowerCase(Locale.US);
        if (s.length() != 64) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_NOTES_LEN ? s : s.substring(0, MAX_NOTES_LEN) + "…";
    }
}
