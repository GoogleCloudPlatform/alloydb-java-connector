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

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.alloydb.v1.InstanceName;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ConnectionInfoCacheTest {

  private static final String TEST_INSTANCE_IP = "10.0.0.1";
  private static final String TEST_INSTANCE_ID = "some-instance-id";
  private static final Instant ONE_HOUR_FROM_NOW = Instant.now().plus(1, ChronoUnit.HOURS);
  private InstanceName instanceName;
  private KeyPair keyPair;
  private TestCertificates testCertificates;
  private static final long TEST_TIMEOUT_MS = 1000L;
  ListeningScheduledExecutorService executor;

  @Before
  public void setUp() throws CertificateException, IOException, OperatorCreationException {
    instanceName =
        InstanceName.parse(
            "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>");
    keyPair = RsaKeyPairGenerator.generateKeyPair();
    testCertificates = new TestCertificates();
    ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(4);
    exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    exec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    executor = MoreExecutors.listeningDecorator(exec);
  }

  @After
  public void after() {
    executor.shutdown();
  }

  @Test
  public void testGetConnectionInfo_returnsConnectionInfo() {
    InMemoryConnectionInfoRepo connectionInfoRepo = new InMemoryConnectionInfoRepo();
    connectionInfoRepo.addResponses(
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), ONE_HOUR_FROM_NOW),
                Arrays.asList(
                    testCertificates.getIntermediateCertificate(),
                    testCertificates.getRootCertificate()),
                testCertificates.getRootCertificate()));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            MoreExecutors.listeningDecorator(executor),
            connectionInfoRepo,
            instanceName,
            keyPair,
            TEST_TIMEOUT_MS);

    ConnectionInfo connectionInfo = connectionInfoCache.getConnectionInfo();

    assertThat(connectionInfo.getIpAddress()).isEqualTo(TEST_INSTANCE_IP);
    assertThat(connectionInfo.getInstanceUid()).isEqualTo(TEST_INSTANCE_ID);
    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(ONE_HOUR_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));
    List<X509Certificate> certificateChain = connectionInfo.getCertificateChain();
    assertThat(certificateChain).hasSize(2);
  }

  @Test
  public void testGetConnectionInfo_scheduledNextOperationImmediately_onApiException() {

    InMemoryConnectionInfoRepo connectionInfoRepo = new InMemoryConnectionInfoRepo();
    List<X509Certificate> certificateChain =
        Arrays.asList(
            testCertificates.getIntermediateCertificate(), testCertificates.getRootCertificate());
    connectionInfoRepo.addResponses(
        () -> {
          throw new ApiException(
              "API interaction failed",
              new Throwable("the cause"),
              new StatusCode() {
                @Override
                public Code getCode() {
                  return Code.UNKNOWN;
                }

                @Override
                public Object getTransportCode() {
                  return null;
                }
              },
              true);
        },
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), ONE_HOUR_FROM_NOW),
                certificateChain,
                testCertificates.getRootCertificate()));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            MoreExecutors.listeningDecorator(executor),
            connectionInfoRepo,
            instanceName,
            keyPair,
            TEST_TIMEOUT_MS);

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    ConnectionInfo connectionInfo = connectionInfoCache.getConnectionInfo();

    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(ONE_HOUR_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));
  }

  @Test
  public void testGetConnectionInfo_scheduledNextOperationImmediately_onCertificateException() {

    InMemoryConnectionInfoRepo connectionInfoRepo = new InMemoryConnectionInfoRepo();
    List<X509Certificate> certificateChain =
        Arrays.asList(
            testCertificates.getIntermediateCertificate(), testCertificates.getRootCertificate());
    connectionInfoRepo.addResponses(
        () -> {
          throw new CertificateException();
        },
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), ONE_HOUR_FROM_NOW),
                certificateChain,
                testCertificates.getRootCertificate()));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            MoreExecutors.listeningDecorator(executor),
            connectionInfoRepo,
            instanceName,
            keyPair,
            TEST_TIMEOUT_MS);

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    ConnectionInfo connectionInfo = connectionInfoCache.getConnectionInfo();

    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(ONE_HOUR_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));
  }
}
