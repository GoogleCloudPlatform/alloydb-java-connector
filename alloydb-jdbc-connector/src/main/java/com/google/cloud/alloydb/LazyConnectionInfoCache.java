/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb;

import com.google.cloud.alloydb.v1alpha.InstanceName;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.security.KeyPair;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LazyConnectionInfoCache implements ConnectionInfoCache {

  // Client timeout seconds is the number of seconds to wait for the future to resolve holding the
  // connection info data.
  public static final int CLIENT_TIMEOUT_SECONDS = 30;
  private final Logger logger = LoggerFactory.getLogger(LazyConnectionInfoCache.class);

  private final ConnectionInfoRepository connectionInfoRepo;
  private final InstanceName instanceURI;
  private final KeyPair clientConnectorKeyPair;

  private final Object connectionInfoGuard = new Object();

  @GuardedBy("connectionInfoGuard")
  private ConnectionInfo connectionInfo;

  @GuardedBy("connectionInfoGuard")
  private boolean closed;

  public LazyConnectionInfoCache(
      ConnectionInfoRepository connectionInfoRepo,
      InstanceName instanceURI,
      KeyPair clientConnectorKeyPair) {
    this.connectionInfoRepo = connectionInfoRepo;
    this.instanceURI = instanceURI;
    this.clientConnectorKeyPair = clientConnectorKeyPair;
  }

  @Override
  public ConnectionInfo getConnectionInfo() {
    synchronized (connectionInfoGuard) {
      if (closed) {
        throw new IllegalStateException(
            String.format("[%s] Lazy Refresh: Named connection closed.", instanceURI));
      }

      if (connectionInfo == null || needsRefresh(connectionInfo.getExpiration())) {
        logger.debug(
            String.format(
                "[%s] Lazy Refresh Operation: Client certificate needs refresh. Starting next "
                    + "refresh operation...",
                instanceURI));

        try {
          ListenableFuture<ConnectionInfo> infoFuture =
              connectionInfoRepo.getConnectionInfo(instanceURI, clientConnectorKeyPair);
          this.connectionInfo = infoFuture.get(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TerminalException e) {
          logger.debug(
              String.format(
                  "[%s] Lazy Refresh Operation: Failed with a terminal error.", instanceURI),
              e);
          throw e;
        } catch (Exception e) {
          throw new RuntimeException(
              String.format("[%s] Refresh Operation: Failed!", instanceURI), e);
        }
      }

      logger.debug(
          String.format(
              "[%s] Lazy Refresh Operation: Completed refresh with new certificate "
                  + "expiration at %s.",
              instanceURI, this.connectionInfo.getExpiration().toString()));
      return connectionInfo;
    }
  }

  private boolean needsRefresh(Instant expiration) {
    return Instant.now().isAfter(expiration.minus(RefreshCalculator.DEFAULT_REFRESH_BUFFER));
  }

  /** Force a new refresh of the instance data if the client certificate has expired. */
  @Override
  public void forceRefresh() {
    // invalidate connectionInfo so that the next call to getConectionInfo() will
    // fetch new data.
    synchronized (connectionInfoGuard) {
      if (closed) {
        throw new IllegalStateException(
            String.format("[%s] Lazy Refresh: Named connection closed.", instanceURI));
      }
      this.connectionInfo = null;
      logger.debug(String.format("[%s] Lazy Refresh Operation: Forced refresh.", instanceURI));
    }
  }

  /** Force a new refresh of the instance data if the client certificate has expired. */
  @Override
  public void refreshIfExpired() {
    synchronized (connectionInfoGuard) {
      if (closed) {
        throw new IllegalStateException(
            String.format("[%s] Lazy Refresh: Named connection closed.", instanceURI));
      }
    }
  }

  @Override
  public void close() {
    synchronized (connectionInfoGuard) {
      closed = true;
      logger.debug(String.format("[%s] Lazy Refresh Operation: Connector closed.", instanceURI));
    }
  }
}
