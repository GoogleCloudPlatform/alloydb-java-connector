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
import java.util.concurrent.ScheduledThreadPoolExecutor;
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

  /** Every connector a test builds, so that {@link #after} closes it. */
  private final List<Connector> connectors = new ArrayList<>();

  @BeforeClass
  public static void beforeClass() throws Exception {
    defaultExecutor = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(8));
    sslServer = new FakeSslServer(SERVER_MESSAGE);
    sslServer.start("127.0.0.1");
  }

  @After
  public void after() throws IOException {
    try {
      // A connector left open keeps its connection info cache and its metric recorders, and the
      // socket-tracker task that counting a connection schedules on the shared executor.
      for (Connector connector : connectors) {
        connector.close();
      }
    } finally {
      connectors.clear();
      // The SSL server is shared by every test in this class, so undo any per-test configuration.
      sslServer.succeedMetadataExchange();
    }
  }

  @AfterClass
  public static void afterClass() {
    defaultExecutor.shutdownNow();
    sslServer.stop();
  }

  @Test
  public void create_successfulPrivateConnection() throws IOException {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config = newTelemetryDisabledConfig();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    Socket socket = connector.connect(config);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
  }

  @Test
  public void create_throwsTerminalException() {
    MockAlloyDBAdminGrpc mock =
        new MockAlloyDBAdminGrpc(Code.NOT_FOUND.getNumber(), ERROR_MESSAGE_NOT_FOUND);
    ConnectionConfig config = newTelemetryDisabledConfig();
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

  @Test
  public void connect_returnsThePlainSocket_whenMetricsAreDisabled() throws IOException {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config = newTelemetryDisabledConfig();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    Socket socket = connector.connect(config);

    // An application that has opted out of telemetry pays nothing on its reads and writes.
    assertThat(socket).isNotInstanceOf(InstrumentedSocket.class);
    socket.close();
  }

  @Test
  public void connect_countsTheConnection_whenMetricsAreEnabled() throws Exception {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    RecordingMetricRecorder recorder = new RecordingMetricRecorder();
    recorder.enabled = true;
    Connector connector =
        newConnector(
            config.getConnectorConfig(),
            mock,
            new DefaultConnectionInfoCacheFactory(RefreshStrategy.REFRESH_AHEAD),
            recorder);

    Socket socket = connector.connect(config);

    assertThat(socket).isInstanceOf(InstrumentedSocket.class);
    assertThat(recorder.openConnections.get()).isEqualTo(1);
    assertThat(recorder.closedConnections.get()).isEqualTo(0);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
    socket.close();

    assertThat(recorder.closedConnections.get()).isEqualTo(1);
    assertThat(recorder.bytesRx.get()).isAtLeast(SERVER_MESSAGE.length());
  }

  /**
   * A metric recorder that throws is not a failed dial. The connector's own dial exceptions are
   * unchecked, so recording the success inside the handlers that classify a failure let a broken
   * recorder be counted as a failed dial and force a needless refresh of connection info that was
   * perfectly good.
   */
  @Test
  public void connect_doesNotRecordAFailedDial_whenRecordingTheSuccessThrows() throws Exception {
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    StubConnectionInfoCache stubConnectionInfoCache = newStubConnectionInfoCache("127.0.0.1");
    RecordingMetricRecorder recorder = new RecordingMetricRecorder();
    recorder.failDialLatency = true;
    Connector connector =
        newConnector(
            config.getConnectorConfig(),
            mock,
            new StubConnectionInfoCacheFactory(stubConnectionInfoCache),
            recorder);

    assertThrows(MetadataExchangeException.class, () -> connector.connect(config));

    // The dial itself succeeded, so that is what it is recorded as, exactly once.
    assertThat(recorder.dialStatuses).containsExactly(TelemetryAttributes.DIAL_SUCCESS);
    assertThat(stubConnectionInfoCache.hasForceRefreshed()).isFalse();
  }

  @Test
  public void connect_schedulesTheSocketTracker_onlyOnceThereIsAConnectionToCount()
      throws Exception {
    ScheduledThreadPoolExecutor raw =
        (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2);
    // The stub cache schedules no refresh, so the socket tracker is the only thing that can put a
    // task on this executor.
    ConnectionInfoCacheFactory connectionInfoCacheFactory =
        new StubConnectionInfoCacheFactory(newStubConnectionInfoCache("127.0.0.1"));
    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc("127.0.0.1", IpType.PRIVATE);
    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    RecordingMetricRecorder recorder = new RecordingMetricRecorder();
    try {
      Connector connector =
          newConnector(
              config.getConnectorConfig(),
              mock,
              connectionInfoCacheFactory,
              recorderFor(recorder),
              MoreExecutors.listeningDecorator(raw));

      // An application that has opted out of telemetry has nothing to report, so it must not pay a
      // repeating task on the executor it shares with certificate refresh.
      Socket plain = connector.connect(config);
      plain.close();
      assertThat(raw.getQueue()).isEmpty();

      recorder.enabled = true;
      Socket counted = connector.connect(config);

      // The first counted connection is what the periodic report exists for.
      assertThat(raw.getQueue()).isNotEmpty();
      counted.close();
    } finally {
      raw.shutdownNow();
    }
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

  /**
   * Returns a config with built-in telemetry turned off, so that a test exercising the connection
   * path alone does not stand up a Cloud Monitoring exporter.
   */
  private ConnectionConfig newTelemetryDisabledConfig() {
    return new ConnectionConfig.Builder()
        .withInstanceName(InstanceName.parse(INSTANCE_NAME))
        .withConnectorConfig(
            new ConnectorConfig.Builder().withEnableBuiltinTelemetry(false).build())
        .build();
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
    return newConnector(config, mock, connectionInfoCacheFactory, recorderFor(metricRecorder));
  }

  private ConcurrentHashMap<InstanceName, MetricRecorder> recorderFor(MetricRecorder recorder) {
    ConcurrentHashMap<InstanceName, MetricRecorder> recorders = new ConcurrentHashMap<>();
    recorders.put(InstanceName.parse(INSTANCE_NAME), recorder);
    return recorders;
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
    return newConnector(config, mock, connectionInfoCacheFactory, metricRecorders, defaultExecutor);
  }

  private Connector newConnector(
      ConnectorConfig config,
      MockAlloyDBAdminGrpc mock,
      ConnectionInfoCacheFactory connectionInfoCacheFactory,
      ConcurrentHashMap<InstanceName, MetricRecorder> metricRecorders,
      ListeningScheduledExecutorService executor) {
    CredentialFactoryProvider stubCredentialFactoryProvider =
        new CredentialFactoryProvider(new StubCredentialFactory());
    CredentialFactory instanceCredentialFactory =
        stubCredentialFactoryProvider.getInstanceCredentialFactory(config);
    ConnectionInfoRepositoryFactory connectionInfoRepositoryFactory =
        new StubConnectionInfoRepositoryFactory(executor, mock);
    ConnectionInfoRepository connectionInfoRepository =
        connectionInfoRepositoryFactory.create(instanceCredentialFactory, config);
    AccessTokenSupplier accessTokenSupplier =
        new DefaultAccessTokenSupplier(instanceCredentialFactory);

    Connector connector =
        new Connector(
            config,
            executor,
            connectionInfoRepository,
            TestCertificates.INSTANCE.getClientKey(),
            connectionInfoCacheFactory,
            new ConcurrentHashMap<>(),
            accessTokenSupplier,
            USER_AGENT,
            metricRecorders);
    connectors.add(connector);
    return connector;
  }

  private String readLine(Socket socket) throws IOException {
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
    return bufferedReader.readLine();
  }
}
