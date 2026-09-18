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
import com.google.common.base.Objects;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyPair;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLException;
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
  private final String clientUid;
  private final ConcurrentHashMap<InstanceName, MetricRecorder> metricRecorders;

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
    this.clientUid = UUID.randomUUID().toString();
    this.metricRecorders = new ConcurrentHashMap<>();
  }

  public ConnectorConfig getConfig() {
    return config;
  }

  public void close() throws IOException {
    logger.debug("Close all connections and remove them from cache.");
    this.instances.forEach((key, c) -> c.close());
    this.instances.clear();
    this.connectionInfoRepo.close();
    // Shut down all metric recorders. One recorder failing to shut down must not leave the rest
    // of them, and the gRPC channel they share, running.
    this.metricRecorders.forEach(
        (key, mr) -> {
          try {
            mr.shutdown();
          } catch (RuntimeException e) {
            logger.debug(String.format("[%s] Metric recorder failed to shut down.", key), e);
          }
        });
    this.metricRecorders.clear();
  }

  Socket connect(ConnectionConfig config) throws IOException {
    long startNanos = System.nanoTime();

    InstanceName instanceName = config.getInstanceName();
    MetricRecorder metricRecorder = getMetricRecorder(instanceName);

    boolean iamAuthn = config.getAuthType() == AuthType.IAM;
    // Whether the connector already held connection info for this instance before this dial.
    boolean cacheHit = instances.containsKey(config);

    ConnectionInfoCache connectionInfoCache = getConnection(config, metricRecorder);
    ConnectionInfo connectionInfo;
    try {
      connectionInfo = connectionInfoCache.getConnectionInfo();
    } catch (RuntimeException e) {
      recordDial(metricRecorder, iamAuthn, cacheHit, TelemetryAttributes.DIAL_CACHE_ERROR);
      throw e;
    }

    try {
      ConnectionSocket socket =
          new ConnectionSocket(
              connectionInfo, config, clientConnectorKeyPair, accessTokenSupplier, userAgents);
      Socket s = socket.connect();

      // When telemetry is disabled there is nothing to count, so hand back the socket itself
      // rather than paying for an instrumented wrapper on every read and write.
      Socket result = s;
      TelemetryAttributes connectionAttrs = null;
      if (metricRecorder.isEnabled()) {
        connectionAttrs = TelemetryAttributes.forConnection(iamAuthn);
        try {
          result = new InstrumentedSocket(s, metricRecorder, connectionAttrs);
        } catch (SocketException e) {
          s.close();
          throw e;
        }
      }

      TelemetryAttributes dialAttrs =
          TelemetryAttributes.forDial(iamAuthn, cacheHit, TelemetryAttributes.DIAL_SUCCESS);
      metricRecorder.recordDialCount(dialAttrs);
      metricRecorder.recordDialLatency((System.nanoTime() - startNanos) / 1_000_000.0);
      if (connectionAttrs != null) {
        metricRecorder.recordOpenConnection(connectionAttrs);
      }
      return result;
    } catch (SSLException e) {
      logger.debug(String.format("[%s] TLS handshake failed! Trigger a refresh.", instanceName));
      recordDial(metricRecorder, iamAuthn, cacheHit, TelemetryAttributes.DIAL_TLS_ERROR);
      connectionInfoCache.forceRefresh();
      throw e;
    } catch (UserConfigException e) {
      logger.debug(
          String.format("[%s] Connection failed due to user configuration error.", instanceName));
      recordDial(metricRecorder, iamAuthn, cacheHit, TelemetryAttributes.DIAL_USER_ERROR);
      throw e;
    } catch (MetadataExchangeException e) {
      logger.debug(
          String.format("[%s] Metadata exchange failed! Trigger a refresh.", instanceName));
      recordDial(metricRecorder, iamAuthn, cacheHit, TelemetryAttributes.DIAL_MDX_ERROR);
      connectionInfoCache.forceRefresh();
      throw e;
    } catch (IOException e) {
      logger.debug(
          String.format("[%s] Socket connection failed! Trigger a refresh.", instanceName));
      recordDial(metricRecorder, iamAuthn, cacheHit, TelemetryAttributes.DIAL_TCP_ERROR);
      // The Socket methods above will throw an IOException or a SocketException (subclass of
      // IOException). Catch that exception, trigger a refresh, and then throw it again so
      // the caller sees the problem, but the connector will have a refreshed certificate on the
      // next invocation.
      connectionInfoCache.forceRefresh();
      throw e;
    }
  }

  private static void recordDial(
      MetricRecorder metricRecorder, boolean iamAuthn, boolean cacheHit, String status) {
    metricRecorder.recordDialCount(TelemetryAttributes.forDial(iamAuthn, cacheHit, status));
  }

  ConnectionInfoCache getConnection(ConnectionConfig config, MetricRecorder metricRecorder) {
    ConnectionInfoCache instance =
        instances.computeIfAbsent(config, k -> createConnectionInfo(config, metricRecorder));

    // If the client certificate has expired (as when the computer goes to
    // sleep, and the refresh cycle cannot run), force a refresh immediately.
    // The TLS handshake will not fail on an expired client certificate. It's
    // not until the first read where the client cert error will be surfaced.
    // So check that the certificate is valid before proceeding.
    instance.refreshIfExpired();

    return instance;
  }

  private ConnectionInfoCache createConnectionInfo(
      ConnectionConfig config, MetricRecorder metricRecorder) {
    logger.debug(String.format("[%s] Connection info added to cache.", config.getInstanceName()));

    InstanceName instanceName = config.getInstanceName();

    return connectionInfoCacheFactory.create(
        this.executor,
        this.connectionInfoRepo,
        instanceName,
        this.clientConnectorKeyPair,
        MIN_RATE_LIMIT_MS,
        metricRecorder);
  }

  private MetricRecorder getMetricRecorder(InstanceName instanceName) {
    MetricRecorder existing = metricRecorders.get(instanceName);
    if (existing != null) {
      return existing;
    }
    // Building a recorder stands up a gRPC channel, which is far too much work to do inside
    // computeIfAbsent while holding a bin lock. Build it outside the map and discard the loser of
    // any race instead.
    MetricRecorder created =
        MetricRecorderFactory.newMetricRecorder(
            this.config.isEnableBuiltinTelemetry(),
            instanceName.getProject(),
            instanceName.getLocation(),
            instanceName.getCluster(),
            instanceName.getInstance(),
            clientUid);
    MetricRecorder raced = metricRecorders.putIfAbsent(instanceName, created);
    if (raced != null) {
      created.shutdown();
      return raced;
    }
    return created;
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
