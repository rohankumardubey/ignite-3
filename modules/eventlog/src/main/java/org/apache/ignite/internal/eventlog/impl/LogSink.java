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

package org.apache.ignite.internal.eventlog.impl;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import org.apache.ignite.internal.eventlog.api.Event;
import org.apache.ignite.internal.eventlog.api.Sink;
import org.apache.ignite.internal.eventlog.config.schema.LogSinkView;
import org.apache.ignite.internal.eventlog.ser.EventSerializer;
import org.apache.ignite.internal.eventlog.ser.JsonEventSerializer;

/** Sink that writes events to the log using any logging framework the user has configured. */
class LogSink implements Sink {
    private final Logger logger;
    private final EventSerializer serializer;
    private final String level;

    LogSink(LogSinkView cfg) {
        this.level = cfg.level();
        this.logger = System.getLogger(cfg.criteria());
        this.serializer = new JsonEventSerializer();
    }

    /** {@inheritDoc} */
    @Override
    public void write(Event event) {
        logger.log(Level.valueOf(level), serializer.serialize(event));
    }
}
