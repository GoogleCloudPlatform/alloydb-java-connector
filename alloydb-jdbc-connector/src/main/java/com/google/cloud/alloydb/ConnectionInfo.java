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

import com.google.protobuf.ByteString;
import java.util.List;

class ConnectionInfo {

  private final String ipAddress;
  private final String instanceUid;
  private final ByteString clientCertificate;
  private final List<ByteString> certificateChain;

  ConnectionInfo(
      String ipAddress,
      String instanceUid,
      ByteString clientCertificate,
      List<ByteString> certificateChain) {
    this.ipAddress = ipAddress;
    this.instanceUid = instanceUid;
    this.clientCertificate = clientCertificate;
    this.certificateChain = certificateChain;
  }

  String getIpAddress() {
    return ipAddress;
  }

  String getInstanceUid() {
    return instanceUid;
  }

  ByteString getClientCertificate() {
    return clientCertificate;
  }

  List<ByteString> getCertificateChain() {
    return certificateChain;
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

    if (!ipAddress.equals(that.ipAddress)) {
      return false;
    }
    if (!instanceUid.equals(that.instanceUid)) {
      return false;
    }
    if (!clientCertificate.equals(that.clientCertificate)) {
      return false;
    }
    return certificateChain.equals(that.certificateChain);
  }

  @Override
  public int hashCode() {
    int result = ipAddress.hashCode();
    result = 31 * result + instanceUid.hashCode();
    result = 31 * result + clientCertificate.hashCode();
    result = 31 * result + certificateChain.hashCode();
    return result;
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
        + '}';
  }
}
