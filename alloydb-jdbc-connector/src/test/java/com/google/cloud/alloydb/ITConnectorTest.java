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

import com.google.cloud.alloydb.v1beta.AlloyDBAdminClient;
import com.google.cloud.alloydb.v1beta.InstanceName;
import java.io.IOException;
import java.net.ConnectException;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.net.ssl.SSLSocket;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.Before;
import org.junit.Test;

public class ITConnectorTest {

  private String instanceUri;

  @Before
  public void setUp() {
    instanceUri = System.getenv("ALLOYDB_INSTANCE_URI");
  }

  @Test
  public void testConnect_createsSocketConnection() throws IOException {
    SSLSocket socket = null;
    ScheduledThreadPoolExecutor executor = null;
    try (AlloyDBAdminClient alloyDBAdminClient = AlloyDBAdminClientFactory.create()) {
      executor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2);
      ConnectionInfoRepository connectionInfoRepository =
          new DefaultConnectionInfoRepository(executor, alloyDBAdminClient);
      Connector connector =
          new Connector(
              executor,
              connectionInfoRepository,
              RsaKeyPairGenerator.generateKeyPair(),
              new DefaultConnectionInfoCacheFactory());

      socket = (SSLSocket) connector.connect(InstanceName.parse(instanceUri));

      assertThat(socket.getKeepAlive()).isTrue();
      assertThat(socket.getTcpNoDelay()).isTrue();
    } finally {
      if (socket != null) {
        socket.close();
      }
      if (executor != null) {
        executor.shutdown();
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
                testCertificates.getRootCertificate())));
    StubConnectionInfoCacheFactory connectionInfoCacheFactory =
        new StubConnectionInfoCacheFactory(stubConnectionInfoCache);
    SSLSocket socket = null;
    ScheduledThreadPoolExecutor executor = null;

    try (AlloyDBAdminClient alloyDBAdminClient = AlloyDBAdminClientFactory.create()) {
      executor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2);
      Connector connector =
          new Connector(
              executor,
              new DefaultConnectionInfoRepository(executor, alloyDBAdminClient),
              clientConnectorKeyPair,
              connectionInfoCacheFactory);
      socket = (SSLSocket) connector.connect(InstanceName.parse(instanceUri));
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
  }
}
