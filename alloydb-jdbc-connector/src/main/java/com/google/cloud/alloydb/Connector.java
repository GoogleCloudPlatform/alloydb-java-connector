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
import java.security.KeyPair;
import javax.net.ssl.SSLException;
import java.util.UUID;
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
  private final String clientUid;
  private final ConcurrentHashMap<String, MetricRecorder> metricRecorders;

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
    // Shut down all metric recorders
    this.metricRecorders.forEach((key, mr) -> mr.shutdown());
    this.metricRecorders.clear();
  }

  Socket connect(ConnectionConfig config) throws IOException {
    long startTimeMs = System.nanoTime() / 1_000_000;

    InstanceName instanceName = config.getInstanceName();
    MetricRecorder metricRecorder = getMetricRecorder(instanceName);

    TelemetryAttributes attrs = new TelemetryAttributes();
    attrs.setIamAuthn(config.getAuthType() == AuthType.IAM);
    attrs.setRefreshType(
        this.config.getRefreshStrategy() == RefreshStrategy.LAZY
            ? TelemetryAttributes.REFRESH_LAZY_TYPE
            : TelemetryAttributes.REFRESH_AHEAD_TYPE);

    // Check if this instance was already cached
    boolean cacheHit = instances.containsKey(config);
    attrs.setCacheHit(cacheHit);

    ConnectionInfoCache connectionInfoCache = getConnection(config, metricRecorder);
    ConnectionInfo connectionInfo;
    try {
      connectionInfo = connectionInfoCache.getConnectionInfo();
    } catch (Exception e) {
      attrs.setDialStatus(TelemetryAttributes.DIAL_CACHE_ERROR);
      metricRecorder.recordDialCount(attrs);
      throw e;
    }

    try {
      ConnectionSocket socket =
          new ConnectionSocket(
              connectionInfo, config, clientConnectorKeyPair, accessTokenSupplier, userAgents);
      Socket s = socket.connect();

      // Record successful dial metrics
      attrs.setDialStatus(TelemetryAttributes.DIAL_SUCCESS);
      long latencyMs = (System.nanoTime() / 1_000_000) - startTimeMs;
      metricRecorder.recordDialCount(attrs);
      metricRecorder.recordDialLatency((double) latencyMs, attrs);
      metricRecorder.recordOpenConnection(attrs);

      return new InstrumentedSocket(s, metricRecorder, attrs);
    } catch (SSLException e) {
      logger.debug(
          String.format(
              "[%s] TLS handshake failed! Trigger a refresh.", config.getInstanceName()));
      attrs.setDialStatus(TelemetryAttributes.DIAL_TLS_ERROR);
      metricRecorder.recordDialCount(attrs);
      connectionInfoCache.forceRefresh();
      throw e;
    } catch (UserConfigException e) {
      logger.debug(
          String.format(
              "[%s] Connection failed due to user configuration error.", config.getInstanceName()));
      attrs.setDialStatus(TelemetryAttributes.DIAL_USER_ERROR);
      metricRecorder.recordDialCount(attrs);
      throw e;
    } catch (MetadataExchangeException e) {
      logger.debug(
          String.format(
              "[%s] Metadata exchange failed! Trigger a refresh.", config.getInstanceName()));
      attrs.setDialStatus(TelemetryAttributes.DIAL_MDX_ERROR);
      metricRecorder.recordDialCount(attrs);
      connectionInfoCache.forceRefresh();
      throw e;
    } catch (IOException e) {
      logger.debug(
          String.format(
              "[%s] Socket connection failed! Trigger a refresh.", config.getInstanceName()));
      attrs.setDialStatus(TelemetryAttributes.DIAL_TCP_ERROR);
      metricRecorder.recordDialCount(attrs);
      connectionInfoCache.forceRefresh();
      throw e;
    }
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

  private ConnectionInfoCache createConnectionInfo(ConnectionConfig config,
      MetricRecorder metricRecorder) {
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
    String key = instanceName.toString();
    return metricRecorders.computeIfAbsent(
        key,
        k ->
            MetricRecorderFactory.newMetricRecorder(
                this.config.isEnableBuiltinTelemetry(),
                instanceName.getProject(),
                instanceName.getLocation(),
                instanceName.getCluster(),
                instanceName.getInstance(),
                clientUid
            ));
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
