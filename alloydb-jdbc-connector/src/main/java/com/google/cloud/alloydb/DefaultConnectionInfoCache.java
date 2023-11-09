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

import com.google.cloud.alloydb.v1.InstanceName;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.security.KeyPair;

/**
 * DefaultConnectionInfoCache is the cache used by default to hold connection info. In testing, this
 * class may be replaced with alternative implementations of ConnectionInfoCache.
 */
class DefaultConnectionInfoCache implements ConnectionInfoCache {

  private final Refresher refresher;

  private static final long DEFAULT_TIMEOUT_MS = 30000;

  DefaultConnectionInfoCache(
      ListeningScheduledExecutorService executor,
      ConnectionInfoRepository connectionInfoRepo,
      InstanceName instanceName,
      KeyPair clientConnectorKeyPair,
      long minRefreshDelayMs) {
    this.refresher =
        new Refresher(
            instanceName.toString(),
            executor,
            () -> connectionInfoRepo.getConnectionInfo(instanceName, clientConnectorKeyPair),
            new AsyncRateLimiter(minRefreshDelayMs));
  }

  /** Returns the most recent connection info. */
  @Override
  public ConnectionInfo getConnectionInfo() {
    return this.refresher.getConnectionInfo(DEFAULT_TIMEOUT_MS);
  }

  /**
   * Schedules a refresh to start immediately or if a refresh is already scheduled, makes it
   * available for getConnectionInfo().
   */
  @Override
  public void forceRefresh() {
    refresher.forceRefresh();
  }

  /** Closes the */
  @Override
  public void close() {
    refresher.close();
  }
}
