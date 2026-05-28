/*
 * Copyright 2024 Google LLC
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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.cloud.alloydb.v1alpha.InstanceName;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.rpc.Code;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ConnectorTest {

  private static final String INSTANCE_NAME =
      "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>";
  private static final String SERVER_MESSAGE = "HELLO";
  private static final String ERROR_MESSAGE_NOT_FOUND = "Resource 'instance' was not found";
  private static final String USER_AGENT = "unit tests";
  private static final String ERROR_MESSAGE_MDX_FAILED = "metadata exchange rejected";

  static ListeningScheduledExecutorService defaultExecutor;
  private static FakeSslServer sslServer;

  @BeforeClass
  public static void beforeClass() throws Exception {
    defaultExecutor = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(8));
    sslServer = new FakeSslServer(SERVER_MESSAGE);
    sslServer.start("127.0.0.1");
  }

  @After
  public void after() {
    // The SSL server is shared by every test in this class, so undo any per-test configuration.
    sslServer.succeedMetadataExchange();
  }

  @AfterClass
  public static void afterClass() {
    defaultExecutor.shutdownNow();
    sslServer.stop();
  }

  @Test
  public void create_successfulPrivateConnection() throws IOException {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    Socket socket = connector.connect(config);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  /**
   * End-to-end coverage of the Conscrypt path: a Conscrypt SSLContext, a Conscrypt trust manager, a
   * key manager from the default provider, SNI, HTTPS endpoint identification and the metadata
   * exchange all have to work together. See ConnectionSocketConscryptTest for the certificate
   * identity checks and the post-quantum group.
   */
  @Test
  public void create_successfulPrivateConnection_withConscrypt() throws IOException {
    NativeImage.assumeConscryptIsLoadable();
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(InstanceName.parse(INSTANCE_NAME))
            .withTlsProvider(TlsProvider.CONSCRYPT)
            .build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    Socket socket = connector.connect(config);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_throwsTerminalException() {
    MockAlloyDBAdminGrpc mock =
        new MockAlloyDBAdminGrpc(Code.NOT_FOUND.getNumber(), ERROR_MESSAGE_NOT_FOUND);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    TerminalException ex = assertThrows(TerminalException.class, () -> connector.connect(config));

    assertThat(ex).hasMessageThat().contains(ERROR_MESSAGE_NOT_FOUND);
  }

  @Test
  public void connect_forcesRefresh_whenMetadataExchangeFails() throws Exception {
    sslServer.failMetadataExchange(ERROR_MESSAGE_MDX_FAILED);
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    StubConnectionInfoCache stubConnectionInfoCache = newStubConnectionInfoCache("127.0.0.1");
    Connector connector =
        newConnector(
            config.getConnectorConfig(),
            mock,
            new StubConnectionInfoCacheFactory(stubConnectionInfoCache));

    MetadataExchangeException ex =
        assertThrows(MetadataExchangeException.class, () -> connector.connect(config));

    assertThat(ex).hasMessageThat().contains(ERROR_MESSAGE_MDX_FAILED);
    // Stale connection info is a likely cause of a failed metadata exchange, so the next
    // attempt should use refreshed connection info.
    assertThat(stubConnectionInfoCache.hasForceRefreshed()).isTrue();
  }

  @Test
  public void connect_doesNotForceRefresh_whenInstanceHasNoMatchingAddress() throws Exception {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    // An instance with no private IP address, when the config asks for one.
    StubConnectionInfoCache stubConnectionInfoCache = newStubConnectionInfoCache("");
    Connector connector =
        newConnector(
            config.getConnectorConfig(),
            mock,
            new StubConnectionInfoCacheFactory(stubConnectionInfoCache));

    UserConfigException ex =
        assertThrows(UserConfigException.class, () -> connector.connect(config));

    assertThat(ex).hasMessageThat().contains("does not have an address matching type");
    // A misconfigured connection fails the same way every time, so a refresh would not help.
    assertThat(stubConnectionInfoCache.hasForceRefreshed()).isFalse();
  }

  @Test
  public void connect_forcesRefresh_whenTlsHandshakeFails() throws Exception {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    // Connection info carrying a CA certificate that did not sign the server's certificate, so the
    // client rejects the server during the handshake.
    StubConnectionInfoCache stubConnectionInfoCache =
        newStubConnectionInfoCache(
            "127.0.0.1", TestCertificates.INSTANCE.getIntermediateCertificate());
    RecordingMetricRecorder recorder = new RecordingMetricRecorder();
    Connector connector =
        newConnector(
            config.getConnectorConfig(),
            mock,
            new StubConnectionInfoCacheFactory(stubConnectionInfoCache),
            recorder);

    assertThrows(SSLException.class, () -> connector.connect(config));

    // A certificate the client cannot validate is a likely sign of stale connection info.
    assertThat(stubConnectionInfoCache.hasForceRefreshed()).isTrue();
    assertThat(recorder.dialStatuses).containsExactly(TelemetryAttributes.DIAL_TLS_ERROR);
  }

  private StubConnectionInfoCache newStubConnectionInfoCache(String ipAddress) throws Exception {
    return newStubConnectionInfoCache(ipAddress, TestCertificates.INSTANCE.getRootCertificate());
  }

  private StubConnectionInfoCache newStubConnectionInfoCache(
      String ipAddress, X509Certificate caCertificate) throws Exception {
    KeyPair clientConnectorKeyPair = TestCertificates.INSTANCE.getClientKey();
    X509Certificate clientCertificate =
        TestCertificates.INSTANCE.getEphemeralCertificate(
            clientConnectorKeyPair.getPublic(), Instant.now().plus(1, ChronoUnit.HOURS));
    StubConnectionInfoCache stubConnectionInfoCache = new StubConnectionInfoCache();
    stubConnectionInfoCache.setConnectionInfo(
        new ConnectionInfo(
            ipAddress,
            ipAddress,
            "abcde.12345.us-central1.alloydb.goog",
            "some-instance",
            clientCertificate,
            // The ephemeral certificate leads the chain, as it does in a real connection.
            Arrays.asList(
                clientCertificate,
                TestCertificates.INSTANCE.getIntermediateCertificate(),
                TestCertificates.INSTANCE.getRootCertificate()),
            caCertificate));
    return stubConnectionInfoCache;
  }

  private Connector newConnector(ConnectorConfig config, MockAlloyDBAdminGrpc mock) {
    return newConnector(
        config, mock, new DefaultConnectionInfoCacheFactory(RefreshStrategy.REFRESH_AHEAD));
  }

  private Connector newConnector(
      ConnectorConfig config,
      MockAlloyDBAdminGrpc mock,
      ConnectionInfoCacheFactory connectionInfoCacheFactory,
      MetricRecorder metricRecorder) {
    ConcurrentHashMap<InstanceName, MetricRecorder> recorders = new ConcurrentHashMap<>();
    recorders.put(InstanceName.parse(INSTANCE_NAME), metricRecorder);
    return newConnector(config, mock, connectionInfoCacheFactory, recorders);
  }

  private Connector newConnector(
      ConnectorConfig config,
      MockAlloyDBAdminGrpc mock,
      ConnectionInfoCacheFactory connectionInfoCacheFactory) {
    return newConnector(config, mock, connectionInfoCacheFactory, new ConcurrentHashMap<>());
  }

  private Connector newConnector(
      ConnectorConfig config,
      MockAlloyDBAdminGrpc mock,
      ConnectionInfoCacheFactory connectionInfoCacheFactory,
      ConcurrentHashMap<InstanceName, MetricRecorder> metricRecorders) {
    CredentialFactoryProvider stubCredentialFactoryProvider =
        new CredentialFactoryProvider(new StubCredentialFactory());
    CredentialFactory instanceCredentialFactory =
        stubCredentialFactoryProvider.getInstanceCredentialFactory(config);
    ConnectionInfoRepositoryFactory connectionInfoRepositoryFactory =
        new StubConnectionInfoRepositoryFactory(defaultExecutor, mock);
    ConnectionInfoRepository connectionInfoRepository =
        connectionInfoRepositoryFactory.create(instanceCredentialFactory, config);
    AccessTokenSupplier accessTokenSupplier =
        new DefaultAccessTokenSupplier(instanceCredentialFactory);

    return new Connector(
        config,
        defaultExecutor,
        connectionInfoRepository,
        TestCertificates.INSTANCE.getClientKey(),
        connectionInfoCacheFactory,
        new ConcurrentHashMap<>(),
        accessTokenSupplier,
        USER_AGENT,
        metricRecorders);
  }

  private String readLine(Socket socket) throws IOException {
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
    return bufferedReader.readLine();
  }

  private static final class RecordingMetricRecorder implements MetricRecorder {

    boolean enabled;
    final List<String> dialStatuses = new ArrayList<>();
    final AtomicInteger openConnections = new AtomicInteger();
    final AtomicInteger closedConnections = new AtomicInteger();
    final AtomicLong bytesRx = new AtomicLong();
    final AtomicLong bytesTx = new AtomicLong();

    @Override
    public boolean isEnabled() {
      return enabled;
    }

    @Override
    public void shutdown() {}

    @Override
    public void recordDialCount(TelemetryAttributes attrs) {
      dialStatuses.add(attrs.getDialStatus());
    }

    @Override
    public void recordDialLatency(double latencyMs) {}

    @Override
    public void recordOpenConnection(TelemetryAttributes attrs) {
      openConnections.incrementAndGet();
    }

    @Override
    public void recordClosedConnection(TelemetryAttributes attrs) {
      closedConnections.incrementAndGet();
    }

    @Override
    public void recordBytesRx(long count) {
      bytesRx.addAndGet(count);
    }

    @Override
    public void recordBytesTx(long count) {
      bytesTx.addAndGet(count);
    }

    @Override
    public void recordRefreshCount(TelemetryAttributes attrs) {}
  }
}
