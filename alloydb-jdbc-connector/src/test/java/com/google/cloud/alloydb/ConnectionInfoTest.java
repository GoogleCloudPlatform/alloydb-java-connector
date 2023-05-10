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

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.Before;
import org.junit.Test;

public class ConnectionInfoTest {

  private static final String IP_ADDRESS = "10.0.0.1";
  public static final String INSTANCE_UID = "some-id";
  private TestCertificates testCertificates;

  @Before
  public void setUp() throws Exception {
     testCertificates = new TestCertificates();
  }

  @Test
  public void testGetClientCertificateExpiration()
      throws CertificateException, IOException, OperatorCreationException {
    Instant expected = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    ConnectionInfo connectionInfo =
        new ConnectionInfo(
            IP_ADDRESS,
            INSTANCE_UID,
            testCertificates.getEphemeralCertificate(
                RsaKeyPairGenerator.TEST_KEY_PAIR.getPublic(), expected),
            Arrays.asList(
                testCertificates.getIntermediateCertificate(),
                testCertificates.getRootCertificate()));

    assertThat(connectionInfo.getClientCertificateExpiration()).isEqualTo(expected);
  }

  @Test
  public void testEquals() throws CertificateException, OperatorCreationException, CertIOException {
    Instant now = Instant.now();
    X509Certificate ephemeralCertificate = testCertificates.getEphemeralCertificate(
        RsaKeyPairGenerator.TEST_KEY_PAIR.getPublic(), now);
    ConnectionInfo c1 = new ConnectionInfo(
        IP_ADDRESS,
        INSTANCE_UID,
        ephemeralCertificate,
        Arrays.asList(
            testCertificates.getIntermediateCertificate(),
            testCertificates.getRootCertificate()));

    //noinspection EqualsWithItself
    assertThat(c1.equals(c1)).isTrue();
    assertThat(c1).isNotEqualTo("not a connection info");
    assertThat(c1).isNotEqualTo(new ConnectionInfo(
        "different IP",
        INSTANCE_UID,
        ephemeralCertificate,
        Arrays.asList(
            testCertificates.getIntermediateCertificate(),
            testCertificates.getRootCertificate())
    ));
    assertThat(c1).isNotEqualTo(new ConnectionInfo(
        IP_ADDRESS,
        "different instance Uid",
        ephemeralCertificate,
        Arrays.asList(
            testCertificates.getIntermediateCertificate(),
            testCertificates.getRootCertificate()
        )
    ));
    assertThat(c1).isNotEqualTo(new ConnectionInfo(
        IP_ADDRESS,
        INSTANCE_UID,
        testCertificates.getEphemeralCertificate(
            RsaKeyPairGenerator.TEST_KEY_PAIR.getPublic(),
            Instant.now().plus(1, ChronoUnit.DAYS)),
        Arrays.asList(
            testCertificates.getIntermediateCertificate(),
            testCertificates.getRootCertificate()
        )
    ));
    assertThat(c1).isNotEqualTo(new ConnectionInfo(
        IP_ADDRESS,
        INSTANCE_UID,
        testCertificates.getEphemeralCertificate(
            RsaKeyPairGenerator.TEST_KEY_PAIR.getPublic(),
            Instant.now().plus(1, ChronoUnit.DAYS)),
        Collections.emptyList()
    ));

    ConnectionInfo c2 = new ConnectionInfo(
        "10.0.0.1",
        "some-id",
        ephemeralCertificate,
        Arrays.asList(
            testCertificates.getIntermediateCertificate(),
            testCertificates.getRootCertificate()));
    assertThat(c1).isEqualTo(c2);
  }

  @Test
  public void testHashCode()
      throws CertificateException, OperatorCreationException, CertIOException {
    ConnectionInfo c1 = new ConnectionInfo(
        "10.0.0.1",
        "some-id",
        testCertificates.getEphemeralCertificate(
            RsaKeyPairGenerator.TEST_KEY_PAIR.getPublic(), Instant.now()),
        Arrays.asList(
            testCertificates.getIntermediateCertificate(),
            testCertificates.getRootCertificate()));

    assertThat(c1.hashCode()).isEqualTo(getHashCode(c1));
  }

  long getHashCode(ConnectionInfo connectionInfo) {
    int result = connectionInfo.getIpAddress().hashCode();
    result = 31 * result + connectionInfo.getInstanceUid().hashCode();
    result = 31 * result + connectionInfo.getClientCertificate().hashCode();
    result = 31 * result + connectionInfo.getCertificateChain().hashCode();
    return result;
  }
}
