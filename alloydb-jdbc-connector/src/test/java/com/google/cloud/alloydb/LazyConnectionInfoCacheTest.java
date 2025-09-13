/*
 * Copyright 2025 Google LLC
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
import static org.junit.Assert.assertThrows;

import com.google.cloud.alloydb.v1alpha.InstanceName;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.Test;

@SuppressWarnings("TimeInStaticInitializer")
public class LazyConnectionInfoCacheTest {

  private static final Instant ONE_HOUR_AGO =
      Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
  private static final Instant ONE_HOUR_FROM_NOW =
      Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
  private static final Instant TWO_HOURS_FROM_NOW =
      Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

  private static final InstanceName TEST_INSTANCE_NAME =
      InstanceName.parse(
          "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>");

  private final KeyPair keyPair = RsaKeyPairGenerator.generateKeyPair();

  @Test
  public void testGetConnectionInfo() {
    InMemoryConnectionInfoRepo repo = new InMemoryConnectionInfoRepo();
    repo.addResponses(() -> buildConnectionInfoWithClientCertExpiration(ONE_HOUR_FROM_NOW));
    LazyConnectionInfoCache cache = new LazyConnectionInfoCache(repo, TEST_INSTANCE_NAME, keyPair);

    ConnectionInfo connectionInfo = cache.getConnectionInfo();
    assertThat(connectionInfo.getClientCertificate().getNotAfter().toInstant())
        .isEqualTo(ONE_HOUR_FROM_NOW);
  }

  @Test
  public void testGetConnectionInfo_updatesCacheWhenCertificateExpires() {
    InMemoryConnectionInfoRepo repo = new InMemoryConnectionInfoRepo();
    repo.addResponses(
        () -> buildConnectionInfoWithClientCertExpiration(ONE_HOUR_AGO),
        () -> buildConnectionInfoWithClientCertExpiration(ONE_HOUR_FROM_NOW));
    LazyConnectionInfoCache cache = new LazyConnectionInfoCache(repo, TEST_INSTANCE_NAME, keyPair);

    // seed internal cache with first response from connection info repo (an expired certificate).
    ConnectionInfo connectionInfo = cache.getConnectionInfo();
    assertThat(connectionInfo.getClientCertificate().getNotAfter().toInstant())
        .isEqualTo(ONE_HOUR_AGO);

    connectionInfo = cache.getConnectionInfo();
    assertThat(connectionInfo.getClientCertificate().getNotAfter().toInstant())
        .isEqualTo(ONE_HOUR_FROM_NOW);
  }

  @Test
  public void testForceRefresh() {
    InMemoryConnectionInfoRepo repo = new InMemoryConnectionInfoRepo();
    repo.addResponses(
        () -> buildConnectionInfoWithClientCertExpiration(ONE_HOUR_FROM_NOW),
        () -> buildConnectionInfoWithClientCertExpiration(TWO_HOURS_FROM_NOW));
    LazyConnectionInfoCache cache = new LazyConnectionInfoCache(repo, TEST_INSTANCE_NAME, keyPair);

    // seed the internal cache
    ConnectionInfo connectionInfo = cache.getConnectionInfo();
    assertThat(connectionInfo.getClientCertificate().getNotAfter().toInstant())
        .isEqualTo(ONE_HOUR_FROM_NOW);

    cache.forceRefresh(); // invalidate the cache

    connectionInfo = cache.getConnectionInfo();
    assertThat(connectionInfo.getClientCertificate().getNotAfter().toInstant())
        .isEqualTo(TWO_HOURS_FROM_NOW);
  }

  @Test
  public void testClose() {
    LazyConnectionInfoCache cache =
        new LazyConnectionInfoCache(new InMemoryConnectionInfoRepo(), TEST_INSTANCE_NAME, keyPair);

    cache.close();

    // After the cache is closed, subsequent usage throws an exception.
    assertThrows(IllegalStateException.class, cache::getConnectionInfo);
    assertThrows(IllegalStateException.class, cache::forceRefresh);
    assertThrows(IllegalStateException.class, cache::refreshIfExpired);
  }

  private ConnectionInfo buildConnectionInfoWithClientCertExpiration(Instant notAfter)
      throws CertificateException, OperatorCreationException, CertIOException {
    return new ConnectionInfo(
        "10.0.0.1",
        "34.0.0.1",
        "",
        "some-instance-id",
        TestCertificates.INSTANCE.getEphemeralCertificate(keyPair.getPublic(), notAfter),
        Arrays.asList(
            TestCertificates.INSTANCE.getIntermediateCertificate(),
            TestCertificates.INSTANCE.getRootCertificate()),
        TestCertificates.INSTANCE.getRootCertificate());
  }
}
