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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InternalConnectorRegistry is a singleton that creates a single Executor, KeyPair, and AlloyDB
 * Admin Client for the lifetime of the SocketFactory. When callers are finished with the Connector,
 * they should use the InternalConnectorRegistry to shut down all the associated resources.
 */
enum InternalConnectorRegistry implements Closeable {
  INSTANCE;

  private static final Logger logger = LoggerFactory.getLogger(InternalConnectorRegistry.class);
  private static final String USER_AGENT = "alloydb-java-connector/" + Version.VERSION;

  @SuppressWarnings("ImmutableEnumChecker")
  private final ListeningScheduledExecutorService executor;

  @SuppressWarnings("ImmutableEnumChecker")
  private final ConcurrentHashMap<ConnectorConfig, Connector> unnamedConnectors;

  @SuppressWarnings("ImmutableEnumChecker")
  private final ConcurrentHashMap<String, Connector> namedConnectors;

  @SuppressWarnings("ImmutableEnumChecker")
  private final Object shutdownGuard = new Object();

  @SuppressWarnings("ImmutableEnumChecker")
  private final List<String> userAgents = new ArrayList<>();

  @SuppressWarnings("ImmutableEnumChecker")
  private CredentialFactoryProvider credentialFactoryProvider;

  @SuppressWarnings("ImmutableEnumChecker")
  @GuardedBy("shutdownGuard")
  private boolean shutdown = false;

  InternalConnectorRegistry() {
    this.executor =
        MoreExecutors.listeningDecorator(
            Executors.newScheduledThreadPool(
                2,
                r -> {
                  Thread t = new Thread(r);
                  t.setDaemon(true);
                  return t;
                }));
    this.unnamedConnectors = new ConcurrentHashMap<>();
    this.namedConnectors = new ConcurrentHashMap<>();
    this.credentialFactoryProvider = new CredentialFactoryProvider();
    this.addArtifactId(USER_AGENT);
  }

  /** Test use only: Set a new CredentialFactoryProvider */
  @VisibleForTesting
  void setCredentialFactoryProvider(CredentialFactoryProvider credentialFactoryProvider) {
    this.credentialFactoryProvider = credentialFactoryProvider;
  }

  /**
   * Internal use only: Creates a socket representing a connection to a AlloyDB instance.
   *
   * @param config used to configure the connection.
   * @return the newly created Socket.
   * @throws IOException if error occurs during socket creation.
   */
  public Socket connect(ConnectionConfig config) throws IOException {
    synchronized (shutdownGuard) {
      if (shutdown) {
        throw new IllegalStateException("ConnectorRegistry was shut down.");
      }
    }

    if (config.getNamedConnector() != null) {
      Connector connector = getNamedConnector(config.getNamedConnector());
      return connector.connect(config.withConnectorConfig(connector.getConfig()));
    }

    // Validate parameters
    Preconditions.checkArgument(
        config.getInstanceName() != null,
        "alloydbInstance property not set. Please specify this property in the JDBC URL or the "
            + "connection Properties");

    return getConnector(config).connect(config);
  }

  /** Register the configuration for a named connector. */
  public void register(String name, ConnectorConfig config) {
    synchronized (shutdownGuard) {
      if (shutdown) {
        throw new IllegalStateException("ConnectorRegistry was shut down.");
      }
    }

    if (this.namedConnectors.containsKey(name)) {
      throw new IllegalArgumentException("Named connection " + name + " exists.");
    }
    this.namedConnectors.put(name, createConnector(config));
  }

  /** Close a named connector, stopping the refresh process and removing it from the registry. */
  public void close(String name) {
    synchronized (shutdownGuard) {
      if (shutdown) {
        throw new IllegalStateException("ConnectorRegistry was shut down.");
      }
    }

    Connector connector = namedConnectors.remove(name);
    if (connector == null) {
      throw new IllegalArgumentException("Named connection " + name + " does not exist.");
    }
    try {
      connector.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Shutdown all connectors. */
  private void shutdownConnectors() {
    this.unnamedConnectors.forEach(
        (key, c) -> {
          try {
            c.close();
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
    this.unnamedConnectors.clear();
    this.namedConnectors.forEach(
        (key, c) -> {
          try {
            c.close();
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
    this.namedConnectors.clear();
  }

  @Override
  public void close() {
    shutdownInstance();
  }

  /** Calls shutdown on the singleton. */
  public void resetInstance() {
    shutdownConnectors();
  }

  /** Calls shutdown on the singleton. */
  public void shutdownInstance() {
    synchronized (shutdownGuard) {
      shutdown = true;
      shutdownConnectors();
      this.executor.shutdown();
    }
  }

  /**
   * Sets the default string which is appended to the AlloyDB Admin API client User-Agent header.
   */
  public void addArtifactId(String artifactId) {
    if (!userAgents.contains(artifactId)) {
      userAgents.add(artifactId);
    }
  }

  /**
   * Returns the default string which is appended to the AlloyDB Admin API client User-Agent header.
   */
  @VisibleForTesting
  String getUserAgents() {
    return String.join(" ", userAgents);
  }

  private Connector getConnector(ConnectionConfig config) {
    ConnectorConfig connectorConfig = config.getConnectorConfig();

    return unnamedConnectors.compute(
        connectorConfig,
        (k, existing) -> {
          if (existing == null) {
            // No connector was found. Create a new connector.
            return createConnector(connectorConfig);
          }

          if (existing.getConfig().isSshEnabled() && existing.isSshTunnelClosed()) {
            logger.info("SSH tunnel is closed, evicting connector to force reconnection.");
            try {
              existing.close();
            } catch (IOException e) {
              logger.warn("Error closing connector with closed SSH tunnel", e);
            }
            return createConnector(connectorConfig);
          }
          // No SSH tunnel is configured. Return the existing connector.
          return existing;
        });
  }

  private Connector createConnector(ConnectorConfig config) {
    CredentialFactory instanceCredentialFactory =
        credentialFactoryProvider.getInstanceCredentialFactory(config);
    DefaultConnectionInfoRepositoryFactory connectionInfoRepositoryFactory =
        new DefaultConnectionInfoRepositoryFactory(executor, getUserAgents());
    DefaultConnectionInfoRepository connectionInfoRepository =
        connectionInfoRepositoryFactory.create(instanceCredentialFactory, config);
    AccessTokenSupplier accessTokenSupplier =
        new DefaultAccessTokenSupplier(instanceCredentialFactory);

    SshTunnel sshTunnel = null;
    if (config.isSshEnabled()) {
      sshTunnel = SshTunnel.open(config.getSshTunnelConfig());
    }

    return new Connector(
        config,
        executor,
        connectionInfoRepository,
        RsaKeyPairGenerator.generateKeyPair(),
        new DefaultConnectionInfoCacheFactory(config.getRefreshStrategy()),
        new ConcurrentHashMap<>(),
        accessTokenSupplier,
        getUserAgents(),
        sshTunnel);
  }

  private Connector getNamedConnector(String name) {
    Connector connector =
        namedConnectors.computeIfPresent(
            name,
            (k, existing) -> {
              if (existing.getConfig().isSshEnabled() && existing.isSshTunnelClosed()) {
                logger.info("SSH tunnel is closed, recreating connector.");
                try {
                  existing.close();
                } catch (IOException e) {
                  logger.warn("Error closing connector with closed SSH tunnel", e);
                }
                // Recreate connector with fresh SSH tunnel.
                return createConnector(existing.getConfig());
              }
              return existing;
            });
    if (connector == null) {
      throw new IllegalArgumentException("Named connection " + name + " does not exist.");
    }
    return connector;
  }
}
