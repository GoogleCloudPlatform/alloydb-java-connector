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

import com.google.common.base.Objects;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyPair;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Connector {

  private static final Logger logger = LoggerFactory.getLogger(Connector.class);
  private static final long MIN_RATE_LIMIT_MS = 30000;

  private final ListeningScheduledExecutorService executor;
  private final ConnectionInfoRepository connectionInfoRepo;
  private final KeyPair clientConnectorKeyPair;
  private final ConnectionInfoCacheFactory connectionInfoCacheFactory;
  private final ConcurrentHashMap<ConnectionConfig, ConnectionInfoCache> instances;
  private final ConnectorConfig config;
  private final AccessTokenSupplier accessTokenSupplier;
  private final String userAgents;

  Connector(
      ConnectorConfig config,
      ListeningScheduledExecutorService executor,
      ConnectionInfoRepository connectionInfoRepo,
      KeyPair clientConnectorKeyPair,
      ConnectionInfoCacheFactory connectionInfoCacheFactory,
      ConcurrentHashMap<ConnectionConfig, ConnectionInfoCache> instances,
      AccessTokenSupplier accessTokenSupplier,
      String userAgents) {
    this.config = config;
    this.executor = executor;
    this.connectionInfoRepo = connectionInfoRepo;
    this.clientConnectorKeyPair = clientConnectorKeyPair;
    this.connectionInfoCacheFactory = connectionInfoCacheFactory;
    this.instances = instances;
    this.accessTokenSupplier = accessTokenSupplier;
    this.userAgents = userAgents;
  }

  public ConnectorConfig getConfig() {
    return config;
  }

  public void close() throws IOException {
    logger.debug("Close all connections and remove them from cache.");
    this.instances.forEach((key, c) -> c.close());
    this.instances.clear();
    this.connectionInfoRepo.close();
  }

  Socket connect(ConnectionConfig config) throws IOException {
    ConnectionInfoCache connectionInfoCache = getConnection(config);
    ConnectionInfo connectionInfo = connectionInfoCache.getConnectionInfo();

    try {
      ConnectionSocket socket =
          new ConnectionSocket(
              connectionInfo, config, clientConnectorKeyPair, accessTokenSupplier, userAgents);
      return socket.connect();
    } catch (IOException e) {
      logger.debug(
          String.format(
              "[%s] Socket connection failed! Trigger a refresh.", config.getInstanceName()));
      connectionInfoCache.forceRefresh();
      // The Socket methods above will throw an IOException or a SocketException (subclass of
      // IOException). Catch that exception, trigger a refresh, and then throw it again so
      // the caller sees the problem, but the connector will have a refreshed certificate on the
      // next invocation.
      throw e;
    }
  }

  ConnectionInfoCache getConnection(ConnectionConfig config) {
    ConnectionInfoCache instance =
        instances.computeIfAbsent(config, k -> createConnectionInfo(config));

    // If the client certificate has expired (as when the computer goes to
    // sleep, and the refresh cycle cannot run), force a refresh immediately.
    // The TLS handshake will not fail on an expired client certificate. It's
    // not until the first read where the client cert error will be surfaced.
    // So check that the certificate is valid before proceeding.
    instance.refreshIfExpired();

    return instance;
  }

  private ConnectionInfoCache createConnectionInfo(ConnectionConfig config) {
    logger.debug(String.format("[%s] Connection info added to cache.", config.getInstanceName()));
    return connectionInfoCacheFactory.create(
        this.executor,
        this.connectionInfoRepo,
        config.getInstanceName(),
        this.clientConnectorKeyPair,
        MIN_RATE_LIMIT_MS);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Connector)) {
      return false;
    }
    Connector that = (Connector) o;
    return Objects.equal(config, that.config)
        && Objects.equal(executor, that.executor)
        && Objects.equal(connectionInfoRepo, that.connectionInfoRepo)
        && Objects.equal(clientConnectorKeyPair, that.clientConnectorKeyPair)
        && Objects.equal(connectionInfoCacheFactory, that.connectionInfoCacheFactory)
        && Objects.equal(instances, that.instances)
        && Objects.equal(accessTokenSupplier, that.accessTokenSupplier)
        && Objects.equal(userAgents, that.userAgents);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        config,
        executor,
        connectionInfoRepo,
        clientConnectorKeyPair,
        connectionInfoCacheFactory,
        instances,
        accessTokenSupplier,
        userAgents);
  }
}
