/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark.extensions;

import static org.apache.iceberg.TableProperties.MERGE_ISOLATION_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.ParameterizedTestExtension;
import org.apache.iceberg.RowLevelOperationMode;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.util.concurrent.MoreExecutors;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.iceberg.spark.SparkSQLProperties;
import org.apache.iceberg.util.SnapshotUtil;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.internal.SQLConf;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ParameterizedTestExtension.class)
public class TestCopyOnWriteMerge extends TestMerge {

  @Override
  protected Map<String, String> extraTableProperties() {
    return ImmutableMap.of(
        TableProperties.MERGE_MODE, RowLevelOperationMode.COPY_ON_WRITE.modeName());
  }

  @TestTemplate
  public synchronized void testMergeWithConcurrentTableRefresh() throws Exception {
    // this test can only be run with Hive tables as it requires a reliable lock
    // also, the table cache must be enabled so that the same table instance can be reused
    assumeThat(catalogName).isEqualToIgnoringCase("testhive");

    createAndInitTable("id INT, dep STRING");
    createOrReplaceView("source", Collections.singletonList(1), Encoders.INT());

    sql(
        "ALTER TABLE %s SET TBLPROPERTIES('%s' '%s')",
        tableName, MERGE_ISOLATION_LEVEL, "snapshot");

    sql("INSERT INTO TABLE %s VALUES (1, 'hr')", tableName);
    createBranchIfNeeded();

    Table table = Spark3Util.loadIcebergTable(spark, tableName);

    ExecutorService executorService =
        MoreExecutors.getExitingExecutorService(
            (ThreadPoolExecutor) Executors.newFixedThreadPool(2));

    AtomicInteger barrier = new AtomicInteger(0);
    AtomicBoolean shouldAppend = new AtomicBoolean(true);

    // merge thread
    Future<?> mergeFuture =
        executorService.submit(
            () -> {
              for (int numOperations = 0; numOperations < Integer.MAX_VALUE; numOperations++) {
                int currentNumOperations = numOperations;
                Awaitility.await()
                    .pollInterval(10, TimeUnit.MILLISECONDS)
                    .atMost(5, TimeUnit.SECONDS)
                    .until(() -> barrier.get() >= currentNumOperations * 2);

                sql(
                    "MERGE INTO %s t USING source s "
                        + "ON t.id == s.value "
                        + "WHEN MATCHED THEN "
                        + "  UPDATE SET dep = 'x'",
                    tableName);

                barrier.incrementAndGet();
              }
            });

    // append thread
    Future<?> appendFuture =
        executorService.submit(
            () -> {
              GenericRecord record = GenericRecord.create(table.schema());
              record.set(0, 1); // id
              record.set(1, "hr"); // dep

              for (int numOperations = 0; numOperations < Integer.MAX_VALUE; numOperations++) {
                int currentNumOperations = numOperations;
                Awaitility.await()
                    .pollInterval(10, TimeUnit.MILLISECONDS)
                    .atMost(5, TimeUnit.SECONDS)
                    .until(() -> !shouldAppend.get() || barrier.get() >= currentNumOperations * 2);

                if (!shouldAppend.get()) {
                  return;
                }

                for (int numAppends = 0; numAppends < 5; numAppends++) {
                  DataFile dataFile = writeDataFile(table, ImmutableList.of(record));
                  table.newFastAppend().appendFile(dataFile).commit();
                }

                barrier.incrementAndGet();
              }
            });

    try {
      assertThatThrownBy(mergeFuture::get)
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("the table has been concurrently modified");
    } finally {
      shouldAppend.set(false);
      appendFuture.cancel(true);
    }

    executorService.shutdown();
    assertThat(executorService.awaitTermination(2, TimeUnit.MINUTES)).as("Timeout").isTrue();
  }

  @TestTemplate
  public void testRuntimeFilteringWithReportedPartitioning() {
    createAndInitTable("id INT, dep STRING");
    sql("ALTER TABLE %s ADD PARTITION FIELD dep", tableName);

    append(tableName, "{ \"id\": 1, \"dep\": \"hr\" }\n" + "{ \"id\": 3, \"dep\": \"hr\" }");
    createBranchIfNeeded();
    append(
        commitTarget(),
        "{ \"id\": 1, \"dep\": \"hardware\" }\n" + "{ \"id\": 2, \"dep\": \"hardware\" }");

    createOrReplaceView("source", Collections.singletonList(2), Encoders.INT());

    Map<String, String> sqlConf =
        ImmutableMap.of(
            SQLConf.V2_BUCKETING_ENABLED().key(),
            "true",
            SparkSQLProperties.PRESERVE_DATA_GROUPING,
            "true");

    withSQLConf(
        sqlConf,
        () ->
            sql(
                "MERGE INTO %s t USING source s "
                    + "ON t.id == s.value "
                    + "WHEN MATCHED THEN "
                    + "  UPDATE SET id = -1",
                commitTarget()));

    Table table = validationCatalog.loadTable(tableIdent);
    assertThat(table.snapshots()).as("Should have 3 snapshots").hasSize(3);

    Snapshot currentSnapshot = SnapshotUtil.latestSnapshot(table, branch);
    validateCopyOnWrite(currentSnapshot, "1", "1", "1");

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(-1, "hardware"), row(1, "hardware"), row(1, "hr"), row(3, "hr")),
        sql("SELECT * FROM %s ORDER BY id, dep", selectTarget()));
  }
}
