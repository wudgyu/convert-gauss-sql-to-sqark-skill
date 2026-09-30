# 静默语义漂移检查清单

审核节点逐项核对本清单。每一条判定的依据都是实测结论，
出处见同目录下的 `spark-4.2.0-baseline.md`。

静默漂移指转换结果能编译、能执行、不报错，但结果与源端不同。
这是本工作流最需要防的错误类型，也是审核节点存在的首要理由。

## 一、必须逐项核对的漂移项

| 编号 | 检查项 | 命中表现 | 严重级 | 处置 |
|---|---|---|---|---|
| drift.null-ordering | 排序的 NULL 位置 | 排序项没有显式 NULLS FIRST / NULLS LAST | error | 升序补 NULLS LAST，降序补 NULLS FIRST |
| drift.concat-null | concat 的 NULL 语义 | 出现 concat( 且参数可能为 NULL | error | 改写为 concat_ws('', ...)，并确认参数均为标量 |
| drift.format-token | 日期格式串 token | 格式串含大写 SS、MI、HH24 或未翻译的 YYYY / DD | error | 按 core.datetime.format-tokens 翻译，SS 必须小写 |
| drift.distribute-by | DISTRIBUTE BY 残留 | 结果中出现 DISTRIBUTE BY，任何上下文都算 | error | 移除。Spark 中同名关键字是重分区，会静默改变语义 |
| drift.numeric-precision | 无精度 numeric / decimal | 出现不带 (p,s) 的 DECIMAL 或 NUMERIC | error | 补显式精度，默认 DECIMAL(38,18)，需人工确认 |
| drift.array-agg-collect | array_agg 被改写 | array_agg 被替换为 collect_list 或 collect_set | error | 改回 array_agg。collect_list 会丢弃 NULL |
| drift.reduce-accuracy | 精度降级 | 出现 percentile_approx，或源端 double 被降为 float | error | 改回精确写法。Spark 4.2 原生支持 percentile_cont |
| drift.time-type | TIME 类型 | 结果中出现 TIME 类型，或 TIME 列被静默改成 STRING | error | TIME 不受支持，必须阻断或人工确认降级方案 |
| drift.storage-clause | 存储子句残留 | 出现 WITH (ORIENTATION= 或 TABLESPACE | error | 移除，并标注存储属性未迁移 |
| drift.ddl-comment | 列注释写法 | 出现 COMMENT ON COLUMN | error | 改用 ALTER TABLE ... ALTER COLUMN ... COMMENT |
| drift.nextval | 序列取值残留 | 出现 nextval( / currval( / setval( | error | 必须阻断。Spark 无序列 |
| drift.timestamp-tz | 时区语义 | TIMESTAMPTZ 被改成 TIMESTAMP 且无说明 | warning | 需人工确认时区处理方式 |
| drift.case-folding | 标识符大小写折叠 | 出现双引号标识符，或源端未加引号而结果加了引号 | warning | 确认大小写敏感性与列名解析 |
| drift.implicit-cast | 隐式类型转换 | 数值列与字符串列参与运算，或原有 CAST 被删除 | warning | 显式补 CAST，不要依赖隐式转换 |
| drift.interval-arithmetic | 间隔运算 | interval '1' day 等写法被改写或简化 | warning | 确认日期加减结果与源端一致 |
| drift.grouping-null | 分组与排序的 NULL 归类 | 涉及分组键或排序列的 NULL 行为变化 | warning | 对照源端默认行为逐项核对 |

## 二、残留方言扫描

对每条转换结果做关键词扫描，命中即产出 finding：

```
openGauss 类型名  int2 int4 int8 float4 float8 bpchar bytea text uuid
                  jsonb timestamptz money，以及无精度的 numeric
函数              to_char(  strpos(  sha256(  gen_random_uuid(  sysdate
                  pg_  current_setting(  version(
运算符            ~  !~  ~*
DDL               DISTRIBUTE BY  WITH (ORIENTATION=  TABLESPACE
                  PARTITION BY RANGE / LIST / HASH  COMMENT ON COLUMN
                  CREATE INDEX  CREATE SEQUENCE
过程式            $$  DECLARE  LANGUAGE plpgsql
```

两个例外，命中不等于错误：

- text 出现在字符串字面量或注释里，不是错误。
- position(x in y)、substring(x from a for b)、string_agg、listagg、array_agg、decode、
  nvl、nvl2、ilike 都是 Spark 原生可用写法，不得判为残留。

## 三、审核的动作要求

1. 独立复核：先只读源语句与转换结果，自己推导一遍语义，再去看转换者的理由。
   不得以转换者的说明作为判断依据。
2. 对每条 source 为 llm 的记录，必须逐项核对上述清单。
3. 每条 finding 必须给出可直接执行的修改动作，不接受「建议优化」这类模糊结论。
4. 无法判定时给 warning，并提出具体的人工确认问题，不得默认放过。
