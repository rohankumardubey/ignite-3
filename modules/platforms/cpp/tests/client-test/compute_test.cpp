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

#include "ignite_runner_suite.h"

#include "ignite/client/ignite_client.h"
#include "ignite/client/ignite_client_configuration.h"

#include <gmock/gmock-matchers.h>
#include <gtest/gtest.h>

#include <chrono>

using namespace ignite;

/**
 * Test suite.
 */
class compute_test : public ignite_runner_suite {
protected:
    void SetUp() override {
        ignite_client_configuration cfg{get_node_addrs()};
        cfg.set_logger(get_logger());

        m_client = ignite_client::start(cfg, std::chrono::seconds(30));
    }

    void TearDown() override {
        // remove all
    }

    /** Ignite client. */
    ignite_client m_client;
};

TEST_F(compute_test, get_cluster_nodes) {
    auto cluster_nodes = m_client.get_cluster_nodes();

    std::sort(cluster_nodes.begin(), cluster_nodes.end(), [] (const auto &n1, const auto &n2) {
        return n1.get_name() < n2.get_name();
    });

    ASSERT_EQ(2, cluster_nodes.size());

    EXPECT_FALSE(cluster_nodes[0].get_id().empty());
    EXPECT_FALSE(cluster_nodes[1].get_id().empty());

    EXPECT_EQ(3344, cluster_nodes[0].get_address().port);
    EXPECT_EQ(3345, cluster_nodes[1].get_address().port);

    EXPECT_FALSE(cluster_nodes[0].get_address().host.empty());
    EXPECT_FALSE(cluster_nodes[1].get_address().host.empty());

    EXPECT_EQ(cluster_nodes[0].get_address().host, cluster_nodes[1].get_address().host);
}

TEST_F(compute_test, execute_on_random_node) {
    auto cluster_nodes = m_client.get_cluster_nodes();

    auto result = m_client.get_compute().execute(cluster_nodes, NODE_NAME_JOB, {});

    ASSERT_TRUE(result.has_value());
    EXPECT_THAT(result.value().get<std::string>(), ::testing::StartsWith(PLATFORM_TEST_NODE_RUNNER));
}