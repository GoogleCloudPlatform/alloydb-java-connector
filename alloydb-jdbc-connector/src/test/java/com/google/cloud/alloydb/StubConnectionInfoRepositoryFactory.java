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

import com.google.cloud.alloydb.v1alpha.AlloyDBAdminClient;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.io.IOException;

public class StubConnectionInfoRepositoryFactory implements ConnectionInfoRepositoryFactory {
  private ListeningScheduledExecutorService executor;
  private MockAlloyDBAdminGrpc mock;

  StubConnectionInfoRepositoryFactory(
      ListeningScheduledExecutorService executor, MockAlloyDBAdminGrpc mock) {
    this.executor = executor;
    this.mock = mock;
  }

  @Override
  public ConnectionInfoRepository create(
      CredentialFactory credentialFactory, ConnectorConfig config) {

    try {
      AlloyDBAdminClient alloyDBAdminClient =
          StubAlloyDBAdminClientFactory.create(credentialFactory.create(), mock);
      return new DefaultConnectionInfoRepository(executor, alloyDBAdminClient);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
