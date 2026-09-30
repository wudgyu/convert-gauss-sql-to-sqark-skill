package g2s.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 闸门：决定这次运行能否产出最终 SQL。
 *
 * 规则很硬：只有 auto 档自动放行；confirm 与 blocked 必须有审批记录。
 * 任何缺失都视为阻断，宁可不出结果，也不出结果不明的 SQL。
 */
public final class Gate {

    public static final class Result {
        public int total;
        public int auto;
        public int confirm;
        public int blocked;
        public int approved;
        public final List<String> unresolvedIds = new ArrayList<>();
        public final Map<String, String> unresolvedReasons = new LinkedHashMap<>();
        public boolean passed;

        public int unresolvedCount() {
            return unresolvedIds.size();
        }
    }

    public static Result evaluate(WorkflowContext ctx) {
        Result r = new Result();
        r.total = ctx.statements.size();

        for (String id : ctx.statements.keySet()) {
            Map<String, Object> an = ctx.analysis.get(id);
            Map<String, Object> cv = ctx.conversion.get(id);

            if (an == null) {
                r.unresolvedIds.add(id);
                r.unresolvedReasons.put(id, "缺少分析记录");
                continue;
            }
            String tier = JsonParser.str(an, "tier");
            if ("auto".equals(tier)) {
                r.auto++;
            } else if ("confirm".equals(tier)) {
                r.confirm++;
            } else if ("blocked".equals(tier)) {
                r.blocked++;
            } else {
                r.unresolvedIds.add(id);
                r.unresolvedReasons.put(id, "档位缺失或非法: " + tier);
                continue;
            }

            if (cv == null) {
                r.unresolvedIds.add(id);
                r.unresolvedReasons.put(id, "缺少转换记录");
                continue;
            }
            String decision = JsonParser.str(cv, "decision");
            boolean needsApproval = !"auto".equals(tier)
                    || "blocked".equals(decision)
                    || "needs_manual".equals(decision);

            // 审核要求修改但没有任何纠错记录的语句不得静默放行
            Map<String, Object> rv = ctx.review.get(id);
            if (rv != null) {
                String verdict = JsonParser.str(rv, "verdict");
                if ("block".equals(verdict)) {
                    needsApproval = true;
                } else if ("revise".equals(verdict) && !ctx.fix.containsKey(id)) {
                    needsApproval = true;
                }
            }

            if (!needsApproval) {
                continue;
            }
            if (ctx.approvals.containsKey(id)) {
                r.approved++;
            } else {
                r.unresolvedIds.add(id);
                r.unresolvedReasons.put(id, describe(tier, decision, ctx, id));
            }
        }

        r.passed = r.unresolvedCount() == 0 && ctx.contract.ok();
        return r;
    }

    private static String describe(String tier, String decision, WorkflowContext ctx, String id) {
        Map<String, Object> rv = ctx.review.get(id);
        if (rv != null) {
            String verdict = JsonParser.str(rv, "verdict");
            if ("block".equals(verdict) && "auto".equals(tier)) {
                return "审核结论为 block，需人工介入";
            }
            if ("revise".equals(verdict) && !ctx.fix.containsKey(id)) {
                return "审核要求修改但缺少纠错记录";
            }
        }
        if ("blocked".equals(tier) || "blocked".equals(decision)) {
            return "无法转换，需人工改写或排除";
        }
        if ("needs_manual".equals(decision)) {
            return "转换未完成，需人工处理";
        }
        return "可转换但需人工确认（" + tier + " 档）";
    }
}
