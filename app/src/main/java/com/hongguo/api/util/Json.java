package com.hongguo.api.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 解析 / 序列化。
 *
 * 为什么不用第三方库：整个项目要求「下载即用」，不引入 Maven/Gradle 依赖，
 * 因此这里用 JDK 自带能力实现一份与 JavaScript JSON 语义对齐的最小实现。
 *
 * 关键对齐点（迁移自 Node.js 时踩过的坑）：
 *  1. JS 里 obj.key / obj['key'] / obj?.key 三种写法在解析层需统一为「键不存在返回 undefined」，
 *     Java 侧对应返回 null，因此提供 optObj / optStr / optInt 等安全取值方法。
 *  2. JS 的 JSON.parse 对非法输入抛异常；本类parse 同样抛 JsonException。
 *  3. JS 的 JSON.stringify 对 undefined 返回 undefined（字段被省略），
 *     Java 侧用 skipNulls 控制；null 会输出为字面量 null。
 *  4. 数字不做字符串转换：整型保持 Long，小数保持 Double，避免 JS 里 1.0 与 1 的差异。
 */
public final class Json {

    private Json() {}

    // ==================== 异常 ====================

    public static class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }

    // ==================== 解析 ====================

    /**
     * 解析 JSON 文本。
     *
     * @returnMap / List / String / Double / Long / Boolean / null
     */
    public static Object parse(String text) {
        if (text == null) return null;
        Parser p = new Parser(text);
        p.skipWs();
        if (p.eof()) return null;
        Object v = p.parseValue();
        p.skipWs();
        if (!p.eof()) {
            throw new JsonException("JSON 尾部有多余内容，位置 " + p.pos);
        }
        return v;
    }

    /**
     * 解析并要求顶层是对象。空串返回空 Map（对齐 Node 的 JSON.parse('') 抛错的差异：
     * 上游偶尔返回空体，这里宽容处理）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.trim().isEmpty()) return new LinkedHashMap<>();
        Object v = parse(text);
        if (v == null) return new LinkedHashMap<>();
        if (!(v instanceof Map)) throw new JsonException("期望 JSON 对象，实际是 " + typeName(v));
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWs();
            if (eof()) throw new JsonException("JSON 意外结束");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return parseObj();
                case '[': return parseArr();
                case '"': return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw new JsonException("位置 " + pos + " 处期望 " + lit);
            }
            pos += lit.length();
        }

        private Map<String, Object> parseObj() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (!eof() && s.charAt(pos) == '}') { pos++; return m; }
            for (;;) {
                skipWs();
                if (eof() || s.charAt(pos) != '"') {
                    throw new JsonException("位置 " + pos + " 处期望对象键字符串");
                }
                String k = parseString();
                skipWs();
                if (eof() || s.charAt(pos) != ':') {
                    throw new JsonException("位置 " + pos + " 处期望 ':'");
                }
                pos++;
                m.put(k, parseValue());
                skipWs();
                if (eof()) throw new JsonException("对象未闭合");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new JsonException("位置 " + pos + " 处期望 ',' 或 '}'");
            }
        }

        private List<Object> parseArr() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (!eof() && s.charAt(pos) == ']') { pos++; return list; }
            for (;;) {
                list.add(parseValue());
                skipWs();
                if (eof()) throw new JsonException("数组未闭合");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw new JsonException("位置 " + pos + " 处期望 ',' 或 ']'");
            }
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            pos++; // 开引号
            while (true) {
                if (eof()) throw new JsonException("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (eof()) throw new JsonException("转义未完成");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw new JsonException("\\u 转义不完整");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new JsonException("非法转义 \\" + e);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (!eof() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean isFloat = false;
            while (!eof()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') { pos++; continue; }
                if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    if (c == '.' || c == 'e' || c == 'E') isFloat = true;
                    pos++;
                    continue;
                }
                break;
            }
            String t = s.substring(start, pos);
            if (t.isEmpty() || t.equals("-") || t.equals("+")) {
                throw new JsonException("位置 " + start + " 处不是合法数字");
            }
            try {
                if (isFloat) return Double.parseDouble(t);
                long v = Long.parseLong(t);
                return v;
            } catch (NumberFormatException e) {
                try {
                    // IDs can exceed both IEEE-754 precision and signed 64-bit range.
                    if (!isFloat) return new java.math.BigInteger(t);
                    return Double.parseDouble(t);
                } catch (NumberFormatException e2) {
                    throw new JsonException("非法数字: " + t);
                }
            }
        }
    }

    // ==================== 序列化 ====================

    /**
     * 序列化为 JSON 文本。
     *
     * @param skipNulls 为 true 时 null 值字段直接省略（对齐 JS 中undefined 被JSON.stringify 省略的行为）
     */
    public static String stringify(Object v) {
        return stringify(v, false);
    }

    public static String stringify(Object v, boolean skipNulls) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, skipNulls);
        return sb.toString();
    }

    /** 美化输出（仅用于日志与落盘）。 */
    public static String stringifyPretty(Object v) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean skipNulls) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { quote(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v.toString()); return; }
        if (v instanceof Number) { sb.append(numToJson((Number) v)); return; }
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (skipNulls && e.getValue() == null) continue;
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue(), skipNulls);
            }
            sb.append('}');
            return;
        }
        if (v instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, skipNulls);
            }
            sb.append(']');
            return;
        }
        if (v.getClass().isArray()) {
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                write(sb, java.lang.reflect.Array.get(v, i), skipNulls);
            }
            sb.append(']');
            return;
        }
        // 其他对象按 toString 处理
        quote(sb, String.valueOf(v));
    }

    private static void writePretty(StringBuilder sb, Object v, int depth) {
        StringBuilder indentation = new StringBuilder();
        for (int i = 0; i < depth; i++) indentation.append("  ");
        String padEnd = indentation.toString();
        String pad = padEnd + "  ";
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (i++ > 0) sb.append(",\n");
                sb.append(pad);
                quote(sb, e.getKey());
                sb.append(": ");
                writePretty(sb, e.getValue(), depth + 1);
            }
            sb.append('\n').append(padEnd).append('}');
            return;
        }
        if (v instanceof Collection) {
            Collection<?> c = (Collection<?>) v;
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            int i = 0;
            for (Object o : c) {
                if (i++ > 0) sb.append(",\n");
                sb.append(pad);
                writePretty(sb, o, depth + 1);
            }
            sb.append('\n').append(padEnd).append(']');
            return;
        }
        write(sb, v, false);
    }

    /**
     * 数字转 JSON 文本。
     * 与 JS 一致：整数不带小数点；Double 若值恰好为整数也输出整数形式，
     * 避免 1.0 变成 "1.0" 破坏上游签名/校验。
     */
    private static String numToJson(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
            if (d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return n.toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ==================== 安全取值（对齐 JS 的 undefined 语义） ====================

    public static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Number) return "number";
        return v.getClass().getSimpleName();
    }

    /** 取对象字段；不存在或非对象返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> optObj(Object v) {
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    /** 取数组字段；不存在或非数组返回空列表。 */
    @SuppressWarnings("unchecked")
    public static List<Object> optArr(Object v) {
        return (v instanceof List) ? (List<Object>) v : new ArrayList<>();
    }

    /** 取字符串字段；非字符串返回 def。 */
    public static String optStr(Object v, String def) {
        if (v == null) return def;
        if (v instanceof String) return (String) v;
        // JS 里数字/布尔拼进字符串的隐式转换
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return def;
    }

    /** 取字符串字段；缺失返回 ""。 */
    public static String optStr(Object v) { return optStr(v, ""); }

    /** 取整数字段；非数字返回 def。 */
    public static long optLong(Object v, long def) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    /** 取 int 字段。 */
    public static int optInt(Object v, int def) { return (int) optLong(v, def); }

    /** 取double 字段；整型值也会正确转换。 */
    public static double optDouble(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    /** 取布尔字段；非布尔返回 def。 */
    public static boolean optBool(Object v, boolean def) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
            if (s.equalsIgnoreCase("false") || s.equals("0")) return false;
        }
        return def;
    }

    /** 就地新建一个可写对象。 */
    public static Map<String, Object> obj() { return new LinkedHashMap<>(); }

    /** 就地新建一个可写数组。 */
    public static List<Object> arr() { return new ArrayList<>(); }
}
