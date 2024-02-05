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

import com.google.api.gax.core.ExecutorProvider;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannel;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.alloydb.v1alpha.AlloyDBAdminClient;
import com.google.cloud.alloydb.v1alpha.AlloyDBAdminSettings;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import java.io.IOException;

class StubAlloyDBAdminClientFactory {

  private static final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  static AlloyDBAdminClient create(
      FixedCredentialsProvider credentialsProvider, MockAlloyDBAdminGrpc mock) throws IOException {

    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(mock)
            .build()
            .start();

    grpcCleanup.register(server);

    ExecutorProvider executorProvider =
        InstantiatingExecutorProvider.newBuilder().setExecutorThreadCount(1).build();

    ManagedChannel managedChannel =
        InProcessChannelBuilder.forName(serverName).directExecutor().build();

    TransportChannel transportChannel = GrpcTransportChannel.create(managedChannel);
    TransportChannelProvider transportChannelProvider =
        FixedTransportChannelProvider.create(transportChannel);

    AlloyDBAdminSettings alloyDBAdminSettings =
        AlloyDBAdminSettings.newBuilder()
            .setTransportChannelProvider(transportChannelProvider)
            .setBackgroundExecutorProvider(executorProvider)
            .setCredentialsProvider(credentialsProvider)
            .build();

    return AlloyDBAdminClient.create(alloyDBAdminSettings);
  }
}
