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
