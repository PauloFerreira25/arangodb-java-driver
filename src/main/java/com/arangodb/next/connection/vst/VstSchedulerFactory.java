/*
 * DISCLAIMER
 *
 * Copyright 2016 ArangoDB GmbH, Cologne, Germany
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright holder is ArangoDB GmbH, Cologne, Germany
 */

package com.arangodb.next.connection.vst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Michele Rastelli
 */
public class VstSchedulerFactory {

    static final String THREAD_PREFIX = "arango-vst";
    private static final Logger log = LoggerFactory.getLogger(VstSchedulerFactory.class);
    private static VstSchedulerFactory instance;

    private final int maxThreads;
    private final List<Scheduler> schedulers;
    private AtomicInteger cursor;

    private VstSchedulerFactory(int maxThreads) {
        this.maxThreads = maxThreads;
        schedulers = new ArrayList<>();
        cursor = new AtomicInteger();
    }

    public synchronized static VstSchedulerFactory getInstance(int maxThreads) {
        if (instance == null) {
            instance = new VstSchedulerFactory(maxThreads);
        }
        return instance;
    }

    synchronized Scheduler getScheduler() {
        int position = cursor.getAndIncrement();
        if (position < maxThreads) {
            log.debug("Creating single thread vst scheduler #{}", position);
            schedulers.add(Schedulers.newSingle(THREAD_PREFIX));
        }
        return schedulers.get(position % maxThreads);
    }

}