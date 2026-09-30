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

    /** 聚合语义确认：带 FROM nums 求值。 */
    static void agg(String label, String expr) {
        try {
            Row row = spark.sql("SELECT " + expr + " AS v FROM nums").first();
            System.out.println("AGG_OK   | " + label + " = " + String.valueOf(row.get(0)));
        } catch (Throwable e) {
            System.out.println("AGG_FAIL | " + label + "  ==>  " + shortMsg(e));
        }
    }

    static int typeCounter = 0;

    /**
     * 函数可用性检查：对表达式跑 EXPLAIN，判定 Spark 是否接受（含分析期）。
     * 用于确定哪些 openGauss 函数需要映射、哪些可以原样保留。
     */
    static void funcCheck(String label, String expr) {
        String sql = "SELECT " + expr + " FROM nums";
        try {
            String plan = spark.sql("EXPLAIN " + sql).first().getString(0);
            if (plan.contains("Error occurred during query planning")) {
                System.out.println("FUNC_ANALYSIS_ERROR | " + label);
            } else {
                System.out.println("FUNC_OK             | " + label);
            }
        } catch (Throwable e) {
            System.out.println("FUNC_REJECTED       | " + label + "  ==>  " + shortMsg(e));
        }
    }

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

        System.out.println();
        System.out.println("=== 7. 函数可用性实测（EXPLAIN 判定，nums.a 为 INT） ===");
        funcCheck("nvl(a,0)", "nvl(a, 0)");
        funcCheck("nvl2(a,1,0)", "nvl2(a, 1, 0)");
        funcCheck("ifnull(a,0)", "ifnull(a, 0)");
        funcCheck("coalesce(a,0)", "coalesce(a, 0)");
        funcCheck("nullif(a,0)", "nullif(a, 0)");
        funcCheck("decode(a,1,'x','y')", "decode(a, 1, 'x', 'y')");
        funcCheck("strpos('abc','b')", "strpos('abc', 'b')");
        funcCheck("instr('abc','b')", "instr('abc', 'b')");
        funcCheck("position('b' in 'abc')", "position('b' in 'abc')");
        funcCheck("substring('abc' from 1 for 2)", "substring('abc' from 1 for 2)");
        funcCheck("substr('abc',1,2)", "substr('abc', 1, 2)");
        funcCheck("to_char(current_date,'YYYY-MM-DD')", "to_char(current_date, 'YYYY-MM-DD')");
        funcCheck("to_date('2026-01-01','YYYY-MM-DD')", "to_date('2026-01-01', 'YYYY-MM-DD')");
        funcCheck("to_timestamp('2026-01-01 00:00:00','YYYY-MM-DD HH24:MI:SS')",
                "to_timestamp('2026-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')");
        funcCheck("to_number('12.5','999.9')", "to_number('12.5', '999.9')");
        funcCheck("date_format(current_date,'yyyy-MM-dd')", "date_format(current_date, 'yyyy-MM-dd')");
        funcCheck("date_trunc('month',current_date)", "date_trunc('month', current_date)");
        funcCheck("date_trunc('MONTH',current_date)", "date_trunc('MONTH', current_date)");
        funcCheck("date_part('month',current_date)", "date_part('month', current_date)");
        funcCheck("extract(month from current_date)", "extract(month from current_date)");
        funcCheck("string_agg(a,',')", "string_agg(a, ',')");
        funcCheck("listagg(a,',')", "listagg(a, ',')");
        funcCheck("array_agg(a)", "array_agg(a)");
        funcCheck("collect_list(a)", "collect_list(a)");
        funcCheck("concat_ws(',',collect_list(a))", "concat_ws(',', collect_list(a))");
        funcCheck("concat('a',1)", "concat('a', 1)");
        funcCheck("concat_ws('','a',1)", "concat_ws('', 'a', 1)");
        funcCheck("sha256('x')", "sha256('x')");
        funcCheck("sha2('x',256)", "sha2('x', 256)");
        funcCheck("gen_random_uuid()", "gen_random_uuid()");
        funcCheck("uuid()", "uuid()");
        funcCheck("sysdate", "sysdate");
        funcCheck("now()", "now()");
        funcCheck("current_date", "current_date");
        funcCheck("current_timestamp", "current_timestamp");
        funcCheck("generate_series(1,3)", "generate_series(1, 3)");
        funcCheck("sequence(1,3)", "sequence(1, 3)");
        funcCheck("unnest(array(1,2))", "unnest(array(1, 2))");
        funcCheck("explode(array(1,2))", "explode(array(1, 2))");
        funcCheck("regexp_like('a','a')", "regexp_like('a', 'a')");
        funcCheck("regexp_replace('a','a','b')", "regexp_replace('a', 'a', 'b')");
        funcCheck("'a' ~ 'a'", "'a' ~ 'a'");
        funcCheck("'a' ilike 'A'", "'a' ilike 'A'");
        funcCheck("md5('x')", "md5('x')");
        funcCheck("split_part('a,b',',',1)", "split_part('a,b', ',', 1)");
        funcCheck("count(*) filter (where a>0)", "count(*) filter (where a > 0)");
        funcCheck("percentile_cont(0.5) with group", "percentile_cont(0.5) WITHIN GROUP (ORDER BY a)");
        funcCheck("cast(a as string)", "cast(a as string)");
        funcCheck("a::string", "a::string");
        funcCheck("(a + interval 1 day)", "a + interval 1 day");
        funcCheck("cast('2026-01-01' as date) + interval '1' day",
                "cast('2026-01-01' as date) + interval '1' day");

        System.out.println();
        System.out.println("=== 8. 可疑 FUNC_OK 的语义确认 ===");
        value("decode(1,1,'a','b') 期望 a", "decode(1, 1, 'a', 'b')");
        value("decode(2,1,'a','b') 期望 b", "decode(2, 1, 'a', 'b')");
        value("decode(NULL,1,'a','b') 期望 b", "decode(CAST(NULL AS INT), 1, 'a', 'b')");
        value("date_format 替代 to_char", "date_format(cast('2026-01-01' as date), 'yyyy-MM-dd')");
        value("to_date 用 Java 格式", "to_date('2026-01-01', 'yyyy-MM-dd')");
        agg("string_agg 含 NULL(期望 1,2)", "string_agg(cast(a as string), ',')");
        agg("listagg 含 NULL", "listagg(cast(a as string), ',')");
        agg("collect_list 含 NULL", "size(collect_list(a))");
        value("nvl2(NULL,1,0) 期望 0", "nvl2(CAST(NULL AS INT), 1, 0)");

        System.out.println();
        System.out.println("=== 9. 日期格式 token 大小写陷阱 ===");
        String ts = "cast('2026-01-01 13:05:07' as timestamp)";
        value("HH24:MI:SS 直译（未翻译）", "date_format(" + ts + ", 'yyyy-MM-dd HH24:MI:SS')");
        value("HH:mm:ss 正确翻译", "date_format(" + ts + ", 'yyyy-MM-dd HH:mm:ss')");
        value("SS 未改成小写", "date_format(" + ts + ", 'yyyy-MM-dd HH:mm:SS')");
        value("MI 未改成小写", "date_format(" + ts + ", 'yyyy-MM-dd HH:MI')");
        value("YYYY-MM-DD 未翻译", "date_format(cast('2026-01-01' as date), 'YYYY-MM-DD')");

        System.out.println();
        System.out.println("=== 10. DDL 构造实测（对应真实语料中的写法） ===");
        exec("COMMENT ON TABLE", "COMMENT ON TABLE probe_t IS '注释'");
        exec("COMMENT ON COLUMN", "COMMENT ON COLUMN probe_t.a IS '注释'");
        exec("CREATE VIEW", "CREATE VIEW v_probe AS SELECT 1 AS x");
        exec("CREATE OR REPLACE VIEW", "CREATE OR REPLACE VIEW v_probe AS SELECT 1 AS x");
        exec("CREATE TABLE AS SELECT", "CREATE TABLE t_ctas AS SELECT a FROM nums");
        exec("CREATE TABLE LIKE", "CREATE TABLE t_like LIKE probe_t");
        exec("WITH ORIENTATION 带引号", "CREATE TABLE t_orient (a INT) USING parquet WITH (ORIENTATION = 'COLUMN')");
        exec("WITH ORIENTATION 不带引号", "CREATE TABLE t_orient2 (a INT) USING parquet WITH (ORIENTATION=COLUMN)");
        exec("DISTRIBUTE BY HASH", "CREATE TABLE t_dist (a INT) DISTRIBUTE BY HASH(a)");
        exec("PARTITION BY RANGE", "CREATE TABLE t_part (a INT) PARTITION BY RANGE (a)");
        exec("DEFAULT nextval", "CREATE TABLE t_def (a BIGINT DEFAULT nextval('s'))");
        exec("ALTER ADD COLUMN（openGauss 写法）", "ALTER TABLE probe_t ADD COLUMN c INT");
        exec("ALTER ADD COLUMNS（Spark 写法）", "ALTER TABLE probe_t ADD COLUMNS (d INT)");
        exec("TRUNCATE TABLE", "TRUNCATE TABLE probe_t");
        exec("INSERT INTO ... SELECT", "INSERT INTO probe_t SELECT a, cast(a as string) FROM nums");
        exec("ALTER COLUMN COMMENT（列注释的 Spark 写法）",
                "ALTER TABLE probe_t ALTER COLUMN b COMMENT '列注释'");
        exec("CREATE TABLE 无存储子句", "CREATE TABLE t_plain (a INT, b STRING)");

        spark.stop();
    }
}
