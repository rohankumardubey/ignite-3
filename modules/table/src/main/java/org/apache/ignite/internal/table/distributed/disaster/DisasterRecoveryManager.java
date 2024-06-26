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

package org.apache.ignite.internal.table.distributed.disaster;

import static org.apache.ignite.internal.util.CompletableFutures.nullCompletedFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.ignite.internal.affinity.Assignments;
import org.apache.ignite.internal.catalog.CatalogManager;
import org.apache.ignite.internal.catalog.descriptors.CatalogTableDescriptor;
import org.apache.ignite.internal.catalog.descriptors.CatalogZoneDescriptor;
import org.apache.ignite.internal.distributionzones.DistributionZoneManager;
import org.apache.ignite.internal.distributionzones.NodeWithAttributes;
import org.apache.ignite.internal.distributionzones.exception.DistributionZoneNotFoundException;
import org.apache.ignite.internal.lang.ByteArray;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.manager.IgniteComponent;
import org.apache.ignite.internal.metastorage.Entry;
import org.apache.ignite.internal.metastorage.MetaStorageManager;
import org.apache.ignite.internal.metastorage.WatchEvent;
import org.apache.ignite.internal.metastorage.WatchListener;
import org.apache.ignite.internal.network.MessagingService;
import org.apache.ignite.internal.network.NetworkMessage;
import org.apache.ignite.internal.raft.Loza;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.table.distributed.TableMessageGroup;
import org.apache.ignite.internal.table.distributed.TableMessagesFactory;
import org.apache.ignite.internal.table.distributed.disaster.messages.LocalPartitionState;
import org.apache.ignite.internal.table.distributed.disaster.messages.LocalPartitionStatesRequest;
import org.apache.ignite.internal.table.distributed.disaster.messages.LocalPartitionStatesResponse;
import org.apache.ignite.internal.util.ByteUtils;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.raft.jraft.Node;
import org.apache.ignite.raft.jraft.core.State;
import org.jetbrains.annotations.Nullable;

/**
 * Manager, responsible for "disaster recovery" operations.
 * Internally it triggers meta-storage updates, in order to acquire unique causality token.
 * As a reaction to these updates, manager performs actual recovery operations, such as {@link #resetPartitions(int, int)}.
 * More details are in the <a href="https://issues.apache.org/jira/browse/IGNITE-21140">epic</a>.
 */
public class DisasterRecoveryManager implements IgniteComponent {
    /** Logger. */
    private static final IgniteLogger LOG = Loggers.forClass(DisasterRecoveryManager.class);

    /** Single key for writing disaster recovery requests into meta-storage. */
    static final ByteArray RECOVERY_TRIGGER_KEY = new ByteArray("disaster.recovery.trigger");

    private static final TableMessagesFactory MSG_FACTORY = new TableMessagesFactory();

    private static final int TIMEOUT = 30;

    private static final int CATCH_UP_THRESHOLD = 10;

    /** Thread pool executor for async parts. */
    private final ExecutorService threadPool;

    /** Messaging service. */
    private final MessagingService messagingService;

    /** Meta-storage manager. */
    final MetaStorageManager metaStorageManager;

    /** Catalog manager. */
    final CatalogManager catalogManager;

    /** Distribution zone manager. */
    final DistributionZoneManager dzManager;

    /** Raft manager. */
    private final Loza raftManager;

    /** Watch listener for {@link #RECOVERY_TRIGGER_KEY}. */
    private final WatchListener watchListener;

    /**
     * Map of operations, triggered by local node, that have not yet been processed by {@link #watchListener}. Values in the map are the
     * futures, returned from the {@link #processNewRequest(ManualGroupUpdateRequest)}, they are completed by
     * {@link #handleTriggerKeyUpdate(WatchEvent)} when node receives corresponding events from the metastorage (or if it doesn't receive
     * this event within a 30 seconds window).
     */
    private final Map<UUID, CompletableFuture<Void>> ongoingOperationsById = new ConcurrentHashMap<>();

    /**
     * Constructor.
     */
    public DisasterRecoveryManager(
            ExecutorService threadPool,
            MessagingService messagingService,
            MetaStorageManager metaStorageManager,
            CatalogManager catalogManager,
            DistributionZoneManager dzManager,
            Loza raftManager
    ) {
        this.threadPool = threadPool;
        this.messagingService = messagingService;
        this.metaStorageManager = metaStorageManager;
        this.catalogManager = catalogManager;
        this.dzManager = dzManager;
        this.raftManager = raftManager;

        watchListener = new WatchListener() {
            @Override
            public CompletableFuture<Void> onUpdate(WatchEvent event) {
                return handleTriggerKeyUpdate(event);
            }

            @Override
            public void onError(Throwable e) {
                // No-op.
            }
        };
    }

