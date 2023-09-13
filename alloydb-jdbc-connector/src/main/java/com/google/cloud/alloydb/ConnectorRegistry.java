/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.alloydb;

import com.google.cloud.alloydb.v1beta.AlloyDBAdminClient;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * ConnectorRegistry is a singleton that creates a single Executor, KeyPair, and AlloyDB Admin
 * Client for the lifetime of the SocketFactory. When callers are finished with the Connector, they
 * should use the ConnectorRegistry to shut down all the associated resources.
 */
public enum ConnectorRegistry implements Closeable {
  INSTANCE;

  private final ScheduledExecutorService executor;
  private final AlloyDBAdminClient alloyDBAdminClient;
  private final Connector connector;

  ConnectorRegistry() {
    // During refresh, each instance consumes 2 threads from the thread pool. By using 8 threads,
    // there should be enough free threads so that there will not be a deadlock. Most users
    // configure 3 or fewer instances, requiring 6 threads during refresh. By setting
    // this to 8, it's enough threads for most users, plus a safety factor of 2.
    this.executor = Executors.newScheduledThreadPool(8);
    try {
      alloyDBAdminClient = AlloyDBAdminClient.create();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    this.connector =
        new Connector(
            executor,
            new DefaultConnectionInfoRepository(executor, alloyDBAdminClient),
            RsaKeyPairGenerator.generateKeyPair(),
            new DefaultConnectionInfoCacheFactory(),
            new ConcurrentHashMap<>());
  }

  Connector getConnector() {
    return this.connector;
  }

  @Override
  public void close() {
    this.executor.shutdown();
    this.alloyDBAdminClient.close();
  }
}
