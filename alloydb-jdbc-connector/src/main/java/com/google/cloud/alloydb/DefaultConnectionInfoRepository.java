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

import com.google.cloud.alloydb.v1.AlloyDBAdminClient;
import com.google.cloud.alloydb.v1.ClusterName;
import com.google.cloud.alloydb.v1.GenerateClientCertificateRequest;
import com.google.cloud.alloydb.v1.GenerateClientCertificateResponse;
import com.google.cloud.alloydb.v1.InstanceName;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

class DefaultConnectionInfoRepository implements ConnectionInfoRepository, Closeable {

  private static final String OPENSSL_PUBLIC_KEY_BEGIN = "-----BEGIN RSA PUBLIC KEY-----";
  private static final String OPENSSL_PUBLIC_KEY_END = "-----END RSA PUBLIC KEY-----";
  private static final String X_509 = "X.509";
  private static final int PEM_LINE_LENGTH = 64;
  private final ListeningScheduledExecutorService executor;
  private final AlloyDBAdminClient alloyDBAdminClient;

  DefaultConnectionInfoRepository(
      ListeningScheduledExecutorService executor, AlloyDBAdminClient alloyDBAdminClient) {
    this.executor = executor;
    this.alloyDBAdminClient = alloyDBAdminClient;
  }

  @Override
  public ListenableFuture<ConnectionInfo> getConnectionInfo(
      InstanceName instanceName, KeyPair keyPair) {
    ListenableFuture<com.google.cloud.alloydb.v1.ConnectionInfo> infoFuture =
        executor.submit(() -> getConnectionInfo(instanceName));
    ListenableFuture<GenerateClientCertificateResponse> clientCertificateResponseFuture =
        executor.submit(() -> getGenerateClientCertificateResponse(instanceName, keyPair));

    return Futures.whenAllComplete(infoFuture, clientCertificateResponseFuture)
        .call(
            () -> {
              com.google.cloud.alloydb.v1.ConnectionInfo info = Futures.getDone(infoFuture);
              GenerateClientCertificateResponse certificateResponse =
                  Futures.getDone(clientCertificateResponseFuture);

              List<ByteString> certificateChainBytes =
                  certificateResponse.getPemCertificateChainList().asByteStringList();
              List<X509Certificate> certificateChain = new ArrayList<>();
              for (ByteString certificateChainByte : certificateChainBytes) {
                certificateChain.add(parseCertificate(certificateChainByte));
              }
              X509Certificate clientCertificate = certificateChain.get(0);
              ByteString caCertificateBytes = certificateResponse.getCaCertBytes();
              X509Certificate caCertificate = parseCertificate(caCertificateBytes);

              return new ConnectionInfo(
                  info.getIpAddress(),
                  info.getInstanceUid(),
                  clientCertificate,
                  certificateChain,
                  caCertificate);
            },
            executor);
  }

  @Override
  public void close() {
    this.alloyDBAdminClient.close();
  }

  private com.google.cloud.alloydb.v1.ConnectionInfo getConnectionInfo(InstanceName instanceName) {
    return alloyDBAdminClient.getConnectionInfo(instanceName);
  }

  private GenerateClientCertificateResponse getGenerateClientCertificateResponse(
      InstanceName instanceName, KeyPair keyPair) {
    GenerateClientCertificateRequest request =
        GenerateClientCertificateRequest.newBuilder()
            .setParent(getParent(instanceName))
            .setCertDuration(Duration.newBuilder().setSeconds(3600 /* 1 hour */))
            .setPublicKey(generatePublicKeyCert(keyPair))
            .setUseMetadataExchange(true)
            .build();

    return alloyDBAdminClient.generateClientCertificate(request);
  }

  private String getParent(InstanceName instanceName) {
    return ClusterName.of(
            instanceName.getProject(), instanceName.getLocation(), instanceName.getCluster())
        .toString();
  }

  private String generatePublicKeyCert(KeyPair keyPair) {
    StringBuilder sb = new StringBuilder();
    sb.append(OPENSSL_PUBLIC_KEY_BEGIN).append("\n");
    String base64Key =
        BaseEncoding.base64()
            .withSeparator("\n", PEM_LINE_LENGTH)
            .encode(keyPair.getPublic().getEncoded());
    sb.append(base64Key).append("\n");
    sb.append(OPENSSL_PUBLIC_KEY_END).append("\n");
    return sb.toString();
  }

  private X509Certificate parseCertificate(ByteString cert) throws CertificateException {
    ByteArrayInputStream certStream = new ByteArrayInputStream(cert.toByteArray());
    CertificateFactory x509CertificateFactory = CertificateFactory.getInstance(X_509);
    return (X509Certificate) x509CertificateFactory.generateCertificate(certStream);
  }
}
