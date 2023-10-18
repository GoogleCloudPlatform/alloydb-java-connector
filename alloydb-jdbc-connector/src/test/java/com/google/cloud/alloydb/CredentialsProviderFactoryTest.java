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

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.UserCredentials;
import java.io.IOException;
import org.junit.Test;

public class CredentialsProviderFactoryTest {
  @Test
  public void returnsDefaultCredentials() throws IOException {
    ConnectionConfig config = new ConnectionConfig.Builder().build();
    FixedCredentialsProvider credentials = CredentialsProviderFactory.create(config);
    assertThat(credentials.getCredentials()).isInstanceOf(UserCredentials.class);
  }

  @Test
  public void returnsImpersonationCredentials() throws IOException {
    ConnectionConfig config =
        new ConnectionConfig.Builder().withTargetPrincipal("first@serviceaccount.com").build();
    FixedCredentialsProvider credentials = CredentialsProviderFactory.create(config);
    assertThat(credentials.getCredentials()).isInstanceOf(ImpersonatedCredentials.class);
  }
}
