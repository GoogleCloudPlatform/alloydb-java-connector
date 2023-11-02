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
import com.google.cloud.alloydb.v1.InstanceName;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DefaultConnectionInfoCache is the cache used by default to hold connection info. In testing, this
 * class may be replaced with alternative implementations of ConnectionInfoCache.
 */
class DefaultConnectionInfoCache implements ConnectionInfoCache {

  private static final Logger logger = LoggerFactory.getLogger(DefaultConnectionInfoCache.class);

  private final ListeningScheduledExecutorService executor;
  private final ConnectionInfoRepository connectionInfoRepo;
  private final InstanceName instanceName;
  private final KeyPair clientConnectorKeyPair;
  private final RateLimiter rateLimiter;
  private final Object connectionInfoLock = new Object();
  private final RefreshCalculator refreshCalculator;

  @GuardedBy("connectionInfoLock")
  private Future<ConnectionInfo> current;

  @GuardedBy("connectionInfoLock")
  private Future<ConnectionInfo> next;

  @GuardedBy("connectionInfoLock")
  private boolean forceRefreshRunning;

  DefaultConnectionInfoCache(
      ListeningScheduledExecutorService executor,
      ConnectionInfoRepository connectionInfoRepo,
      InstanceName instanceName,
      KeyPair clientConnectorKeyPair,
      RefreshCalculator refreshCalculator,
      RateLimiter rateLimiter) {
    this.executor = executor;
    this.connectionInfoRepo = connectionInfoRepo;
    this.instanceName = instanceName;
    this.clientConnectorKeyPair = clientConnectorKeyPair;
    this.refreshCalculator = refreshCalculator;
    this.rateLimiter = rateLimiter;
    synchronized (connectionInfoLock) {
      // Assign to current and next to avoid null references.
      this.current = executor.submit(this::performRefresh);
      this.next = this.current;
    }
  }

  /** Returns the most recent connection info. */
  @Override
  public ConnectionInfo getConnectionInfo() {
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
    logger.info(
        String.format("[%s] Refresh Operation: Acquiring rate limiter permit.", instanceName));
    // Rate limit the speed of refresh operations.
    this.rateLimiter.acquire();
    logger.info(
        String.format(
            "[%s] Refresh Operation: Acquired rate limiter permit. Starting refresh...",
            instanceName));

    try {
      ConnectionInfo connectionInfo =
          this.connectionInfoRepo.getConnectionInfo(this.instanceName, this.clientConnectorKeyPair);
      logger.info(
          String.format(
              "[%s] Refresh Operation: Completed refresh with new certificate expiration at %s.",
              instanceName, connectionInfo.getClientCertificateExpiration().toString()));

      long secondsToRefresh =
          refreshCalculator.calculateSecondsUntilNextRefresh(
              Instant.now(), connectionInfo.getClientCertificateExpiration());
      logger.info(
          String.format(
              "[%s] Refresh Operation: Next operation scheduled at %s.",
              instanceName,
              Instant.now()
                  .plus(secondsToRefresh, ChronoUnit.SECONDS)
                  .truncatedTo(ChronoUnit.SECONDS)
                  .toString()));

      synchronized (connectionInfoLock) {
        current = Futures.immediateFuture(connectionInfo);
        next = executor.schedule(this::performRefresh, secondsToRefresh, TimeUnit.SECONDS);
        forceRefreshRunning = false;
      }

      return connectionInfo;
    } catch (CertificateException | ExecutionException | InterruptedException e) {
      logger.info(
          String.format(
              "[%s] Refresh Operation: Failed! Starting next refresh operation immediately.",
              instanceName),
          e);
      // For known exceptions, schedule a refresh immediately.
      synchronized (connectionInfoLock) {
        next = executor.submit(this::performRefresh);
      }
      throw e;
    } catch (RuntimeException e) {
      logger.info(String.format("[%s] Refresh Operation: Failed!", instanceName), e);
      // If the exception is an ApiException, schedule a refresh immediately
      // before re-throwing the exception.
      Throwable cause = e.getCause();
      if (cause instanceof ApiException) {
        logger.info(
            String.format("[%s] Starting next refresh operation immediately.", instanceName), e);
        synchronized (connectionInfoLock) {
          next = executor.submit(this::performRefresh);
        }
      }
      throw e;
    }
  }

  /**
   * Schedules a refresh to start immediately or if a refresh is already scheduled, makes it
   * available for getConnectionInfo().
   */
  @Override
  public void forceRefresh() {
    synchronized (connectionInfoLock) {
      // Don't force a refresh until the current forceRefresh operation
      // has produced a successful refresh.
      if (forceRefreshRunning) {
        logger.info(
            String.format(
                "[%s] Force Refresh: ignore this call as a refresh operation is currently in progress.",
                instanceName));
        return;
      }

      forceRefreshRunning = true;
      // If a scheduled refresh hasn't started, perform one immediately.
      next.cancel(false);
      logger.info(
          String.format(
              "[%s] Force Refresh: the next refresh operation was cancelled."
                  + " Scheduling new refresh operation immediately.",
              instanceName));
      next = executor.submit(this::performRefresh);
    }
  }
}
