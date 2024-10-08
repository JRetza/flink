/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.operators.window.groupwindow.operator;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.utils.JoinedRowData;
import org.apache.flink.table.runtime.dataview.StateDataViewStore;
import org.apache.flink.table.runtime.generated.GeneratedNamespaceTableAggsHandleFunction;
import org.apache.flink.table.runtime.generated.NamespaceAggsHandleFunction;
import org.apache.flink.table.runtime.generated.NamespaceAggsHandleFunctionBase;
import org.apache.flink.table.runtime.generated.NamespaceTableAggsHandleFunction;
import org.apache.flink.table.runtime.generated.RecordEqualiser;
import org.apache.flink.table.runtime.keyselector.RowDataKeySelector;
import org.apache.flink.table.runtime.operators.window.CountWindow;
import org.apache.flink.table.runtime.operators.window.TimeWindow;
import org.apache.flink.table.runtime.operators.window.Window;
import org.apache.flink.table.runtime.operators.window.groupwindow.assigners.GroupWindowAssigner;
import org.apache.flink.table.runtime.operators.window.groupwindow.assigners.SessionWindowAssigner;
import org.apache.flink.table.runtime.operators.window.groupwindow.assigners.TumblingWindowAssigner;
import org.apache.flink.table.runtime.operators.window.groupwindow.triggers.ElementTriggers;
import org.apache.flink.table.runtime.operators.window.groupwindow.triggers.EventTimeTriggers;
import org.apache.flink.table.runtime.operators.window.groupwindow.triggers.ProcessingTimeTriggers;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.runtime.util.GenericRowRecordSortComparator;
import org.apache.flink.table.runtime.util.RowDataHarnessAssertor;
import org.apache.flink.table.runtime.util.RowDataTestUtil;
import org.apache.flink.table.types.logical.BigIntType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.VarCharType;
import org.apache.flink.table.utils.HandwrittenSelectorUtil;
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension;
import org.apache.flink.testutils.junit.extensions.parameterized.Parameters;
import org.apache.flink.util.Collector;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.flink.table.data.StringData.fromString;
import static org.apache.flink.table.runtime.util.StreamRecordUtils.insertRecord;
import static org.apache.flink.table.runtime.util.StreamRecordUtils.updateAfterRecord;
import static org.apache.flink.table.runtime.util.StreamRecordUtils.updateBeforeRecord;
import static org.apache.flink.table.runtime.util.TimeWindowUtil.toUtcTimestampMills;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@link WindowOperator} tests for {@link AggregateWindowOperator} or {@link
 * TableAggregateWindowOperator}.
 *
 * <p>To simplify the testing logic, the table aggregate outputs same value with the aggregate
 * except that the table aggregate outputs two same records each time.
 */
@ExtendWith(ParameterizedTestExtension.class)
class WindowOperatorTest {

    private static final ZoneId UTC_ZONE_ID = ZoneId.of("UTC");
    private static final ZoneId SHANGHAI_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private final boolean isTableAggregate;
    private final ZoneId shiftTimeZone;

    @Parameters(name = "isTableAggregate = {0}, TimeZone = {1}")
    private static Collection<Object[]> runMode() {
        return Arrays.asList(
                new Object[] {false, UTC_ZONE_ID},
                new Object[] {true, UTC_ZONE_ID},
                new Object[] {false, SHANGHAI_ZONE_ID},
                new Object[] {true, SHANGHAI_ZONE_ID});
    }

    public WindowOperatorTest(boolean isTableAggregate, ZoneId shiftTimeZone) {
        this.isTableAggregate = isTableAggregate;
        this.shiftTimeZone = shiftTimeZone;
    }

    private static final SumAndCountAggTimeWindow sumAndCountAggTimeWindow =
            new SumAndCountAggTimeWindow();
    private static final SumAndCountTableAggTimeWindow sumAndCountTableAggTimeWindow =
            new SumAndCountTableAggTimeWindow();
    private static final SumAndCountAggCountWindow sumAndCountAggCountWindow =
            new SumAndCountAggCountWindow();

    private static final SumAndCountTableAggCountWindow sumAndCountTableAggCountWindow =
            new SumAndCountTableAggCountWindow();

    private NamespaceAggsHandleFunctionBase getTimeWindowAggFunction() {
        return isTableAggregate ? sumAndCountTableAggTimeWindow : sumAndCountAggTimeWindow;
    }

    private NamespaceAggsHandleFunctionBase getCountWindowAggFunction() {
        return isTableAggregate ? sumAndCountTableAggCountWindow : sumAndCountAggCountWindow;
    }

    // For counting if close() is called the correct number of times on the SumReducer
    private static AtomicInteger closeCalled = new AtomicInteger(0);

    private LogicalType[] inputFieldTypes =
            new LogicalType[] {VarCharType.STRING_TYPE, new IntType(), new BigIntType()};

    private InternalTypeInfo<RowData> outputType =
            InternalTypeInfo.ofFields(
                    VarCharType.STRING_TYPE,
                    new BigIntType(),
                    new BigIntType(),
                    new BigIntType(),
                    new BigIntType(),
                    new BigIntType());

    private LogicalType[] aggResultTypes = new LogicalType[] {new BigIntType(), new BigIntType()};
    private LogicalType[] accTypes = new LogicalType[] {new BigIntType(), new BigIntType()};
    private LogicalType[] windowTypes =
            new LogicalType[] {new BigIntType(), new BigIntType(), new BigIntType()};
    private GenericRowEqualiser equaliser = new GenericRowEqualiser(accTypes, windowTypes);
    private RowDataKeySelector keySelector =
            HandwrittenSelectorUtil.getRowDataSelector(new int[] {0}, inputFieldTypes);
    private TypeInformation<RowData> keyType = keySelector.getProducedType();
    private RowDataHarnessAssertor assertor =
            new RowDataHarnessAssertor(
                    outputType.toRowFieldTypes(),
                    new GenericRowRecordSortComparator(0, VarCharType.STRING_TYPE));

    private ConcurrentLinkedQueue<Object> doubleRecord(
            boolean isDouble, StreamRecord<RowData> record) {
        ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();
        results.add(record);
        if (isDouble) {
            results.add(record);
        }
        return results;
    }

