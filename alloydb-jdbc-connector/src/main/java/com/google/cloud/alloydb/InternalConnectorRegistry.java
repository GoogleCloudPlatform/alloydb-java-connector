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

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.cloud.alloydb.v1.AlloyDBAdminClient;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * InternalConnectorRegistry is a singleton that creates a single Executor, KeyPair, and AlloyDB
 * Admin Client for the lifetime of the SocketFactory. When callers are finished with the Connector,
 * they should use the InternalConnectorRegistry to shut down all the associated resources.
 */
enum InternalConnectorRegistry implements Closeable {
  INSTANCE;

  @SuppressWarnings("ImmutableEnumChecker")
  private final ListeningScheduledExecutorService executor;

  @SuppressWarnings("ImmutableEnumChecker")
  private ConcurrentHashMap<ConnectorConfig, Connector> unnamedConnectors;

  @SuppressWarnings("ImmutableEnumChecker")
  private ConcurrentHashMap<String, Connector> namedConnectors;

  InternalConnectorRegistry() {
    // During refresh, each instance consumes 2 threads from the thread pool. By using 8 threads,
    // there should be enough free threads so that there will not be a deadlock. Most users
    // configure 3 or fewer instances, requiring 6 threads during refresh. By setting
    // this to 8, it's enough threads for most users, plus a safety factor of 2.
    this.executor = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(8));
    this.unnamedConnectors = new ConcurrentHashMap<>();
    this.namedConnectors = new ConcurrentHashMap<>();
  }

  /**
   * Internal use only: Creates a socket representing a connection to a AlloyDB instance.
   *
   * @param config used to configure the connection.
   * @return the newly created Socket.
   * @throws IOException if error occurs during socket creation.
   */
  public Socket connect(ConnectionConfig config) throws IOException {
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
    if (this.namedConnectors.containsKey(name)) {
      throw new IllegalArgumentException("Named connection " + name + " exists.");
    }
    this.namedConnectors.put(name, createConnector(config));
  }

  /** Close a named connector, stopping the refresh process and removing it from the registry. */
  public void close(String name) {
    Connector connector = namedConnectors.remove(name);
    if (connector == null) {
      throw new IllegalArgumentException("Named connection " + name + " does not exist.");
    }
    connector.close();
  }

  /** Shutdown all connectors and remove the singleton instance. */
  public void shutdown() {
    this.unnamedConnectors.forEach((key, c) -> c.close());
    this.unnamedConnectors.clear();
    this.namedConnectors.forEach((key, c) -> c.close());
    this.namedConnectors.clear();
    this.executor.shutdown();
  }

  @Override
  public void close() {
    shutdown();
  }

  private Connector getConnector(ConnectionConfig config) {
    return unnamedConnectors.computeIfAbsent(
        config.getConnectorConfig(), k -> createConnector(config.getConnectorConfig()));
  }

  private Connector createConnector(ConnectorConfig config) {
    try {
      FixedCredentialsProvider credentialsProvider = CredentialsProviderFactory.create(config);
      AlloyDBAdminClient alloyDBAdminClient =
          AlloyDBAdminClientFactory.create(credentialsProvider, config.getAdminServiceEndpoint());

      return new Connector(
          config,
          executor,
          new DefaultConnectionInfoRepository(executor, alloyDBAdminClient),
          RsaKeyPairGenerator.generateKeyPair(),
          new DefaultConnectionInfoCacheFactory(),
          new ConcurrentHashMap<>());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Connector getNamedConnector(String name) {
    Connector connector = namedConnectors.get(name);
    if (connector == null) {
      throw new IllegalArgumentException("Named connection " + name + " does not exist.");
    }
    return connector;
  }
}
