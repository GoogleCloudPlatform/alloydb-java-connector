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

import com.google.common.base.Objects;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

class ConnectionInfo {

  private final String ipAddress;
  private final String instanceUid;
  private final X509Certificate clientCertificate;
  private final List<X509Certificate> certificateChain;
  private final X509Certificate caCertificate;

  ConnectionInfo(
      String ipAddress,
      String instanceUid,
      X509Certificate clientCertificate,
      List<X509Certificate> certificateChain,
      X509Certificate caCertificate) {
    this.ipAddress = ipAddress;
    this.instanceUid = instanceUid;
    this.clientCertificate = clientCertificate;
    this.certificateChain = certificateChain;
    this.caCertificate = caCertificate;
  }

  String getIpAddress() {
    return ipAddress;
  }

  String getInstanceUid() {
    return instanceUid;
  }

  X509Certificate getClientCertificate() {
    return clientCertificate;
  }

  Instant getClientCertificateExpiration() {
    return clientCertificate.getNotAfter().toInstant();
  }

  Instant getExpiration() {
    return getClientCertificateExpiration();
  }

  List<X509Certificate> getCertificateChain() {
    return certificateChain;
  }

  X509Certificate getCaCertificate() {
    return caCertificate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ConnectionInfo)) {
      return false;
    }

    ConnectionInfo that = (ConnectionInfo) o;
    return Objects.equal(ipAddress, that.ipAddress)
        && Objects.equal(instanceUid, that.instanceUid)
        && Objects.equal(clientCertificate, that.clientCertificate)
        && Objects.equal(certificateChain, that.certificateChain)
        && Objects.equal(caCertificate, that.caCertificate);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        ipAddress, instanceUid, clientCertificate, certificateChain, caCertificate);
  }

  @Override
  public String toString() {
    return "ConnectionInfo{"
        + "ipAddress='"
        + ipAddress
        + '\''
        + ", instanceUid='"
        + instanceUid
        + '\''
        + ", clientCertificate="
        + clientCertificate
        + ", certificateChain="
        + certificateChain
        + ", caCertificate="
        + caCertificate
        + '}';
  }
}
