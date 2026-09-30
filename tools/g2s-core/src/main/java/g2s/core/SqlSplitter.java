package g2s.core;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多语句 SQL 文件的词法切分器。
 *
 * 只在最外层（NORMAL 状态）遇到分号才断句。以下位置的分号一律视为普通字符：
 *   - 单引号字符串（含 '' 转义与 E'...' 反斜杠转义）
 *   - 双引号标识符（含 "" 转义）
 *   - 美元引用体 $tag$ ... $tag$ 与 $$ ... $$
 *   - 行注释 -- ...
 *   - 块注释，支持嵌套
 *
 * 另外识别 psql 元命令（行首反斜杠）与 PL 块（DO / CREATE FUNCTION / PROCEDURE /
 * TRIGGER / BEGIN），PL 块即使不含美元引用也不会被内部分号切开。
 */
public final class SqlSplitter {

    private static final int NORMAL = 0;
    private static final int SINGLE_QUOTE = 3;
    private static final int DOUBLE_QUOTE = 4;
    private static final int DOLLAR_QUOTE = 5;

    /** 首个关键字即代表 PL 结构的情形。 */
    private static final Set<String> PL_FIRST_WORDS = Set.of("DO", "CALL", "DECLARE");
    /** CREATE/ALTER/DROP 的对象是这些关键字时属于 PL 结构。 */
    private static final Set<String> PL_OBJECTS = Set.of(
            "FUNCTION", "PROCEDURE", "TRIGGER", "PACKAGE");
    private static final Set<String> END_COMPOUND = Set.of(
            "IF", "LOOP", "CASE", "WHILE", "FOR");
    private static final Set<String> DDL_VERBS = Set.of("CREATE", "ALTER", "DROP");
    /** 判定语句类型与 PL 形态需要回看的关键字数。 */
    private static final int LOOKAHEAD_WORDS = 6;

    public static final class Result {
        public final List<Statement> statements = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public int lineCount;
        public Charset charset;
        public String charsetName;
        public boolean hadBom;
    }

