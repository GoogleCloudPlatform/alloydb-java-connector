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

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import java.io.IOException;
import java.util.Arrays;

class CredentialsProviderFactory {

  private static final String CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";
  private static final String ALLOYDB_LOGIN = "https://www.googleapis.com/auth/alloydb.login";

  static FixedCredentialsProvider create(ConnectionConfig config) {
    GoogleCredentials credentials;
    try {
      credentials = GoogleCredentials.getApplicationDefault();
    } catch (IOException e) {
      throw new RuntimeException("failed to retrieve OAuth2 access token", e);
    }

    if (config.getConnectorConfig().getTargetPrincipal() != null
        && !config.getConnectorConfig().getTargetPrincipal().isEmpty()) {
      credentials =
          ImpersonatedCredentials.newBuilder()
              .setSourceCredentials(credentials)
              .setTargetPrincipal(config.getConnectorConfig().getTargetPrincipal())
              .setDelegates(config.getConnectorConfig().getDelegates())
              .setScopes(Arrays.asList(ALLOYDB_LOGIN, CLOUD_PLATFORM))
              .build();
    }

    return FixedCredentialsProvider.create(credentials);
  }
}
