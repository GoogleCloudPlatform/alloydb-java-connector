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
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class ConnectionInfoCache {

  private final ScheduledExecutorService executor;
  private final ConnectionInfoRepository connectionInfoRepo;
  private final InstanceName instanceName;
  private final KeyPair clientConnectorKeyPair;
  private final RateLimiter<Object> rateLimiter;
  private final Object connectionInfoLock = new Object();
  private final RefreshCalculator refreshCalculator;

  @GuardedBy("connectionInfoLock")
  private Future<ConnectionInfo> current;

  @GuardedBy("connectionInfoLock")
  private Future<ConnectionInfo> next;

  ConnectionInfoCache(
      ScheduledExecutorService executor,
      ConnectionInfoRepository connectionInfoRepo,
      InstanceName instanceName,
      KeyPair clientConnectorKeyPair,
      RefreshCalculator refreshCalculator,
      RateLimiter<Object> rateLimiter) {
    this.executor = executor;
    this.connectionInfoRepo = connectionInfoRepo;
    this.instanceName = instanceName;
    this.clientConnectorKeyPair = clientConnectorKeyPair;
    this.refreshCalculator = refreshCalculator;
    this.rateLimiter = rateLimiter;
    synchronized (connectionInfoLock) {
      this.current = executor.submit(this::performRefresh);
      this.next = this.current;
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
          this.connectionInfoRepo.getConnectionInfo(this.instanceName, this.clientConnectorKeyPair);

      synchronized (connectionInfoLock) {
        current = Futures.immediateFuture(connectionInfo);
        next =
            executor.schedule(
                this::performRefresh,
                refreshCalculator.calculateSecondsUntilNextRefresh(
                    Instant.now(), connectionInfo.getClientCertificateExpiration()),
                TimeUnit.SECONDS);
      }

      return connectionInfo;
    } catch (CertificateException | ExecutionException | InterruptedException e) {
      // For known exceptions, schedule a refresh immediately.
      synchronized (connectionInfoLock) {
        next = executor.submit(this::performRefresh);
      }
      throw e;
    } catch (RuntimeException e) {
      // If the exception is an ApiException, schedule a refresh immediately.
      // Otherwise, just throw the exception.
      Throwable cause = e.getCause();
      if (cause instanceof ApiException) {
        synchronized (connectionInfoLock) {
          next = executor.submit(this::performRefresh);
        }
      }
      throw e;
    }
  }

  /**
   * Schedules a refresh to start immediately or if a refresh is already scheduled,
   * makes it available for getConnectionInfo().
   */
  void forceRefresh() {
    synchronized (connectionInfoLock) {
      // If a scheduled refresh hasn't started, perform one immediately.
      next.cancel(false);
      if (next.isCancelled()) {
        current = executor.submit(this::performRefresh);
        next = current;
      } else {
        // Otherwise it's already running, so just move next to current.
        current = next;
      }
    }
  }
}
