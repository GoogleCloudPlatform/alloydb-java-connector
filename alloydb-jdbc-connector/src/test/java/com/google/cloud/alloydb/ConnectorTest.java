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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ConnectorTest {

  private static final String INSTANCE_NAME =
      "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>";
  private static final String SERVER_MESSAGE = "HELLO";
  private static final String ERROR_MESSAGE_NOT_FOUND = "Resource 'instance' was not found";
  private static final String USER_AGENT = "unit tests";

  static ListeningScheduledExecutorService defaultExecutor;
  private static FakeSslServer sslServer;

  @BeforeClass
  public static void beforeClass() throws Exception {
    defaultExecutor = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(8));
    sslServer = new FakeSslServer(SERVER_MESSAGE);
    sslServer.start("127.0.0.1");
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

  private Connector newConnector(ConnectorConfig config, MockAlloyDBAdminGrpc mock) {
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
        new DefaultConnectionInfoCacheFactory(RefreshStrategy.REFRESH_AHEAD),
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
