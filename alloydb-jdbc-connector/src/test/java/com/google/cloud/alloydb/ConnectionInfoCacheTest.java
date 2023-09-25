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

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.alloydb.v1beta.InstanceName;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bouncycastle.operator.OperatorCreationException;
import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.Before;
import org.junit.Test;

public class ConnectionInfoCacheTest {

  private static final String TEST_INSTANCE_IP = "10.0.0.1";
  private static final String TEST_INSTANCE_ID = "some-instance-id";
  private static final Instant ONE_HOUR_FROM_NOW = Instant.now().plus(1, ChronoUnit.HOURS);
  private static final Instant TWO_HOURS_FROM_NOW = ONE_HOUR_FROM_NOW.plus(1, ChronoUnit.HOURS);
  private static final Instant THREE_HOURS_FROM_NOW = TWO_HOURS_FROM_NOW.plus(1, ChronoUnit.HOURS);
  private InstanceName instanceName;
  private KeyPair keyPair;
  private SpyRateLimiter spyRateLimiter;
  private TestCertificates testCertificates;

  @Before
  public void setUp() throws CertificateException, IOException, OperatorCreationException {
    instanceName =
        InstanceName.parse(
            "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>");
    keyPair = RsaKeyPairGenerator.generateKeyPair();
    spyRateLimiter = new SpyRateLimiter();
    testCertificates = new TestCertificates();
  }

