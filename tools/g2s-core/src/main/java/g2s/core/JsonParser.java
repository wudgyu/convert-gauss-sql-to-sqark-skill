package g2s.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器，只覆盖本项目产物需要的子集。
 *
 * 之所以自己实现：工具链约定零第三方依赖，而 run 产物、规则索引都是 JSON/JSONL，
 * 必须能读回来做契约校验。
 */
public final class JsonParser {

    private final String s;
    private int i;

    private JsonParser(String s) {
        this.s = s;
        this.i = 0;
    }

    public static Object parse(String text) {
        JsonParser p = new JsonParser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i < p.s.length()) {
            throw new IllegalArgumentException("JSON 尾部存在多余内容，位置 " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("顶层不是 JSON 对象");
        }
        return (Map<String, Object>) v;
    }

    // ---- 取值辅助 ----

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static String str(Map<String, Object> m, String key) {
        return str(m.get(key));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        return list(m.get(key));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object o = m.get(key);
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof String) {
            return Boolean.parseBoolean((String) o);
        }
        return fallback;
    }

    public static long num(Object o, long fallback) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof String) {
            try {
                return Long.parseLong(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static long num(Map<String, Object> m, String key, long fallback) {
        return num(m.get(key), fallback);
    }

    public static List<String> strList(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(m, key)) {
            out.add(str(o));
        }
        return out;
    }

    // ---- 解析实现 ----

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return objectInternal();
            case '[': return arrayInternal();
            case '"': return stringInternal();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return numberInternal();
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) {
            throw new IllegalArgumentException("非法字面量，位置 " + i);
        }
        i += word.length();
    }

    private Map<String, Object> objectInternal() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        skipWs();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            skipWs();
            String key = stringInternal();
            skipWs();
            expect(':');
            skipWs();
            m.put(key, value());
            skipWs();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> arrayInternal() {
        List<Object> a = new ArrayList<>();
        i++;
        skipWs();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return a;
        }
        while (true) {
            skipWs();
            a.add(value());
            skipWs();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            expect(']');
            return a;
        }
    }

    private String stringInternal() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                break;
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw new IllegalArgumentException("\\u 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    throw new IllegalArgumentException("无法识别的转义: \\" + e);
            }
        }
        throw new IllegalArgumentException("字符串未闭合");
    }

    private Object numberInternal() {
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                i++;
            } else {
                break;
            }
        }
        String text = s.substring(start, i);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("非法数值，位置 " + start);
        }
        if (text.contains(".") || text.contains("e") || text.contains("E")) {
            return Double.parseDouble(text);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return Double.parseDouble(text);
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw new IllegalArgumentException("期望字符 " + c + "，位置 " + i);
        }
        i++;
    }
}
