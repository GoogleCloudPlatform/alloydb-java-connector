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

import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.alloydb.v1.InstanceName;
import com.google.common.base.Objects;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.net.ConnectException;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.net.ssl.SSLSocket;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ITConnectorTest {

  private String instanceName;
  private ListeningScheduledExecutorService executor;
  private ConnectionInfoRepositoryFactory connectionInfoRepositoryFactory;
  private ConnectionInfoRepository connectionInfoRepo;
  private CredentialFactoryProvider credentialFactoryProvider;
  private AccessTokenSupplier accessTokenSupplier;

  @Before
  public void setUp() throws IOException {
    executor = newTestExecutor();
    instanceName = System.getenv("ALLOYDB_INSTANCE_NAME");
    // Create the client once and close it later.
    ConnectorConfig config = new ConnectorConfig.Builder().build();
    credentialFactoryProvider = new CredentialFactoryProvider();
    CredentialFactory instanceCredentialFactory =
        credentialFactoryProvider.getInstanceCredentialFactory(config);
    connectionInfoRepositoryFactory = new DefaultConnectionInfoRepositoryFactory(executor);
    connectionInfoRepo = connectionInfoRepositoryFactory.create(instanceCredentialFactory, config);
    accessTokenSupplier = new DefaultAccessTokenSupplier(instanceCredentialFactory);
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  private ListeningScheduledExecutorService newTestExecutor() {
    ScheduledThreadPoolExecutor executor =
        (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2);
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    return MoreExecutors.listeningDecorator(
        MoreExecutors.getExitingScheduledExecutorService(executor));
  }

  @Test
  public void testConnect_createsSocketConnection() throws IOException {
    SSLSocket socket = null;
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(instanceName)).build();
    try {
      Connector connector =
          new Connector(
              config.getConnectorConfig(),
              executor,
              connectionInfoRepo,
              RsaKeyPairGenerator.generateKeyPair(),
              new DefaultConnectionInfoCacheFactory(),
              new ConcurrentHashMap<>(),
              accessTokenSupplier);

      socket = (SSLSocket) connector.connect(config);

      assertThat(socket.getKeepAlive()).isTrue();
      assertThat(socket.getTcpNoDelay()).isTrue();
    } finally {
      if (socket != null) {
        socket.close();
      }
    }
  }

  @Test
  public void testConnect_whenTlsHandshakeFails()
      throws IOException, CertificateException, OperatorCreationException {
    KeyPair clientConnectorKeyPair = RsaKeyPairGenerator.generateKeyPair();
    TestCertificates testCertificates = new TestCertificates();
    StubConnectionInfoCache stubConnectionInfoCache = new StubConnectionInfoCache();
    stubConnectionInfoCache.setConnectionInfo(
        new ConnectionInfo(
            "127.0.0.1", // localhost doesn't do TLS
            "some-instance",
            testCertificates.getEphemeralCertificate(
                clientConnectorKeyPair.getPublic(), Instant.now()),
            Arrays.asList(
                testCertificates.getIntermediateCertificate(),
                testCertificates.getRootCertificate()),
            testCertificates.getRootCertificate()));
    StubConnectionInfoCacheFactory connectionInfoCacheFactory =
        new StubConnectionInfoCacheFactory(stubConnectionInfoCache);
    SSLSocket socket = null;
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(instanceName)).build();

    try {
      Connector connector =
          new Connector(
              config.getConnectorConfig(),
              executor,
              connectionInfoRepo,
              clientConnectorKeyPair,
              connectionInfoCacheFactory,
              new ConcurrentHashMap<>(),
              accessTokenSupplier);
      socket = (SSLSocket) connector.connect(config);
    } catch (ConnectException ignore) {
      // The socket connect will fail because it's trying to connect to localhost with TLS certs.
      // So ignore the exception here.
    } finally {
      if (socket != null) {
        socket.close();
      }
      if (executor != null) {
        executor.shutdown();
      }
    }

    assertThat(stubConnectionInfoCache.hasForceRefreshed()).isTrue();
    assertThat(stubConnectionInfoCache.hasClosed()).isFalse();
  }

  @Test
  public void testEquals() {
    KeyPair clientConnectorKeyPair = RsaKeyPairGenerator.generateKeyPair();
    DefaultConnectionInfoCacheFactory connectionInfoCacheFactory =
        new DefaultConnectionInfoCacheFactory();
    ListeningScheduledExecutorService exec =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor());
    ConnectorConfig config = new ConnectorConfig.Builder().build();
    ConnectorConfig newConfig =
        new ConnectorConfig.Builder().withAdminServiceEndpoint("endpoint:3443").build();
    CredentialFactory instanceCredentialFactory =
        credentialFactoryProvider.getInstanceCredentialFactory(newConfig);
    ConnectionInfoRepository newConnectionInfoRepo =
        connectionInfoRepositoryFactory.create(instanceCredentialFactory, config);

    Connector a =
        new Connector(
            config,
            executor,
            connectionInfoRepo,
            clientConnectorKeyPair,
            connectionInfoCacheFactory,
            new ConcurrentHashMap<>(),
            accessTokenSupplier);

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                newConfig, // Different
                executor,
                connectionInfoRepo,
                clientConnectorKeyPair,
                connectionInfoCacheFactory,
                new ConcurrentHashMap<>(),
                accessTokenSupplier));

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                config,
                exec, // Different
                connectionInfoRepo,
                clientConnectorKeyPair,
                connectionInfoCacheFactory,
                new ConcurrentHashMap<>(),
                accessTokenSupplier));

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                config,
                executor,
                newConnectionInfoRepo, // Different
                clientConnectorKeyPair,
                connectionInfoCacheFactory,
                new ConcurrentHashMap<>(),
                accessTokenSupplier));

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                config,
                executor,
                connectionInfoRepo,
                RsaKeyPairGenerator.generateKeyPair(), // Different
                connectionInfoCacheFactory,
                new ConcurrentHashMap<>(),
                accessTokenSupplier));

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                config,
                executor,
                connectionInfoRepo,
                clientConnectorKeyPair,
                new DefaultConnectionInfoCacheFactory(), // Different
                new ConcurrentHashMap<>(),
                accessTokenSupplier));

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                config,
                executor,
                connectionInfoRepo,
                clientConnectorKeyPair,
                connectionInfoCacheFactory,
                new ConcurrentHashMap<>(),
                null)); // Different
  }

  @Test
  public void testHashCode() {
    KeyPair clientConnectorKeyPair = RsaKeyPairGenerator.generateKeyPair();
    DefaultConnectionInfoCacheFactory connectionInfoCacheFactory =
        new DefaultConnectionInfoCacheFactory();
    ConcurrentHashMap<ConnectionConfig, ConnectionInfoCache> instances = new ConcurrentHashMap<>();
    ConnectorConfig config = new ConnectorConfig.Builder().build();

    Connector a =
        new Connector(
            config,
            executor,
            connectionInfoRepo,
            clientConnectorKeyPair,
            connectionInfoCacheFactory,
            instances,
            accessTokenSupplier);

    assertThat(a.hashCode())
        .isEqualTo(
            Objects.hashCode(
                config,
                executor,
                connectionInfoRepo,
                clientConnectorKeyPair,
                connectionInfoCacheFactory,
                instances,
                accessTokenSupplier));
  }
}
