package g2s.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 产物组装：最终 SQL、转换过程文档、审计报告、运行清单。 */
public final class Assemble {

    public static final class Options {
        public boolean allowPartial;
        public String modelHost = "unknown";
        public String modelName = "unknown";
        public String verificationTier = "static-only";
        public String toolVersion = "unknown";
    }

    public static void run(WorkflowContext ctx, Gate.Result gate, Options opts) throws IOException {
        List<Map<String, Object>> ordered = ctx.run.jsonl(Run.STATEMENTS);
        writeFinalSql(ctx, gate, opts, ordered);
        writeProcessLog(ctx, gate, ordered);
        writeAuditReport(ctx, gate, opts);
        writeManifest(ctx, gate, opts);
        ctx.run.trace("assemble", Map.of(
                "statements", ordered.size(),
                "verification_tier", opts.verificationTier,
                "allow_partial", opts.allowPartial));
    }

    // ---- 05_final.sql ----

    private static void writeFinalSql(WorkflowContext ctx, Gate.Result gate, Options opts,
                                      List<Map<String, Object>> ordered) throws IOException {
        if (!gate.passed && !opts.allowPartial) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("-- 由 gauss2spark 生成，请勿手工在此文件上维护\n");
        sb.append("-- 源文件: ").append(inputName(ctx)).append('\n');
        sb.append("-- 目标方言: Spark SQL ").append(orEmpty(ctx.index.sparkTarget, "4.2.0")).append('\n');
        sb.append("-- 生成时间: ").append(OffsetDateTime.now()).append('\n');
        sb.append("-- 校验档位: ").append(opts.verificationTier).append('\n');
        if (!gate.passed) {
            sb.append("--\n");
            sb.append("-- ！！！这是残缺输出：仍有 ").append(gate.unresolvedCount())
                    .append(" 条语句未获审批，已用 [BLOCKED] 标记跳过 ！！！\n");
            sb.append("-- 禁止直接执行，请先完成人工确认。\n");
        }
        sb.append('\n');

        for (Map<String, Object> st : ordered) {
            String id = JsonParser.str(st, "statement_id");
            Map<String, Object> approval = ctx.approvals.get(id);
            String action = approval == null ? null : JsonParser.str(approval, "action");
            Map<String, Object> cv = ctx.conversion.get(id);
            String decision = cv == null ? null : JsonParser.str(cv, "decision");

            for (Object c : JsonParser.list(st, "leading_comments")) {
                sb.append(JsonParser.str(JsonParser.map(c), "text")).append('\n');
            }

            if ("exclude".equals(action)) {
                sb.append("-- [排除 ").append(id).append("] ")
                        .append(orEmpty(JsonParser.str(approval, "note"), "人工确认排除")).append('\n');
                sb.append('\n');
                continue;
            }
            if ("use-manual".equals(action)) {
                sb.append("-- [人工改写 ").append(id).append("] ")
                        .append(orEmpty(JsonParser.str(approval, "note"), "人工提供的实现")).append('\n');
                sb.append(stripSemicolon(JsonParser.str(approval, "manual_sql"))).append(";\n\n");
                continue;
            }
            if (cv == null || "blocked".equals(decision) || "needs_manual".equals(decision)) {
                sb.append("-- [BLOCKED ").append(id).append("] ")
                        .append(orEmpty(reasonOf(ctx, id), "未获审批，跳过")).append('\n');
                sb.append("-- 原语句: ")
                        .append(oneLine(JsonParser.str(st, "sql"))).append('\n');
                sb.append('\n');
                continue;
            }
            sb.append(stripSemicolon(JsonParser.str(cv, "sql_after"))).append(";\n\n");
        }
        Files.writeString(ctx.run.path(Run.FINAL_SQL), sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- 06_process-log.md ----

    private static void writeProcessLog(WorkflowContext ctx, Gate.Result gate,
                                        List<Map<String, Object>> ordered) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 转换过程文档\n\n");
        sb.append("本文件记录每条语句从识别到定稿的决策链。机器可读的全量记录在\n")
                .append("`statements.jsonl`、`01_analysis.jsonl`、`02_conversion.jsonl`、\n")
                .append("`03_review.jsonl`、`04_fix.jsonl` 中，本文件是它们的可读视图。\n\n");
        sb.append("闸门状态: ").append(gate.passed ? "已放行" : "未放行")
                .append("（auto ").append(gate.auto)
                .append(" / confirm ").append(gate.confirm)
                .append(" / blocked ").append(gate.blocked)
                .append(" / 已审批 ").append(gate.approved)
                .append(" / 待确认 ").append(gate.unresolvedCount()).append("）\n\n");

        sb.append("## 全部语句一览\n\n");
        sb.append("| 语句 | 类型 | 档位 | 转换结果 | 来源 | 规则数 | 审核 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> st : ordered) {
            String id = JsonParser.str(st, "statement_id");
            Map<String, Object> an = ctx.analysis.get(id);
            Map<String, Object> cv = ctx.conversion.get(id);
            Map<String, Object> rv = ctx.review.get(id);
            sb.append("| ").append(id)
                    .append(" | ").append(orEmpty(JsonParser.str(st, "kind"), "?"))
                    .append(" | ").append(an == null ? "-" : JsonParser.str(an, "tier"))
                    .append(" | ").append(cv == null ? "-" : JsonParser.str(cv, "decision"))
                    .append(" | ").append(cv == null ? "-" : JsonParser.str(cv, "source"))
                    .append(" | ").append(cv == null ? "0" : String.valueOf(JsonParser.strList(cv, "rules_used").size()))
                    .append(" | ").append(rv == null ? "-" : JsonParser.str(rv, "verdict"))
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## 需要细读的语句\n\n");
        int detailed = 0;
        for (Map<String, Object> st : ordered) {
            String id = JsonParser.str(st, "statement_id");
            Map<String, Object> an = ctx.analysis.get(id);
            Map<String, Object> cv = ctx.conversion.get(id);
            Map<String, Object> rv = ctx.review.get(id);
            String tier = an == null ? null : JsonParser.str(an, "tier");
            boolean hasFinding = rv != null && !JsonParser.list(rv, "findings").isEmpty();
            // 详述范围：非 auto 档、非 converted 结果、或审核有 findings 的语句
            boolean needDetail = (tier != null && !"auto".equals(tier))
                    || (cv != null && !"converted".equals(JsonParser.str(cv, "decision")))
                    || hasFinding;
            if (!needDetail) {
                continue;
            }
            detailed++;
            sb.append("### ").append(id)
                    .append("（").append(orEmpty(JsonParser.str(st, "kind"), "?"))
                    .append("，第 ").append(JsonParser.num(st, "start_line", 0))
                    .append("-").append(JsonParser.num(st, "end_line", 0)).append(" 行）\n\n");
            if (an != null) {
                sb.append("- 档位: ").append(JsonParser.str(an, "tier"))
                        .append("（风险 ").append(JsonParser.str(an, "risk")).append("）\n");
                sb.append("- 判定依据: ").append(oneLine(JsonParser.str(an, "reason"))).append('\n');
                List<String> hit = JsonParser.strList(an, "rules_hit");
                if (!hit.isEmpty()) {
                    sb.append("- 命中规则: ").append(String.join(", ", hit)).append('\n');
                }
            }
            if (cv != null) {
                sb.append("- 识别结果: ").append(oneLine(JsonParser.str(cv, "understanding"))).append('\n');
                sb.append("- 采用策略: ").append(oneLine(JsonParser.str(cv, "strategy"))).append('\n');
                sb.append("- 置信度: ").append(JsonParser.str(cv, "confidence"))
                        .append("，来源: ").append(JsonParser.str(cv, "source")).append('\n');
                List<Object> alts = JsonParser.list(cv, "alternatives");
                if (alts.isEmpty()) {
                    sb.append("- 备选方案: 无\n");
                } else {
                    sb.append("- 备选方案与放弃原因:\n");
                    for (Object o : alts) {
                        Map<String, Object> a = JsonParser.map(o);
                        sb.append("  - ").append(oneLine(JsonParser.str(a, "option")))
                                .append(" —— 放弃原因: ")
                                .append(oneLine(JsonParser.str(a, "rejected_because"))).append('\n');
                    }
                }
                List<String> risks = JsonParser.strList(cv, "risks");
                sb.append("- 风险自评: ").append(risks.isEmpty() ? "无" : String.join("；", risks)).append('\n');
                List<Object> ev = JsonParser.list(cv, "evidence");
                if (!ev.isEmpty()) {
                    sb.append("- 证据片段:\n");
                    for (Object o : ev) {
                        Map<String, Object> e = JsonParser.map(o);
                        sb.append("  - 第 ").append(JsonParser.num(e, "line", 0)).append(" 行: ")
                                .append(oneLine(JsonParser.str(e, "snippet"))).append('\n');
                    }
                }
                List<String> used = JsonParser.strList(cv, "rules_used");
                sb.append("- 应用规则: ").append(used.isEmpty() ? "（无，模型自由发挥）" : String.join(", ", used))
                        .append('\n');
            }
            if (rv != null) {
                sb.append("- 审核结论: ").append(JsonParser.str(rv, "verdict")).append('\n');
                if (!JsonParser.list(rv, "findings").isEmpty()) {
                    sb.append("- 审核发现:\n");
                    for (Object o : JsonParser.list(rv, "findings")) {
                        Map<String, Object> f = JsonParser.map(o);
                        sb.append("  - [").append(JsonParser.str(f, "severity")).append("] ")
                                .append(JsonParser.str(f, "id")).append(": ")
                                .append(oneLine(JsonParser.str(f, "message")))
                                .append(" → ").append(oneLine(JsonParser.str(f, "suggestion"))).append('\n');
                    }
                }
            }
            Map<String, Object> fx = ctx.fix.get(id);
            if (fx != null) {
                sb.append("- 纠错第 ").append(JsonParser.num(fx, "round", 0)).append(" 轮: ")
                        .append(oneLine(JsonParser.str(fx, "change")))
                        .append("（回应 ").append(JsonParser.str(fx, "answers_finding")).append("）\n");
            }
            Map<String, Object> ap = ctx.approvals.get(id);
            if (ap != null) {
                sb.append("- 人工确认: ").append(JsonParser.str(ap, "action"))
                        .append("，由 ").append(JsonParser.str(ap, "by"))
                        .append(" 于 ").append(JsonParser.str(ap, "at"))
                        .append(" 确认");
                String note = JsonParser.str(ap, "note");
                if (note != null && !note.isBlank()) {
                    sb.append("，说明: ").append(oneLine(note));
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (detailed == 0) {
            sb.append("本次运行所有语句均为 auto 档且转换顺利，无需细读项。\n");
        }
        Files.writeString(ctx.run.path(Run.PROCESS_LOG), sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- 07_audit-report.md ----

    private static void writeAuditReport(WorkflowContext ctx, Gate.Result gate, Options opts)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 审计报告\n\n");
        sb.append("## 结论\n\n");
        sb.append("- 闸门状态: ").append(gate.passed ? "**已放行**" : "**未放行**").append('\n');
        sb.append("- 校验档位: `").append(opts.verificationTier).append("`\n");
        if (!"execution-verified".equals(opts.verificationTier)) {
            sb.append("- **本次结果未经真实数据执行验证**，仅完成到上述档位。\n");
        }
        sb.append("- 源方言: openGauss（DBCOMPATIBILITY = PG），目标方言: Spark SQL ")
                .append(orEmpty(ctx.index.sparkTarget, "4.2.0")).append('\n');
        sb.append("- 模型宿主: ").append(opts.modelHost)
                .append("，模型: ").append(opts.modelName).append('\n');
        sb.append("- 生成时间: ").append(OffsetDateTime.now()).append("\n\n");

        sb.append("## 统计\n\n");
        sb.append("| 项 | 数量 |\n|---|---|\n");
        sb.append("| 语句总数 | ").append(gate.total).append(" |\n");
        sb.append("| auto 档（自动放行） | ").append(gate.auto).append(" |\n");
        sb.append("| confirm 档 | ").append(gate.confirm).append(" |\n");
        sb.append("| blocked 档 | ").append(gate.blocked).append(" |\n");
        sb.append("| 已获人工确认 | ").append(gate.approved).append(" |\n");
        sb.append("| 仍待确认 | ").append(gate.unresolvedCount()).append(" |\n\n");

        List<String> llmRecords = new ArrayList<>();
        Map<String, Integer> ruleUsage = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> e : ctx.conversion.entrySet()) {
            Map<String, Object> cv = e.getValue();
            if ("llm".equals(JsonParser.str(cv, "source"))) {
                llmRecords.add(e.getKey());
            }
            for (String rid : JsonParser.strList(cv, "rules_used")) {
                ruleUsage.merge(rid, 1, Integer::sum);
            }
        }
        sb.append("## 模型自由发挥的语句\n\n");
        if (llmRecords.isEmpty()) {
            sb.append("无。本次全部转换都由规则库推导得出。\n\n");
        } else {
            sb.append("以下语句没有规则依据，由模型自行判断完成，审核时应逐条复核：\n\n");
            for (String id : llmRecords) {
                sb.append("- ").append(id).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## 规则使用情况\n\n");
        if (ruleUsage.isEmpty()) {
            sb.append("无规则被应用。\n\n");
        } else {
            sb.append("| 规则 | 命中次数 |\n|---|---|\n");
            for (Map.Entry<String, Integer> e : ruleUsage.entrySet()) {
                sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 待确认与阻断清单\n\n");
        if (gate.unresolvedIds.isEmpty()) {
            sb.append("无。\n\n");
        } else {
            sb.append("| 语句 | 原因 | 处置 |\n|---|---|---|\n");
            for (String id : gate.unresolvedIds) {
                Map<String, Object> an = ctx.analysis.get(id);
                String tier = an == null ? "-" : JsonParser.str(an, "tier");
                sb.append("| ").append(id).append(" | ")
                        .append(gate.unresolvedReasons.getOrDefault(id, ""))
                        .append(" | 档位 ").append(tier)
                        .append("，需人工确认或改写 |\n");
            }
            sb.append('\n');
        }

        sb.append("## 人工确认记录\n\n");
        if (ctx.approvals.isEmpty()) {
            sb.append("无。\n\n");
        } else {
            sb.append("| 语句 | 处置 | 确认人 | 时间 | 说明 |\n|---|---|---|---|---|\n");
            for (Map.Entry<String, Map<String, Object>> e : ctx.approvals.entrySet()) {
                Map<String, Object> ap = e.getValue();
                sb.append("| ").append(e.getKey())
                        .append(" | ").append(JsonParser.str(ap, "action"))
                        .append(" | ").append(JsonParser.str(ap, "by"))
                        .append(" | ").append(JsonParser.str(ap, "at"))
                        .append(" | ").append(orEmpty(JsonParser.str(ap, "note"), ""))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 契约校验\n\n");
        if (ctx.contract.ok()) {
            sb.append("通过，无错误。\n");
        } else {
            sb.append("存在 ").append(ctx.contract.errors.size()).append(" 项错误：\n\n");
            for (String e : ctx.contract.errors) {
                sb.append("- ").append(e).append('\n');
            }
        }
        if (!ctx.contract.warnings.isEmpty()) {
            sb.append("\n警告 ").append(ctx.contract.warnings.size()).append(" 项：\n\n");
            for (String w : ctx.contract.warnings) {
                sb.append("- ").append(w).append('\n');
            }
        }
        Files.writeString(ctx.run.path(Run.AUDIT_REPORT), sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- run_manifest.json ----

    private static void writeManifest(WorkflowContext ctx, Gate.Result gate, Options opts)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", ctx.run.dir.getFileName().toString());
        m.put("generated_at", OffsetDateTime.now().toString());
        m.put("tool", Map.of("name", "g2s-core", "version", opts.toolVersion));

        Map<String, Object> input = new LinkedHashMap<>();
        Path inputPath = ctx.run.path(Run.INPUT);
        input.put("file", Run.INPUT);
        input.put("exists", Files.exists(inputPath));
        if (Files.exists(inputPath)) {
            input.put("bytes", Files.size(inputPath));
            input.put("sha256", Run.sha256OfFile(inputPath));
        }
        Map<String, Object> summary = ctx.run.json("split_summary.json");
        if (!summary.isEmpty()) {
            input.put("encoding", summary.get("encoding"));
            input.put("had_bom", summary.get("had_bom"));
            input.put("physical_lines", summary.get("physical_lines"));
            input.put("route", summary.get("route"));
        }
        input.put("statement_count", ctx.statements.size());
        m.put("input", input);

        m.put("dialect", Map.of(
                "source", "openGauss-PG",
                "target", "spark-" + orEmpty(ctx.index.sparkTarget, "4.2.0")));

        Map<String, Object> ruleSet = new LinkedHashMap<>();
        ruleSet.put("index_available", ctx.index.available);
        ruleSet.put("index_version", ctx.index.indexVersion);
        ruleSet.put("spark_target", ctx.index.sparkTarget);
        ruleSet.put("files", ctx.index.fileHashes);
        if (ctx.index.note != null) {
            ruleSet.put("note", ctx.index.note);
        }
        m.put("rule_set", ruleSet);

        m.put("stages", Map.of(
                "analyze", ctx.analysis.size(),
                "convert", ctx.conversion.size(),
                "review", ctx.review.size(),
                "fix", ctx.fix.size()));
        m.put("gate", Map.of(
                "passed", gate.passed,
                "total", gate.total,
                "auto", gate.auto,
                "confirm", gate.confirm,
                "blocked", gate.blocked,
                "approved", gate.approved,
                "unresolved", gate.unresolvedCount()));
        m.put("verification_tier", opts.verificationTier);
        m.put("model", Map.of("host", opts.modelHost, "name", opts.modelName));
        m.put("contract", Map.of(
                "errors", ctx.contract.errors.size(),
                "warnings", ctx.contract.warnings.size()));

        Files.writeString(ctx.run.path(Run.MANIFEST),
                Json.value(m) + "\n", StandardCharsets.UTF_8);
    }

    // ---- 小工具 ----

    private static String inputName(WorkflowContext ctx) {
        Map<String, Object> summary = new LinkedHashMap<>();
        try {
            summary = ctx.run.json("split_summary.json");
        } catch (IOException ignored) {
            // 摘要缺失不影响组装
        }
        String in = JsonParser.str(summary, "input");
        return in == null ? Run.INPUT : in;
    }

    private static String reasonOf(WorkflowContext ctx, String id) {
        Map<String, Object> an = ctx.analysis.get(id);
        return an == null ? null : JsonParser.str(an, "reason");
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    private static String stripSemicolon(String sql) {
        if (sql == null) {
            return "";
        }
        String t = sql.strip();
        while (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1).strip();
        }
        return t;
    }

    private static String orEmpty(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
