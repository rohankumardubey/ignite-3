/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.cluster;

import static org.apache.ignite.internal.testframework.IgniteTestUtils.assertThrowsWithCause;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.configuration.picocli.MicronautFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgnitionManager;
import org.apache.ignite.internal.app.EnvironmentDefaultValueProvider;
import org.apache.ignite.internal.app.IgniteImpl;
import org.apache.ignite.internal.cli.commands.TopLevelCliCommand;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Tuple;
import org.apache.ignite.tx.Transaction;
import org.hamcrest.text.IsEmptyString;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import picocli.CommandLine;

/**
 * Test node start/stop in different scenarios and validate grid services behavior depending on availability/absence of quorums.
 */
//@Disabled("https://issues.apache.org/jira/browse/IGNITE-18441")
@SuppressWarnings("ThrowableNotThrown")
public class ItNodeRestartTest extends AbstractClusterStartStopTest {
    /** Initialize grid. */
    @BeforeEach
    public void before() throws Exception {
        initGrid(nodeAliasToNameMapping.values());

        stopAllNodes();
    }


    /** Stop grid and clear persistence. */
    @AfterEach
    public void afterEach() {
        stopAllNodes();

        for (String name : nodesCfg.keySet()) {
            IgniteUtils.deleteIfExists(WORK_DIR.resolve(name));
        }
    }

    /**
     * Generate grid configuration to check.
     *
     * @return Test parameters.
     */
    static Object[] generateSequence() {
        return new SequenceGenerator(
                nodeAliasToNameMapping.keySet(),
                (name, grid) -> (!"D2".equals(name) || grid.contains("D")),  // Data nodes are interchangeable.
                new UniqueSetFilter<String>().and(grid -> grid.size() > 2)
        ).generate().toArray(Object[]::new);
    }

    /** Checks new node joining to the grid. */
    @ParameterizedTest(name = "Grid=" + ParameterizedTest.ARGUMENTS_PLACEHOLDER)
    @MethodSource("generateSequence")
    public void testNodeJoin(List<String> nodeNames) {
        runTest(nodeNames, () -> checkNodeJoin(NEW_NODE));
    }

    /** Checks table creation. */
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-18328")
    @ParameterizedTest(name = "Grid=" + ParameterizedTest.ARGUMENTS_PLACEHOLDER)
    @MethodSource("generateSequence")
    public void testCreateTable(List<String> nodeNames) {
        runTest(nodeNames, this::checkCreateTable);
    }

    /** Checks implicit transaction. */
    @ParameterizedTest(name = "Grid=" + ParameterizedTest.ARGUMENTS_PLACEHOLDER)
    @MethodSource("generateSequence")
    public void testImplicitTransaction(List<String> nodeNames) {
        int key = UNIQ_INT.get();

        runTest(nodeNames, () -> checkImplicitTx((node, tx) -> {
            RecordView<Tuple> tupleRecordView = node.tables().tableAsync("tbl1")
                    .orTimeout(OPERATION_WAIT_TIMEOUT, TimeUnit.MILLISECONDS)
                    .join()
                    .recordView();

            return tupleRecordView.insertAsync(tx, Tuple.create(Map.of("id", key, "val", key)))
                    .orTimeout(OPERATION_WAIT_TIMEOUT, TimeUnit.MILLISECONDS)
                    .join();
        }));
    }

    /** Checks read-write transaction. */
    @ParameterizedTest(name = "Grid=" + ParameterizedTest.ARGUMENTS_PLACEHOLDER)
    @MethodSource("generateSequence")
    public void testReadWriteTransaction(List<String> nodeNames) {
        int key = UNIQ_INT.get();

        runTest(nodeNames, () -> checkRwTx((node, tx) -> {
            RecordView<Tuple> tupleRecordView = node.tables().tableAsync("tbl1")
                    .orTimeout(OPERATION_WAIT_TIMEOUT, TimeUnit.MILLISECONDS)
                    .join()
                    .recordView();

            return tupleRecordView.insertAsync(tx, Tuple.create(Map.of("id", key, "val", key)))
                    .orTimeout(OPERATION_WAIT_TIMEOUT, TimeUnit.MILLISECONDS)
                    .join();
        }));
    }

    /** Checks read-only transaction. */
    @ParameterizedTest(name = "Grid=" + ParameterizedTest.ARGUMENTS_PLACEHOLDER)
    @MethodSource("generateSequence")
    public void testReadOnlyTransaction(List<String> nodeNames) {
        runTest(nodeNames, () ->
                checkRoTx((node, tx) -> node.tables().tableAsync("tbl1").thenCompose(
                                tbl -> tbl.keyValueView().getAsync(tx, Tuple.create(Map.of("id", 1))))
                        .orTimeout(OPERATION_WAIT_TIMEOUT, TimeUnit.MILLISECONDS)
                        .join()
                ));
    }

