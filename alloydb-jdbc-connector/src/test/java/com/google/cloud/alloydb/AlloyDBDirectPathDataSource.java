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

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;

/**
 * The AlloyDB direct path data source demonstrates how to enable Auto IAM AuthN without using the
 * AlloyDB Java Connector. To do that, the code here overrides the HikariDataSource's getPassword
 * method to dynamically retrieve an OAuth2 token. This means every new connection made by the
 * connection pool will get a fresh OAuth2 token without relying on the AlloyDB Java Connector. For
 * details on how this is used, see <a
 * href="https://github.com/GoogleCloudPlatform/alloydb-java-connector/blob/main/alloydb-jdbc-connector/src/test/java/com/google/cloud/alloydb/AlloyDbJdbcDirectPathDataSourceFactory.java">
 * AlloyDbJdbcDirectPathDataSourceFactory </a>
 */
public class AlloyDBDirectPathDataSource extends HikariDataSource {

  public AlloyDBDirectPathDataSource(HikariConfig configuration) {
    super(configuration);
  }

  @Override
  public String getPassword() {
    try {
      GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
      credentials.refreshIfExpired();
      AccessToken accessToken = credentials.getAccessToken();
      return accessToken.getTokenValue();
    } catch (IOException e) {
      throw new RuntimeException("failed to retrieve OAuth2 access token", e);
    }
  }
}