  @Test
  public void testGetConnectionInfo_returnsConnectionInfo() {
    DeterministicScheduler executor = new DeterministicScheduler();
    InMemoryConnectionInfoRepo connectionInfoRepo = new InMemoryConnectionInfoRepo();
    connectionInfoRepo.addResponses(
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), ONE_HOUR_FROM_NOW),
                Arrays.asList(
                    testCertificates.getIntermediateCertificate(),
                    testCertificates.getRootCertificate())));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            executor,
            connectionInfoRepo,
            instanceName,
            keyPair,
            new RefreshCalculator(),
            spyRateLimiter);

    executor.runNextPendingCommand(); // Simulate completion of background thread.
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
  public void testGetConnectionInfo_schedulesNextOperation() {
    DeterministicScheduler executor = new DeterministicScheduler();
    InMemoryConnectionInfoRepo connectionInfoRepo = new InMemoryConnectionInfoRepo();
    List<X509Certificate> certificateChain =
        Arrays.asList(
            testCertificates.getIntermediateCertificate(), testCertificates.getRootCertificate());
    connectionInfoRepo.addResponses(
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), ONE_HOUR_FROM_NOW),
                certificateChain),
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), TWO_HOURS_FROM_NOW),
                certificateChain));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            executor,
            connectionInfoRepo,
            instanceName,
            keyPair,
            new RefreshCalculator(),
            spyRateLimiter);

    executor.runNextPendingCommand(); // Simulate completion of background thread.
    ConnectionInfo connectionInfo = connectionInfoCache.getConnectionInfo();

    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(ONE_HOUR_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));

    executor.tick(1, TimeUnit.HOURS); // Advance time to just after next refresh
    executor.runUntilIdle(); // Simulate completion of background thread.

    ConnectionInfo nextConnectionInfo = connectionInfoCache.getConnectionInfo();

    assertThat(
            nextConnectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(TWO_HOURS_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));
  }

  @Test
  public void testGetConnectionInfo_scheduledNextOperationImmediately_onApiException() {
    DeterministicScheduler executor = new DeterministicScheduler();
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
                certificateChain));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            executor,
            connectionInfoRepo,
            instanceName,
            keyPair,
            new RefreshCalculator(),
            spyRateLimiter);

    executor.runNextPendingCommand(); // Simulate completion of background thread.
    assertThrows(RuntimeException.class, connectionInfoCache::getConnectionInfo);

    executor.tick(1, TimeUnit.SECONDS); // Advance time just a little
    executor.runUntilIdle(); // Simulate completion of background thread.

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
    DeterministicScheduler executor = new DeterministicScheduler();
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
                certificateChain));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            executor,
            connectionInfoRepo,
            instanceName,
            keyPair,
            new RefreshCalculator(),
            spyRateLimiter);

    executor.runNextPendingCommand(); // Simulate completion of background thread.
    RuntimeException runtimeException =
        assertThrows(RuntimeException.class, connectionInfoCache::getConnectionInfo);

    // The RuntimeException wraps an ExecutionException which wraps the original exception.
    assertThat(runtimeException.getCause().getCause()).isInstanceOf(CertificateException.class);

    executor.tick(1, TimeUnit.SECONDS); // Advance time just a little
    executor.runUntilIdle(); // Simulate completion of background thread.

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
  public void testGetConnectionInfo_isRateLimited() {
    DeterministicScheduler executor = new DeterministicScheduler();
    InMemoryConnectionInfoRepo connectionInfoRepo = new InMemoryConnectionInfoRepo();
    connectionInfoRepo.addResponses(
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), ONE_HOUR_FROM_NOW),
                Arrays.asList(
                    testCertificates.getIntermediateCertificate(),
                    testCertificates.getRootCertificate())));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            executor,
            connectionInfoRepo,
            instanceName,
            keyPair,
            new RefreshCalculator(),
            spyRateLimiter);

    assertThat(spyRateLimiter.wasRateLimited.get()).isFalse();

    executor.runNextPendingCommand(); // Simulate completion of background thread.
    connectionInfoCache.getConnectionInfo();

    assertThat(spyRateLimiter.wasRateLimited.get()).isTrue();
  }

  @Test
  public void testForceRefresh_schedulesNextRefreshImmediately() throws InterruptedException {
    DeterministicScheduler executor = new DeterministicScheduler();

    InMemoryConnectionInfoRepo connectionInfoRepo = new InMemoryConnectionInfoRepo();
    List<X509Certificate> certificateChain =
        Arrays.asList(
            testCertificates.getIntermediateCertificate(), testCertificates.getRootCertificate());
    connectionInfoRepo.addResponses(
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), ONE_HOUR_FROM_NOW),
                certificateChain),
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), TWO_HOURS_FROM_NOW),
                certificateChain));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            executor,
            connectionInfoRepo,
            instanceName,
            keyPair,
            new RefreshCalculator(),
            spyRateLimiter);

    executor.runNextPendingCommand(); // Simulate completion of background thread.

    // Before force refresh, the first refresh data is available.
    ConnectionInfo connectionInfo = connectionInfoCache.getConnectionInfo();
    assertThat(connectionInfoRepo.getIndex()).isEqualTo(1);
    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(ONE_HOUR_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));

    connectionInfoCache.forceRefresh();

    // Refresh data hasn't changed because we re-use the existing connection info.
    connectionInfo = connectionInfoCache.getConnectionInfo();
    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(ONE_HOUR_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));

    executor.runUntilIdle(); // Simulate completion of background thread.

    // After the force refresh, new refresh data is available.
    connectionInfo = connectionInfoCache.getConnectionInfo();
    assertThat(connectionInfoRepo.getIndex()).isEqualTo(2);
    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(TWO_HOURS_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));
  }

  @Test
  public void testForceRefresh_refreshCalledOnlyOnceDuringMultipleCalls()
      throws InterruptedException {
    DeterministicScheduler executor = new DeterministicScheduler();

    InMemoryConnectionInfoRepo connectionInfoRepo = new InMemoryConnectionInfoRepo();
    List<X509Certificate> certificateChain =
        Arrays.asList(
            testCertificates.getIntermediateCertificate(), testCertificates.getRootCertificate());
    connectionInfoRepo.addResponses(
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), ONE_HOUR_FROM_NOW),
                certificateChain),
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), TWO_HOURS_FROM_NOW),
                certificateChain),
        () ->
            new ConnectionInfo(
                TEST_INSTANCE_IP,
                TEST_INSTANCE_ID,
                testCertificates.getEphemeralCertificate(keyPair.getPublic(), THREE_HOURS_FROM_NOW),
                certificateChain));
    DefaultConnectionInfoCache connectionInfoCache =
        new DefaultConnectionInfoCache(
            executor,
            connectionInfoRepo,
            instanceName,
            keyPair,
            new RefreshCalculator(),
            spyRateLimiter);

    executor.runNextPendingCommand(); // Simulate completion of background thread.

    // Before force refresh, the first refresh data is available.
    ConnectionInfo connectionInfo = connectionInfoCache.getConnectionInfo();
    assertThat(connectionInfoRepo.getIndex()).isEqualTo(1);
    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(ONE_HOUR_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));

    connectionInfoCache.forceRefresh();
    // This second call is ignored as there is a refresh operation in progress.
    connectionInfoCache.forceRefresh();

    executor.runUntilIdle(); // Simulate completion of background thread.

    // After the force refresh, new refresh data is available.
    connectionInfo = connectionInfoCache.getConnectionInfo();
    assertThat(connectionInfoRepo.getIndex()).isEqualTo(2);
    assertThat(
            connectionInfo
                .getClientCertificate()
                .getNotAfter()
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(TWO_HOURS_FROM_NOW.truncatedTo(ChronoUnit.SECONDS));
  }

  private static class SpyRateLimiter implements RateLimiter {
    public final AtomicBoolean wasRateLimited = new AtomicBoolean(false);

    @Override
    public void acquire() {
      wasRateLimited.set(true);
    }
  }
}