    @Override
    public CompletableFuture<Void> start() {
        messagingService.addMessageHandler(TableMessageGroup.class, this::handleMessage);

        metaStorageManager.registerExactWatch(RECOVERY_TRIGGER_KEY, watchListener);

        return nullCompletedFuture();
    }

    @Override
    public void stop() throws Exception {
        metaStorageManager.unregisterWatch(watchListener);
    }

    /**
     * Updates assignments of the table in a forced manner, allowing for the recovery of raft group with lost majorities. It is achieved via
     * triggering a new rebalance with {@code force} flag enabled in {@link Assignments} for partitions where it's required. New pending
     * assignments with {@code force} flag remove old stable nodes from the distribution, and force new Raft configuration via "resetPeers"
     * so that a new leader could be elected.
     *
     * @param zoneId Distribution zone ID.
     * @param tableId Table ID.
     * @return Operation future.
     */
    public CompletableFuture<Void> resetPartitions(int zoneId, int tableId) {
        return processNewRequest(new ManualGroupUpdateRequest(UUID.randomUUID(), zoneId, tableId));
    }

    /**
     * Returns partition states for all zones' partitions in the cluster. Result is a mapping of {@link TablePartitionId} to the mapping
     * between a node name and a partition state.
     *
     * @param zoneName Zone name.
     * @return Future with the mapping.
     */
    public CompletableFuture<Map<TablePartitionId, Map<String, LocalPartitionState>>> partitionStates(String zoneName) {
        int latestCatalogVersion = catalogManager.latestCatalogVersion();
        Optional<CatalogZoneDescriptor> zoneDesciptorOptional = catalogManager.zones(latestCatalogVersion).stream()
                .filter(catalogZoneDescriptor -> catalogZoneDescriptor.name().equals(zoneName))
                .findAny();

        if (zoneDesciptorOptional.isEmpty()) {
            return CompletableFuture.failedFuture(new DistributionZoneNotFoundException(zoneName, null));
        }

        CatalogZoneDescriptor zoneDescriptor = zoneDesciptorOptional.get();

        Set<NodeWithAttributes> logicalTopology = dzManager.logicalTopology();

        LocalPartitionStatesRequest localPartitionStatesRequest = MSG_FACTORY.localPartitionStatesRequest()
                .zoneId(zoneDescriptor.id())
                .catalogVersion(latestCatalogVersion)
                .build();

        Map<TablePartitionId, Map<String, LocalPartitionState>> result = new ConcurrentHashMap<>();
        CompletableFuture<?>[] futures = new CompletableFuture[logicalTopology.size()];

        int i = 0;
        for (NodeWithAttributes node : logicalTopology) {
            CompletableFuture<NetworkMessage> invokeFuture = messagingService.invoke(
                    node.nodeName(),
                    localPartitionStatesRequest,
                    TimeUnit.SECONDS.toMillis(TIMEOUT)
            );

            futures[i++] = invokeFuture.thenAccept(networkMessage -> {
                assert networkMessage instanceof LocalPartitionStatesResponse : networkMessage;

                var response = (LocalPartitionStatesResponse) networkMessage;

                for (LocalPartitionState state : response.states()) {
                    result.compute(state.partitionId().asTablePartitionId(), (tablePartitionId, map) -> {
                        if (map == null) {
                            return Map.of(node.nodeName(), state);
                        }

                        map = new HashMap<>(map);
                        map.put(node.nodeName(), state);
                        return map;
                    });
                }
            });
        }

        return CompletableFuture.allOf(futures).handle((unused, throwable) -> normalize(result));
    }

    /**
     * Creates new operation future, associated with the request, and writes it into meta-storage.
     *
     * @param request Request.
     * @return Operation future.
     */
    private CompletableFuture<Void> processNewRequest(ManualGroupUpdateRequest request) {
        UUID operationId = request.operationId();

        CompletableFuture<Void> operationFuture = new CompletableFuture<Void>()
                .whenComplete((v, throwable) -> ongoingOperationsById.remove(operationId))
                .orTimeout(TIMEOUT, TimeUnit.SECONDS);

        ongoingOperationsById.put(operationId, operationFuture);

        metaStorageManager.put(RECOVERY_TRIGGER_KEY, ByteUtils.toBytes(request));

        return operationFuture;
    }

    /**
     * Handler for {@link #RECOVERY_TRIGGER_KEY} update event. Deserializes the request and delegates the execution to
     * {@link DisasterRecoveryRequest#handle(DisasterRecoveryManager, long, CompletableFuture)}.
     */
    private CompletableFuture<Void> handleTriggerKeyUpdate(WatchEvent watchEvent) {
        Entry newEntry = watchEvent.entryEvent().newEntry();

        byte[] requestBytes = newEntry.value();
        assert requestBytes != null;

        DisasterRecoveryRequest request;
        try {
            request = ByteUtils.fromBytes(requestBytes);
        } catch (Exception e) {
            LOG.warn("Unable to deserialize disaster recovery request.", e);

            return nullCompletedFuture();
        }

        CompletableFuture<Void> operationFuture = ongoingOperationsById.remove(request.operationId());

        if (operationFuture == null) {
            // We're not the initiator, or timeout has passed. Just ignore it.
            return nullCompletedFuture();
        }

        return request.handle(this, watchEvent.revision(), operationFuture);
    }

