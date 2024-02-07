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

// [START alloydb_hikaricp_connect_iam_authn_direct]
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class AlloyDbJdbcDirectPathDataSourceFactory {

  public static final String ALLOYDB_DB = System.getenv("ALLOYDB_DB");
  public static final String ALLOYDB_USER = System.getenv("ALLOYDB_IAM_USER");
  public static final String ALLOYDB_INSTANCE_IP = System.getenv("ALLOYDB_INSTANCE_IP");

  static HikariDataSource createDataSource() {
    HikariConfig config = new HikariConfig();

    config.setJdbcUrl(String.format("jdbc:postgresql://%s/%s", ALLOYDB_INSTANCE_IP, ALLOYDB_DB));
    config.setUsername(ALLOYDB_USER); // e.g., "postgres"
    // No need to set password, as that's dynamically configured in the
    // AlloyDBDirectPathDataSource with a refreshed OAuth2 token.

    return new AlloyDBDirectPathDataSource(config);
  }
}
// [END alloydb_hikaricp_connect_iam_authn_direct]
