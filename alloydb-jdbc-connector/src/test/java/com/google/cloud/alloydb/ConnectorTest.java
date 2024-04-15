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
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ConnectorTest {

  private static final String INSTANCE_NAME =
      "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>";
  private static final String PRIVATE_IP = "127.0.0.2";
  private static final String DNS_NAME = "localhost";
  private static final String SERVER_MESSAGE = "HELLO";
  private static final String ERROR_MESSAGE_NOT_FOUND = "Resource 'instance' was not found";
  private static final String ERROR_MESSAGE_PERMISSION_DENIED =
      "Location not found or access is unauthorized.";
  private static final String ERROR_MESSAGE_INTERNAL = "Internal Error";
  private static final String USER_AGENT = "unit tests";

  ListeningScheduledExecutorService defaultExecutor;

  @Before
  public void setUp() throws Exception {
    defaultExecutor = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(8));
  }

  @After
  public void tearDown() throws Exception {
    defaultExecutor.shutdownNow();
  }

  @Test
  public void create_successfulPrivateConnection()
      throws IOException, InterruptedException, NoSuchAlgorithmException, InvalidKeySpecException {
    FakeSslServer sslServer = new FakeSslServer(SERVER_MESSAGE);
    sslServer.start(PRIVATE_IP);

    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();

    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc(PRIVATE_IP, IpType.PRIVATE);
    Connector connector = newConnector(config.getConnectorConfig(), mock);
    Socket socket = connector.connect(config);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
    sslServer.stop();
  }

  @Test
  public void create_successfulPscConnection()
      throws IOException, InterruptedException, NoSuchAlgorithmException, InvalidKeySpecException {
    FakeSslServer sslServer = new FakeSslServer(SERVER_MESSAGE);
    sslServer.start(DNS_NAME);

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(InstanceName.parse(INSTANCE_NAME))
            .withIpType(IpType.PSC)
            .build();

    MockAlloyDBAdminGrpc mock = new MockAlloyDBAdminGrpc(DNS_NAME, IpType.PSC);
    Connector connector = newConnector(config.getConnectorConfig(), mock);
    Socket socket = connector.connect(config);

    assertThat(readLine(socket)).isEqualTo(SERVER_MESSAGE);
    sslServer.stop();
  }

  @Test
  public void create_throwsTerminalException_notFound()
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    MockAlloyDBAdminGrpc mock =
        new MockAlloyDBAdminGrpc(Code.NOT_FOUND.getNumber(), ERROR_MESSAGE_NOT_FOUND);

    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    TerminalException ex = assertThrows(TerminalException.class, () -> connector.connect(config));
    assertThat(ex).hasMessageThat().contains(ERROR_MESSAGE_NOT_FOUND);
  }

  @Test
  public void create_throwsTerminalException_notAuthorized()
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    MockAlloyDBAdminGrpc mock =
        new MockAlloyDBAdminGrpc(
            Code.PERMISSION_DENIED.getNumber(), ERROR_MESSAGE_PERMISSION_DENIED);

    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    TerminalException ex = assertThrows(TerminalException.class, () -> connector.connect(config));
    assertThat(ex).hasMessageThat().contains(ERROR_MESSAGE_PERMISSION_DENIED);
  }

  @Test
  public void create_throwsNonTerminalException_internalError()
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    MockAlloyDBAdminGrpc mock =
        new MockAlloyDBAdminGrpc(Code.INTERNAL.getNumber(), ERROR_MESSAGE_INTERNAL);

    ConnectionConfig config =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    Connector connector = newConnector(config.getConnectorConfig(), mock);

    RuntimeException ex = assertThrows(RuntimeException.class, () -> connector.connect(config));
    assertThat(ex).hasMessageThat().contains(ERROR_MESSAGE_INTERNAL);
  }

  private Connector newConnector(ConnectorConfig config, MockAlloyDBAdminGrpc mock)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
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
        new DefaultConnectionInfoCacheFactory(),
        new ConcurrentHashMap<>(),
        accessTokenSupplier,
        USER_AGENT);
  }

  private String readLine(Socket socket) throws IOException {
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
    return bufferedReader.readLine();
  }
}