    public static Result split(Encoding encoding) {
        final String text = encoding.text;
        final int n = text.length();
        final Result result = new Result();
        result.charset = encoding.charset;
        result.charsetName = encoding.charsetName;
        result.hadBom = encoding.hadBom;

        int line = 1;
        long bytePos = 0;
        int i = 0;

        int stmtStartChar = 0;
        int stmtStartLine = 1;
        long stmtStartByte = 0;

        List<CommentSpan> pendingLeading = new ArrayList<>();
        List<CommentSpan> pendingTrailing = new ArrayList<>();

        int mode = NORMAL;
        String dollarTag = null;
        boolean eString = false;

        List<String> firstWords = new ArrayList<>();
        boolean plMode = false;
        int plDepth = 0;
        boolean hasDollarQuote = false;
        boolean heuristicBoundary = false;
        boolean atLineStart = true;
        // 本语句是否已出现实质内容（注释不算）。用于区分前导注释与行尾注释。
        boolean seenContent = false;
        // 最近一条已产出语句及其结束行，用于把"与分号同行"的注释归给它。
        Statement lastEmitted = null;
        int lastEmitLine = -1;

        List<Statement> statements = result.statements;

        while (i < n) {
            char c = text.charAt(i);
            char next = (i + 1 < n) ? text.charAt(i + 1) : '\0';

            if (mode == NORMAL) {
                if (c == '\\' && atLineStart) {
                    if (seenContent) {
                        emit(statements, text, stmtStartChar, i, stmtStartLine, stmtStartByte,
                                pendingLeading, pendingTrailing, plMode, hasDollarQuote,
                                heuristicBoundary, false);
                        pendingLeading = new ArrayList<>();
                        pendingTrailing = new ArrayList<>();
                        firstWords = new ArrayList<>();
                        plMode = false;
                        plDepth = 0;
                        hasDollarQuote = false;
                        heuristicBoundary = false;
                    }
                    int j = i;
                    while (j < n && text.charAt(j) != '\n') {
                        j++;
                    }
                    Statement meta = emit(statements, text, stmtStartChar, j, stmtStartLine,
                            stmtStartByte, new ArrayList<>(), new ArrayList<>(),
                            false, false, false, true);
                    if (meta != null) {
                        meta.warnings.add("psql 元命令由客户端执行，不属于 SQL 语句");
                    }
                    stmtStartChar = j;
                    stmtStartLine = line;
                    stmtStartByte = bytePosAt(text, stmtStartChar, j, encoding.charset, stmtStartByte);
                    pendingLeading = new ArrayList<>();
                    pendingTrailing = new ArrayList<>();
                    firstWords = new ArrayList<>();
                    plMode = false;
                    plDepth = 0;
                    hasDollarQuote = false;
                    heuristicBoundary = false;
                    seenContent = false;
                    i = j;
                    continue;
                }

                if (c == '-' && next == '-') {
                    int startLine = line;
                    int j = i;
                    while (j < n && text.charAt(j) != '\n') {
                        j++;
                    }
                    CommentSpan span = new CommentSpan("line", text.substring(i, j), startLine, startLine);
                    if (seenContent) {
                        pendingTrailing.add(span);
                    } else if (lastEmitted != null && line == lastEmitLine) {
                        lastEmitted.trailingComments.add(span);
                        // 该注释归上一条语句，下一条语句的跨度必须从注释之后开始
                        stmtStartByte = bytePosAt(text, stmtStartChar, j, encoding.charset, stmtStartByte);
                        stmtStartChar = j;
                        stmtStartLine = line;
                    } else {
                        pendingLeading.add(span);
                    }
                    i = j;
                    continue;
                }

                if (c == '/' && next == '*') {
                    int startLine = line;
                    int j = i + 2;
                    int depth = 1;
                    int jLine = line;
                    while (j < n && depth > 0) {
                        char cj = text.charAt(j);
                        if (cj == '\n') {
                            jLine++;
                        } else if (cj == '/' && j + 1 < n && text.charAt(j + 1) == '*') {
                            depth++;
                            j++;
                        } else if (cj == '*' && j + 1 < n && text.charAt(j + 1) == '/') {
                            depth--;
                            j++;
                        }
                        j++;
                    }
                    CommentSpan span = new CommentSpan("block",
                            text.substring(i, Math.min(j, n)), startLine, jLine);
                    if (seenContent) {
                        pendingTrailing.add(span);
                    } else if (lastEmitted != null && line == lastEmitLine) {
                        lastEmitted.trailingComments.add(span);
                        stmtStartByte = bytePosAt(text, stmtStartChar, j, encoding.charset, stmtStartByte);
                        stmtStartChar = j;
                        stmtStartLine = line;
                    } else {
                        pendingLeading.add(span);
                    }
                    line = jLine;
                    atLineStart = true;
                    i = j;
                    continue;
                }

                if (c == '\'') {
                    mode = SINGLE_QUOTE;
                    eString = isEStringPrefix(text, i);
                    seenContent = true;
                    i++;
                    atLineStart = false;
                    continue;
                }

                if (c == '"') {
                    mode = DOUBLE_QUOTE;
                    seenContent = true;
                    i++;
                    atLineStart = false;
                    continue;
                }

                if (c == '$') {
                    String tag = matchDollarTag(text, i);
                    if (tag != null) {
                        dollarTag = tag;
                        mode = DOLLAR_QUOTE;
                        hasDollarQuote = true;
                        seenContent = true;
                        i += tag.length();
                        atLineStart = false;
                        continue;
                    }
                }

                if (Character.isLetter(c) || c == '_') {
                    int j = i;
                    while (j < n && (Character.isLetterOrDigit(text.charAt(j))
                            || text.charAt(j) == '_' || text.charAt(j) == '$')) {
                        j++;
                    }
                    String word = text.substring(i, j).toUpperCase(Locale.ROOT);
                    if (firstWords.size() < LOOKAHEAD_WORDS) {
                        firstWords.add(word);
                        plMode = detectPlMode(firstWords);
                    }
                    if (plMode) {
                        if ("BEGIN".equals(word)) {
                            plDepth++;
                        } else if ("END".equals(word)) {
                            String following = peekWord(text, j, n);
                            if (following == null || !END_COMPOUND.contains(following)) {
                                plDepth = Math.max(0, plDepth - 1);
                            }
                        }
                    }
                    i = j;
                    atLineStart = false;
                    seenContent = true;
                    continue;
                }

                if (c == ';') {
                    if (plMode && plDepth > 0) {
                        heuristicBoundary = true;
                        i++;
                        atLineStart = false;
                        continue;
                    }
                    int endExclusive = i + 1;
                    Statement st = emit(statements, text, stmtStartChar, endExclusive, stmtStartLine,
                            stmtStartByte, pendingLeading, pendingTrailing, plMode, hasDollarQuote,
                            heuristicBoundary, false);
                    if (st != null && plMode && heuristicBoundary) {
                        st.warnings.add("PL 块不含美元引用，边界依赖 BEGIN/END 计数，属于启发式切分");
                    }
                    if (st != null) {
                        lastEmitted = st;
                        lastEmitLine = line;
                    }
                    stmtStartChar = endExclusive;
                    stmtStartLine = line;
                    stmtStartByte = bytePosAt(text, stmtStartChar, endExclusive, encoding.charset, stmtStartByte);
                    pendingLeading = new ArrayList<>();
                    pendingTrailing = new ArrayList<>();
                    firstWords = new ArrayList<>();
                    plMode = false;
                    plDepth = 0;
                    hasDollarQuote = false;
                    heuristicBoundary = false;
                    seenContent = false;
                    i = endExclusive;
                    atLineStart = false;
                    continue;
                }

                if (c == '\n') {
                    line++;
                    atLineStart = true;
                } else if (!Character.isWhitespace(c)) {
                    atLineStart = false;
                    seenContent = true;
                }
                i++;
                continue;
            }

            if (mode == SINGLE_QUOTE) {
                if (c == '\n') {
                    line++;
                }
                if (eString && c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    if (next == '\'') {
                        i += 2;
                        continue;
                    }
                    mode = NORMAL;
                }
                i++;
                continue;
            }

            if (mode == DOUBLE_QUOTE) {
                if (c == '\n') {
                    line++;
                }
                if (c == '"') {
                    if (next == '"') {
                        i += 2;
                        continue;
                    }
                    mode = NORMAL;
                }
                i++;
                continue;
            }

            if (mode == DOLLAR_QUOTE) {
                if (c == '\n') {
                    line++;
                }
                if (c == '$' && text.startsWith(dollarTag, i)) {
                    i += dollarTag.length();
                    mode = NORMAL;
                    atLineStart = false;
                    continue;
                }
                i++;
                continue;
            }

            i++;
        }

        if (seenContent) {
            Statement st = emit(statements, text, stmtStartChar, n, stmtStartLine, stmtStartByte,
                    pendingLeading, pendingTrailing, plMode, hasDollarQuote, heuristicBoundary, false);
            if (st != null) {
                st.warnings.add("文件末尾语句没有以分号结束");
            }
        }
        if (mode != NORMAL) {
            result.warnings.add("文件结束时仍处于未闭合状态（字符串/注释/美元引用体），请检查源文件完整性");
        }

        result.lineCount = countLines(text);
        for (int k = 0; k < statements.size(); k++) {
            statements.get(k).statementId = String.format("s%04d", k + 1);
            statements.get(k).index = k + 1;
        }
        return result;
    }

