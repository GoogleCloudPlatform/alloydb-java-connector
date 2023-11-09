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

import java.util.concurrent.atomic.AtomicBoolean;

public class StubConnectionInfoCache implements ConnectionInfoCache {

  private final AtomicBoolean forceRefreshWasCalled = new AtomicBoolean(false);
  private final AtomicBoolean closeWasCalled = new AtomicBoolean(false);
  private ConnectionInfo connectionInfo;

  @Override
  public ConnectionInfo getConnectionInfo() {
    return connectionInfo;
  }

  @Override
  public void forceRefresh() {
    forceRefreshWasCalled.set(true);
  }

  @Override
  public void close() {
    closeWasCalled.set(true);
  }

  public boolean hasForceRefreshed() {
    return forceRefreshWasCalled.get();
  }

  public boolean hasClosed() {
    return closeWasCalled.get();
  }

  public void setConnectionInfo(ConnectionInfo connectionInfo) {
    this.connectionInfo = connectionInfo;
  }
}
