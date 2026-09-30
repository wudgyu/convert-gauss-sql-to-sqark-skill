package g2s.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一次运行的全部上下文：run 目录、规则索引、各阶段记录与契约校验结果。 */
public final class WorkflowContext {

    public final Run run;
    public final Path repoRoot;
    public final RuleIndex index;
    public final Contract contract = new Contract();

    public Map<String, Map<String, Object>> statements = new LinkedHashMap<>();
    public Map<String, Map<String, Object>> analysis = new LinkedHashMap<>();
    public Map<String, Map<String, Object>> conversion = new LinkedHashMap<>();
    public Map<String, Map<String, Object>> review = new LinkedHashMap<>();
    public Map<String, Map<String, Object>> fix = new LinkedHashMap<>();
    public Map<String, Map<String, Object>> approvals = new LinkedHashMap<>();

    private WorkflowContext(Run run, Path repoRoot, RuleIndex index) {
        this.run = run;
        this.repoRoot = repoRoot;
        this.index = index;
    }

    public static WorkflowContext load(Path runDir, Path repoRoot) throws IOException {
        Run run = new Run(runDir);
        WorkflowContext ctx = new WorkflowContext(run, repoRoot, RuleIndex.load(repoRoot));

        ctx.statements = ctx.contract.checkStatements(run);
        ctx.analysis = Run.byStatementId(run.jsonl(Run.ANALYSIS));
        ctx.conversion = Run.byStatementId(run.jsonl(Run.CONVERSION));
        ctx.review = Run.byStatementId(run.jsonl(Run.REVIEW));
        ctx.fix = Run.byStatementId(run.jsonl(Run.FIX));

        ctx.contract.checkAnalysis(run, ctx.statements, ctx.index);
        ctx.contract.checkConversion(run, ctx.statements, ctx.index);
        ctx.contract.checkReview(run, ctx.statements);
        ctx.contract.checkFix(run);
        ctx.approvals = ctx.contract.checkApprovals(run, ctx.statements);
        return ctx;
    }
}