    /** Checks implicit transaction. */
    @ParameterizedTest(name = "Grid=" + ParameterizedTest.ARGUMENTS_PLACEHOLDER)
    @MethodSource("generateSequence")
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-18328")
    public void testSqlWithImplicitTransaction(List<String> nodeNames) {
        int key = UNIQ_INT.get();

        runTest(nodeNames, () -> checkImplicitTx((node, tx) -> String.format("INSERT INTO tbl1 VALUES (%d, %d)", key, key)));
    }

    /** Checks read-write transaction. */
    @ParameterizedTest(name = "Grid=" + ParameterizedTest.ARGUMENTS_PLACEHOLDER)
    @MethodSource("generateSequence")
    public void testSqlWithReadWriteTransaction(List<String> nodeNames) {
        int key = UNIQ_INT.get();

        runTest(nodeNames, () -> checkRwTx((node, tx) -> String.format("INSERT INTO tbl1 VALUES (%d, %d)", key, key)));
    }

    /** Checks read-only transaction. */
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-18328")
    @ParameterizedTest(name = "Grid=" + ParameterizedTest.ARGUMENTS_PLACEHOLDER)
    @MethodSource("generateSequence")
    public void testSqlWithReadOnlyTransaction(List<String> nodeNames) {
        runTest(nodeNames, () -> checkRoTx((node, tx) -> sql(node, tx, "SELECT * FROM tbl1")));
    }

    private void runTest(List<String> nodeNames, Runnable testBody) {
        Set<String> realNames = nodeNames.stream().map(nodeAliasToNameMapping::get).collect(Collectors.toCollection(LinkedHashSet::new));

        for (String name : nodeNames) {
            try {
                prestartGrid(realNames);

                log.info("Restarting node: label=" + name + ", name=" + nodeAliasToNameMapping.get(name));

                stopNode(nodeAliasToNameMapping.get(name));

                testBody.run();

                startNode(nodeAliasToNameMapping.get(name));

                testBody.run();
            } finally {
                stopAllNodes();
            }
        }
    }

    private void prestartGrid(Set<String> nodeNames) {
        Set<String> expectedNodes = Set.copyOf(nodeNames);

        // Start CMG and MetaStorage first, to activate cluster.
        List<CompletableFuture<Ignite>> futs = new ArrayList<>();
        futs.add(startNode(nodeAliasToNameMapping.get("C")));
        futs.add(startNode(nodeAliasToNameMapping.get("M")));

        nodeNames.stream()
                .filter(n -> !clusterNodes.containsKey(n))
                .map(this::startNode)
                .forEach(futs::add);

        assertThat(CompletableFuture.allOf(futs.toArray(CompletableFuture[]::new)), willCompleteSuccessfully());

        // Stop unwanted nodes.
        futs.stream()
                .map(f -> f.join().name())
                .filter(n -> !expectedNodes.contains(n))
                .forEach(this::stopNode);
    }

    private void checkNodeJoin(String nodeName) {
        try {
            CompletableFuture<Ignite> fut = startNode(nodeName);

            if (!isNodeStarted(CMG_NODE)) {
                assertThrowsWithCause(() -> fut.get(OPERATION_WAIT_TIMEOUT, TimeUnit.MILLISECONDS), TimeoutException.class);

                assertTrue(topologyContainsNode("physical", nodeName));

                // CMG holds logical topology state.
                assertThrowsWithCause(() -> topologyContainsNode("logical", nodeName), AssertionError.class);

                return;
            } else if (!isNodeStarted(METASTORAGE_NODE)) {
                // Node future can't complete as some components requires MetaStorage on start.
                assertThrowsWithCause(() -> fut.get(OPERATION_WAIT_TIMEOUT, TimeUnit.MILLISECONDS), TimeoutException.class);

                assertTrue(topologyContainsNode("physical", nodeName));
                //TODO: Is Metastore required to promote node to logical topology?
                assertFalse(topologyContainsNode("logical", nodeName));

                return;
            }

            assertThat(fut, willCompleteSuccessfully());

            assertTrue(topologyContainsNode("physical", ((IgniteImpl) fut.join()).id()));
            assertTrue(topologyContainsNode("logical", ((IgniteImpl) fut.join()).id()));
        } finally {
            IgnitionManager.stop(nodeName);
        }
    }

    private void checkCreateTable() {
        Ignite node = initializedNode();

        if (node == null) {
            return;
        }

        String createTableCommand = "CREATE TABLE tempTbl (id INT PRIMARY KEY, val INT) WITH partitions = 1";
        String dropTableCommand = "DROP TABLE IF EXISTS tempTbl";

        try {
            sql(node, null, createTableCommand);
        } finally {
            sql(node, null, dropTableCommand);
        }
    }

