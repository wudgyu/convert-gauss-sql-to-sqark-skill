package g2s.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产物契约校验。
 *
 * 校验的是「记录是否完整、枚举是否合法、引用的规则是否存在」这类机械可判定的事，
 * 不判断转换内容对不对——那是审核节点的职责。
 */
public final class Contract {

    public static final Set<String> TIERS = Set.of("auto", "confirm", "blocked");
    public static final Set<String> RISKS = Set.of("low", "medium", "high");
    public static final Set<String> DECISIONS =
            Set.of("converted", "unchanged", "blocked", "needs_manual");
    public static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");
    public static final Set<String> SOURCES = Set.of("rule", "llm", "none");
    public static final Set<String> SEVERITIES = Set.of("error", "warning", "info");
    public static final Set<String> VERDICTS = Set.of("pass", "revise", "block");
    public static final Set<String> APPROVAL_ACTIONS =
            Set.of("accept-converted", "use-manual", "exclude");

    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public boolean ok() {
        return errors.isEmpty();
    }

    private void err(String msg) {
        errors.add(msg);
    }

    private void warn(String msg) {
        warnings.add(msg);
    }

    private static boolean blank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static void requireEnum(Contract c, String id, String field, Object value, Set<String> allowed) {
        String v = JsonParser.str(value);
        if (v == null || !allowed.contains(v)) {
            c.err(id + " 的 " + field + " 取值非法: " + v + "（允许 " + allowed + "）");
        }
    }

    private void checkRuleIds(String id, String field, List<String> ids, RuleIndex index) {
        for (String ruleId : ids) {
            if (index.available && !index.hasRule(ruleId)) {
                err(id + " 的 " + field + " 引用了不存在的规则: " + ruleId);
            }
        }
    }

    /** 校验 statements.jsonl，返回按 id 建立的对齐表。 */
    public Map<String, Map<String, Object>> checkStatements(Run run) throws IOException {
        List<Map<String, Object>> statements = run.jsonl(Run.STATEMENTS);
        if (statements.isEmpty()) {
            err("缺少 statements.jsonl 或内容为空，请先运行 g2s split");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> st : statements) {
            String id = JsonParser.str(st, "statement_id");
            if (blank(id)) {
                err("statements.jsonl 存在缺少 statement_id 的记录");
                continue;
            }
            if (!seen.add(id)) {
                err("statements.jsonl 中 statement_id 重复: " + id);
            }
        }
        return Run.byStatementId(statements);
    }

    public void checkAnalysis(Run run, Map<String, Map<String, Object>> statements, RuleIndex index)
            throws IOException {
        List<Map<String, Object>> records = run.jsonl(Run.ANALYSIS);
        Set<String> covered = new LinkedHashSet<>();
        for (Map<String, Object> r : records) {
            String id = JsonParser.str(r, "statement_id");
            if (blank(id)) {
                err("01_analysis.jsonl 存在缺少 statement_id 的记录");
                continue;
            }
            if (!statements.containsKey(id)) {
                err("分析记录 " + id + " 不在 statements.jsonl 中");
            }
            covered.add(id);
            requireEnum(this, id, "tier", r.get("tier"), TIERS);
            requireEnum(this, id, "risk", r.get("risk"), RISKS);
            if (!(r.get("features") instanceof List)) {
                err(id + " 的 features 必须是数组（无内容时用空数组）");
            }
            if (blank(r.get("reason"))) {
                err(id + " 缺少 reason，档位判定必须给出依据");
            }
            checkRuleIds(id, "rules_hit", JsonParser.strList(r, "rules_hit"), index);
        }
        for (String id : statements.keySet()) {
            if (!covered.contains(id)) {
                err("语句 " + id + " 缺少分析记录，阶段未完成");
            }
        }
    }

