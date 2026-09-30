/**
 * Spark 行为基线探针。
 *
 * 用途：在任意目标 Spark 版本上重跑，产出该版本的权威行为基线，
 * 供 checklist/spark-<version>-baseline.md 记录，并用于校准规则库。
 *
 * 覆盖三层：
 *   1. 语法层 parsePlan —— 无需任何表，判定语法是否被接受
 *   2. 分析层 EXPLAIN  —— 需要 schema，判定列名/函数/类型是否可用
 *   3. 语义层实测      —— NULL 排序、greatest/least、concat 的实际取值
 *
 * 编译运行（Spark 需要本地 socket，切勿在受限沙箱内运行）：
 *   SPARK_HOME=/path/to/spark
 *   javac -cp "$SPARK_HOME/jars/*" -d /tmp/probe validation/SparkBaselineProbe.java
 *   jar cf /tmp/probe.jar -C /tmp/probe .
 *   "$SPARK_HOME/bin/spark-submit" --class SparkBaselineProbe --master 'local[1]' /tmp/probe.jar
 */

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

public class SparkBaselineProbe {

    static SparkSession spark;

    static String shortMsg(Throwable e) {
        String m = String.valueOf(e.getMessage());
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl);
        return e.getClass().getSimpleName() + ": " + (m.length() > 130 ? m.substring(0, 130) + "..." : m);
    }

    /** 语法层：只解析，不分析。解析期错误会抛异常。 */
    static void parseCheck(String sql) {
        try {
            spark.sessionState().sqlParser().parsePlan(sql);
            System.out.println("PARSE_OK   | " + sql);
        } catch (Throwable e) {
            System.out.println("PARSE_FAIL | " + sql + "  ==>  " + shortMsg(e));
        }
    }

    /** 分析层：EXPLAIN。分析期错误不抛异常，而是渲染进计划文本。 */
    static void explain(String label, String sql) {
        try {
            String plan = spark.sql("EXPLAIN " + sql).first().getString(0);
            if (plan.contains("Error occurred during query planning")) {
                System.out.println("ANALYSIS_ERROR | " + label);
                for (String line : plan.split("\n")) {
                    String t = line.trim();
                    if (t.contains("Error occurred") || t.contains("cannot resolve")
                            || t.contains("UNRESOLVED") || t.contains("not supported")
                            || t.contains("does not support")) {
                        System.out.println("    " + t);
                    }
                }
            } else {
                System.out.println("PLAN_OK        | " + label);
                for (String line : plan.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("==") || t.contains("Sort") || t.contains("NULLS")) {
                        System.out.println("    " + t);
                    }
                }
            }
        } catch (Throwable e) {
            System.out.println("QUERY_REJECTED | " + label + "  ==>  " + shortMsg(e));
        }
    }

    /** DDL 执行：结果集为空，不能用 .first()。 */
    static void exec(String label, String sql) {
        try {
            Dataset<Row> df = spark.sql(sql);
            df.collect();
            System.out.println("EXEC_OK   | " + label);
        } catch (Throwable e) {
            System.out.println("EXEC_FAIL | " + label + "  ==>  " + shortMsg(e));
        }
    }

    /** 语义层：实际求值，观察标量函数的真实取值。 */
    static void value(String label, String expr) {
        try {
            Row row = spark.sql("SELECT " + expr + " AS v").first();
            System.out.println("VALUE_OK   | " + label + " = " + String.valueOf(row.get(0)));
        } catch (Throwable e) {
            System.out.println("VALUE_FAIL | " + label + "  ==>  " + shortMsg(e));
        }
    }

    static int typeCounter = 0;

    /**
     * 类型映射实测：建单列表再 DESCRIBE，读出引擎实际解析成的类型。
     * 关注点是"无精度声明的类型会不会被默认值截断"。
     */
    static void typeCheck(String label, String colDef) {
        String table = "t_type_" + (++typeCounter);
        try {
            spark.sql("CREATE TABLE " + table + " (" + colDef + ")").collect();
            Row row = spark.sql("DESCRIBE TABLE " + table).first();
            System.out.println("TYPE_OK   | " + label + "  [" + colDef + "]  ->  " + row.getString(1));
        } catch (Throwable e) {
            System.out.println("TYPE_FAIL | " + label + "  [" + colDef + "]  ==>  " + shortMsg(e));
        }
    }

    public static void main(String[] args) {
        spark = SparkSession.builder()
                .appName("g2s-baseline-probe")
                .master("local[1]")
                .config("spark.ui.enabled", "false")
                .config("spark.sql.shuffle.partitions", "1")
                .config("spark.sql.warehouse.dir", "/tmp/g2s-probe-warehouse")
                .getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");

        System.out.println("=== ENV ===");
        System.out.println("spark = " + spark.version());
        System.out.println("java  = " + System.getProperty("java.version"));

        exec("建立测试视图", "CREATE TEMPORARY VIEW nums AS "
                + "SELECT 1 AS a UNION ALL SELECT 2 AS a UNION ALL SELECT CAST(NULL AS INT) AS a");
        exec("建立测试表", "CREATE TABLE probe_t (a INT, b STRING)");

        System.out.println();
        System.out.println("=== 1. 语法层（无需表） ===");
        parseCheck("SELECT 1");
        parseCheck("SELECT a::int FROM t");
        parseCheck("SELECT a FROM t DISTRIBUTE BY a");
        parseCheck("SELECT DISTINCT ON (a) a FROM t");
        parseCheck("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY a) FROM t");
        parseCheck("SELECT a FROM t CONNECT BY PRIOR a = b");
        parseCheck("SELECT a FROM t FOR UPDATE");
        parseCheck("UPDATE t SET a = 1");
        parseCheck("DELETE FROM t WHERE a = 1");
        parseCheck("MERGE INTO t USING s ON t.a = s.a WHEN MATCHED THEN UPDATE SET t.b = s.b");
        parseCheck("ALTER TABLE t RENAME COLUMN a TO b");
        parseCheck("CREATE TABLE t (a VARCHAR(10), b DECIMAL(10,2))");
        parseCheck("CREATE SEQUENCE seq1");
        parseCheck("COPY t FROM '/tmp/x.csv'");

        System.out.println();
        System.out.println("=== 2. 类型名（openGauss 类型是否被接受） ===");
        explain("a::int", "SELECT a::int FROM nums");
        explain("a::int4", "SELECT a::int4 FROM nums");
        explain("a::bpchar", "SELECT a::bpchar FROM nums");

        System.out.println();
        System.out.println("=== 3. 分析层（真实 schema） ===");
        explain("列名正确", "SELECT a FROM nums");
        explain("列名错误", "SELECT nonexistent_col FROM nums");
        explain("函数不存在", "SELECT nosuchfunction(a) FROM nums");
        explain("DISTINCT ON", "SELECT DISTINCT ON (a) a FROM nums");
        explain("percentile_cont", "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY a) FROM nums");
        explain("UPDATE 普通表", "UPDATE probe_t SET a = 1");
        explain("MERGE 普通表", "MERGE INTO probe_t t USING nums s ON t.a = s.a "
                + "WHEN MATCHED THEN UPDATE SET t.a = s.a");

        System.out.println();
        System.out.println("=== 4. NULL 排序默认值 ===");
        explain("ORDER BY a", "SELECT a FROM nums ORDER BY a");
        explain("ORDER BY a DESC", "SELECT a FROM nums ORDER BY a DESC");
        explain("ORDER BY a NULLS LAST", "SELECT a FROM nums ORDER BY a NULLS LAST");

        System.out.println();
        System.out.println("=== 5. 语义取值 ===");
        value("greatest(1, NULL)", "greatest(1, CAST(NULL AS INT))");
        value("least(1, NULL)", "least(1, CAST(NULL AS INT))");
        value("concat('a', NULL)", "concat('a', CAST(NULL AS STRING))");
        value("concat_ws('', 'a', NULL)", "concat_ws('', 'a', CAST(NULL AS STRING))");

        System.out.println();
        System.out.println("=== 6. 类型映射实测（DESCRIBE 解析结果） ===");
        typeCheck("DECIMAL 无精度", "a DECIMAL");
        typeCheck("NUMERIC 写法", "a NUMERIC");
        typeCheck("NUMERIC(10,2)", "a NUMERIC(10,2)");
        typeCheck("VARCHAR 无长度", "a VARCHAR");
        typeCheck("VARCHAR(10)", "a VARCHAR(10)");
        typeCheck("CHAR(5)", "a CHAR(5)");
        typeCheck("STRING", "a STRING");
        typeCheck("BINARY", "a BINARY");
        typeCheck("INT[] 数组写法", "a INT[]");
        typeCheck("ARRAY<INT>", "a ARRAY<INT>");
        typeCheck("TIME 类型", "a TIME");
        typeCheck("INTERVAL 列类型", "a INTERVAL");
        // 以下为 openGauss/PostgreSQL 类型名，预期被引擎拒绝
        typeCheck("BYTEA", "a BYTEA");
        typeCheck("UUID", "a UUID");
        typeCheck("JSONB", "a JSONB");
        typeCheck("TIMESTAMPTZ", "a TIMESTAMPTZ");
        typeCheck("MONEY", "a MONEY");
        typeCheck("TEXT", "a TEXT");
        typeCheck("INT4", "a INT4");
        typeCheck("FLOAT4", "a FLOAT4");
        typeCheck("BPCHAR", "a BPCHAR");

        spark.stop();
    }
}