    private static boolean detectPlMode(List<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        if (PL_FIRST_WORDS.contains(words.get(0))) {
            return true;
        }
        String object = objectWord(words);
        return object != null && PL_OBJECTS.contains(object);
    }

    /**
     * 取 DDL 语句的操作对象关键字，跳过 OR REPLACE 与 GLOBAL/LOCAL/TEMP 等修饰。
     * 例：CREATE OR REPLACE PROCEDURE -> PROCEDURE；CREATE TEMP TABLE -> TABLE。
     */
    private static String objectWord(List<String> words) {
        if (words.isEmpty() || !DDL_VERBS.contains(words.get(0))) {
            return null;
        }
        int i = 1;
        if (i < words.size() && "OR".equals(words.get(i))) {
            if (i + 1 < words.size() && "REPLACE".equals(words.get(i + 1))) {
                i += 2;
            } else {
                i += 1;
            }
        }
        while (i < words.size()) {
            String w = words.get(i);
            if ("GLOBAL".equals(w) || "LOCAL".equals(w) || "TEMP".equals(w)
                    || "TEMPORARY".equals(w) || "IF".equals(w) || "NOT".equals(w)
                    || "EXISTS".equals(w) || "UNIQUE".equals(w) || "MATERIALIZED".equals(w)) {
                i++;
                continue;
            }
            return w;
        }
        return null;
    }

