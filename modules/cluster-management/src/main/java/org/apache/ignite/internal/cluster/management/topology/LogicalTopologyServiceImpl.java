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

package org.apache.ignite.internal.cluster.management.topology;

import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.cluster.management.ClusterManagementGroupManager;
import org.apache.ignite.network.LogicalTopologyEventListener;
import org.apache.ignite.network.LogicalTopologyService;
import org.apache.ignite.network.LogicalTopologySnapshot;

/**
 * {@link LogicalTopologyService} implementation.
 */
public class LogicalTopologyServiceImpl implements LogicalTopologyService {
    private final LogicalTopology logicalTopology;

    private final ClusterManagementGroupManager clusterManagementGroupManager;

    public LogicalTopologyServiceImpl(LogicalTopology logicalTopology, ClusterManagementGroupManager clusterManagementGroupManager) {
        this.logicalTopology = logicalTopology;
        this.clusterManagementGroupManager = clusterManagementGroupManager;
    }

    @Override
    public void addEventListener(LogicalTopologyEventListener listener) {
        logicalTopology.addEventListener(listener);
    }

    @Override
    public void removeEventListener(LogicalTopologyEventListener listener) {
        logicalTopology.removeEventListener(listener);
    }

    @Override
    public CompletableFuture<LogicalTopologySnapshot> logicalTopologyOnLeader() {
        return clusterManagementGroupManager.logicalTopology();
    }
}