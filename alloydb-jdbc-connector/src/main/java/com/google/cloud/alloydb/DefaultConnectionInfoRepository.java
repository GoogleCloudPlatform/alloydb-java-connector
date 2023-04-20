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

import com.google.cloud.alloydb.v1beta.AlloyDBAdminClient;
import com.google.cloud.alloydb.v1beta.GenerateClientCertificateRequest;
import com.google.cloud.alloydb.v1beta.GenerateClientCertificateResponse;
import com.google.cloud.alloydb.v1beta.InstanceName;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import java.io.IOException;
import java.io.StringWriter;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;

class DefaultConnectionInfoRepository implements ConnectionInfoRepository {

  private final ScheduledExecutorService executor;
  private final AlloyDBAdminClient alloyDBAdminClient;

  DefaultConnectionInfoRepository(ScheduledExecutorService executor,
      AlloyDBAdminClient alloyDBAdminClient) {
    this.executor = executor;
    this.alloyDBAdminClient = alloyDBAdminClient;
  }

  @Override
  public ConnectionInfo getConnectionInfo(InstanceName instanceName, KeyPair keyPair) {
    Future<com.google.cloud.alloydb.v1beta.ConnectionInfo> infoFuture = executor.submit(
        () -> getConnectionInfo(instanceName));
    Future<GenerateClientCertificateResponse> clientCertificateResponseFuture = executor.submit(
        () -> getGenerateClientCertificateResponse(instanceName, keyPair));

    com.google.cloud.alloydb.v1beta.ConnectionInfo info;
    try {
      info = infoFuture.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }

    GenerateClientCertificateResponse certificateResponse;
    try {
      certificateResponse = clientCertificateResponseFuture.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
    ByteString clientCertificate = certificateResponse.getPemCertificateBytes();
    List<ByteString> certificateChain = certificateResponse.getPemCertificateChainList().asByteStringList();

    return new ConnectionInfo(
        info.getIpAddress(), info.getInstanceUid(), clientCertificate, certificateChain);
  }

  private com.google.cloud.alloydb.v1beta.ConnectionInfo getConnectionInfo(
      InstanceName instanceName) {
    return alloyDBAdminClient.getConnectionInfo(instanceName);
  }

  private GenerateClientCertificateResponse getGenerateClientCertificateResponse(
      InstanceName instanceName, KeyPair keyPair) {
    StringWriter str = new StringWriter();
    try {
      PKCS10CertificationRequest certRequest = createPKCS10(keyPair);
      PemObject pemObject = new PemObject("CERTIFICATE REQUEST", certRequest.getEncoded());
      JcaPEMWriter pemWriter = new JcaPEMWriter(str);
      pemWriter.writeObject(pemObject);
      pemWriter.close();
    } catch (OperatorCreationException | IOException e) {
      throw new RuntimeException(e);
    }

    GenerateClientCertificateRequest request =
        GenerateClientCertificateRequest.newBuilder()
            .setParent(getParent(instanceName))
            .setCertDuration(Duration.newBuilder().setSeconds(3600 /* 1 hour */))
            .setPemCsr(str.toString())
            .build();

    GenerateClientCertificateResponse response =
        alloyDBAdminClient.generateClientCertificate(request);
    return response;
  }

  private String getParent(InstanceName instanceName) {
    return String.format(
        "projects/%s/locations/%s/clusters/%s",
        instanceName.getProject(), instanceName.getLocation(), instanceName.getCluster());
  }

  private PKCS10CertificationRequest createPKCS10(KeyPair keyPair)
      throws OperatorCreationException, IOException {
    X500Name subject = new X500Name("CN=alloydb-proxy");

    PKCS10CertificationRequestBuilder requestBuilder =
        new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());

    ContentSigner signer =
        new JcaContentSignerBuilder("SHA256WithRSA")
            .build(keyPair.getPrivate());

    return requestBuilder.build(signer);
  }
}
