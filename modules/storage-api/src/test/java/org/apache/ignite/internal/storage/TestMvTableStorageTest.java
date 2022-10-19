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

package org.apache.ignite.internal.storage;

import org.apache.ignite.internal.configuration.testframework.ConfigurationExtension;
import org.apache.ignite.internal.configuration.testframework.InjectConfiguration;
import org.apache.ignite.internal.schema.configuration.TablesConfiguration;
import org.apache.ignite.internal.storage.impl.TestMvTableStorage;
import org.apache.ignite.internal.storage.impl.TestStorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Class for testing the {@link TestMvTableStorage} class.
 */
@ExtendWith(ConfigurationExtension.class)
public class TestMvTableStorageTest extends AbstractMvTableStorageTest {
    private TestMvTableStorage storage;

    @BeforeEach
    void setUp(
            @InjectConfiguration(
                    value = "mock.tables.foo.dataStorage.name = " + TestStorageEngine.ENGINE_NAME
            )
            TablesConfiguration tablesConfig
    ) {
        storage = new TestMvTableStorage(tablesConfig.tables().get("foo"), tablesConfig);

        storage.start();

        initialize(storage, tablesConfig);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.stop();
        }
    }
}