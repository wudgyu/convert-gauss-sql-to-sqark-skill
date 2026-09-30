package g2s.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** run 目录的读取入口：所有产物都是 UTF-8 的 JSON / JSONL。 */
public final class Run {

    public static final String INPUT = "00_input.sql";
    public static final String STATEMENTS = "statements.jsonl";
    public static final String ANALYSIS = "01_analysis.jsonl";
    public static final String CONVERSION = "02_conversion.jsonl";
    public static final String REVIEW = "03_review.jsonl";
    public static final String FIX = "04_fix.jsonl";
    public static final String APPROVALS = "approvals.jsonl";
    public static final String FINAL_SQL = "05_final.sql";
    public static final String PROCESS_LOG = "06_process-log.md";
    public static final String AUDIT_REPORT = "07_audit-report.md";
    public static final String HTML_REPORT = "08_report.html";
    public static final String MANIFEST = "run_manifest.json";
    public static final String TRACE = "trace.jsonl";

    public final Path dir;

    public Run(Path dir) {
        this.dir = dir;
    }

    public Path path(String name) {
        return dir.resolve(name);
    }

    public boolean has(String name) {
        return Files.exists(path(name));
    }

    /** 读取 JSONL；文件不存在返回空列表，行号会体现在异常信息里。 */
    public List<Map<String, Object>> jsonl(String name) throws IOException {
        Path p = path(name);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(p)) {
            return out;
        }
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        for (int k = 0; k < lines.size(); k++) {
            String line = lines.get(k);
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(JsonParser.parseObject(line));
            } catch (RuntimeException e) {
                throw new IOException(name + " 第 " + (k + 1) + " 行不是合法 JSON: " + e.getMessage());
            }
        }
        return out;
    }

    public Map<String, Object> json(String name) throws IOException {
        Path p = path(name);
        if (!Files.exists(p)) {
            return new LinkedHashMap<>();
        }
        return JsonParser.parseObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    public String text(String name) throws IOException {
        Path p = path(name);
        if (!Files.exists(p)) {
            return "";
        }
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 追加一条事件到 trace.jsonl。 */
    public void trace(String event, Map<String, Object> payload) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('{')
                .append("\"at\":").append(Json.str(java.time.OffsetDateTime.now().toString())).append(',')
                .append("\"event\":").append(Json.str(event)).append(',')
                .append("\"payload\":{");
        boolean first = true;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(Json.str(e.getKey())).append(':').append(Json.value(e.getValue()));
        }
        sb.append("}}\n");
        Files.write(path(TRACE),
                sb.toString().getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    public static String sha256OfFile(Path p) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 按 statement_id 建立索引，便于跨阶段对齐。 */
    public static Map<String, Map<String, Object>> byStatementId(List<Map<String, Object>> records) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> r : records) {
            String id = JsonParser.str(r, "statement_id");
            if (id != null) {
                m.put(id, r);
            }
        }
        return m;
    }
}
