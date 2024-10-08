/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * imitations under the License.
 */

package org.apache.flink.table.planner.plan.batch.sql;

import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.config.AggregatePhaseStrategy;
import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.flink.table.api.config.OptimizerConfigOptions;
import org.apache.flink.table.planner.utils.BatchTableTestUtil;
import org.apache.flink.table.planner.utils.TableTestBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for ForwardHashExchangeProcessor. */
class ForwardHashExchangeTest extends TableTestBase {

    private BatchTableTestUtil util;

    @BeforeEach
    void before() {
        util = batchTestUtil(TableConfig.getDefault());
        util.tableEnv()
                .executeSql(
                        "CREATE TABLE T (\n"
                                + "  a BIGINT,\n"
                                + "  b BIGINT,\n"
                                + "  c VARCHAR,\n"
                                + "  d BIGINT\n"
                                + ") WITH (\n"
                                + " 'connector' = 'values',\n"
                                + " 'bounded' = 'true',\n"
                                + " 'enable-aggregate-push-down' = 'false'\n"
                                + ")");
        util.tableEnv()
                .executeSql(
                        "CREATE TABLE T1 (\n"
                                + "  a1 BIGINT,\n"
                                + "  b1 BIGINT,\n"
                                + "  c1 VARCHAR,\n"
                                + "  d1 BIGINT\n"
                                + ") WITH (\n"
                                + " 'connector' = 'values',\n"
                                + " 'bounded' = 'true',\n"
                                + " 'enable-aggregate-push-down' = 'false'\n"
                                + ")");
        util.tableEnv()
                .executeSql(
                        "CREATE TABLE T2 (\n"
                                + "  a2 BIGINT,\n"
                                + "  b2 BIGINT,\n"
                                + "  c2 VARCHAR,\n"
                                + "  d2 BIGINT\n"
                                + ") WITH (\n"
                                + " 'connector' = 'values',\n"
                                + " 'bounded' = 'true',\n"
                                + " 'enable-aggregate-push-down' = 'false'\n"
                                + ")");
    }

    @Test
    void testRankWithHashShuffle() {
        util.verifyExecPlan(
                "SELECT * FROM (SELECT a, b, RANK() OVER(PARTITION BY a ORDER BY b) rk FROM T) WHERE rk <= 10");
    }