    private void checkRoTx(BiFunction<Ignite, Transaction, Object> op) {
        Ignite node = initializedNode();

        if (node == null) {
            return;
        }

        if (!isNodeStarted(METASTORAGE_NODE) || (!isNodeStarted(DATA_NODE) && !isNodeStarted(DATA_NODE_2))) {
            Transaction roTx = node.transactions().readOnly().begin();

            assertThrowsWithCause(() -> op.apply(node, roTx), Exception.class);

            return;
            // TODO: Bound table distribution zone to data nodes and uncomment.
            // else if (!clusterNodes.containsKey(DATA_NODE_2)) {
        } else if (isNodeStarted(DATA_NODE_2) && clusterNodes.size() <= 2 /* no quorum */) {
            // Fake transaction with a timestamp from the past.
            Transaction tx0 = Mockito.spy(node.transactions().readOnly().begin());
            Mockito.when(tx0.readTimestamp()).thenReturn(new HybridTimestamp(1L, 0));

            try {
                op.apply(node, tx0);

                tx0.commit();
            } catch (Throwable th) {
                tx0.rollback();

                throw th;
            }

            Transaction roTx = node.transactions().readOnly().begin();
            // Transaction with recent timestamp.
            assertThrowsWithCause(() -> op.apply(node, roTx), Exception.class);

            return;
        }

        Transaction roTx = node.transactions().readOnly().begin();

        try {
            op.apply(node, roTx);

            roTx.commit();
        } catch (Throwable th) {
            roTx.rollback();

            throw th;
        }
    }

    private void checkImplicitTx(BiFunction<Ignite, Transaction, Object> op) {
        Ignite node = initializedNode();

        if (node == null) {
            return;
        }

        // TODO: Bound table distribution zone to data nodes and uncomment.
        // if (!clusterNodes.containsKey(DATA_NODE) || !clusterNodes.containsKey(DATA_NODE_2)) {
        if (!isNodeStarted(DATA_NODE) || !isNodeStarted(METASTORAGE_NODE)) {
            assertThrowsWithCause(() -> op.apply(node, null), Exception.class);

            return;
        }

        op.apply(node, null);
    }

    private void checkRwTx(BiFunction<Ignite, Transaction, Object> op) {
        Ignite node = initializedNode();

        if (node == null) {
            return;
        }

        // TODO: Bound table distribution zone to data nodes and uncomment.
        // if (!clusterNodes.containsKey(DATA_NODE) || !clusterNodes.containsKey(DATA_NODE_2)) {
        if (clusterNodes.size() <= 2 || !isNodeStarted(DATA_NODE) || !isNodeStarted(METASTORAGE_NODE)) {
            Transaction tx = node.transactions().begin();
            try {
                assertThrowsWithCause(() -> op.apply(node, tx), Exception.class);
            } finally {
                tx.rollback();
            }

            return;
        }

        Transaction tx = node.transactions().begin();
        try {
            op.apply(node, tx);

            tx.commit();
        } catch (Throwable th) {
            tx.rollback();

            throw th;
        }
    }

    private boolean topologyContainsNode(String topologyType, String nodeId) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        String url = "http://localhost:" + clusterNodes.values().stream()
                .map(n -> n.getNow(null))
                .filter(Objects::nonNull)
                .map(n -> ((IgniteImpl) n).restAddress().port())
                .findFirst()
                .orElse(10300);

        new CommandLine(TopLevelCliCommand.class, new MicronautFactory(ApplicationContext.run(Environment.TEST)))
                .setDefaultValueProvider(new EnvironmentDefaultValueProvider())
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("cluster", "topology", topologyType, "--cluster-endpoint-url", url);

        assertThat(err.toString(), IsEmptyString.emptyString());

        return Pattern.compile("\\b" + nodeId + "\\b").matcher(out.toString()).find();
    }

    /** Find started cluster node or return {@code null} if not found. */
    private @Nullable Ignite initializedNode() {
        assert !clusterNodes.isEmpty();

        return clusterNodes.values().stream()
                .map(f -> f.getNow(null))
                .filter(Objects::nonNull)
                .findAny()
                .orElse(null);
    }


    /** Filters out non-unique sets. */
    private static class UniqueSetFilter<T> implements Predicate<Set<T>> {
        final Set<Set<T>> seenBefore = new HashSet<>();

        @Override
        public boolean test(Set<T> s) {
            return seenBefore.add(new HashSet<>(s)); // Copy mutable collection.
        }
    }
}
