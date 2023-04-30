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

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.alloydb.v1beta.InstanceName;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import dev.failsafe.RateLimiter;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class ConnectionInfoCache {

  private final ScheduledExecutorService executor;
  private final ConnectionInfoRepository connectionInfoRepo;
  private final InstanceName instanceName;
  private final KeyPair keyPair;
  private final RateLimiter<Object> rateLimiter;

  private final Object connectionInfoLock = new Object();

  @GuardedBy("connectionInfoLock")
  private Future<ConnectionInfo> current;

  ConnectionInfoCache(
      ScheduledExecutorService executor,
      ConnectionInfoRepository connectionInfoRepo,
      InstanceName instanceName,
      KeyPair keyPair,
      RateLimiter<Object> rateLimiter) {
    this.executor = executor;
    this.connectionInfoRepo = connectionInfoRepo;
    this.instanceName = instanceName;
    this.keyPair = keyPair;
    this.rateLimiter = rateLimiter;
    synchronized (connectionInfoLock) {
      this.current = executor.submit(this::performRefresh);
    }
  }

  /** Returns the most recent connection info. */
  ConnectionInfo getConnectionInfo() {
    Future<ConnectionInfo> connectionInfoFuture;

    synchronized (connectionInfoLock) {
      connectionInfoFuture = this.current;
    }

    try {
      return Uninterruptibles.getUninterruptibly(connectionInfoFuture);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Retrieves connection info for the instance and schedules a refresh operation for the next
   * connection info.
   */
  private ConnectionInfo performRefresh()
      throws CertificateException, ExecutionException, InterruptedException {
    // Rate limit the speed of refresh operations.
    this.rateLimiter.acquirePermit();

    try {
      ConnectionInfo connectionInfo =
          this.connectionInfoRepo.getConnectionInfo(this.instanceName, this.keyPair);

      synchronized (connectionInfoLock) {
        current = Futures.immediateFuture(connectionInfo);
      }

      executor.schedule(this::performRefresh, 60 * 56 /* FIXME: 56 minutes */, TimeUnit.SECONDS);

      return connectionInfo;
    } catch (CertificateException | ExecutionException | InterruptedException e) {
      // For known exceptions, schedule a refresh immediately.
      executor.submit(this::performRefresh);
      throw e;
    } catch (RuntimeException e) {
      // If the exception is an ApiException, schedule a refresh immediately.
      // Otherwise, just throw the exception.
      Throwable cause = e.getCause();
      if (cause instanceof ApiException) {
        executor.submit(this::performRefresh);
      }
      throw e;
    }
  }
}