    @Test
    void testSortAggregateWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "HashAgg");
        util.verifyExecPlan(" SELECT a, SUM(b) AS b FROM T GROUP BY a");
    }

    @Test
    void testOverAggOnHashAggWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "SortAgg");
        util.verifyExecPlan(
                " SELECT\n"
                        + "   SUM(b) sum_b,\n"
                        + "   AVG(SUM(b)) OVER (PARTITION BY c) avg_b,\n"
                        + "   RANK() OVER (PARTITION BY c ORDER BY c) rn,\n"
                        + "   c\n"
                        + " FROM T\n"
                        + " GROUP BY c");
    }

    @Test
    void testOverAggOnHashAggWithGlobalShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "SortAgg");
        util.verifyExecPlan("SELECT b, RANK() OVER (ORDER BY b) FROM (SELECT SUM(b) AS b FROM T)");
    }

    @Test
    void testOverAggOnSortAggWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "HashAgg");
        util.verifyExecPlan(
                " SELECT\n"
                        + "   SUM(b) sum_b,\n"
                        + "   AVG(SUM(b)) OVER (PARTITION BY c) avg_b,\n"
                        + "   RANK() OVER (PARTITION BY c ORDER BY c) rn,\n"
                        + "   c\n"
                        + " FROM T\n"
                        + " GROUP BY c");
    }

    @Test
    void testOverAggOnSortAggWithGlobalShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "HashAgg");
        util.verifyExecPlan("SELECT b, RANK() OVER (ORDER BY b) FROM (SELECT SUM(b) AS b FROM T)");
    }

    @Test
    void testHashAggOnHashJoinWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(
                        ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS,
                        "SortMergeJoin,NestedLoopJoin,SortAgg");
        util.verifyExecPlan(
                "WITH r AS (SELECT * FROM T1, T2 WHERE a1 = a2 AND c1 LIKE 'He%')\n"
                        + "SELECT sum(b1) FROM r group by a1");
    }

    @Test
    void testOnePhaseSortAggOnSortMergeJoinWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(
                        ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS,
                        "HashJoin,NestedLoopJoin,HashAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("ONE_PHASE"));
        util.verifyExecPlan(
                "WITH r AS (SELECT * FROM T1, T2 WHERE a1 = a2 AND c1 LIKE 'He%')\n"
                        + "SELECT sum(b1) FROM r group by a1");
    }

    @Test
    void testTwoPhaseSortAggOnSortMergeJoinWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(
                        ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS,
                        "HashJoin,NestedLoopJoin,HashAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("TWO_PHASE"));
        util.verifyExecPlan(
                "WITH r AS (SELECT * FROM T1, T2 WHERE a1 = a2 AND c1 LIKE 'He%')\n"
                        + "SELECT sum(b1) FROM r group by a1");
    }

    @Test
    void testAutoPhaseSortAggOnSortMergeJoinWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(
                        ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS,
                        "HashJoin,NestedLoopJoin,HashAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("AUTO"));
        util.verifyExecPlan(
                "WITH r AS (SELECT * FROM T1, T2 WHERE a1 = a2 AND c1 LIKE 'He%')\n"
                        + "SELECT sum(b1) FROM r group by a1");
    }

    @Test
    void testHashAggOnNestedLoopJoinWithGlobalShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "SortAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("ONE_PHASE"));
        // TODO the shuffle between join and agg can be removed
        util.verifyExecPlan(
                "WITH r AS (SELECT * FROM T1 FULL OUTER JOIN T2 ON true)\n"
                        + "SELECT sum(b1) FROM r");
    }

    @Test
    void testSortAggOnNestedLoopJoinWithGlobalShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "HashAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("ONE_PHASE"));
        // TODO the shuffle between join and agg can be removed
        util.verifyExecPlan(
                "WITH r AS (SELECT * FROM T1 FULL OUTER JOIN T2 ON true)\n"
                        + "SELECT sum(b1) FROM r");
    }

    @Test
    void testRankOnHashAggWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "SortAgg");
        util.verifyExecPlan(
                "SELECT * FROM (\n"
                        + "                SELECT a, b, RANK() OVER(PARTITION BY a ORDER BY b) rk FROM (\n"
                        + "                        SELECT a, SUM(b) AS b FROM T GROUP BY a\n"
                        + "                )\n"
                        + "        ) WHERE rk <= 10");
    }

    @Test
    void testRankOnHashAggWithGlobalShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "SortAgg");
        util.verifyExecPlan(
                "SELECT * FROM (\n"
                        + "                SELECT b, RANK() OVER(ORDER BY b) rk FROM (\n"
                        + "                        SELECT SUM(b) AS b FROM T\n"
                        + "                )\n"
                        + "        ) WHERE rk <= 10");
    }

    @Test
    void testRankOnOnePhaseSortAggWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "HashAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("ONE_PHASE"));
        util.verifyExecPlan(
                "SELECT * FROM (\n"
                        + "                SELECT a, b, RANK() OVER(PARTITION BY a ORDER BY b) rk FROM (\n"
                        + "                        SELECT a, SUM(b) AS b FROM T GROUP BY a\n"
                        + "                )\n"
                        + "        ) WHERE rk <= 10");
    }

    @Test
    void testRankOnTwoPhaseSortAggWithHashShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "HashAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("TWO_PHASE"));
        util.verifyExecPlan(
                "SELECT * FROM (\n"
                        + "                SELECT a, b, RANK() OVER(PARTITION BY a ORDER BY b) rk FROM (\n"
                        + "                        SELECT a, SUM(b) AS b FROM T GROUP BY a\n"
                        + "                )\n"
                        + "        ) WHERE rk <= 10");
    }

    @Test
    void testRankOnOnePhaseSortAggWithGlobalShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "HashAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("ONE_PHASE"));
        util.verifyExecPlan(
                "SELECT * FROM (\n"
                        + "                SELECT b, RANK() OVER(ORDER BY b) rk FROM (\n"
                        + "                        SELECT SUM(b) AS b FROM T\n"
                        + "                )\n"
                        + "        ) WHERE rk <= 10");
    }

    @Test
    void testRankOnTwoPhaseSortAggWithGlobalShuffle() {
        util.tableEnv()
                .getConfig()
                .set(ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS, "HashAgg");
        util.tableEnv()
                .getConfig()
                .set(
                        OptimizerConfigOptions.TABLE_OPTIMIZER_AGG_PHASE_STRATEGY,
                        AggregatePhaseStrategy.valueOf("TWO_PHASE"));
        util.verifyExecPlan(
                "SELECT * FROM (\n"
                        + "                SELECT b, RANK() OVER(ORDER BY b) rk FROM (\n"
                        + "                        SELECT SUM(b) AS b FROM T\n"
                        + "                )\n"
                        + "        ) WHERE rk <= 10");
    }

    @Test
    void testHashJoinWithMultipleInputDisabled() {
        util.tableEnv()
                .getConfig()
                .set(
                        ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS,
                        "SortMergeJoin,NestedLoopJoin")
                .set(OptimizerConfigOptions.TABLE_OPTIMIZER_MULTIPLE_INPUT_ENABLED, false);
        util.verifyExecPlan(
                "SELECT * FROM\n"
                        + "  (SELECT a FROM T1 JOIN T ON a = a1) t1\n"
                        + "  INNER JOIN\n"
                        + "  (SELECT d2 FROM T JOIN T2 ON d2 = a) t2\n"
                        + "  ON t1.a = t2.d2");
    }

    @Test
    void testSortJoinWithMultipleInputDisabled() {
        util.tableEnv()
                .getConfig()
                .set(
                        ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS,
                        "HashJoin,NestedLoopJoin")
                .set(OptimizerConfigOptions.TABLE_OPTIMIZER_MULTIPLE_INPUT_ENABLED, false);
        util.verifyExecPlan(
                "SELECT * FROM\n"
                        + "  (SELECT a FROM T1 JOIN T ON a = a1) t1\n"
                        + "  INNER JOIN\n"
                        + "  (SELECT d2 FROM T JOIN T2 ON d2 = a) t2\n"
                        + "  ON t1.a = t2.d2");
    }

    @Test
    void testMultipleInputs() {
        util.getTableEnv()
                .getConfig()
                .set(OptimizerConfigOptions.TABLE_OPTIMIZER_JOIN_REORDER_ENABLED, false)
                .set(
                        ExecutionConfigOptions.TABLE_EXEC_DISABLED_OPERATORS,
                        "NestedLoopJoin,SortMergeJoin,SortAgg");

        util.tableEnv()
                .executeSql(
                        "CREATE TABLE x (\n"
                                + "  a INT,\n"
                                + "  b BIGINT,\n"
                                + "  c VARCHAR,\n"
                                + "  nx INT\n"
                                + ") WITH (\n"
                                + " 'connector' = 'values',\n"
                                + " 'bounded' = 'true'\n"
                                + ")");
        util.tableEnv()
                .executeSql(
                        "CREATE TABLE y (\n"
                                + "  d INT,\n"
                                + "  e BIGINT,\n"
                                + "  f VARCHAR,\n"
                                + "  ny INT\n"
                                + ") WITH (\n"
                                + " 'connector' = 'values',\n"
                                + " 'bounded' = 'true'\n"
                                + ")");
        util.tableEnv()
                .executeSql(
                        "CREATE TABLE z (\n"
                                + "  g INT,\n"
                                + "  h BIGINT,\n"
                                + "  i VARCHAR,\n"
                                + "  nz INT\n"
                                + ") WITH (\n"
                                + " 'connector' = 'values',\n"
                                + " 'bounded' = 'true'\n"
                                + ")");

        util.tableEnv()
                .executeSql(
                        "CREATE TABLE t (\n"
                                + "  a INT,\n"
                                + "  b BIGINT,\n"
                                + "  c VARCHAR\n"
                                + ") WITH (\n"
                                + " 'connector' = 'values',\n"
                                + " 'bounded' = 'true'\n"
                                + ")");

        util.verifyExecPlan(
                "WITH\n"
                        + "  v1 AS (\n"
                        + "    SELECT a, ny, nz FROM x\n"
                        + "      LEFT JOIN y ON x.a = y.ny\n"
                        + "      LEFT JOIN z ON x.a = z.nz),\n"
                        + "  v2 AS (\n"
                        + "    SELECT v1.a AS a, t.b AS b, d, v1.ny AS ny, nz FROM v1\n"
                        + "      LEFT JOIN t ON v1.a = t.a\n"
                        + "      INNER JOIN y ON v1.a = y.d),\n"
                        + "  v3 AS (\n"
                        + "    SELECT v1.a AS a, t.b AS b, d, v1.ny AS ny, nz FROM v1\n"
                        + "      LEFT JOIN y ON v1.a = y.d\n"
                        + "      INNER JOIN t ON v1.a = t.a),\n"
                        + "  v4 AS (SELECT b, SUM(d) AS sd, SUM(ny) AS sy, SUM(nz) AS sz FROM v2 GROUP BY b),\n"
                        + "  v5 AS (SELECT b, SUM(d) AS sd, SUM(ny) AS sy, SUM(nz) AS sz FROM v3 GROUP BY b)\n"
                        + "SELECT * FROM\n"
                        + "  (SELECT t.b, sd, sy, sz FROM v4 LEFT JOIN t ON v4.b = t.b)\n"
                        + "  UNION ALL\n"
                        + "  (SELECT y.e, sd, sy, sz FROM v5 LEFT JOIN y ON v5.b = y.e)");
    }
}