    @TestTemplate
    void testEventTimeSlidingWindows() throws Exception {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .sliding(Duration.ofSeconds(3), Duration.ofSeconds(1))
                        .withEventTime(2)
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        testHarness.open();

        // process elements
        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        // add elements out-of-order
        testHarness.processElement(insertRecord("key2", 1, 3999L));
        testHarness.processElement(insertRecord("key2", 1, 3000L));

        testHarness.processElement(insertRecord("key1", 1, 20L));
        testHarness.processElement(insertRecord("key1", 1, 0L));
        testHarness.processElement(insertRecord("key1", 1, 999L));

        testHarness.processElement(insertRecord("key2", 1, 1998L));
        testHarness.processElement(insertRecord("key2", 1, 1999L));
        testHarness.processElement(insertRecord("key2", 1, 1000L));

        testHarness.processWatermark(new Watermark(999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(-2000L),
                                localMills(1000L),
                                localMills(999L))));
        expectedOutput.add(new Watermark(999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(1999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(-1000L),
                                localMills(2000L),
                                localMills(1999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(-1000L),
                                localMills(2000L),
                                localMills(1999L))));
        expectedOutput.add(new Watermark(1999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(2999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.add(new Watermark(2999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshot = testHarness.snapshot(0L, 0);
        testHarness.close();
        expectedOutput.clear();

        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshot);
        testHarness.open();

        testHarness.processWatermark(new Watermark(3999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                5L,
                                5L,
                                localMills(1000L),
                                localMills(4000L),
                                localMills(3999L))));
        expectedOutput.add(new Watermark(3999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(4999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                2L,
                                2L,
                                localMills(2000L),
                                localMills(5000L),
                                localMills(4999L))));
        expectedOutput.add(new Watermark(4999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(5999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                2L,
                                2L,
                                localMills(3000L),
                                localMills(6000L),
                                localMills(5999L))));
        expectedOutput.add(new Watermark(5999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // those don't have any effect...
        testHarness.processWatermark(new Watermark(6999));
        testHarness.processWatermark(new Watermark(7999));
        expectedOutput.add(new Watermark(6999));
        expectedOutput.add(new Watermark(7999));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
    }

    @TestTemplate
    void testProcessingTimeSlidingWindows() throws Throwable {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withShiftTimezone(shiftTimeZone)
                        .withInputFields(inputFieldTypes)
                        .sliding(Duration.ofSeconds(3), Duration.ofSeconds(1))
                        .withProcessingTime()
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        // timestamp is ignored in processing time
        testHarness.setProcessingTime(3);
        testHarness.processElement(insertRecord("key2", 1, Long.MAX_VALUE));

        testHarness.setProcessingTime(1000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                1L,
                                1L,
                                localMills(-2000L),
                                localMills(1000L),
                                localMills(999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key2", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key2", 1, Long.MAX_VALUE));

        testHarness.setProcessingTime(2000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(-1000L),
                                localMills(2000L),
                                localMills(1999L))));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));

        testHarness.setProcessingTime(3000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                2L,
                                2L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));

        testHarness.setProcessingTime(7000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                2L,
                                2L,
                                localMills(1000L),
                                localMills(4000L),
                                localMills(3999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                5L,
                                5L,
                                localMills(1000L),
                                localMills(4000L),
                                localMills(3999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                5L,
                                5L,
                                localMills(2000L),
                                localMills(5000L),
                                localMills(4999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(3000L),
                                localMills(6000L),
                                localMills(5999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();
    }

    @TestTemplate
    void testEventTimeCumulativeWindows() throws Exception {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .cumulative(Duration.ofSeconds(3), Duration.ofSeconds(1))
                        .withEventTime(2)
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        testHarness.open();

        // process elements
        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        // add elements out-of-order
        testHarness.processElement(insertRecord("key2", 1, 2999L));
        testHarness.processElement(insertRecord("key2", 1, 3000L));

        testHarness.processElement(insertRecord("key1", 1, 20L));
        testHarness.processElement(insertRecord("key1", 1, 0L));
        testHarness.processElement(insertRecord("key1", 1, 999L));

        testHarness.processElement(insertRecord("key2", 1, 1998L));
        testHarness.processElement(insertRecord("key2", 1, 1999L));
        testHarness.processElement(insertRecord("key2", 1, 1000L));

        testHarness.processWatermark(new Watermark(999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(1000L),
                                localMills(999L))));
        expectedOutput.add(new Watermark(999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(1999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(2000L),
                                localMills(1999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(2000L),
                                localMills(1999L))));
        expectedOutput.add(new Watermark(1999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(2999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                4L,
                                4L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.add(new Watermark(2999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshot = testHarness.snapshot(0L, 0);
        testHarness.close();
        expectedOutput.clear();

        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshot);
        testHarness.open();

        testHarness.processWatermark(new Watermark(3999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                1L,
                                1L,
                                localMills(3000L),
                                localMills(4000L),
                                localMills(3999L))));
        expectedOutput.add(new Watermark(3999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(4999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                1L,
                                1L,
                                localMills(3000L),
                                localMills(5000L),
                                localMills(4999L))));
        expectedOutput.add(new Watermark(4999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(5999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                1L,
                                1L,
                                localMills(3000L),
                                localMills(6000L),
                                localMills(5999L))));
        expectedOutput.add(new Watermark(5999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // those don't have any effect...
        testHarness.processWatermark(new Watermark(6999));
        testHarness.processWatermark(new Watermark(7999));
        expectedOutput.add(new Watermark(6999));
        expectedOutput.add(new Watermark(7999));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
    }

    @TestTemplate
    void testEventTimeCumulativeWindowsWithLateArrival() throws Exception {
        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .cumulative(Duration.ofSeconds(3), Duration.ofSeconds(1))
                        .withEventTime(2)
                        .withAllowedLateness(Duration.ofMillis(500))
                        .produceUpdates()
                        .aggregateAndBuild(
                                new SumAndCountAggTimeWindow(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        testHarness.processElement(insertRecord("key2", 1, 500L));
        testHarness.processWatermark(new Watermark(1500));

        expectedOutput.add(
                insertRecord("key2", 1L, 1L, localMills(0L), localMills(1000L), localMills(999L)));
        expectedOutput.add(new Watermark(1500));

        testHarness.processElement(insertRecord("key2", 1, 1300L));
        testHarness.processWatermark(new Watermark(2300));

        expectedOutput.add(
                insertRecord("key2", 2L, 2L, localMills(0L), localMills(2000L), localMills(1999L)));
        expectedOutput.add(new Watermark(2300));

        // this will not be dropped because window.maxTimestamp() + allowedLateness >
        // currentWatermark
        testHarness.processElement(insertRecord("key2", 1, 1997L));
        testHarness.processWatermark(new Watermark(6000));

        // this is 1 and not 3 because the trigger fires and purges
        expectedOutput.add(
                updateBeforeRecord(
                        "key2", 2L, 2L, localMills(0L), localMills(2000L), localMills(1999L)));
        expectedOutput.add(
                updateAfterRecord(
                        "key2", 3L, 3L, localMills(0L), localMills(2000L), localMills(1999L)));
        expectedOutput.add(
                insertRecord("key2", 3L, 3L, localMills(0L), localMills(3000L), localMills(2999L)));
        expectedOutput.add(new Watermark(6000));

        // this will be dropped because window.maxTimestamp() + allowedLateness < currentWatermark
        testHarness.processElement(insertRecord("key2", 1, 1998L));
        testHarness.processWatermark(new Watermark(7000));

        expectedOutput.add(new Watermark(7000));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        assertThat(operator.getNumLateRecordsDropped().getCount()).isEqualTo(1);

        testHarness.close();
    }

    @TestTemplate
    void testProcessingTimeCumulativeWindows() throws Throwable {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .cumulative(Duration.ofSeconds(3), Duration.ofSeconds(1))
                        .withProcessingTime()
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        // timestamp is ignored in processing time
        testHarness.setProcessingTime(3);
        testHarness.processElement(insertRecord("key2", 1, Long.MAX_VALUE));

        testHarness.setProcessingTime(1000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                1L,
                                1L,
                                localMills(0L),
                                localMills(1000L),
                                localMills(999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key2", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key2", 1, Long.MAX_VALUE));

        testHarness.setProcessingTime(2000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(2000L),
                                localMills(1999L))));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));

        testHarness.setProcessingTime(3000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                2L,
                                2L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key2", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key1", 1, Long.MAX_VALUE));

        testHarness.setProcessingTime(7000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                2L,
                                2L,
                                localMills(3000L),
                                localMills(4000L),
                                localMills(3999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                1L,
                                1L,
                                localMills(3000L),
                                localMills(4000L),
                                localMills(3999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                2L,
                                2L,
                                localMills(3000L),
                                localMills(5000L),
                                localMills(4999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                1L,
                                1L,
                                localMills(3000L),
                                localMills(5000L),
                                localMills(4999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                2L,
                                2L,
                                localMills(3000L),
                                localMills(6000L),
                                localMills(5999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                1L,
                                1L,
                                localMills(3000L),
                                localMills(6000L),
                                localMills(5999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();
    }

    @TestTemplate
    @SuppressWarnings("unchecked")
    void testEventTimeTumblingWindows() throws Exception {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .tumble(Duration.ofSeconds(3))
                        .withEventTime(2)
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        // add elements out-of-order
        testHarness.processElement(insertRecord("key2", 1, 3999L));
        testHarness.processElement(insertRecord("key2", 1, 3000L));

        testHarness.processElement(insertRecord("key1", 1, 20L));
        testHarness.processElement(insertRecord("key1", 1, 0L));
        testHarness.processElement(insertRecord("key1", 1, 999L));

        testHarness.processElement(insertRecord("key2", 1, 1998L));
        testHarness.processElement(insertRecord("key2", 1, 1999L));
        testHarness.processElement(insertRecord("key2", 1, 1000L));

        testHarness.processWatermark(new Watermark(999));
        expectedOutput.add(new Watermark(999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(1999));
        expectedOutput.add(new Watermark(1999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshot = testHarness.snapshot(0L, 0);
        testHarness.close();
        expectedOutput.clear();

        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshot);
        testHarness.open();

        testHarness.processWatermark(new Watermark(2999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.add(new Watermark(2999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(3999));
        expectedOutput.add(new Watermark(3999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(4999));
        expectedOutput.add(new Watermark(4999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(5999));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                2L,
                                2L,
                                localMills(3000L),
                                localMills(6000L),
                                localMills(5999L))));
        expectedOutput.add(new Watermark(5999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // those don't have any effect...
        testHarness.processWatermark(new Watermark(6999));
        testHarness.processWatermark(new Watermark(7999));
        expectedOutput.add(new Watermark(6999));
        expectedOutput.add(new Watermark(7999));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
    }

    @TestTemplate
    @SuppressWarnings("unchecked")
    void testEventTimeTumblingWindowsWithEarlyFiring() throws Exception {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .tumble(Duration.ofSeconds(3))
                        .withShiftTimezone(shiftTimeZone)
                        .withEventTime(2)
                        .triggering(
                                EventTimeTriggers.afterEndOfWindow()
                                        .withEarlyFirings(
                                                ProcessingTimeTriggers.every(
                                                        Duration.ofSeconds(1))))
                        .produceUpdates()
                        .aggregate(
                                new SumAndCountAggTimeWindow(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes)
                        .build();

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();
        testHarness.setProcessingTime(0L);

        // add elements out-of-order
        testHarness.processElement(insertRecord("key2", 1, 3999L));
        testHarness.processElement(insertRecord("key2", 1, 3000L));

        testHarness.setProcessingTime(1L);
        testHarness.processElement(insertRecord("key1", 1, 20L));
        testHarness.processElement(insertRecord("key1", 1, 0L));
        testHarness.processElement(insertRecord("key1", 1, 999L));

        testHarness.processElement(insertRecord("key2", 1, 1998L));
        testHarness.processElement(insertRecord("key2", 1, 1999L));
        testHarness.processElement(insertRecord("key2", 1, 1000L));

        testHarness.setProcessingTime(1000);
        expectedOutput.add(
                insertRecord(
                        "key2", 2L, 2L, localMills(3000L), localMills(6000L), localMills(5999L)));
        testHarness.processWatermark(new Watermark(999));
        expectedOutput.add(new Watermark(999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.setProcessingTime(1001);
        expectedOutput.add(
                insertRecord("key1", 3L, 3L, localMills(0L), localMills(3000L), localMills(2999L)));
        expectedOutput.add(
                insertRecord("key2", 3L, 3L, localMills(0L), localMills(3000L), localMills(2999L)));

        testHarness.processWatermark(new Watermark(1999));
        testHarness.setProcessingTime(2001);
        expectedOutput.add(new Watermark(1999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshot = testHarness.snapshot(0L, 0);
        testHarness.close();
        expectedOutput.clear();

        // new a testHarness
        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshot);
        testHarness.open();

        testHarness.setProcessingTime(3001);
        testHarness.processWatermark(new Watermark(2999));
        // on time fire key1 & key2 [0 ~ 3000) window, but because of early firing, on time result
        // is ignored
        expectedOutput.add(new Watermark(2999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key2", 1, 4999L));
        testHarness.processWatermark(new Watermark(3999));
        testHarness.setProcessingTime(4001);
        expectedOutput.add(new Watermark(3999));
        expectedOutput.add(
                updateBeforeRecord(
                        "key2", 2L, 2L, localMills(3000L), localMills(6000L), localMills(5999L)));
        expectedOutput.add(
                updateAfterRecord(
                        "key2", 3L, 3L, localMills(3000L), localMills(6000L), localMills(5999L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // late arrival
        testHarness.processElement(insertRecord("key2", 1, 2001L));
        testHarness.processElement(insertRecord("key1", 1, 2030L));
        // drop late elements
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.setProcessingTime(5100);
        testHarness.processElement(insertRecord("key2", 1, 5122L));
        testHarness.processWatermark(new Watermark(4999));
        expectedOutput.add(new Watermark(4999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(5999));
        expectedOutput.add(
                updateBeforeRecord(
                        "key2", 3L, 3L, localMills(3000L), localMills(6000L), localMills(5999L)));
        expectedOutput.add(
                updateAfterRecord(
                        "key2", 4L, 4L, localMills(3000L), localMills(6000L), localMills(5999L)));
        expectedOutput.add(new Watermark(5999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.setProcessingTime(6001);
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // those don't have any effect...
        testHarness.processWatermark(new Watermark(6999));
        testHarness.processWatermark(new Watermark(7999));
        expectedOutput.add(new Watermark(6999));
        expectedOutput.add(new Watermark(7999));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // late arrival, drop
        testHarness.processElement(insertRecord("key2", 1, 2877L));
        testHarness.processElement(insertRecord("key1", 1, 2899L));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
    }

    @TestTemplate
    @SuppressWarnings("unchecked")
    void testEventTimeTumblingWindowsWithEarlyAndLateFirings() throws Exception {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .tumble(Duration.ofSeconds(3))
                        .withEventTime(2)
                        .triggering(
                                EventTimeTriggers.afterEndOfWindow()
                                        .withEarlyFirings(
                                                ProcessingTimeTriggers.every(Duration.ofSeconds(1)))
                                        .withLateFirings(ElementTriggers.every()))
                        .withAllowedLateness(Duration.ofSeconds(3))
                        .produceUpdates()
                        .aggregate(
                                new SumAndCountAggTimeWindow(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes)
                        .build();

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();
        testHarness.setProcessingTime(0L);

        // add elements out-of-order
        testHarness.processElement(insertRecord("key2", 1, 3999L));
        testHarness.processElement(insertRecord("key2", 1, 3000L));

        testHarness.setProcessingTime(1L);
        testHarness.processElement(insertRecord("key1", 1, 20L));
        testHarness.processElement(insertRecord("key1", 1, 0L));
        testHarness.processElement(insertRecord("key1", 1, 999L));

        testHarness.processElement(insertRecord("key2", 1, 1998L));
        testHarness.processElement(insertRecord("key2", 1, 1999L));
        testHarness.processElement(insertRecord("key2", 1, 1000L));

        testHarness.setProcessingTime(1000);
        expectedOutput.add(
                insertRecord(
                        "key2", 2L, 2L, localMills(3000L), localMills(6000L), localMills(5999L)));
        testHarness.processWatermark(new Watermark(999));
        expectedOutput.add(new Watermark(999));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.setProcessingTime(1001);
        expectedOutput.add(
                insertRecord("key1", 3L, 3L, localMills(0L), localMills(3000L), localMills(2999L)));
        expectedOutput.add(
                insertRecord("key2", 3L, 3L, localMills(0L), localMills(3000L), localMills(2999L)));

        testHarness.processWatermark(new Watermark(1999));
        testHarness.setProcessingTime(2001);
        expectedOutput.add(new Watermark(1999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshot = testHarness.snapshot(0L, 0);
        testHarness.close();
        expectedOutput.clear();

        // new a testHarness
        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshot);
        testHarness.open();

        testHarness.setProcessingTime(3001);
        testHarness.processWatermark(new Watermark(2999));
        // on time fire key1 & key2 [0 ~ 3000) window, but because of early firing, on time result
        // is ignored
        expectedOutput.add(new Watermark(2999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key2", 1, 4999L));
        testHarness.processWatermark(new Watermark(3999));
        testHarness.setProcessingTime(4001);
        expectedOutput.add(new Watermark(3999));
        expectedOutput.add(
                updateBeforeRecord(
                        "key2", 2L, 2L, localMills(3000L), localMills(6000L), localMills(5999L)));
        expectedOutput.add(
                updateAfterRecord(
                        "key2", 3L, 3L, localMills(3000L), localMills(6000L), localMills(5999L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // late arrival
        testHarness.processElement(insertRecord("key2", 1, 2001L));
        expectedOutput.add(
                updateBeforeRecord(
                        "key2", 3L, 3L, localMills(0L), localMills(3000L), localMills(2999L)));
        expectedOutput.add(
                updateAfterRecord(
                        "key2", 4L, 4L, localMills(0L), localMills(3000L), localMills(2999L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // late arrival
        testHarness.processElement(insertRecord("key1", 1, 2030L));
        expectedOutput.add(
                updateBeforeRecord(
                        "key1", 3L, 3L, localMills(0L), localMills(3000L), localMills(2999L)));
        expectedOutput.add(
                updateAfterRecord(
                        "key1", 4L, 4L, localMills(0L), localMills(3000L), localMills(2999L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.setProcessingTime(5100);
        testHarness.processElement(insertRecord("key2", 1, 5122L));
        testHarness.processWatermark(new Watermark(4999));
        expectedOutput.add(new Watermark(4999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processWatermark(new Watermark(5999));
        expectedOutput.add(
                updateBeforeRecord(
                        "key2", 3L, 3L, localMills(3000L), localMills(6000L), localMills(5999L)));
        expectedOutput.add(
                updateAfterRecord(
                        "key2", 4L, 4L, localMills(3000L), localMills(6000L), localMills(5999L)));
        expectedOutput.add(new Watermark(5999));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.setProcessingTime(6001);
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // those don't have any effect...
        testHarness.processWatermark(new Watermark(6999));
        testHarness.processWatermark(new Watermark(7999));
        expectedOutput.add(new Watermark(6999));
        expectedOutput.add(new Watermark(7999));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // late arrival, but too late, drop
        testHarness.processElement(insertRecord("key2", 1, 2877L));
        testHarness.processElement(insertRecord("key1", 1, 2899L));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
    }

    @TestTemplate
    @SuppressWarnings("unchecked")
    void testProcessingTimeTumblingWindows() throws Exception {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .tumble(Duration.ofSeconds(3))
                        .withProcessingTime()
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        testHarness.setProcessingTime(3);

        // timestamp is ignored in processing time
        testHarness.processElement(insertRecord("key2", 1, Long.MAX_VALUE));
        testHarness.processElement(insertRecord("key2", 1, 7000L));
        testHarness.processElement(insertRecord("key2", 1, 7000L));

        testHarness.processElement(insertRecord("key1", 1, 7000L));
        testHarness.processElement(insertRecord("key1", 1, 7000L));

        testHarness.setProcessingTime(5000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                3L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                2L,
                                2L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key1", 1, 7000L));
        testHarness.processElement(insertRecord("key1", 1, 7000L));
        testHarness.processElement(insertRecord("key1", 1, 7000L));

        testHarness.setProcessingTime(7000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(3000L),
                                localMills(6000L),
                                localMills(5999L))));

        assertThat(operator.getWatermarkLatency().getValue()).isEqualTo(0L);
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();
    }

    @TestTemplate
    @SuppressWarnings("unchecked")
    void testEventTimeSessionWindows() throws Exception {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .session(Duration.ofSeconds(3))
                        .withEventTime(2)
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        // add elements out-of-order
        testHarness.processElement(insertRecord("key2", 1, 0L));
        testHarness.processElement(insertRecord("key2", 2, 1000L));
        testHarness.processElement(insertRecord("key2", 3, 2500L));

        testHarness.processElement(insertRecord("key1", 1, 10L));
        testHarness.processElement(insertRecord("key1", 2, 1000L));

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshotV2 = testHarness.snapshot(0L, 0);
        testHarness.close();
        expectedOutput.clear();

        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshotV2);
        testHarness.open();

        assertThat(operator.getWatermarkLatency().getValue()).isEqualTo(0L);

        testHarness.processElement(insertRecord("key1", 3, 2500L));

        testHarness.processElement(insertRecord("key2", 4, 5501L));
        testHarness.processElement(insertRecord("key2", 5, 6000L));
        testHarness.processElement(insertRecord("key2", 5, 6000L));
        testHarness.processElement(insertRecord("key2", 6, 6050L));

        testHarness.processWatermark(new Watermark(12000));

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                6L,
                                3L,
                                localMills(10L),
                                localMills(5500L),
                                localMills(5499L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                6L,
                                3L,
                                localMills(0L),
                                localMills(5500L),
                                localMills(5499L))));

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                20L,
                                4L,
                                localMills(5501L),
                                localMills(9050L),
                                localMills(9049L))));
        expectedOutput.add(new Watermark(12000));

        // add a late data
        testHarness.processElement(insertRecord("key1", 3, 4000L));
        testHarness.processElement(insertRecord("key2", 10, 15000L));
        testHarness.processElement(insertRecord("key2", 20, 15000L));

        testHarness.processWatermark(new Watermark(17999));

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                30L,
                                2L,
                                localMills(15000L),
                                localMills(18000L),
                                localMills(17999L))));
        expectedOutput.add(new Watermark(17999));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.setProcessingTime(18000);
        assertThat(operator.getWatermarkLatency().getValue()).isEqualTo(1L);

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
        assertThat(operator.getNumLateRecordsDropped().getCount()).isEqualTo(1);
    }

    @TestTemplate
    void testProcessingTimeSessionWindows() throws Throwable {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .session(Duration.ofSeconds(3))
                        .withProcessingTime()
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        RowDataHarnessAssertor assertor =
                new RowDataHarnessAssertor(
                        outputType.toRowFieldTypes(),
                        new GenericRowRecordSortComparator(0, VarCharType.STRING_TYPE));

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        // timestamp is ignored in processing time
        testHarness.setProcessingTime(3);
        testHarness.processElement(insertRecord("key2", 1, 1L));

        testHarness.setProcessingTime(1000);
        testHarness.processElement(insertRecord("key2", 1, 1002L));

        testHarness.setProcessingTime(5000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                2L,
                                2L,
                                localMills(3L),
                                localMills(4000L),
                                localMills(3999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key2", 1, 5000L));
        testHarness.processElement(insertRecord("key2", 1, 5000L));
        testHarness.processElement(insertRecord("key1", 1, 5000L));
        testHarness.processElement(insertRecord("key1", 1, 5000L));
        testHarness.processElement(insertRecord("key1", 1, 5000L));

        testHarness.setProcessingTime(10000);

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                2L,
                                2L,
                                localMills(5000L),
                                localMills(8000L),
                                localMills(7999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                3L,
                                3L,
                                localMills(5000L),
                                localMills(8000L),
                                localMills(7999L))));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();
    }

    /**
     * This tests a custom Session window assigner that assigns some elements to "point windows",
     * windows that have the same timestamp for start and end.
     *
     * <p>In this test, elements that have 33 as the second tuple field will be put into a point
     * window.
     */
    @TestTemplate
    @SuppressWarnings("unchecked")
    void testPointSessions() throws Exception {
        closeCalled.set(0);

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .assigner(new PointSessionWindowAssigner(3000))
                        .withEventTime(2)
                        .aggregateAndBuild(
                                getTimeWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        // add elements out-of-order
        testHarness.processElement(insertRecord("key2", 1, 0L));
        testHarness.processElement(insertRecord("key2", 33, 1000L));

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshot = testHarness.snapshot(0L, 0);
        testHarness.close();

        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshot);
        testHarness.open();

        testHarness.processElement(insertRecord("key2", 33, 2500L));

        testHarness.processElement(insertRecord("key1", 1, 10L));
        testHarness.processElement(insertRecord("key1", 2, 1000L));
        testHarness.processElement(insertRecord("key1", 33, 2500L));

        testHarness.processWatermark(new Watermark(12000));

        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key1",
                                36L,
                                3L,
                                localMills(10L),
                                localMills(4000L),
                                localMills(3999L))));
        expectedOutput.addAll(
                doubleRecord(
                        isTableAggregate,
                        insertRecord(
                                "key2",
                                67L,
                                3L,
                                localMills(0L),
                                localMills(3000L),
                                localMills(2999L))));
        expectedOutput.add(new Watermark(12000));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
    }

    @TestTemplate
    void testLateness() throws Exception {
        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .tumble(Duration.ofSeconds(2))
                        .withEventTime(2)
                        .withAllowedLateness(Duration.ofMillis(500))
                        .produceUpdates()
                        .aggregateAndBuild(
                                new SumAndCountAggTimeWindow(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        testHarness.processElement(insertRecord("key2", 1, 500L));
        testHarness.processWatermark(new Watermark(1500));

        expectedOutput.add(new Watermark(1500));

        testHarness.processElement(insertRecord("key2", 1, 1300L));
        testHarness.processWatermark(new Watermark(2300));

        expectedOutput.add(
                insertRecord("key2", 2L, 2L, localMills(0L), localMills(2000L), localMills(1999L)));
        expectedOutput.add(new Watermark(2300));

        // this will not be dropped because window.maxTimestamp() + allowedLateness >
        // currentWatermark
        testHarness.processElement(insertRecord("key2", 1, 1997L));
        testHarness.processWatermark(new Watermark(6000));

        // this is 1 and not 3 because the trigger fires and purges
        expectedOutput.add(
                updateBeforeRecord(
                        "key2", 2L, 2L, localMills(0L), localMills(2000L), localMills(1999L)));
        expectedOutput.add(
                updateAfterRecord(
                        "key2", 3L, 3L, localMills(0L), localMills(2000L), localMills(1999L)));
        expectedOutput.add(new Watermark(6000));

        // this will be dropped because window.maxTimestamp() + allowedLateness < currentWatermark
        testHarness.processElement(insertRecord("key2", 1, 1998L));
        testHarness.processWatermark(new Watermark(7000));

        expectedOutput.add(new Watermark(7000));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        assertThat(operator.getNumLateRecordsDropped().getCount()).isEqualTo(1);

        testHarness.close();
    }

    @TestTemplate
    void testCleanupTimerWithEmptyReduceStateForTumblingWindows() throws Exception {
        final int windowSize = 2;
        final long lateness = 1;

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .tumble(Duration.ofSeconds(windowSize))
                        .withEventTime(2)
                        .withAllowedLateness(Duration.ofMillis(lateness))
                        .produceUpdates()
                        .aggregateAndBuild(
                                new SumAndCountAggTimeWindow(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        testHarness.open();

        ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

        // normal element
        testHarness.processElement(insertRecord("key2", 1, 1000L));
        testHarness.processWatermark(new Watermark(1599));
        testHarness.processWatermark(new Watermark(1999));
        testHarness.processWatermark(new Watermark(2000));
        testHarness.processWatermark(new Watermark(5000));

        expected.add(new Watermark(1599));
        expected.add(
                insertRecord("key2", 1L, 1L, localMills(0L), localMills(2000L), localMills(1999L)));
        expected.add(new Watermark(1999)); // here it fires and purges
        expected.add(new Watermark(2000)); // here is the cleanup timer
        expected.add(new Watermark(5000));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expected, testHarness.getOutput());
        testHarness.close();
    }

    @TestTemplate
    void testCleanupTimeOverflow() throws Exception {
        if (!UTC_ZONE_ID.equals(shiftTimeZone)) {
            return;
        }
        long windowSize = 1000;
        long lateness = 2000;
        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .tumble(Duration.ofMillis(windowSize))
                        .withEventTime(2)
                        .withAllowedLateness(Duration.ofMillis(lateness))
                        .produceUpdates()
                        .aggregateAndBuild(
                                new SumAndCountAggTimeWindow(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                new KeyedOneInputStreamOperatorTestHarness<RowData, RowData, RowData>(
                        operator, keySelector, keyType);

        testHarness.open();

        ConcurrentLinkedQueue<Object> expected = new ConcurrentLinkedQueue<>();

        GroupWindowAssigner<TimeWindow> windowAssigner =
                TumblingWindowAssigner.of(Duration.ofMillis(windowSize));
        long timestamp = Long.MAX_VALUE - 1750;
        Collection<TimeWindow> windows =
                windowAssigner.assignWindows(GenericRowData.of(fromString("key2"), 1), timestamp);
        TimeWindow window = windows.iterator().next();

        testHarness.processElement(insertRecord("key2", 1, timestamp));

        // the garbage collection timer would wrap-around
        assertThat(window.maxTimestamp() + lateness).isLessThan(window.maxTimestamp());

        // and it would prematurely fire with watermark (Long.MAX_VALUE - 1500)
        assertThat(window.maxTimestamp() + lateness).isLessThan(Long.MAX_VALUE - 1500);

        // if we don't correctly prevent wrap-around in the garbage collection
        // timers this watermark will clean our window state for the just-added
        // element/window
        testHarness.processWatermark(new Watermark(Long.MAX_VALUE - 1500));

        // this watermark is before the end timestamp of our only window
        assertThat(Long.MAX_VALUE - 1500).isLessThan(window.maxTimestamp());
        assertThat(window.maxTimestamp()).isLessThan(Long.MAX_VALUE);

        // push in a watermark that will trigger computation of our window
        testHarness.processWatermark(new Watermark(window.maxTimestamp()));

        expected.add(new Watermark(Long.MAX_VALUE - 1500));
        expected.add(
                insertRecord(
                        "key2", 1L, 1L, window.getStart(), window.getEnd(), window.maxTimestamp()));
        expected.add(new Watermark(window.maxTimestamp()));

        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expected, testHarness.getOutput());
        testHarness.close();
    }

    @TestTemplate
    void testTumblingCountWindow() throws Exception {
        if (!UTC_ZONE_ID.equals(shiftTimeZone)) {
            return;
        }
        closeCalled.set(0);
        final int windowSize = 3;
        LogicalType[] windowTypes = new LogicalType[] {new BigIntType()};

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .countWindow(windowSize)
                        .aggregateAndBuild(
                                getCountWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        testHarness.processElement(insertRecord("key2", 1, 0L));
        testHarness.processElement(insertRecord("key2", 2, 1000L));
        testHarness.processElement(insertRecord("key2", 3, 2500L));
        testHarness.processElement(insertRecord("key1", 1, 10L));
        testHarness.processElement(insertRecord("key1", 2, 1000L));

        testHarness.processWatermark(new Watermark(12000));
        testHarness.setProcessingTime(12000L);
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key2", 6L, 3L, 0L)));
        expectedOutput.add(new Watermark(12000));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshotV2 = testHarness.snapshot(0L, 0);
        testHarness.close();
        expectedOutput.clear();

        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshotV2);
        testHarness.open();

        testHarness.processElement(insertRecord("key1", 2, 2500L));
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key1", 5L, 3L, 0L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key2", 4, 5501L));
        testHarness.processElement(insertRecord("key2", 5, 6000L));
        testHarness.processElement(insertRecord("key2", 5, 6000L));
        testHarness.processElement(insertRecord("key2", 6, 6050L));

        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key2", 14L, 3L, 1L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key1", 3, 4000L));
        testHarness.processElement(insertRecord("key2", 10, 15000L));
        testHarness.processElement(insertRecord("key2", 20, 15000L));
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key2", 36L, 3L, 2L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key1", 2, 2500L));
        testHarness.processElement(insertRecord("key1", 2, 2500L));
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key1", 7L, 3L, 1L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
    }

    @TestTemplate
    void testSlidingCountWindow() throws Exception {
        if (!UTC_ZONE_ID.equals(shiftTimeZone)) {
            return;
        }
        closeCalled.set(0);
        final int windowSize = 5;
        final int windowSlide = 3;
        LogicalType[] windowTypes = new LogicalType[] {new BigIntType()};

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .countWindow(windowSize, windowSlide)
                        .aggregateAndBuild(
                                getCountWindowAggFunction(),
                                equaliser,
                                accTypes,
                                aggResultTypes,
                                windowTypes);

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createTestHarness(operator);

        ConcurrentLinkedQueue<Object> expectedOutput = new ConcurrentLinkedQueue<>();

        testHarness.open();

        testHarness.processElement(insertRecord("key2", 1, 0L));
        testHarness.processElement(insertRecord("key2", 2, 1000L));
        testHarness.processElement(insertRecord("key2", 3, 2500L));
        testHarness.processElement(insertRecord("key2", 4, 2500L));
        testHarness.processElement(insertRecord("key2", 5, 2500L));
        testHarness.processElement(insertRecord("key1", 1, 10L));
        testHarness.processElement(insertRecord("key1", 2, 1000L));

        testHarness.processWatermark(new Watermark(12000));
        testHarness.setProcessingTime(12000L);
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key2", 15L, 5L, 0L)));
        expectedOutput.add(new Watermark(12000));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshotV2 = testHarness.snapshot(0L, 0);
        testHarness.close();
        expectedOutput.clear();

        testHarness = createTestHarness(operator);
        testHarness.setup();
        testHarness.initializeState(snapshotV2);
        testHarness.open();

        testHarness.processElement(insertRecord("key1", 3, 2500L));
        testHarness.processElement(insertRecord("key1", 4, 2500L));
        testHarness.processElement(insertRecord("key1", 5, 2500L));
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key1", 15L, 5L, 0L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key2", 6, 6000L));
        testHarness.processElement(insertRecord("key2", 7, 6000L));
        testHarness.processElement(insertRecord("key2", 8, 6050L));
        testHarness.processElement(insertRecord("key2", 9, 6050L));
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key2", 30L, 5L, 1L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.processElement(insertRecord("key1", 6, 4000L));
        testHarness.processElement(insertRecord("key1", 7, 4000L));
        testHarness.processElement(insertRecord("key1", 8, 4000L));
        testHarness.processElement(insertRecord("key2", 10, 15000L));
        testHarness.processElement(insertRecord("key2", 11, 15000L));
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key1", 30L, 5L, 1L)));
        expectedOutput.addAll(doubleRecord(isTableAggregate, insertRecord("key2", 45L, 5L, 2L)));
        assertor.assertOutputEqualsSorted(
                "Output was not correct.", expectedOutput, testHarness.getOutput());

        testHarness.close();

        // we close once in the rest...
        assertThat(closeCalled.get()).as("Close was not called.").isEqualTo(2);
    }

    @TestTemplate
    void testWindowCloseWithoutOpen() throws Exception {
        if (!UTC_ZONE_ID.equals(shiftTimeZone)) {
            return;
        }
        final int windowSize = 3;
        LogicalType[] windowTypes = new LogicalType[] {new BigIntType()};

        WindowOperator operator =
                WindowOperatorBuilder.builder()
                        .withInputFields(inputFieldTypes)
                        .withShiftTimezone(shiftTimeZone)
                        .countWindow(windowSize)
                        .aggregate(
                                new GeneratedNamespaceTableAggsHandleFunction<>(
                                        "MockClass", "", new Object[] {}),
                                accTypes,
                                aggResultTypes,
                                windowTypes)
                        .build();

        // close() before open() called
        operator.close();
    }

    // --------------------------------------------------------------------------------

    /** Get the timestamp in mills by given epoch mills and timezone. */
    private long localMills(long epochMills) {
        return toUtcTimestampMills(epochMills, shiftTimeZone);
    }

    private static class PointSessionWindowAssigner extends SessionWindowAssigner {
        private static final long serialVersionUID = 1L;

        private final long sessionTimeout;

        private PointSessionWindowAssigner(long sessionTimeout) {
            super(sessionTimeout, true);
            this.sessionTimeout = sessionTimeout;
        }

        private PointSessionWindowAssigner(long sessionTimeout, boolean isEventTime) {
            super(sessionTimeout, isEventTime);
            this.sessionTimeout = sessionTimeout;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Collection<TimeWindow> assignWindows(RowData element, long timestamp) {
            int second = element.getInt(1);
            if (second == 33) {
                return Collections.singletonList(new TimeWindow(timestamp, timestamp));
            }
            return Collections.singletonList(new TimeWindow(timestamp, timestamp + sessionTimeout));
        }

        @Override
        public SessionWindowAssigner withEventTime() {
            return new PointSessionWindowAssigner(sessionTimeout, true);
        }

        @Override
        public SessionWindowAssigner withProcessingTime() {
            return new PointSessionWindowAssigner(sessionTimeout, false);
        }
    }

    // sum, count, window_start, window_end
    private static class SumAndCountAggTimeWindow extends SumAndCountAggBase<TimeWindow>
            implements NamespaceAggsHandleFunction<TimeWindow> {

        private static final long serialVersionUID = 2062031590687738047L;

        @Override
        public RowData getValue(TimeWindow namespace) throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            GenericRowData row = new GenericRowData(5);
            if (!sumIsNull) {
                row.setField(0, sum);
            }
            if (!countIsNull) {
                row.setField(1, count);
            }
            row.setField(2, namespace.getStart());
            row.setField(3, namespace.getEnd());
            row.setField(4, namespace.maxTimestamp());
            return row;
        }
    }

    // sum, count, window_id
    private static class SumAndCountAggCountWindow extends SumAndCountAggBase<CountWindow>
            implements NamespaceAggsHandleFunction<CountWindow> {

        private static final long serialVersionUID = -2634639678371135643L;

        @Override
        public RowData getValue(CountWindow namespace) throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            GenericRowData row = new GenericRowData(3);
            if (!sumIsNull) {
                row.setField(0, sum);
            }
            if (!countIsNull) {
                row.setField(1, count);
            }
            row.setField(2, namespace.getId());
            return row;
        }
    }

    // (table aggregate) sum, count, window_start, window_end
    private static class SumAndCountTableAggTimeWindow extends SumAndCountAggBase<TimeWindow>
            implements NamespaceTableAggsHandleFunction<TimeWindow> {

        private static final long serialVersionUID = 2062031590687738047L;

        @Override
        public void emitValue(TimeWindow namespace, RowData key, Collector<RowData> out)
                throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            GenericRowData row = new GenericRowData(5);
            if (!sumIsNull) {
                row.setField(0, sum);
            }
            if (!countIsNull) {
                row.setField(1, count);
            }
            row.setField(2, namespace.getStart());
            row.setField(3, namespace.getEnd());
            row.setField(4, namespace.maxTimestamp());

            result.replace(key, row);
            // Simply output two lines
            out.collect(result);
            out.collect(result);
        }
    }

    // (table aggregate) sum, count, window_id
    private static class SumAndCountTableAggCountWindow extends SumAndCountAggBase<CountWindow>
            implements NamespaceTableAggsHandleFunction<CountWindow> {

        private static final long serialVersionUID = -2634639678371135643L;

        @Override
        public void emitValue(CountWindow namespace, RowData key, Collector<RowData> out)
                throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            GenericRowData row = new GenericRowData(3);
            if (!sumIsNull) {
                row.setField(0, sum);
            }
            if (!countIsNull) {
                row.setField(1, count);
            }
            row.setField(2, namespace.getId());

            result.replace(key, row);
            // Simply output two lines
            out.collect(result);
            out.collect(result);
        }
    }

    private static class SumAndCountAggBase<W extends Window> {

        boolean openCalled;

        long sum;
        boolean sumIsNull;
        long count;
        boolean countIsNull;

        protected transient JoinedRowData result;

        public void open(StateDataViewStore store) throws Exception {
            openCalled = true;
            result = new JoinedRowData();
        }

        public void setAccumulators(W namespace, RowData acc) throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            sumIsNull = acc.isNullAt(0);
            if (!sumIsNull) {
                sum = acc.getLong(0);
            }

            countIsNull = acc.isNullAt(1);
            if (!countIsNull) {
                count = acc.getLong(1);
            }
        }

        public void accumulate(RowData inputRow) throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            boolean inputIsNull = inputRow.isNullAt(1);
            if (!inputIsNull) {
                sum += inputRow.getInt(1);
                count += 1;
            }
        }

        public void retract(RowData inputRow) throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            boolean inputIsNull = inputRow.isNullAt(1);
            if (!inputIsNull) {
                sum -= inputRow.getInt(1);
                count -= 1;
            }
        }

        public void merge(W w, RowData otherAcc) throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            boolean sumIsNull2 = otherAcc.isNullAt(0);
            if (!sumIsNull2) {
                sum += otherAcc.getLong(0);
            }
            boolean countIsNull2 = otherAcc.isNullAt(1);
            if (!countIsNull2) {
                count += otherAcc.getLong(1);
            }
        }

        public RowData createAccumulators() {
            if (!openCalled) {
                fail("Open was not called");
            }
            GenericRowData acc = new GenericRowData(2);
            acc.setField(0, 0L);
            acc.setField(1, 0L);
            return acc;
        }

        public RowData getAccumulators() throws Exception {
            if (!openCalled) {
                fail("Open was not called");
            }
            GenericRowData row = new GenericRowData(2);
            if (!sumIsNull) {
                row.setField(0, sum);
            }
            if (!countIsNull) {
                row.setField(1, count);
            }
            return row;
        }

        public void cleanup(W window) {}

        public void close() {
            closeCalled.incrementAndGet();
        }
    }

    private static class GenericRowEqualiser implements RecordEqualiser {

        private final LogicalType[] fieldTypes;

        GenericRowEqualiser(LogicalType[] aggResultTypes, LogicalType[] windowTypes) {
            int size = aggResultTypes.length + windowTypes.length;
            this.fieldTypes = new LogicalType[size];
            for (int i = 0; i < size; i++) {
                if (i < aggResultTypes.length) {
                    fieldTypes[i] = aggResultTypes[i];
                } else {
                    fieldTypes[i] = windowTypes[i - aggResultTypes.length];
                }
            }
        }

        @Override
        public boolean equals(RowData row1, RowData row2) {
            GenericRowData left = RowDataTestUtil.toGenericRowDeeply(row1, fieldTypes);
            GenericRowData right = RowDataTestUtil.toGenericRowDeeply(row2, fieldTypes);
            return left.equals(right);
        }
    }

    private OneInputStreamOperatorTestHarness<RowData, RowData> createTestHarness(
            WindowOperator operator) throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<RowData, RowData, RowData>(
                operator, keySelector, keyType);
    }
}
