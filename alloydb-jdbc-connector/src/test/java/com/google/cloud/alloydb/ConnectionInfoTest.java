package com.google.cloud.alloydb;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import org.bouncycastle.operator.OperatorCreationException;
import org.junit.Test;

public class ConnectionInfoTest {

  @Test
  public void testGetClientCertificateExpiration()
      throws CertificateException, IOException, OperatorCreationException {
    TestCertificates testCertificates = new TestCertificates();

    Instant expected = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    ConnectionInfo connectionInfo =
        new ConnectionInfo(
            "10.0.0.1",
            "some-id",
            testCertificates.getEphemeralCertificate(
                RsaKeyPairGenerator.TEST_KEY_PAIR.getPublic(), expected),
            Arrays.asList(
                testCertificates.getIntermediateCertificate(),
                testCertificates.getRootCertificate()));

    assertThat(connectionInfo.getClientCertificateExpiration()).isEqualTo(expected);
  }
}