    private void handleMessage(NetworkMessage message, ClusterNode sender, @Nullable Long correlationId) {
        if (message instanceof LocalPartitionStatesRequest) {
            handleLocalPartitionStatesRequest((LocalPartitionStatesRequest) message, sender, correlationId);
        }
    }

    private void handleLocalPartitionStatesRequest(LocalPartitionStatesRequest request, ClusterNode sender, @Nullable Long correlationId) {
        assert correlationId != null;

        int catalogVersion = request.catalogVersion();
        catalogManager.catalogReadyFuture(catalogVersion).thenRunAsync(() -> {
            List<LocalPartitionState> statesList = new ArrayList<>();

            raftManager.forEach((raftNodeId, raftGroupService) -> {
                if (raftNodeId.groupId() instanceof TablePartitionId) {
                    var tablePartitionId = (TablePartitionId) raftNodeId.groupId();

                    CatalogTableDescriptor tableDescriptor = catalogManager.table(tablePartitionId.tableId(), catalogVersion);
                    if (tableDescriptor == null || tableDescriptor.zoneId() != request.zoneId()) {
                        return;
                    }

                    Node raftNode = raftGroupService.getRaftNode();
                    State nodeState = raftNode.getNodeState();

                    LocalPartitionStateEnum localState = convertState(nodeState);
                    long lastLogIndex = raftNode.lastLogIndex();

                    if (localState == LocalPartitionStateEnum.HEALTHY) {
                        // Node without log didn't process anything yet, it's not really "healthy" before it accepts leader's configuration.
                        if (lastLogIndex == 0) {
                            localState = LocalPartitionStateEnum.INITIALIZING;
                        }

                        if (raftNode.isInstallingSnapshot()) {
                            localState = LocalPartitionStateEnum.INSTALLING_SNAPSHOT;
                        }
                    }

                    statesList.add(MSG_FACTORY.localPartitionState()
                            .partitionId(MSG_FACTORY.tablePartitionIdMessage()
                                    .tableId(tablePartitionId.tableId())
                                    .partitionId(tablePartitionId.partitionId())
                                    .build())
                            .state(localState)
                            .logIndex(lastLogIndex)
                            .build()
                    );
                }
            });

            LocalPartitionStatesResponse response = MSG_FACTORY.localPartitionStatesResponse().states(statesList).build();

            messagingService.respond(sender, response, correlationId);
        }, threadPool);
    }

    /**
     * Converts internal raft node state into public local partition state.
     */
    private static LocalPartitionStateEnum convertState(State nodeState) {
        switch (nodeState) {
            case STATE_LEADER:
            case STATE_TRANSFERRING:
            case STATE_CANDIDATE:
            case STATE_FOLLOWER:
                return LocalPartitionStateEnum.HEALTHY;

            case STATE_ERROR:
                return LocalPartitionStateEnum.BROKEN;

            case STATE_UNINITIALIZED:
                return LocalPartitionStateEnum.INITIALIZING;

            case STATE_SHUTTING:
            case STATE_SHUTDOWN:
            case STATE_END:
                return LocalPartitionStateEnum.UNAVAILABLE;

            default:
                // Unrecognized state, better safe than sorry.
                return LocalPartitionStateEnum.BROKEN;
        }
    }

    /**
     * Replaces some healthy states with a {@link LocalPartitionStateEnum#CATCHING_UP},it can only be done once the state of all peers is
     * known.
     */
    private Map<TablePartitionId, Map<String, LocalPartitionState>> normalize(
            Map<TablePartitionId, Map<String, LocalPartitionState>> result
    ) {
        return result.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            Map<String, LocalPartitionState> map = entry.getValue();

            // noinspection OptionalGetWithoutIsPresent
            long maxLogIndex = map.values().stream().mapToLong(LocalPartitionState::logIndex).max().getAsLong();

            return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry2 -> {
                LocalPartitionState state = entry2.getValue();

                if (state.state() != LocalPartitionStateEnum.HEALTHY || maxLogIndex - state.logIndex() < CATCH_UP_THRESHOLD) {
                    return state;
                }

                return MSG_FACTORY.localPartitionState()
                        .state(LocalPartitionStateEnum.CATCHING_UP)
                        .partitionId(state.partitionId())
                        .logIndex(state.logIndex())
                        .build();
            }));
        }));
    }
}
