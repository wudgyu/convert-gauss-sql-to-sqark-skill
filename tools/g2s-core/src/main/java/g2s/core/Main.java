package g2s.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * g2s 确定性内核 CLI。
 *
 * 当前实现 split 子命令：切分 + 编号 + 阈值路由判定。
 */
public final class Main {

    private static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        try {
            switch (command) {
                case "split":
                    System.exit(cmdSplit(args));
                    break;
                case "version":
                    System.out.println("g2s-core " + VERSION);
                    break;
                default:
                    usage();
                    System.exit(2);
            }
        } catch (IOException e) {
            System.err.println("IO 错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("用法: g2s <command> [options]");
        System.err.println();
        System.err.println("命令:");
        System.err.println("  split --in <file> [--out <dir>] [--max-lines N] [--max-statements N]");
        System.err.println("  version");
        System.err.println();
        System.err.println("split 说明: 切分 SQL 文件并按阈值判定走直通还是分片路径，");
        System.err.println("            输出 <dir>/statements.jsonl 与 <dir>/split_summary.json。");
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                        ? args[++i] : "true";
                opts.put(key, value);
            }
        }
        return opts;
    }

    private static int cmdSplit(String[] args) throws IOException {
        Map<String, String> opts = parseOptions(args);
        String in = opts.get("in");
        if (in == null) {
            System.err.println("split 需要 --in <file>");
            return 2;
        }
        int maxLines = Integer.parseInt(opts.getOrDefault("max-lines", "200"));
        int maxStatements = Integer.parseInt(opts.getOrDefault("max-statements", "20"));

        Path inPath = Paths.get(in);
        Encoding encoding = Encoding.read(inPath);
        SqlSplitter.Result result = SqlSplitter.split(encoding);
        int physicalLines = result.lineCount;

        String route = (physicalLines > maxLines || result.statements.size() > maxStatements)
                ? "split" : "direct";

        Map<String, Integer> kindHistogram = new LinkedHashMap<>();
        int plBlocks = 0;
        int metaCommands = 0;
        int heuristic = 0;
        for (Statement st : result.statements) {
            kindHistogram.merge(st.kind, 1, Integer::sum);
            if (st.isPlBlock) {
                plBlocks++;
            }
            if (st.isMetaCommand) {
                metaCommands++;
            }
            if ("heuristic".equals(st.splitConfidence)) {
                heuristic++;
            }
        }

        String out = opts.get("out");
        if (out != null) {
            Path outDir = Paths.get(out);
            Files.createDirectories(outDir);
            writeStatements(outDir.resolve("statements.jsonl"), result.statements);
            writeSummary(outDir.resolve("split_summary.json"), inPath, encoding, result,
                    physicalLines, route, maxLines, maxStatements, kindHistogram,
                    plBlocks, metaCommands, heuristic);
        }

        System.out.println("文件        : " + inPath.toAbsolutePath());
        System.out.println("编码        : " + encoding.charsetName + (encoding.hadBom ? " (BOM)" : ""));
        System.out.println("物理行数    : " + physicalLines + "   (阈值 " + maxLines + ")");
        System.out.println("语句数      : " + result.statements.size() + "   (阈值 " + maxStatements + ")");
        System.out.println("路由        : " + ("split".equals(route)
                ? "使用切分器分片处理" : "文件足够小，直通模型"));
        System.out.println("语句类型    : " + kindHistogram);
        System.out.println("PL 块       : " + plBlocks + " 条（一律阻断）");
        System.out.println("元命令      : " + metaCommands + " 条（客户端执行，阻断）");
        if (heuristic > 0) {
            System.out.println("启发式切分  : " + heuristic + " 条（PL 块边界依赖 BEGIN/END 计数）");
        }
        for (String w : result.warnings) {
            System.out.println("警告        : " + w);
        }
        return 0;
    }

    private static void writeStatements(Path path, List<Statement> statements) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Statement st : statements) {
            sb.append('{')
                    .append("\"statement_id\":").append(Json.str(st.statementId)).append(',')
                    .append("\"index\":").append(st.index).append(',')
                    .append("\"kind\":").append(Json.str(st.kind)).append(',')
                    .append("\"start_line\":").append(st.startLine).append(',')
                    .append("\"end_line\":").append(st.endLine).append(',')
                    .append("\"start_char\":").append(st.startChar).append(',')
                    .append("\"end_char\":").append(st.endChar).append(',')
                    .append("\"start_byte\":").append(st.startByte).append(',')
                    .append("\"end_byte\":").append(st.endByte).append(',')
                    .append("\"sha256\":").append(Json.str(st.sha256)).append(',')
                    .append("\"has_dollar_quote\":").append(st.hasDollarQuote).append(',')
                    .append("\"is_pl_block\":").append(st.isPlBlock).append(',')
                    .append("\"is_meta_command\":").append(st.isMetaCommand).append(',')
                    .append("\"split_confidence\":").append(Json.str(st.splitConfidence)).append(',')
                    .append("\"leading_comments\":").append(Json.commentList(st.leadingComments)).append(',')
                    .append("\"trailing_comments\":").append(Json.commentList(st.trailingComments)).append(',')
                    .append("\"warnings\":").append(Json.strList(st.warnings)).append(',')
                    .append("\"sql\":").append(Json.str(st.sql)).append(',')
                    .append("\"text\":").append(Json.str(st.text))
                    .append("}\n");
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path path, Path in, Encoding encoding, SqlSplitter.Result result,
                                     int physicalLines, String route, int maxLines, int maxStatements,
                                     Map<String, Integer> kindHistogram, int plBlocks,
                                     int metaCommands, int heuristic) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"input\": ").append(Json.str(in.toAbsolutePath().toString())).append(",\n");
        sb.append("  \"encoding\": ").append(Json.str(encoding.charsetName)).append(",\n");
        sb.append("  \"had_bom\": ").append(encoding.hadBom).append(",\n");
        sb.append("  \"byte_length\": ").append(encoding.byteLength).append(",\n");
        sb.append("  \"physical_lines\": ").append(physicalLines).append(",\n");
        sb.append("  \"statement_count\": ").append(result.statements.size()).append(",\n");
        sb.append("  \"route\": ").append(Json.str(route)).append(",\n");
        sb.append("  \"thresholds\": {\"max_lines\": ").append(maxLines)
                .append(", \"max_statements\": ").append(maxStatements).append("},\n");
        sb.append("  \"kind_histogram\": ").append(Json.intMap(kindHistogram)).append(",\n");
        sb.append("  \"pl_block_count\": ").append(plBlocks).append(",\n");
        sb.append("  \"meta_command_count\": ").append(metaCommands).append(",\n");
        sb.append("  \"heuristic_split_count\": ").append(heuristic).append(",\n");
        sb.append("  \"warnings\": ").append(Json.strList(result.warnings)).append("\n");
        sb.append("}\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}
