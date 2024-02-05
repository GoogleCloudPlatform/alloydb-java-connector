/*
 * Copyright 2024 Google LLC
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

import com.google.cloud.alloydb.v1alpha.AlloyDBAdminGrpc;
import com.google.cloud.alloydb.v1alpha.ConnectionInfo;
import com.google.cloud.alloydb.v1alpha.GenerateClientCertificateRequest;
import com.google.cloud.alloydb.v1alpha.GenerateClientCertificateResponse;
import com.google.cloud.alloydb.v1alpha.GetConnectionInfoRequest;
import com.google.rpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;

class MockAlloyDBAdminGrpc extends AlloyDBAdminGrpc.AlloyDBAdminImplBase {

  private int errorCode;
  private String errorMessage;
  private String ipAddress;

  MockAlloyDBAdminGrpc(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  MockAlloyDBAdminGrpc(int errorCode, String errorMessage) {
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
  }

  @Override
  public void generateClientCertificate(
      GenerateClientCertificateRequest request,
      StreamObserver<GenerateClientCertificateResponse> responseObserver) {

    if (errorCode != 0) {
      Status status = Status.newBuilder().setCode(errorCode).setMessage(errorMessage).build();
      responseObserver.onError(StatusProto.toStatusRuntimeException(status));
    } else {
      try {
        GenerateClientCertificateResponse response =
            GenerateClientCertificateResponse.newBuilder()
                .setCaCert(TestKeys.SIGNING_CA_CERT)
                .addPemCertificateChain(TestKeys.CLIENT_CERT)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void getConnectionInfo(
      GetConnectionInfoRequest request, StreamObserver<ConnectionInfo> responseObserver) {

    if (errorCode != 0) {
      Status status = Status.newBuilder().setCode(errorCode).setMessage(errorMessage).build();
      responseObserver.onError(StatusProto.toStatusRuntimeException(status));
    } else {
      responseObserver.onNext(ConnectionInfo.newBuilder().setIpAddress(ipAddress).build());
      responseObserver.onCompleted();
    }
  }
}
