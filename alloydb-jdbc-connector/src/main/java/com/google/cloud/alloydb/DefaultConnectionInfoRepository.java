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

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.alloydb.v1beta.AlloyDBAdminClient;
import com.google.cloud.alloydb.v1beta.ClusterName;
import com.google.cloud.alloydb.v1beta.GenerateClientCertificateRequest;
import com.google.cloud.alloydb.v1beta.GenerateClientCertificateResponse;
import com.google.cloud.alloydb.v1beta.InstanceName;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

class DefaultConnectionInfoRepository implements ConnectionInfoRepository {

  private static final String X_509 = "X.509";
  private final ExecutorService executor;
  private final AlloyDBAdminClient alloyDBAdminClient;

  DefaultConnectionInfoRepository(ExecutorService executor, AlloyDBAdminClient alloyDBAdminClient) {
    this.executor = executor;
    this.alloyDBAdminClient = alloyDBAdminClient;
  }

  @Override
  public ConnectionInfo getConnectionInfo(InstanceName instanceName, KeyPair keyPair)
      throws ExecutionException, InterruptedException, CertificateException, ApiException {
    Future<com.google.cloud.alloydb.v1beta.ConnectionInfo> infoFuture =
        executor.submit(() -> getConnectionInfo(instanceName));
    Future<GenerateClientCertificateResponse> clientCertificateResponseFuture =
        executor.submit(() -> getGenerateClientCertificateResponse(instanceName, keyPair));

    com.google.cloud.alloydb.v1beta.ConnectionInfo info = infoFuture.get();

    GenerateClientCertificateResponse certificateResponse = clientCertificateResponseFuture.get();
    ByteString pemCertificateBytes = certificateResponse.getPemCertificateBytes();
    X509Certificate clientCertificate = parseCertificate(pemCertificateBytes);

    List<ByteString> certificateChainBytes =
        certificateResponse.getPemCertificateChainList().asByteStringList();
    List<X509Certificate> certificateChain = new ArrayList<>();
    for (ByteString certificateChainByte : certificateChainBytes) {
      certificateChain.add(parseCertificate(certificateChainByte));
    }

    return new ConnectionInfo(
        info.getIpAddress(), info.getInstanceUid(), clientCertificate, certificateChain);
  }

  private com.google.cloud.alloydb.v1beta.ConnectionInfo getConnectionInfo(
      InstanceName instanceName) {
    return alloyDBAdminClient.getConnectionInfo(instanceName);
  }

  private GenerateClientCertificateResponse getGenerateClientCertificateResponse(
      InstanceName instanceName, KeyPair keyPair) {

    GenerateClientCertificateRequest request =
        GenerateClientCertificateRequest.newBuilder()
            .setParent(getParent(instanceName))
            .setCertDuration(Duration.newBuilder().setSeconds(3600 /* 1 hour */))
            .setPublicKey(keyPair.getPublic().toString())
            .build();

    return alloyDBAdminClient.generateClientCertificate(request);
  }

  private String getParent(InstanceName instanceName) {
    return ClusterName.of(
            instanceName.getProject(), instanceName.getLocation(), instanceName.getCluster())
        .toString();
  }

  private X509Certificate parseCertificate(ByteString cert) throws CertificateException {
    ByteArrayInputStream certStream = new ByteArrayInputStream(cert.toByteArray());
    CertificateFactory x509CertificateFactory = CertificateFactory.getInstance(X_509);
    return (X509Certificate) x509CertificateFactory.generateCertificate(certStream);
  }
}
