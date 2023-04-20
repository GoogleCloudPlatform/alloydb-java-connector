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

import com.google.cloud.alloydb.v1beta.AlloyDBAdminClient;
import com.google.cloud.alloydb.v1beta.InstanceName;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ITDefaultConnectionInfoRepositoryTest {

  private DefaultConnectionInfoRepository defaultConnectionInfoRepository;
  private KeyPair keyPair;
  private AlloyDBAdminClient alloyDBAdminClient;
  private String instanceUri;
  private ScheduledThreadPoolExecutor executor;

  @Before
  public void setUp() throws Exception {
    instanceUri = System.getenv("ALLOYDB_INSTANCE_URI");

    KeyPairGenerator generator;
    try {
      generator = KeyPairGenerator.getInstance("RSA");
    } catch (NoSuchAlgorithmException err) {
      throw new RuntimeException(
          "Unable to initialize Cloud SQL socket factory because no RSA implementation is "
              + "available.");
    }
    generator.initialize(2048);

    keyPair = generator.generateKeyPair();
    executor = new ScheduledThreadPoolExecutor(1);
    alloyDBAdminClient = AlloyDBAdminClient.create();

    defaultConnectionInfoRepository = new DefaultConnectionInfoRepository(
        executor, alloyDBAdminClient);
  }

  @After
  public void tearDown() {
    alloyDBAdminClient.close();
    executor.shutdown();
  }

  @Test
  public void testGetConnectionInfo() {
    InstanceName instanceName = InstanceName.parse(instanceUri);
    ConnectionInfo connectionInfo =
        defaultConnectionInfoRepository.getConnectionInfo(instanceName, keyPair);

    assertThat(connectionInfo.getInstanceUid()).isNotEmpty();
    assertThat(connectionInfo.getIpAddress()).isNotEmpty();
    assertThat(connectionInfo.getClientCertificate()).isNotEmpty();
    assertThat(connectionInfo.getCertificateChain()).hasSize(2);
  }

  @Test
  public void testGentConnectionInfo_throwsException_forInvalidInstanceName() {
    InstanceName instanceName =
        InstanceName.parse("projects/BAD/locations/BAD/clusters/BAD/instances/BAD");
    Exception exception =
        assertThrows(
            Exception.class,
            () -> defaultConnectionInfoRepository.getConnectionInfo(instanceName, keyPair));

    assertThat(exception).hasMessageThat().contains("PERMISSION_DENIED");
  }
}
