package com.autoswipe.ball.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 对象解析器：只支持「扁平对象」（值可为字符串 / 数字 / 布尔 / null，或再嵌套一层对象）。
 *
 * <p>存在的理由：核心逻辑必须能脱离 Android SDK 单独编译与测试，而 {@code org.json}
 * 属于 Android 平台 API。更新元数据是固定结构的扁平对象，用不到数组与复杂特性，
 * 因此自备一个约百行的解析器，换取「纯 Java 可断言」这一性质。</p>
 *
 * <p>任何结构问题都抛 {@link IllegalArgumentException}，由调用方统一转成「检查更新失败」，
 * 绝不让异常逃逸到界面线程。</p>
 */
public final class LiteJson {

    /** UTF-8 BOM，部分服务器会带上 */
    private static final char BOM = '\uFEFF';

    private final String src;
    private int pos;

    private LiteJson(String src) {
        this.src = src;
    }

    /**
     * 解析一个 JSON 对象。
     *
     * @param text 原始文本，允许首尾空白与 BOM
     * @return 按出现顺序排列的键值表；值为 String / Double / Boolean / null / Map
     * @throws IllegalArgumentException 结构非法时抛出
     */
    public static Map<String, Object> parseObject(String text) {
        if (text == null) {
            throw new IllegalArgumentException("输入为空");
        }
        String trimmed = text.trim();
        if (!trimmed.isEmpty() && trimmed.charAt(0) == BOM) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("输入为空");
        }
        LiteJson p = new LiteJson(trimmed);
        p.skipWs();
        Map<String, Object> map = p.readObject();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw new IllegalArgumentException("对象结束后仍有内容（位置 " + p.pos + "）");
        }
        return map;
    }

    // ------------------------------------------------------------------

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            skipWs();
            map.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new IllegalArgumentException("期待 ',' 或 '}'，实际是 '" + c + "'（位置 " + pos + "）");
        }
    }

    private Object readValue() {
        char c = peek();
        if (c == '"') {
            return readString();
        }
        if (c == '{') {
            return readObject();
        }
        if (c == '[') {
            throw new IllegalArgumentException("本解析器不支持数组（位置 " + pos + "）");
        }
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        return readNumber();
    }

    private Double readNumber() {
        int start = pos;
        while (pos < src.length() && isNumberChar(src.charAt(pos))) {
            pos++;
        }
        String raw = src.substring(start, pos);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("期待数值（位置 " + start + "）");
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值非法: " + raw);
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+';
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new IllegalArgumentException("转义符后缺少字符");
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new IllegalArgumentException("\\u 转义不完整");
                    }
                    String hex = src.substring(pos, pos + 4);
                    pos += 4;
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("\\u 转义非法: " + hex);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("不支持的转义: \\" + esc);
            }
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("内容意外结束");
        }
        return src.charAt(pos);
    }

    private void expect(char expected) {
        char actual = peek();
        if (actual != expected) {
            throw new IllegalArgumentException(
                    "期待 '" + expected + "'，实际是 '" + actual + "'（位置 " + pos + "）");
        }
        pos++;
    }
}