    public void checkConversion(Run run, Map<String, Map<String, Object>> statements,
                                RuleIndex index) throws IOException {
        List<Map<String, Object>> records = run.jsonl(Run.CONVERSION);
        Set<String> covered = new LinkedHashSet<>();
        for (Map<String, Object> r : records) {
            String id = JsonParser.str(r, "statement_id");
            if (blank(id)) {
                err("02_conversion.jsonl 存在缺少 statement_id 的记录");
                continue;
            }
            covered.add(id);
            requireEnum(this, id, "decision", r.get("decision"), DECISIONS);
            requireEnum(this, id, "confidence", r.get("confidence"), CONFIDENCES);
            requireEnum(this, id, "source", r.get("source"), SOURCES);

            String decision = JsonParser.str(r, "decision");
            String sqlAfter = JsonParser.str(r, "sql_after");
            if ("converted".equals(decision) || "unchanged".equals(decision)) {
                if (blank(sqlAfter)) {
                    err(id + " 的 decision 为 " + decision + " 但 sql_after 为空");
                } else if (sqlAfter.strip().endsWith(";")) {
                    warn(id + " 的 sql_after 以分号结尾；契约要求不含结尾分号，组装时会去掉");
                }
            }
            if (blank(r.get("understanding"))) {
                err(id + " 缺少 understanding：必须写明源端语义");
            }
            if (blank(r.get("strategy"))) {
                err(id + " 缺少 strategy：必须写明采用的转换策略");
            }
            if (!(r.get("alternatives") instanceof List)) {
                err(id + " 的 alternatives 必须是数组（没有备选时用空数组）");
            } else {
                for (Object o : JsonParser.list(r, "alternatives")) {
                    Map<String, Object> alt = JsonParser.map(o);
                    if (blank(alt.get("option")) || blank(alt.get("rejected_because"))) {
                        err(id + " 的 alternatives 条目必须同时含 option 与 rejected_because");
                    }
                }
            }
            if (!(r.get("risks") instanceof List)) {
                err(id + " 的 risks 必须是数组");
            }
            Object evidence = r.get("evidence");
            if (!(evidence instanceof List) || ((List<?>) evidence).isEmpty()) {
                err(id + " 缺少 evidence：必须给出原文片段与行号");
            } else {
                for (Object o : JsonParser.list(r, "evidence")) {
                    Map<String, Object> ev = JsonParser.map(o);
                    if (blank(ev.get("snippet"))) {
                        err(id + " 的 evidence 条目缺少 snippet");
                    }
                }
            }

            List<String> rulesUsed = JsonParser.strList(r, "rules_used");
            checkRuleIds(id, "rules_used", rulesUsed, index);
            if ("rule".equals(JsonParser.str(r, "source")) && rulesUsed.isEmpty()) {
                err(id + " 的 source 为 rule 但 rules_used 为空");
            }
            if ("none".equals(JsonParser.str(r, "source")) && !rulesUsed.isEmpty()) {
                err(id + " 的 source 为 none 表示未应用任何规则，rules_used 必须为空");
            }
            if ("llm".equals(JsonParser.str(r, "source")) && !rulesUsed.isEmpty()) {
                warn(id + " 的 source 为 llm 但填了 rules_used，请确认归属");
            }
        }
        for (String id : statements.keySet()) {
            if (!covered.contains(id)) {
                err("语句 " + id + " 缺少转换记录，阶段未完成");
            }
        }
    }

    public void checkReview(Run run, Map<String, Map<String, Object>> statements) throws IOException {
        for (Map<String, Object> r : run.jsonl(Run.REVIEW)) {
            String id = JsonParser.str(r, "statement_id");
            if (blank(id)) {
                err("03_review.jsonl 存在缺少 statement_id 的记录");
                continue;
            }
            if (blank(r.get("independent_reading"))) {
                err(id + " 缺少 independent_reading：审核必须先独立推导语义");
            }
            requireEnum(this, id, "verdict", r.get("verdict"), VERDICTS);
            Object findings = r.get("findings");
            if (!(findings instanceof List)) {
                err(id + " 的 findings 必须是数组");
                continue;
            }
            for (Object o : JsonParser.list(r, "findings")) {
                Map<String, Object> f = JsonParser.map(o);
                if (blank(f.get("id"))) {
                    err(id + " 的 findings 条目缺少 id");
                }
                requireEnum(this, id, "findings.severity", f.get("severity"), SEVERITIES);
                if (blank(f.get("message"))) {
                    err(id + " 的 findings 条目缺少 message");
                }
                if (blank(f.get("suggestion"))) {
                    err(id + " 的 findings 条目缺少 suggestion：必须给出可直接执行的修改动作");
                }
            }
        }
    }

    public void checkFix(Run run) throws IOException {
        for (Map<String, Object> r : run.jsonl(Run.FIX)) {
            String id = JsonParser.str(r, "statement_id");
            if (blank(id)) {
                err("04_fix.jsonl 存在缺少 statement_id 的记录");
                continue;
            }
            long round = JsonParser.num(r, "round", 0);
            if (round < 1 || round > 2) {
                err(id + " 的 round 必须为 1 或 2，纠错最多两轮");
            }
            if (blank(r.get("answers_finding"))) {
                err(id + " 缺少 answers_finding：必须写明回应的是哪条 finding");
            }
            if (blank(r.get("change"))) {
                err(id + " 缺少 change：必须写明改了什么");
            }
            if (blank(r.get("sql_after"))) {
                err(id + " 缺少 sql_after");
            }
        }
    }

    /** 读审批记录，返回 statement_id -> 记录。 */
    public Map<String, Map<String, Object>> checkApprovals(Run run,
                                                          Map<String, Map<String, Object>> statements)
            throws IOException {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : run.jsonl(Run.APPROVALS)) {
            String id = JsonParser.str(r, "statement_id");
            if (blank(id)) {
                err("approvals.jsonl 存在缺少 statement_id 的记录");
                continue;
            }
            if (!statements.containsKey(id)) {
                err("审批记录 " + id + " 不在 statements.jsonl 中");
            }
            requireEnum(this, id, "action", r.get("action"), APPROVAL_ACTIONS);
            if (blank(r.get("by"))) {
                err("审批记录 " + id + " 缺少 by（确认人）");
            }
            if (blank(r.get("at"))) {
                err("审批记录 " + id + " 缺少 at（确认时间）");
            }
            if ("use-manual".equals(JsonParser.str(r, "action")) && blank(r.get("manual_sql"))) {
                err("审批记录 " + id + " 的 action 为 use-manual 但 manual_sql 为空");
            }
            byId.put(id, r);
        }
        return byId;
    }

    public void print() {
        for (String w : warnings) {
            System.out.println("警告: " + w);
        }
        for (String e : errors) {
            System.out.println("错误: " + e);
        }
    }
}
