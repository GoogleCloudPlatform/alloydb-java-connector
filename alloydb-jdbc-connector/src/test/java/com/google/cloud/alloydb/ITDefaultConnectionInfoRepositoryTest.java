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
import static org.junit.Assert.assertThrows;

import com.google.cloud.alloydb.v1alpha.InstanceName;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ITDefaultConnectionInfoRepositoryTest {

  private ConnectionInfoRepository defaultConnectionInfoRepository;
  private KeyPair keyPair;
  private String instanceUri;
  private ListeningScheduledExecutorService executor;

  @Before
  public void setUp() throws Exception {
    instanceUri = System.getenv("ALLOYDB_INSTANCE_NAME");

    KeyPairGenerator generator;
    try {
      generator = KeyPairGenerator.getInstance("RSA");
    } catch (NoSuchAlgorithmException err) {
      throw new RuntimeException(
          "Unable to initialize AlloyDB socket factory because no RSA implementation is "
              + "available.");
    }
    generator.initialize(2048);

    keyPair = generator.generateKeyPair();
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor());
    ConnectorConfig config = new ConnectorConfig.Builder().build();
    CredentialFactoryProvider credentialFactoryProvider = new CredentialFactoryProvider();
    CredentialFactory instanceCredentialFactory =
        credentialFactoryProvider.getInstanceCredentialFactory(config);
    ConnectionInfoRepositoryFactory connectionInfoRepositoryFactory =
        new DefaultConnectionInfoRepositoryFactory(executor);
    defaultConnectionInfoRepository =
        connectionInfoRepositoryFactory.create(instanceCredentialFactory, config);
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  @Test
  public void testGetConnectionInfo()
      throws ExecutionException, InterruptedException, CertificateException {
    InstanceName instanceName = InstanceName.parse(instanceUri);
    ListenableFuture<ConnectionInfo> f =
        defaultConnectionInfoRepository.getConnectionInfo(instanceName, keyPair);

    ConnectionInfo connectionInfo = f.get();

    assertThat(connectionInfo.getInstanceUid()).isNotEmpty();
    assertThat(connectionInfo.getIpAddress()).isNotEmpty();
    assertThat(connectionInfo.getPublicIpAddress()).isNotEmpty();
    assertThat(connectionInfo.getClientCertificate()).isNotNull();
    assertThat(connectionInfo.getCertificateChain()).hasSize(3);
  }

  @Test
  public void testGentConnectionInfo_throwsException_forInvalidInstanceName() {
    InstanceName instanceName =
        InstanceName.parse("projects/BAD/locations/BAD/clusters/BAD/instances/BAD");
    Exception exception =
        assertThrows(
            Exception.class,
            () -> defaultConnectionInfoRepository.getConnectionInfo(instanceName, keyPair).get());

    assertThat(exception).hasMessageThat().contains("PERMISSION_DENIED");
  }
}