    private static String peekWord(String text, int from, int n) {
        int j = from;
        while (j < n && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        int k = j;
        while (k < n && (Character.isLetterOrDigit(text.charAt(k)) || text.charAt(k) == '_')) {
            k++;
        }
        if (k == j) {
            return null;
        }
        return text.substring(j, k).toUpperCase(Locale.ROOT);
    }

    private static boolean isEStringPrefix(String text, int quoteIndex) {
        if (quoteIndex == 0) {
            return false;
        }
        char prev = text.charAt(quoteIndex - 1);
        if (prev != 'E' && prev != 'e') {
            return false;
        }
        return quoteIndex < 2 || !Character.isLetterOrDigit(text.charAt(quoteIndex - 2));
    }

    /** 匹配 $$ 或 $tag$，返回标签本身；不是美元引用时返回 null。 */
    private static String matchDollarTag(String text, int start) {
        int n = text.length();
        if (start + 1 >= n) {
            return null;
        }
        if (text.charAt(start + 1) == '$') {
            return "$$";
        }
        int j = start + 1;
        if (!(Character.isLetter(text.charAt(j)) || text.charAt(j) == '_')) {
            return null;
        }
        while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
            j++;
        }
        if (j < n && text.charAt(j) == '$') {
            return text.substring(start, j + 1);
        }
        return null;
    }

    private static int countLines(String text) {
        int count = 0;
        for (int k = 0; k < text.length(); k++) {
            if (text.charAt(k) == '\n') {
                count++;
            }
        }
        return count + 1;
    }

    /** 计算语句起始/结束的字节偏移（ASCII 走快路径，非 ASCII 按编码实测）。 */
    private static long bytePosAt(String text, int from, int to, Charset cs, long baseByte) {
        long total = baseByte;
        for (int k = from; k < to; k++) {
            char c = text.charAt(k);
            if (c < 0x80) {
                total += 1;
            } else if (Character.isLowSurrogate(c)) {
                total += 0;
            } else {
                total += String.valueOf(c).getBytes(cs).length;
            }
        }
        return total;
    }

    private static Statement emit(List<Statement> out, String text, int startChar, int endChar,
                                  int startLine, long startByte,
                                  List<CommentSpan> leading, List<CommentSpan> trailing,
                                  boolean isPl, boolean dollarQuoted, boolean heuristic,
                                  boolean meta) {
        String raw = text.substring(startChar, Math.min(endChar, text.length()));
        String body = stripComments(raw, leading, trailing);
        if (body.isBlank()) {
            return null;
        }
        Statement st = new Statement();
        st.text = raw;
        st.sql = body;
        st.startChar = startChar;
        st.endChar = endChar;
        st.startLine = startLine;
        st.endLine = startLine + countOccurrences(raw, '\n');
        st.startByte = startByte;
        st.endByte = bytePosAt(text, startChar, endChar, java.nio.charset.StandardCharsets.UTF_8, startByte);
        st.leadingComments = new ArrayList<>(leading);
        st.trailingComments = new ArrayList<>(trailing);
        st.hasDollarQuote = dollarQuoted;
        st.isPlBlock = isPl;
        st.isMetaCommand = meta;
        st.splitConfidence = heuristic ? "heuristic" : "exact";
        st.sha256 = sha256(body);
        st.kind = meta ? "META" : classify(body);
        out.add(st);
        return st;
    }

