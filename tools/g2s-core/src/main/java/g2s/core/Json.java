package g2s.core;

import java.util.List;
import java.util.Map;

/** 极简 JSON 输出，避免为了写 JSONL 引入第三方库。 */
public final class Json {

    private Json() {
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public static String str(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    public static String strList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(str(items.get(i)));
        }
        return sb.append(']').toString();
    }

    public static String comment(CommentSpan c) {
        if (c == null) {
            return "null";
        }
        return "{\"kind\":" + str(c.kind)
                + ",\"text\":" + str(c.text)
                + ",\"start_line\":" + c.startLine
                + ",\"end_line\":" + c.endLine
                + "}";
    }

    public static String commentList(List<CommentSpan> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(comment(list.get(i)));
        }
        return sb.append(']').toString();
    }

    public static String intMap(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(str(e.getKey())).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }
}
