package g2s.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则库的机器可读索引。
 *
 * 索引由 tools/gen_rule_index.py 从 YAML 规则库生成，Java 侧只读 JSON，
 * 因此工具链不需要 YAML 解析库。索引缺失时校验降级但不报错——
 * 规则 id 存在性检查会被跳过，并在结果里说明。
 */
public final class RuleIndex {

    public final boolean available;
    public final String indexVersion;
    public final String sparkTarget;
    public final Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
    public final Map<String, String> fileHashes = new LinkedHashMap<>();
    public final String note;

    private RuleIndex(boolean available, String indexVersion, String sparkTarget, String note) {
        this.available = available;
        this.indexVersion = indexVersion;
        this.sparkTarget = sparkTarget;
        this.note = note;
    }

    public static RuleIndex load(Path repoRoot) {
        Path path = repoRoot.resolve("rules").resolve("index.json");
        if (!Files.exists(path)) {
            return new RuleIndex(false, null, null,
                    "未找到 rules/index.json，规则 id 存在性检查已跳过；"
                            + "请运行 tools/gen_rule_index.py 生成");
        }
        try {
            Map<String, Object> root = JsonParser.parseObject(
                    new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            RuleIndex idx = new RuleIndex(true,
                    JsonParser.str(root, "index_version"),
                    JsonParser.str(root, "spark_target"),
                    null);
            Map<String, Object> rulesNode = JsonParser.map(root.get("rules"));
            for (Map.Entry<String, Object> e : rulesNode.entrySet()) {
                idx.rules.put(e.getKey(), JsonParser.map(e.getValue()));
            }
            Map<String, Object> filesNode = JsonParser.map(root.get("files"));
            for (Map.Entry<String, Object> e : filesNode.entrySet()) {
                idx.fileHashes.put(e.getKey(), JsonParser.str(e.getValue()));
            }
            return idx;
        } catch (IOException | RuntimeException e) {
            return new RuleIndex(false, null, null, "rules/index.json 读取失败: " + e.getMessage());
        }
    }

    public boolean hasRule(String id) {
        return rules.containsKey(id);
    }

    public String tierOf(String id) {
        Map<String, Object> r = rules.get(id);
        return r == null ? null : JsonParser.str(r, "tier");
    }

    public String statusOf(String id) {
        Map<String, Object> r = rules.get(id);
        return r == null ? null : JsonParser.str(r, "status");
    }
}