    private static int countOccurrences(String s, char c) {
        int count = 0;
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == c) {
                count++;
            }
        }
        return count;
    }

    /** 去掉前导注释与行尾注释，保留语句主体。 */
    private static String stripComments(String raw, List<CommentSpan> leading,
                                        List<CommentSpan> trailing) {
        String body = raw;
        if (leading != null) {
            for (CommentSpan c : leading) {
                int idx = body.indexOf(c.text);
                if (idx >= 0) {
                    body = body.substring(0, idx) + body.substring(idx + c.text.length());
                }
            }
        }
        if (trailing != null) {
            for (CommentSpan c : trailing) {
                int idx = body.indexOf(c.text);
                if (idx >= 0) {
                    body = body.substring(0, idx) + body.substring(idx + c.text.length());
                }
            }
        }
        return body.strip();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 依据首个关键字判定语句类型。 */
    public static String classify(String sql) {
        List<String> words = firstWordsOf(sql, LOOKAHEAD_WORDS);
        if (words.isEmpty()) {
            return "EMPTY";
        }
        String w0 = words.get(0);
        switch (w0) {
            case "SELECT":
            case "WITH":
            case "VALUES":
                return "SELECT";
            case "INSERT":
                return "INSERT";
            case "UPDATE":
                return "UPDATE";
            case "DELETE":
                return "DELETE";
            case "MERGE":
                return "MERGE";
            case "TRUNCATE":
                return "TRUNCATE";
            case "COMMENT":
                return "COMMENT";
            case "GRANT":
                return "GRANT";
            case "REVOKE":
                return "REVOKE";
            case "SET":
                return "SET";
            case "RESET":
                return "RESET";
            case "SHOW":
                return "SHOW";
            case "EXPLAIN":
                return "EXPLAIN";
            case "ANALYZE":
            case "VACUUM":
                return "MAINTENANCE";
            case "LOCK":
                return "LOCK";
            case "REINDEX":
            case "CLUSTER":
                return "MAINTENANCE";
            case "COPY":
                return "COPY";
            case "DO":
                return "DO";
            case "CALL":
                return "CALL";
            case "DECLARE":
            case "FETCH":
            case "CLOSE":
            case "MOVE":
                return "CURSOR";
            case "PREPARE":
            case "EXECUTE":
            case "DEALLOCATE":
                return "PREPARED";
            case "LISTEN":
            case "NOTIFY":
            case "UNLISTEN":
                return "ASYNC";
            case "BEGIN":
            case "START":
            case "COMMIT":
            case "ROLLBACK":
            case "SAVEPOINT":
            case "RELEASE":
                return "TRANSACTION";
            case "CREATE":
                return "CREATE_" + normalizeType(objectWord(words));
            case "ALTER":
                return "ALTER_" + normalizeType(objectWord(words));
            case "DROP":
                return "DROP_" + normalizeType(objectWord(words));
            default:
                return "UNKNOWN";
        }
    }

    private static String normalizeType(String word) {
        if (word == null || word.isEmpty()) {
            return "UNKNOWN";
        }
        switch (word) {
            case "TEMP":
            case "TEMPORARY":
            case "GLOBAL":
            case "LOCAL":
                return "TABLE";
            case "OR":
            case "REPLACE":
                return "UNKNOWN";
            default:
                return word;
        }
    }

    private static List<String> firstWordsOf(String sql, int limit) {
        List<String> words = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n && words.size() < limit) {
            char c = sql.charAt(i);
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                words.add(sql.substring(i, j).toUpperCase(Locale.ROOT));
                i = j;
                continue;
            }
            i++;
        }
        return words;
    }
}
