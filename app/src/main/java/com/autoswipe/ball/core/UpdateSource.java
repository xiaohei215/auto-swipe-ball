package com.autoswipe.ball.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 更新源：描述「去哪里取版本元数据与 APK」，并负责拼装 URL 与请求头。
 *
 * <p>纯数据 + 纯字符串运算，不触碰网络与 Android API，因此可以脱离设备直接断言。</p>
 *
 * <p>支持三种源，目的是把「网络可达性」这件事从代码里解耦出去：
 * 国内直连 GitHub 的 raw 域名通常不通，而 Gitee 通；不同网络环境结论还可能变化。
 * 做成可在应用内切换的配置项后，换源不需要重新发版。</p>
 */
public final class UpdateSource {

    public enum Kind {
        /** Gitee（码云）：国内可达，raw 直链走 gitee.com 域名 */
        GITEE,
        /** GitHub：走 contents API（raw.githubusercontent.com 在国内常不可达） */
        GITHUB,
        /** 自定义：局域网 HTTP 服务或任意可达地址 */
        CUSTOM;

        public static Kind fromName(String raw) {
            if (raw != null) {
                String t = raw.trim().toUpperCase(Locale.US);
                for (Kind k : values()) {
                    if (k.name().equals(t)) {
                        return k;
                    }
                }
            }
            return GITEE;
        }
    }

    /**
     * 默认仓库所有者 —— Gitee 的「个人空间地址」（即 URL 里用的那段）。
     *
     * <p>注意：Gitee 上有两个名字，容易被混淆 ——
     * <b>显示名</b>（如 {@code xiaohei123}）与<b>个人空间地址</b>（如 {@code xiaohei1234525}）。
     * URL 里必须用后者，用显示名会 404。这里取的是空间地址。</p>
     *
     * <p>另外：GitHub 用的是另一个账号名。若将来要改用户名、换仓库名或换平台，
     * 用设置里的「自定义地址」填完整 URL 即可，不必重新发版。</p>
     */
    public static final String DEFAULT_OWNER = "xiaohei1234525";
    public static final String DEFAULT_REPO = "auto-swipe-ball";
    public static final String DEFAULT_BRANCH = "main";
    public static final String DEFAULT_DIR = "dist";
    /** 版本元数据文件名，与 APK 同目录 */
    public static final String META_FILE = "version.json";
    /** 部分服务（GitHub API）强制要求 User-Agent */
    public static final String USER_AGENT = "AutoSwipeBall-Android";

    public final Kind kind;
    public final String owner;
    public final String repo;
    public final String branch;
    public final String assetDir;
    public final String customBaseUrl;

    private UpdateSource(Kind kind, String owner, String repo, String branch,
                         String assetDir, String customBaseUrl) {
        this.kind = kind == null ? Kind.GITEE : kind;
        this.owner = safe(owner, DEFAULT_OWNER);
        this.repo = safe(repo, DEFAULT_REPO);
        this.branch = safe(branch, DEFAULT_BRANCH);
        this.assetDir = safe(assetDir, DEFAULT_DIR);
        this.customBaseUrl = customBaseUrl == null ? "" : customBaseUrl.trim();
    }

    public static UpdateSource defaultGitee() {
        return gitee(DEFAULT_OWNER, DEFAULT_REPO, DEFAULT_BRANCH);
    }

    public static UpdateSource gitee(String owner, String repo, String branch) {
        return new UpdateSource(Kind.GITEE, owner, repo, branch, DEFAULT_DIR, "");
    }

    public static UpdateSource github(String owner, String repo, String branch) {
        return new UpdateSource(Kind.GITHUB, owner, repo, branch, DEFAULT_DIR, "");
    }

    /** 自定义源：baseUrl 指向存放 version.json 与 APK 的目录（可带子路径） */
    public static UpdateSource custom(String baseUrl) {
        return new UpdateSource(Kind.CUSTOM, DEFAULT_OWNER, DEFAULT_REPO, DEFAULT_BRANCH,
                DEFAULT_DIR, baseUrl);
    }

    // ------------------------------------------------------------------

    /** 版本元数据的下载地址。 */
    public String metaUrl() {
        return fileUrl(META_FILE);
    }

    /**
     * 指定 APK 文件的下载地址。
     *
     * <p>文件名先经过 {@link UpdateInfo} 的同款校验，防止来自网络的元数据里塞进路径。</p>
     */
    public String apkUrl(String fileName) {
        return fileUrl(UpdateInfo.validateApkFileName(fileName));
    }

    String fileUrl(String fileName) {
        switch (kind) {
            case GITHUB:
                return "https://api.github.com/repos/" + owner + "/" + repo
                        + "/contents/" + assetDir + "/" + fileName + "?ref=" + branch;
            case CUSTOM:
                return stripTrailingSlash(customBaseUrl) + "/" + fileName;
            case GITEE:
            default:
                return "https://gitee.com/" + owner + "/" + repo
                        + "/raw/" + branch + "/" + assetDir + "/" + fileName;
        }
    }

    /**
     * 该源需要的请求头。
     *
     * <p>GitHub 的 contents API 用 {@code Accept: application/vnd.github.raw} 直接拿原始内容，
     * 省掉 base64 解码；同时它的 API 强制要求 User-Agent。</p>
     */
    public Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        if (kind == Kind.GITHUB) {
            headers.put("Accept", "application/vnd.github.raw");
        }
        return Collections.unmodifiableMap(headers);
    }

    /** 配置是否完整到可以发起请求。 */
    public boolean isConfigured() {
        if (kind == Kind.CUSTOM) {
            String u = customBaseUrl.toLowerCase(Locale.US);
            return u.startsWith("http://") || u.startsWith("https://");
        }
        return isSafeToken(owner) && isSafeToken(repo) && isSafeToken(branch) && isSafeToken(assetDir);
    }

    /** 供界面展示的简短描述，例如 {@code gitee.com/xiaohei1234525/auto-swipe-ball@main}。 */
    public String describe() {
        switch (kind) {
            case GITHUB:
                return "api.github.com › " + owner + "/" + repo + "@" + branch;
            case CUSTOM:
                return customBaseUrl.isEmpty() ? "（未填写地址）" : customBaseUrl;
            case GITEE:
            default:
                return "gitee.com/" + owner + "/" + repo + "@" + branch;
        }
    }

    /** 配置是否等价（用于判断设置页的改动是否需要落盘）。 */
    public boolean sameAs(UpdateSource other) {
        if (other == null) {
            return false;
        }
        return kind == other.kind
                && owner.equals(other.owner)
                && repo.equals(other.repo)
                && branch.equals(other.branch)
                && assetDir.equals(other.assetDir)
                && customBaseUrl.equals(other.customBaseUrl);
    }

    @Override
    public String toString() {
        return "UpdateSource{" + kind + " " + describe() + "}";
    }

    // ------------------------------------------------------------------

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String s) {
        String t = s;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** owner / repo / branch / 目录只允许字母数字与 {@code . _ -}，避免拼出意料之外的 URL。 */
    private static boolean isSafeToken(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty() || t.length() > 100) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
