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

public class StubConnectionInfoCacheFactory implements ConnectionInfoCacheFactory {

  private final ConnectionInfoCache stubConnectionInfoCache;

  public StubConnectionInfoCacheFactory(StubConnectionInfoCache stubConnectionInfoCache) {
    this.stubConnectionInfoCache = stubConnectionInfoCache;
  }

  @Override
  public ConnectionInfoCache create(
      ListeningScheduledExecutorService executor,
      ConnectionInfoRepository connectionInfoRepo,
      InstanceName instanceName,
      KeyPair clientConnectorKeyPair,
      long minRefreshDelayMs) {
    return stubConnectionInfoCache;
  }
}
