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

package org.apache.ignite.internal.util;

import static org.apache.ignite.internal.testframework.IgniteTestUtils.runMultiThreaded;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureExceptionMatcher.willThrowFast;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PendingComparableValuesTracker}.
 */
public class PendingComparableValuesTrackerTest {
    @Test
    public void testSimpleWaitFor() {
        HybridTimestamp ts = new HybridTimestamp(1, 0);

        PendingComparableValuesTracker<HybridTimestamp> tracker = new PendingComparableValuesTracker<>(ts);

        HybridTimestamp ts1 = new HybridTimestamp(ts.getPhysical() + 1_000_000, 0);
        HybridTimestamp ts2 = new HybridTimestamp(ts.getPhysical() + 2_000_000, 0);
        HybridTimestamp ts3 = new HybridTimestamp(ts.getPhysical() + 3_000_000, 0);

        CompletableFuture<Void> f0 = tracker.waitFor(ts1);
        CompletableFuture<Void> f1 = tracker.waitFor(ts2);
        CompletableFuture<Void> f2 = tracker.waitFor(ts3);

        assertFalse(f0.isDone());
        assertFalse(f1.isDone());
        assertFalse(f2.isDone());

        tracker.update(ts1);
        assertThat(f0, willCompleteSuccessfully());
        assertFalse(f1.isDone());
        assertFalse(f2.isDone());

        tracker.update(ts2);
        assertThat(f1, willCompleteSuccessfully());
        assertFalse(f2.isDone());

        tracker.update(ts3);
        assertThat(f2, willCompleteSuccessfully());
    }

    @Test
    public void testMultithreadedWaitFor() throws Exception {
        HybridClock clock = new HybridClockImpl();

        PendingComparableValuesTracker<HybridTimestamp> tracker = new PendingComparableValuesTracker<>(clock.now());

        int threads = Runtime.getRuntime().availableProcessors();

        List<CompletableFuture<Void>> allFutures = Collections.synchronizedList(new ArrayList<>());

        int iterations = 1_000;

        runMultiThreaded(() -> {
            NavigableMap<HybridTimestamp, CompletableFuture<Void>> prevFutures = new TreeMap<>();

            ThreadLocalRandom random = ThreadLocalRandom.current();

            for (int i = 0; i < iterations; i++) {
                HybridTimestamp now = clock.now();

                tracker.update(now);

                HybridTimestamp timestampToWait =
                        new HybridTimestamp(now.getPhysical() + 1, now.getLogical() + random.nextInt(1000));

                CompletableFuture<Void> future = tracker.waitFor(timestampToWait);

                prevFutures.put(timestampToWait, future);

                allFutures.add(future);

                if (i % 10 == 0) {
                    SortedMap<HybridTimestamp, CompletableFuture<Void>> beforeNow = prevFutures.headMap(now, true);

                    beforeNow.forEach((t, f) -> assertThat(
                            "now=" + now + ", ts=" + t + ", trackerTs=" + tracker.current(),
                            f, willCompleteSuccessfully())
                    );

                    beforeNow.clear();
                }
            }

            return null;
        }, threads, "trackableHybridClockTest");

        tracker.update(HybridTimestamp.MAX_VALUE);

        assertThat(CompletableFuture.allOf(allFutures.toArray(CompletableFuture[]::new)), willCompleteSuccessfully());
    }

    @RepeatedTest(100)
    void testConcurrentAccess() {
        var tracker = new PendingComparableValuesTracker<>(1);

        var barrier = new CyclicBarrier(2);

        CompletableFuture<Void> writerFuture = CompletableFuture.runAsync(() -> {
            try {
                barrier.await();
                tracker.update(2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<Void> readerFuture = CompletableFuture.runAsync(() -> {
            try {
                barrier.await();
                tracker.waitFor(2).get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(writerFuture, willCompleteSuccessfully());
        assertThat(readerFuture, willCompleteSuccessfully());
    }

    @Test
    void testClose() {
        var tracker = new PendingComparableValuesTracker<>(1);

        CompletableFuture<Void> future0 = tracker.waitFor(2);

        tracker.close();

        assertThrows(TrackerClosedException.class, tracker::current);
        assertThrows(TrackerClosedException.class, () -> tracker.update(2));

        assertThat(future0, willThrowFast(TrackerClosedException.class));
        assertThat(tracker.waitFor(2), willThrowFast(TrackerClosedException.class));
    }
}
