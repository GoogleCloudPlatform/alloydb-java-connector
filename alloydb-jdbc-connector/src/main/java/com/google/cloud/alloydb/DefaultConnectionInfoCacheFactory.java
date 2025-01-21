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

import com.google.cloud.alloydb.v1alpha.InstanceName;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.security.KeyPair;

/**
 * DefaultConnectionInfoCacheFactory encapsulates the creation of ConnectionInfoCache objects,
 * allowing for injecting doubles for test, and the default implementation in production code.
 */
class DefaultConnectionInfoCacheFactory implements ConnectionInfoCacheFactory {

  private final RefreshStrategy refreshStrategy;

  public DefaultConnectionInfoCacheFactory(RefreshStrategy refreshStrategy) {
    this.refreshStrategy = refreshStrategy;
  }

  @Override
  public ConnectionInfoCache create(
      ListeningScheduledExecutorService executor,
      ConnectionInfoRepository connectionInfoRepo,
      InstanceName instanceName,
      KeyPair clientConnectorKeyPair,
      long minRefreshDelayMs) {
    if (refreshStrategy == RefreshStrategy.LAZY) {
      return new LazyConnectionInfoCache(connectionInfoRepo, instanceName, clientConnectorKeyPair);
    }
    return new RefreshAheadConnectionInfoCache(
        executor, connectionInfoRepo, instanceName, clientConnectorKeyPair, minRefreshDelayMs);
  }
}
